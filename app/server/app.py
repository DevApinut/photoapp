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
from fastapi.responses import FileResponse
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
        connection.execute("CREATE TABLE IF NOT EXISTS backup_jobs (job_id TEXT PRIMARY KEY, client_name TEXT NOT NULL, requested_name TEXT NOT NULL, storage_name TEXT NOT NULL)")
        columns = {row[1] for row in connection.execute("PRAGMA table_info(uploaded_photos)")}
        if "job_id" not in columns:
            connection.execute("ALTER TABLE uploaded_photos ADD COLUMN job_id TEXT")
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
    job_id: str | None = None
    client_name: str
    job_name: str
    location_name: str = ""


class NoteRequest(BaseModel):
    job_id: str | None = None
    note_id: str
    client_name: str
    job_name: str
    location_name: str = ""
    title: str
    content: str
    updated_at: str


def storage_job_name(client_name: str, job_name: str, job_id: str | None) -> str:
    requested = safe_part(job_name)
    if not job_id:
        return requested
    with closing(db()) as connection:
        found = connection.execute("SELECT client_name, requested_name, storage_name FROM backup_jobs WHERE job_id=?", (job_id,)).fetchone()
        if found:
            if found["client_name"] == client_name and found["requested_name"] == job_name:
                return found["storage_name"]
            old_parent = PHOTO_ROOT if str(found["client_name"]).strip() == "งานทั่วไป" else PHOTO_ROOT / safe_part(str(found["client_name"]))
            new_parent = PHOTO_ROOT if client_name.strip() == "งานทั่วไป" else PHOTO_ROOT / safe_part(client_name)
            old_path = old_parent / str(found["storage_name"])
            occupant = connection.execute(
                "SELECT job_id FROM backup_jobs WHERE client_name=? AND storage_name=? AND job_id<>?",
                (client_name, requested, job_id),
            ).fetchone()
            requested_path = new_parent / requested
            if occupant and requested_path.exists():
                archived = f"{requested} (เดิม)"; archive_number = 2
                while (new_parent / archived).exists() or connection.execute(
                    "SELECT 1 FROM backup_jobs WHERE client_name=? AND storage_name=?", (client_name, archived)
                ).fetchone():
                    archived = f"{requested} (เดิม {archive_number})"; archive_number += 1
                archived_path = new_parent / archived
                requested_path.rename(archived_path)
                old_prefix = requested_path.relative_to(PHOTO_ROOT).as_posix() + "/"
                archive_prefix = archived_path.relative_to(PHOTO_ROOT).as_posix() + "/"
                occupied_rows = connection.execute("SELECT hash, stored_path FROM uploaded_photos WHERE job_id=?", (occupant["job_id"],)).fetchall()
                for row in occupied_rows:
                    stored = str(row["stored_path"])
                    if stored.startswith(old_prefix):
                        connection.execute("UPDATE uploaded_photos SET stored_path=? WHERE hash=?",
                                           (archive_prefix + stored[len(old_prefix):], row["hash"]))
                connection.execute("UPDATE backup_jobs SET storage_name=? WHERE job_id=?", (archived, occupant["job_id"]))
            candidate = requested; number = 2
            while (new_parent / candidate).exists() and (new_parent / candidate) != old_path:
                candidate = f"{requested} ({number})"; number += 1
            new_path = new_parent / candidate
            if old_path.exists() and old_path != new_path:
                new_parent.mkdir(parents=True, exist_ok=True)
                old_path.rename(new_path)
                old_prefix = old_path.relative_to(PHOTO_ROOT).as_posix() + "/"
                new_prefix = new_path.relative_to(PHOTO_ROOT).as_posix() + "/"
                rows = connection.execute("SELECT hash, stored_path FROM uploaded_photos WHERE job_id=?", (job_id,)).fetchall()
                for row in rows:
                    stored = str(row["stored_path"])
                    if stored.startswith(old_prefix):
                        connection.execute("UPDATE uploaded_photos SET stored_path=?, client_name=?, job_name=? WHERE hash=?",
                                           (new_prefix + stored[len(old_prefix):], client_name, job_name, row["hash"]))
            else:
                connection.execute("UPDATE uploaded_photos SET client_name=?, job_name=? WHERE job_id=?", (client_name, job_name, job_id))
            connection.execute("UPDATE backup_jobs SET client_name=?, requested_name=?, storage_name=? WHERE job_id=?",
                               (client_name, job_name, candidate, job_id))
            connection.commit()
            return candidate
        parent = PHOTO_ROOT if client_name.strip() == "งานทั่วไป" else PHOTO_ROOT / safe_part(client_name)
        candidate = requested; number = 2
        while (parent / candidate).exists() or connection.execute(
            "SELECT 1 FROM backup_jobs WHERE client_name=? AND storage_name=?", (client_name, candidate)
        ).fetchone():
            candidate = f"{requested} ({number})"; number += 1
        connection.execute("INSERT INTO backup_jobs VALUES (?,?,?,?)", (job_id, client_name, job_name, candidate)); connection.commit()
        return candidate


