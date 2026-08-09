import hashlib
import importlib
import json

from fastapi.testclient import TestClient


def test_upload_and_deduplicate(tmp_path, monkeypatch):
    monkeypatch.setenv("PHOTO_SYNC_DATA", str(tmp_path))
    import app as module
    module = importlib.reload(module)
    module.init_db()
    client = TestClient(module.app)
    content = b"original-photo-bytes"
    digest = hashlib.sha256(content).hexdigest()
    data = {
        "photo_id": "p1", "sha256": digest, "client_name": "ABC",
        "job_name": "LHAY69002508", "location_name": "Point 1",
        "filename": "20260805_143210.jpg",
        "captured_at": "2026-08-05T14:32:18+07:00",
    }
    first = client.post("/upload", data=data, files={"photo": ("x.jpg", content, "image/jpeg")})
    second = client.post("/upload", data=data, files={"photo": ("x.jpg", content, "image/jpeg")})
    assert first.json()["status"] == "uploaded"
    assert second.json()["status"] == "already_exists"
    assert client.post("/check", json={"hashes": [digest]}).json() == {"existing": [digest]}
    assert (tmp_path / "PhotoBackup/ABC/LHAY69002508/Point 1/20260805_143210.jpg").read_bytes() == content
    info = json.loads((tmp_path / "PhotoBackup/ABC/LHAY69002508/Point 1/_DN_INFO.json").read_text(encoding="utf-8"))
    assert info["job"] == "LHAY69002508"
    assert info["photos"][0]["captured_at"] == "2026-08-05T14:32:18+07:00"
    catalog = client.get("/catalog").json()["photos"]
    assert catalog[0]["hash"] == digest
    assert client.get(f"/photo/{digest}").content == content

    same_photo_new_job = dict(data, job_name="LHAY69002509", location_name="Point 2")
    third = client.post("/upload", data=same_photo_new_job, files={"photo": ("x.jpg", content, "image/jpeg")})
    assert third.json()["status"] == "already_exists"
    assert (tmp_path / "PhotoBackup/ABC/LHAY69002509/Point 2/20260805_143210.jpg").read_bytes() == content


def test_creates_empty_folder_tree(tmp_path, monkeypatch):
    monkeypatch.setenv("PHOTO_SYNC_DATA", str(tmp_path))
    import app as module
    module = importlib.reload(module)
    module.init_db()
    client = TestClient(module.app)
    response = client.post("/folder", json={
        "client_name": "งานทั่วไป", "job_name": "Job Empty", "location_name": "จุด 1/ก่อนทำ"
    })
    assert response.status_code == 200
    assert (tmp_path / "PhotoBackup/Job Empty/จุด 1/ก่อนทำ").is_dir()
    info = json.loads((tmp_path / "PhotoBackup/Job Empty/จุด 1/ก่อนทำ/_DN_INFO.json").read_text(encoding="utf-8"))
    assert info["folder"] == "จุด 1/ก่อนทำ"
    assert info["photos"] == []

    note = client.post("/note", json={"note_id":"n1","client_name":"งานทั่วไป","job_name":"Job Empty",
        "location_name":"จุด 1/ก่อนทำ","title":"ตรวจงาน","content":"เรียบร้อย","updated_at":"2026-08-09T10:00:00+07:00"})
    assert note.status_code == 200
    assert "เรียบร้อย" in (tmp_path / "PhotoBackup/Job Empty/จุด 1/ก่อนทำ/_DN_NOTES.md").read_text(encoding="utf-8")


def test_rejects_wrong_hash(tmp_path, monkeypatch):
    monkeypatch.setenv("PHOTO_SYNC_DATA", str(tmp_path))
    import app as module
    module = importlib.reload(module)
    module.init_db()
    response = TestClient(module.app).post("/upload", data={
        "photo_id": "p1", "sha256": "0" * 64, "client_name": "ABC",
        "job_name": "Job", "location_name": "Place", "filename": "x.jpg",
        "captured_at": "2026-08-05T14:32:18+07:00",
    }, files={"photo": ("x.jpg", b"actual", "image/jpeg")})
    assert response.status_code == 422


def test_reuploads_when_backup_file_was_moved(tmp_path, monkeypatch):
    monkeypatch.setenv("PHOTO_SYNC_DATA", str(tmp_path))
    import app as module
    module = importlib.reload(module)
    module.init_db()
    client = TestClient(module.app)
    content = b"photo-to-rebackup"
    digest = hashlib.sha256(content).hexdigest()
    data = {
        "photo_id": "p2", "sha256": digest, "client_name": "งานทั่วไป",
        "job_name": "Job-001", "location_name": "เสา/ก่อนทำ",
        "filename": "photo.jpg", "captured_at": "2026-08-08T10:00:00+07:00",
    }
    first = client.post("/upload", data=data, files={"photo": ("photo.jpg", content, "image/jpeg")})
    saved = tmp_path / "PhotoBackup/Job-001/เสา/ก่อนทำ/photo.jpg"
    moved = tmp_path / "archive/photo.jpg"
    moved.parent.mkdir()
    saved.replace(moved)
    assert client.post("/check", json={"hashes": [digest]}).json() == {"existing": []}
    second = client.post("/upload", data=data, files={"photo": ("photo.jpg", content, "image/jpeg")})
    assert first.json()["status"] == "uploaded"
    assert second.json()["status"] == "uploaded"
    assert saved.read_bytes() == content


def test_same_job_name_with_different_ids_is_separated(tmp_path, monkeypatch):
    monkeypatch.setenv("PHOTO_SYNC_DATA", str(tmp_path))
    import app as module
    module = importlib.reload(module); module.init_db(); client = TestClient(module.app)
    first = client.post("/folder", json={"job_id":"job-a", "client_name":"งานทั่วไป", "job_name":"ชื่องานซ้ำ", "location_name":"จุด 1"})
    second = client.post("/folder", json={"job_id":"job-b", "client_name":"งานทั่วไป", "job_name":"ชื่องานซ้ำ", "location_name":"จุด 1"})
    assert first.status_code == second.status_code == 200
    assert (tmp_path / "PhotoBackup/ชื่องานซ้ำ/จุด 1").is_dir()
    assert (tmp_path / "PhotoBackup/ชื่องานซ้ำ (2)/จุด 1").is_dir()
