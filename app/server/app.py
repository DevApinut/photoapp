from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import shutil
import tempfile
from contextlib import closing
from datetime import datetime
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel

BASE_DIR = Path(os.getenv("PHOTO_SYNC_DATA", Path(__file__).parent / "data")).resolve()
PHOTO_ROOT = BASE_DIR / "PhotoBackup"
DB_PATH = BASE_DIR / "photo_sync.sqlite3"
CHUNK_SIZE = 1024 * 1024
INFO_FILENAME = "_DN_INFO.json"

app = FastAPI(title="Photo Sync Server", version="1.0.0")


def db() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH)
    connection.row_factory = sqlite3.Row
    return connection


def init_db() -> None:
    BASE_DIR.mkdir(parents=True, exist_ok=True)
    PHOTO_ROOT.mkdir(parents=True, exist_ok=True)
    with closing(db()) as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS uploaded_photos (
                hash TEXT PRIMARY KEY,
                photo_id TEXT NOT NULL,
                client_name TEXT NOT NULL,
                job_name TEXT NOT NULL,
                location_name TEXT NOT NULL,
                filename TEXT NOT NULL,
                stored_path TEXT NOT NULL,
                captured_at TEXT NOT NULL,
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                size_bytes INTEGER NOT NULL,
                uploaded_at TEXT NOT NULL
            )
            """
        )
        connection.commit()


@app.on_event("startup")
def startup() -> None:
    init_db()


def safe_part(value: str) -> str:
    value = value.strip().replace("\x00", "")
    value = re.sub(r'[<>:"/\\|?*]', "_", value)
    value = value.rstrip(". ")
    if not value or value in {".", ".."}:
        raise HTTPException(400, "Invalid folder or file name")
    return value[:150]


class CheckRequest(BaseModel):
    hashes: list[str]


class FolderRequest(BaseModel):
    client_name: str
    job_name: str
    location_name: str = ""


class NoteRequest(BaseModel):
    note_id: str
    client_name: str
    job_name: str
    location_name: str = ""
    title: str
    content: str
    updated_at: str


def destination_dir(client_name: str, job_name: str, location_name: str) -> Path:
    if client_name.strip() == "งานทั่วไป":
        target_dir = PHOTO_ROOT / safe_part(job_name)
    else:
        target_dir = PHOTO_ROOT / safe_part(client_name) / safe_part(job_name)
    for folder_part in location_name.split("/"):
        if folder_part.strip():
            target_dir = target_dir / safe_part(folder_part)
    return target_dir


def available_target(target_dir: Path, filename: str, expected: str) -> Path:
    target = target_dir / safe_part(filename)
    if target.exists():
        target = target.with_name(f"{target.stem}_{expected[:8]}{target.suffix}")
    return target


def write_folder_info(
    target_dir: Path,
    client_name: str,
    job_name: str,
    location_name: str,
    photo_info: dict[str, object] | None = None,
    document_info: dict[str, object] | None = None,
) -> None:
    info_path = target_dir / INFO_FILENAME
    document: dict[str, object] = {"photos": []}
    if info_path.is_file():
        try:
            loaded = json.loads(info_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                document = loaded
        except (OSError, ValueError):
            pass
    document.update({
        "format": "DN Photo Folder Info",
        "version": 1,
        "client": client_name,
        "job": job_name,
        "folder": location_name,
        "updated_at": datetime.now().astimezone().isoformat(),
    })
    photos = document.get("photos")
    if not isinstance(photos, list):
        photos = []
    if photo_info is not None:
        photos = [item for item in photos if not isinstance(item, dict) or item.get("filename") != photo_info.get("filename")]
        photos.append(photo_info)
        photos.sort(key=lambda item: str(item.get("captured_at", "")) if isinstance(item, dict) else "")
    document["photos"] = photos
    documents = document.get("documents")
    if not isinstance(documents, list):
        documents = []
    if document_info is not None:
        documents = [item for item in documents if not isinstance(item, dict) or item.get("document_id") != document_info.get("document_id")]
        documents.append(document_info)
    document["documents"] = documents
    temporary = info_path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(info_path)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/folder")
def create_folder(request: FolderRequest) -> dict[str, str]:
    target = destination_dir(request.client_name, request.job_name, request.location_name)
    target.mkdir(parents=True, exist_ok=True)
    write_folder_info(target, request.client_name, request.job_name, request.location_name)
    return {"status": "ok", "path": str(target.relative_to(PHOTO_ROOT))}


@app.post("/check")
def check_hashes(request: CheckRequest) -> dict[str, list[str]]:
    normalized = [h.lower() for h in request.hashes if re.fullmatch(r"[0-9a-fA-F]{64}", h)]
    if not normalized:
        return {"existing": []}
    placeholders = ",".join("?" for _ in normalized)
    with closing(db()) as connection:
        rows = connection.execute(
            f"SELECT hash, stored_path FROM uploaded_photos WHERE hash IN ({placeholders})", normalized
        ).fetchall()
    return {"existing": [row["hash"] for row in rows if (PHOTO_ROOT / row["stored_path"]).is_file()]}


@app.post("/upload-document")
async def upload_document(
    document: Annotated[UploadFile, File()], document_id: Annotated[str, Form()], sha256: Annotated[str, Form()],
    client_name: Annotated[str, Form()], job_name: Annotated[str, Form()], location_name: Annotated[str, Form()],
    filename: Annotated[str, Form()], page_count: Annotated[int, Form()], created_at: Annotated[str, Form()],
) -> dict[str, object]:
    expected = sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected): raise HTTPException(400, "Invalid SHA-256")
    target_dir = destination_dir(client_name, job_name, location_name); target_dir.mkdir(parents=True, exist_ok=True)
    target = available_target(target_dir, filename, expected)
    digest = hashlib.sha256(); size = 0
    fd, temp_name = tempfile.mkstemp(prefix=".document-", dir=target_dir); os.close(fd); temp = Path(temp_name)
    try:
        with temp.open("wb") as output:
            while chunk := await document.read(CHUNK_SIZE): digest.update(chunk); size += len(chunk); output.write(chunk)
        if digest.hexdigest() != expected: raise HTTPException(422, "SHA-256 mismatch")
        temp.replace(target)
        write_folder_info(target_dir, client_name, job_name, location_name, document_info={
            "document_id": document_id, "filename": target.name, "sha256": expected,
            "page_count": page_count, "created_at": created_at, "size_bytes": size,
        })
        return {"status": "uploaded", "sha256": expected, "size_bytes": size}
    finally:
        temp.unlink(missing_ok=True); await document.close()


@app.post("/note")
def save_note(request: NoteRequest) -> dict[str, str]:
    target_dir = destination_dir(request.client_name, request.job_name, request.location_name); target_dir.mkdir(parents=True, exist_ok=True)
    data_path = target_dir / "_DN_NOTES.json"
    notes: list[dict[str, str]] = []
    if data_path.is_file():
        try:
            loaded = json.loads(data_path.read_text(encoding="utf-8"))
            notes = loaded if isinstance(loaded, list) else []
        except (OSError, ValueError): pass
    notes = [note for note in notes if note.get("note_id") != request.note_id]
    notes.append(request.model_dump())
    notes.sort(key=lambda note: note.get("updated_at", ""), reverse=True)
    data_path.write_text(json.dumps(notes, ensure_ascii=False, indent=2), encoding="utf-8")
    markdown = ["# DN Notes", ""]
    for note in notes:
        markdown += [f"## {note.get('title', 'โน้ต')}", f"อัปเดต: {note.get('updated_at', '')}", "", note.get("content", ""), ""]
    (target_dir / "_DN_NOTES.md").write_text("\n".join(markdown), encoding="utf-8")
    return {"status": "ok"}


@app.post("/upload")
async def upload(
    photo: Annotated[UploadFile, File()],
    photo_id: Annotated[str, Form()],
    sha256: Annotated[str, Form()],
    client_name: Annotated[str, Form()],
    job_name: Annotated[str, Form()],
    location_name: Annotated[str, Form()],
    filename: Annotated[str, Form()],
    captured_at: Annotated[str, Form()],
    latitude: Annotated[float | None, Form()] = None,
    longitude: Annotated[float | None, Form()] = None,
    accuracy: Annotated[float | None, Form()] = None,
) -> dict[str, object]:
    expected = sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise HTTPException(400, "Invalid SHA-256")
    try:
        datetime.fromisoformat(captured_at)
    except ValueError as exc:
        raise HTTPException(400, "captured_at must be ISO-8601 with timezone") from exc

    target_dir = destination_dir(client_name, job_name, location_name)
    target_dir.mkdir(parents=True, exist_ok=True)

    with closing(db()) as connection:
        found = connection.execute("SELECT stored_path FROM uploaded_photos WHERE hash = ?", (expected,)).fetchone()
        if found and (PHOTO_ROOT / found["stored_path"]).is_file():
            existing = PHOTO_ROOT / found["stored_path"]
            requested = target_dir / safe_part(filename)
            if not requested.exists():
                target = available_target(target_dir, filename, expected)
                try:
                    os.link(existing, target)
                except OSError:
                    shutil.copy2(existing, target)
                requested = target
            write_folder_info(target_dir, client_name, job_name, location_name, {
                "photo_id": photo_id, "filename": requested.name, "sha256": expected,
                "captured_at": captured_at, "latitude": latitude, "longitude": longitude,
                "accuracy": accuracy, "backup_status": "already_exists",
            })
            return {"status": "already_exists", "hash": expected}
        if found:
            connection.execute("DELETE FROM uploaded_photos WHERE hash = ?", (expected,))
            connection.commit()

    target = available_target(target_dir, filename, expected)

    digest = hashlib.sha256()
    size = 0
    fd, temp_name = tempfile.mkstemp(prefix=".upload-", dir=target_dir)
    os.close(fd)
    temp = Path(temp_name)
    try:
        with temp.open("wb") as output:
            while chunk := await photo.read(CHUNK_SIZE):
                digest.update(chunk)
                size += len(chunk)
                output.write(chunk)
        if digest.hexdigest() != expected:
            raise HTTPException(422, "SHA-256 mismatch")
        temp.replace(target)
        with closing(db()) as connection:
            try:
                connection.execute(
                    """
                    INSERT INTO uploaded_photos
                    (hash, photo_id, client_name, job_name, location_name, filename,
                     stored_path, captured_at, latitude, longitude, accuracy, size_bytes, uploaded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (expected, photo_id, client_name, job_name, location_name, target.name,
                     str(target.relative_to(PHOTO_ROOT)), captured_at, latitude, longitude,
                     accuracy, size, datetime.now().astimezone().isoformat()),
                )
                connection.commit()
                write_folder_info(target_dir, client_name, job_name, location_name, {
                    "photo_id": photo_id, "filename": target.name, "sha256": expected,
                    "captured_at": captured_at, "latitude": latitude, "longitude": longitude,
                    "accuracy": accuracy, "size_bytes": size, "backup_status": "uploaded",
                })
            except sqlite3.IntegrityError:
                target.unlink(missing_ok=True)
                return {"status": "already_exists", "hash": expected}
    finally:
        temp.unlink(missing_ok=True)
        await photo.close()
    return {"status": "uploaded", "hash": expected, "size_bytes": size}