def destination_dir(client_name: str, job_name: str, location_name: str, job_id: str | None = None) -> Path:
    stored_job = storage_job_name(client_name, job_name, job_id)
    if client_name.strip() == "งานทั่วไป":
        target_dir = PHOTO_ROOT / stored_job
    else:
        target_dir = PHOTO_ROOT / safe_part(client_name) / stored_job
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
    job_id: str | None = None,
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
        "job_id": job_id,
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


@app.get("/catalog")
def catalog() -> dict[str, list[dict[str, object]]]:
    """List available backup folders, photos, documents and notes."""
    with closing(db()) as connection:
        rows = connection.execute("""
            SELECT hash, job_id, client_name, job_name, location_name, filename, stored_path,
                   captured_at, latitude, longitude, accuracy, size_bytes
            FROM uploaded_photos ORDER BY captured_at DESC
        """).fetchall()
    photos = []
    for row in rows:
        stored = PHOTO_ROOT / row["stored_path"]
        if stored.is_file():
            photos.append({key: row[key] for key in (
                "hash", "job_id", "client_name", "job_name", "location_name", "filename",
                "captured_at", "latitude", "longitude", "accuracy", "size_bytes"
            )})
    folders: list[dict[str, object]] = []
    documents: list[dict[str, object]] = []
    notes: list[dict[str, object]] = []
    for info_path in PHOTO_ROOT.rglob(INFO_FILENAME):
        try:
            info = json.loads(info_path.read_text(encoding="utf-8"))
            if not isinstance(info, dict): continue
            identity = {"job_id": info.get("job_id"), "client_name": str(info.get("client", "")), "job_name": str(info.get("job", "")),
                        "location_name": str(info.get("folder", ""))}
            folders.append(identity)
            for document in info.get("documents", []):
                if isinstance(document, dict) and (info_path.parent / str(document.get("filename", ""))).is_file():
                    documents.append({**identity, **document})
            notes_path = info_path.parent / "_DN_NOTES.json"
            if notes_path.is_file():
                loaded_notes = json.loads(notes_path.read_text(encoding="utf-8"))
                if isinstance(loaded_notes, list):
                    notes.extend({**identity, **note} for note in loaded_notes if isinstance(note, dict))
        except (OSError, ValueError):
            continue
    # Rebuild missing parent folders from every resource path so older backups still browse hierarchically.
    known = {(str(item.get("job_id") or ""), item["client_name"], item["job_name"], item["location_name"]) for item in folders}
    for item in [*photos, *documents, *notes]:
        parts = [part for part in str(item.get("location_name", "")).split("/") if part]
        for index in range(len(parts) + 1):
            location = "/".join(parts[:index])
            key = (str(item.get("job_id") or ""), str(item.get("client_name", "")), str(item.get("job_name", "")), location)
            if key not in known:
                folders.append({"job_id": key[0] or None, "client_name": key[1], "job_name": key[2], "location_name": location})
                known.add(key)
    return {"folders": folders, "photos": photos, "documents": documents, "notes": notes}


@app.get("/photo/{photo_hash}")
def download_photo(photo_hash: str) -> FileResponse:
    normalized = photo_hash.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise HTTPException(400, "Invalid SHA-256")
    with closing(db()) as connection:
        row = connection.execute(
            "SELECT filename, stored_path FROM uploaded_photos WHERE hash = ?", (normalized,)
        ).fetchone()
    if row is None:
        raise HTTPException(404, "Photo not found")
    source = PHOTO_ROOT / row["stored_path"]
    if not source.is_file():
        raise HTTPException(404, "Backup file is no longer on this server")
    return FileResponse(source, media_type="image/jpeg", filename=row["filename"])


@app.get("/document/{document_id}")
def download_document(document_id: str) -> FileResponse:
    for info_path in PHOTO_ROOT.rglob(INFO_FILENAME):
        try:
            info = json.loads(info_path.read_text(encoding="utf-8"))
            for document in info.get("documents", []):
                if isinstance(document, dict) and str(document.get("document_id")) == document_id:
                    source = info_path.parent / safe_part(str(document.get("filename", "")))
                    if source.is_file(): return FileResponse(source, media_type=str(document.get("mime_type", "application/pdf")), filename=source.name)
        except (OSError, ValueError): pass
    raise HTTPException(404, "Document not found")


@app.post("/folder")
def create_folder(request: FolderRequest) -> dict[str, str]:
    target = destination_dir(request.client_name, request.job_name, request.location_name, request.job_id)
    target.mkdir(parents=True, exist_ok=True)
    write_folder_info(target, request.client_name, request.job_name, request.location_name, job_id=request.job_id)
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
    job_id: Annotated[str | None, Form()] = None, mime_type: Annotated[str, Form()] = "application/pdf",
) -> dict[str, object]:
    expected = sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected): raise HTTPException(400, "Invalid SHA-256")
    target_dir = destination_dir(client_name, job_name, location_name, job_id); target_dir.mkdir(parents=True, exist_ok=True)
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
            "page_count": page_count, "created_at": created_at, "size_bytes": size, "mime_type": mime_type,
        }, job_id=job_id)
        return {"status": "uploaded", "sha256": expected, "size_bytes": size}
    finally:
        temp.unlink(missing_ok=True); await document.close()


@app.post("/note")
def save_note(request: NoteRequest) -> dict[str, str]:
    target_dir = destination_dir(request.client_name, request.job_name, request.location_name, request.job_id); target_dir.mkdir(parents=True, exist_ok=True)
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
    job_id: Annotated[str | None, Form()] = None,
) -> dict[str, object]:
    expected = sha256.lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise HTTPException(400, "Invalid SHA-256")
    try:
        datetime.fromisoformat(captured_at)
    except ValueError as exc:
        raise HTTPException(400, "captured_at must be ISO-8601 with timezone") from exc

    target_dir = destination_dir(client_name, job_name, location_name, job_id)
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
            }, job_id=job_id)
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
                     stored_path, captured_at, latitude, longitude, accuracy, size_bytes, uploaded_at, job_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (expected, photo_id, client_name, job_name, location_name, target.name,
                     str(target.relative_to(PHOTO_ROOT)), captured_at, latitude, longitude,
                     accuracy, size, datetime.now().astimezone().isoformat(), job_id),
                )
                connection.commit()
                write_folder_info(target_dir, client_name, job_name, location_name, {
                    "photo_id": photo_id, "filename": target.name, "sha256": expected,
                    "captured_at": captured_at, "latitude": latitude, "longitude": longitude,
                    "accuracy": accuracy, "size_bytes": size, "backup_status": "uploaded",
                }, job_id=job_id)
            except sqlite3.IntegrityError:
                target.unlink(missing_ok=True)
                return {"status": "already_exists", "hash": expected}
    finally:
        temp.unlink(missing_ok=True)
        await photo.close()
    return {"status": "uploaded", "hash": expected, "size_bytes": size}
