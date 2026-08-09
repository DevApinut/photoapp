# DN — Photo Work Manager + Photo Sync

เอกสารส่งต่องานหลักของโปรเจกต์ DN ครอบคลุม Android App และ Computer Server  
อัปเดตล่าสุด: 9 สิงหาคม 2026

> **คำสั่งสำหรับการทำงานครั้งต่อไป:** อ่านไฟล์นี้ทั้งหมดก่อนแก้โค้ด ตรวจไฟล์จริงประกอบเสมอ และอัปเดต README เมื่อมีการเปลี่ยนฟีเจอร์ ฐานข้อมูล API วิธี Build หรือพฤติกรรมสำคัญ

## 1. เป้าหมายของระบบ

DN เป็นแอปจัดรูปงานภาคสนาม เช่น งานหลายจุดของ กฟภ. โดยเน้น:

- แยกรูปเป็นงานและโฟลเดอร์ซ้อนกันได้
- ใช้กล้อง OPPO เพื่อให้ได้การประมวลผลภาพของเครื่อง
- เก็บรูปคุณภาพเต็ม ไม่ลดขนาดสำหรับรูปปกติ
- รูปอยู่ใน Gallery และเชื่อมด้วย MediaStore URI
- Backup แบบ Manual เท่านั้น
- Sync ผ่าน Wi-Fi วงเดียวกันไปยังคอม
- ป้องกันไฟล์ซ้ำด้วย SHA-256
- รองรับรูป, PDF สแกนเอกสาร, โน้ต และข้อมูล GPS/เวลา

โครงสร้างหลัก:

```text
Android DN
  └─ งาน
      └─ โฟลเดอร์ (ซ้อนกี่ชั้นก็ได้)
          ├─ รูป
          ├─ PDF
          └─ โน้ต
                ↓ กด Backup/Sync เอง
Computer Server (FastAPI)
  ├─ PhotoBackup
  └─ SQLite hash ledger
```

## 2. โครงสร้าง Repository

```text
app/
├─ android/                         Android Studio project
│  └─ app/src/main/java/com/fieldphoto/app/
│     ├─ MainActivity.kt            Compose UI และ navigation หลัก
│     ├─ CameraPage.kt              กล้อง CameraX เดิม (UI หลักเน้นกล้อง OPPO)
│     ├─ PhotoApp.kt                Application และสร้าง repository
│     ├─ data/
│     │  ├─ Entities.kt             Room entities และ DTO
│     │  ├─ AppDao.kt               Room queries
│     │  ├─ AppDatabase.kt          Room DB version 2 + migration 1→2
│     │  └─ PhotoRepository.kt      กฎงาน/ไฟล์/ลบ/PDF/โน้ต
│     ├─ media/MediaStoreManager.kt MediaStore, EXIF, SHA-256, Timestamp
│     └─ sync/SyncClient.kt          Manual Sync ด้วย OkHttp
├─ server/
│  ├─ app.py                        FastAPI API และ SQLite
│  ├─ launcher.py                   ตัวเปิด Server สำหรับ EXE
│  ├─ DN-Photo-Server-V3.exe        EXE ล่าสุดที่ START script ใช้
│  ├─ START-DN-SERVER.cmd           เปิดและแสดงสถานะ/IP
│  ├─ STOP-DN-SERVER.cmd            ปิด Server ทุกชื่อเวอร์ชันเดิม
│  ├─ build_exe.ps1                 สร้าง EXE
│  ├─ requirements.txt              Python dependencies
│  ├─ tests/test_server.py          Server tests
│  └─ data/                         ข้อมูลจริงของ Server (ห้ามลบโดยไม่ตั้งใจ)
├─ docs/architecture.md             สถาปัตยกรรมฉบับย่อเดิม
└─ README.md                        เอกสารนี้
```

## 3. สถานะฟีเจอร์ Android ปัจจุบัน

### 3.1 งานและโฟลเดอร์

- ชื่อแอป: **DN**
- ไอคอน: รูปกล้องจาก `@drawable/dn_icon`
- หน้าแรกเป็นรายการงานแบบรวดเร็วภายใต้ลูกค้า `งานทั่วไป`
- สร้างงานโดยกรอกเพียงชื่องาน
- ถ้าชื่อซ้ำ ระบบสร้างชื่อแบบวงเล็บให้เอง
- เปลี่ยนชื่องานได้
- เลือกหลายงานและลบพร้อมกันได้ พร้อมยืนยัน
- ก่อนลบงาน แสดงจำนวน `WAITING`, `ERROR`, `UPLOADED`
- สร้างโฟลเดอร์เองและซ้อนหลายระดับด้วย path เช่น `จุด 1/ก่อนทำ/ตู้ไฟ`
- ลบโฟลเดอร์ได้ รวมโฟลเดอร์ย่อย
- Sync ส่งโฟลเดอร์ว่างไป Server ด้วย
- ปุ่ม Back ของ Android ย้อนหน้าภายในแอป ไม่ปิดแอปทันทีเมื่อมี history

### 3.2 Folder Template และสร้างหลายงาน

- สร้าง Template ได้หลายชุด
- Template รองรับโฟลเดอร์ย่อยหลายระดับ โดยใช้ `/`
- แก้ไขและลบ Template เดิมได้
- สร้างงานจากชื่อ Default ของ Template ได้
- ชื่อซ้ำจะเรียงแบบชื่อโฟลเดอร์ซ้ำ
- นำเข้ารายชื่องานหลายบรรทัดพร้อมกันได้
- ใช้ Template เดียวกับหลายงานพร้อมกันได้
- Template เก็บใน SharedPreferences ชื่อ `folder_templates` ไม่ได้อยู่ใน Room

### 3.3 ค้นหา เรียง และกรองงาน

- ค้นหาจากชื่องาน
- ค้นหาจากหัวข้อและรายละเอียดในโน้ตของงาน
- เรียงตามรูปล่าสุด, ชื่องาน A–Z หรือสร้างล่าสุด
- การเรียงรูปล่าสุดให้งานที่มีรูปขึ้นก่อน งานไม่มีรูปอยู่หลัง
- ปุ่ม `ⓘ` ของงานแสดงเวลาสร้างงานและเวลาของรูปล่าสุด
- ตัวกรองวันที่เลือกได้ว่าอ้างอิง:
  - วันที่รูป (`capturedAt` ของรูปล่าสุด)
  - วันที่สร้างงาน
- เลือกวันเริ่มและวันสิ้นสุดได้
- UI ล่าสุดยุบ Sort เป็น Dropdown และเปิด Filter ใน Dialog เพื่อลดปุ่มรกหน้าจอ

> หมายเหตุ: ฟิลด์ที่ใช้กับรูปคือ `capturedAt` ไม่ใช่เวลาที่นำไฟล์เข้าแอป หากอนาคตต้องกรอง “เวลานำเข้า DN จริง ๆ” ต้องเพิ่มคอลัมน์ใหม่และ Room migration

### 3.4 รูปและ Gallery

- UI หลักใช้ปุ่มเปิดกล้องระบบ/กล้อง OPPO
- ถ่ายต่อเนื่องหลายรูปในแอปกล้อง OPPO แล้วกลับ DN เพื่อดึงรูปใหม่ได้
- ถ้าปัด DN ทิ้งก่อนกลับ แอปจำเวลาและงานเดิมไว้ แล้วเสนอรายการรูปที่อาจยังไม่ได้นำเข้าเมื่อเปิดอีกครั้ง
- หน้ากู้รูปให้ผู้ใช้เลือกรูปเอง ป้องกันการดึงรูปจากกล้องปกติที่ไม่เกี่ยวกับงาน
- เพิ่มรูปจาก Gallery โดยอ้างอิงไฟล์เดิม ไม่สร้างสำเนาสำหรับรูปปกติ
- Android Photo Picker บาง URI ไม่มีสิทธิ์ลบไฟล์ต้นฉบับ แอปจึงลบได้เฉพาะรายการใน DN และต้องลบต้นฉบับจาก Gallery
- เมื่อกลับเข้าหน้าโฟลเดอร์ แอปตรวจไฟล์ที่หายจาก Gallery และเอาแถวที่หายออกจาก DN
- ลบทีละรูปหรือเลือกหลายรูปได้
- แชร์หลายรูปพร้อมกันได้
- ปรับ Grid 1–5 รูปต่อแถวได้
- Viewer เต็มจอ เลื่อนซ้าย/ขวาไปภาพถัดไป และซูมสองนิ้วได้
- ปุ่ม `ⓘ` ใน Viewer แสดงเวลา, GPS, accuracy และสถานะ Backup
- เปิดพิกัดใน Google Maps ได้

### 3.5 Timestamp

มี 3 ลักษณะซึ่งห้ามสับสน:

1. **Overlay ใน Viewer** — ตั้งค่า “แสดง Time stamp” แสดงข้อมูลทับเฉพาะในแอป ไม่แก้ไฟล์
2. **ถ่ายด้วยโหมด Timestamp + GPS** — หลังกล้อง OPPO ส่งรูปกลับ แอปเก็บรูปต้นฉบับใน DN และสร้างรูป Timestamp เพิ่ม
3. **สร้าง Timestamp ภายหลัง** — ในข้อมูลรูป กดสร้างสำเนา Timestamp จากรูปเดิมและ EXIF/GPS

ไฟล์ Timestamp:

- เป็นไฟล์ JPEG ใหม่ใน Gallery และ DN
- ใส่ข้อความโดยไม่มีแถบสีทึบบังภาพ
- ใช้ JPEG quality 100 แต่มีการ decode/re-encode เพราะต้องวาดข้อความ จึงไม่ใช่ byte-identical กับต้นฉบับ
- ถ้าลบไฟล์ Timestamp จาก Gallery รายการควรถูก reconcile ออกจาก DN เมื่อกลับเข้าหน้า

### 3.6 PDF Scanner

- ใช้ Google ML Kit Document Scanner
- นำเข้าจาก Gallery ใน Scanner ได้
- สูงสุด 50 หน้า
- บันทึก PDF ผ่าน `MediaStore.Downloads`
- เส้นทางปัจจุบันเป็น:

```text
Download/MyPhotoApp/<งาน>/<โฟลเดอร์>/SCAN_yyyyMMdd_HHmmss.pdf
```

- ต้องขึ้นต้นด้วย `Download` เพราะ OPPO/ColorOS ปฏิเสธการเขียน `Documents` ผ่าน Downloads collection ด้วย error `Primary directory Documents not allowed for content`
- PDF แสดงใน DN, เปิดด้วย PDF viewer, ลบ และ Sync ไป Server ได้

### 3.7 โน้ต

- เพิ่มโน้ตในแต่ละโฟลเดอร์
- แก้ไขโน้ตเดิมได้โดยแตะการ์ดหรือปุ่มดินสอ
- แก้ไขแล้วใช้ `note_id` เดิมและเปลี่ยนสถานะกลับเป็น `WAITING`
- ลบโน้ตได้
- ปุ่ม `ⓘ` แสดงเวลาแก้ไขล่าสุด, สถานะ Backup และ error
- การค้นหางานครอบคลุมหัวข้อและเนื้อหาโน้ต
- Server บันทึกโน้ตเป็น `_DN_NOTES.json` และ `_DN_NOTES.md`

### 3.8 Manual Sync / Backup

- ไม่มี Auto Sync, background service หรือ WorkManager
- ผู้ใช้ต้องกด Sync ทุกครั้ง
- เลือกสถานะที่จะ Sync ได้:
  - ยังไม่ Backup (`WAITING`)
  - สำเร็จแล้ว (`UPLOADED`)
  - ผิดพลาด (`ERROR`)
  - ทั้งหมด
- แสดง Progress ว่ากำลัง Sync, จำนวนทั้งหมด, สำเร็จ, ข้าม, ผิดพลาด และโฟลเดอร์
- ส่งโฟลเดอร์ก่อน ตามด้วย PDF, โน้ต และรูป
- ก่อนส่งรูป คำนวณ SHA-256 จาก bytes ปัจจุบันใหม่ หากเปลี่ยนจะอัปเดตฐานข้อมูลมือถือ
- ถ้า Server ตอบ SHA-256 mismatch จะคำนวณใหม่และลองอีกครั้งหนึ่ง
- สถานะรูป/PDF/โน้ตคือ `WAITING`, `UPLOADED`, `ERROR`

## 4. การจัดเก็บข้อมูลในมือถือ

Room database: `photo-work.sqlite3`, version 2

Entities:

- `ClientEntity`: id, name, createdAt
- `JobEntity`: id, clientId, name, createdAt
- `LocationEntity`: id, jobId, name/path, createdAt
- `PhotoEntity`: photo id, SHA-256, content URI, path, filename, capturedAt, GPS, accuracy, status, error
- `DocumentEntity`: PDF id, URI, filename, SHA-256, page count, createdAt, status, error
- `NoteEntity`: note id, title, content, updatedAt, status, error

Room migration 1→2 เพิ่ม `documents` และ `notes` ห้ามเปลี่ยน schema โดยไม่เพิ่ม version/migration

รูปที่ DN สร้างอยู่ใต้ MediaStore โดยโครงสร้างแนวคิดคือ:

```text
Pictures/MyPhotoApp/<งาน>/<โฟลเดอร์>/ไฟล์.jpg
```

ไฟล์จาก Gallery บางประเภทอ้างอิง URI เดิมและอาจไม่ได้อยู่ใต้ `MyPhotoApp`

## 5. Server ปัจจุบัน

เทคโนโลยี:

- FastAPI 0.116.1
- Uvicorn 0.35.0
- SQLite
- Python multipart upload
- Port `8080`
- HTTP บน LAN เท่านั้น

API:

| Method | Path | หน้าที่ |
|---|---|---|
| GET | `/health` | ตรวจว่า Server ทำงาน |
| POST | `/folder` | สร้างโฟลเดอร์ รวมโฟลเดอร์ว่าง |
| POST | `/check` | ตรวจ hash และตรวจว่าไฟล์จริงยังอยู่ |
| POST | `/upload` | รับรูป ตรวจ SHA-256 และ metadata |
| POST | `/upload-document` | รับ PDF และตรวจ SHA-256 |
| POST | `/note` | สร้างหรือแก้โน้ตด้วย `note_id` |

ข้อมูล Server:

```text
server/data/
├─ PhotoBackup/
│  └─ <งาน>/...                         เมื่อลูกค้าเป็น “งานทั่วไป”
│  └─ <ลูกค้า>/<งาน>/...                เมื่อลูกค้าเป็นชื่ออื่น
└─ photo_sync.sqlite3                    ตาราง uploaded_photos
```

ทุกโฟลเดอร์ที่ Server สร้างมี `_DN_INFO.json` ซึ่งเก็บ:

- client, job, folder
- updated_at
- รายการรูปพร้อม captured_at, GPS, accuracy, hash, size และ backup status
- รายการ PDF พร้อม page count, hash และ created_at

โน้ตเก็บใน `_DN_NOTES.json` และ `_DN_NOTES.md` เพื่อเปิดอ่านบนคอมได้ง่าย

### กฎ SHA-256 และการ Backup ซ้ำ

- `uploaded_photos.hash` เป็น primary key ป้องกันข้อมูลซ้ำ
- ถ้า hash เดิมและไฟล์เดิมยังอยู่ Server ตอบ `already_exists`
- ถ้า hash เดิมถูกใช้ในอีกงาน Server ทำ hard link หรือ copy ไปยังงานใหม่โดยไม่ต้องรับ bytes ซ้ำ
- **พฤติกรรมปัจจุบัน:** ถ้าผู้ใช้ย้าย/ลบไฟล์ Backup ออกจาก `PhotoBackup`, `/check` ถือว่าไม่มีไฟล์ และการ Sync สถานะ `UPLOADED`/ทั้งหมดจะส่งไฟล์กลับมาใหม่
- พฤติกรรมนี้ตั้งใจรองรับการย้าย Backup ไป HDD แล้วต้องการ Backup รอบใหม่
- Server รับไฟล์ลง temporary file, คำนวณ SHA-256 และย้ายเข้าปลายทางเมื่อถูกต้องเท่านั้น

## 6. วิธีเปิด Server

### วิธีใช้งานจริงบน Windows

1. เปิดโฟลเดอร์ `server`
2. ดับเบิลคลิก `START-DN-SERVER.cmd`
3. หน้าต่างจะแสดง `STATUS: RUNNING` และ IP เช่น `http://192.168.1.53:8080`
4. เปิดหน้าต่างนี้ค้างไว้ระหว่าง Backup
5. ปิดด้วย `STOP-DN-SERVER.cmd`

START script ใช้ `DN-Photo-Server-V3.exe` ซึ่งเป็น EXE ล่าสุดในขณะเขียนเอกสารนี้  
Windows Firewall ครั้งแรกให้อนุญาตเฉพาะ **Private networks**

### วิธีสำหรับนักพัฒนา

```powershell
cd server
py -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8080
```

ทดสอบ:

```powershell
cd server
pytest -q
```

Health check: `http://127.0.0.1:8080/health`

มือถือห้ามกรอก `localhost` เพราะหมายถึงมือถือเอง ต้องกรอก IP ของคอมและมือถือ/คอมต้องอยู่ Wi-Fi เดียวกัน

## 7. วิธี Build และทดสอบ Android

ค่าปัจจุบัน:

- package: `com.fieldphoto.app`
- minSdk: 29 (Android 10)
- targetSdk: 35
- compileSdk: 35
- Java/Kotlin target: 17
- app version: 1.0.0 (versionCode 1)

ขั้นตอน:

1. เปิดโฟลเดอร์ `android` ใน Android Studio
2. รอ Gradle Sync
3. ต่อมือถือ เปิด Developer options และ USB debugging
4. เลือกชื่อมือถือจาก Device selector ด้านบน
5. กด Run

`compileSdk 35` เป็น SDK ที่ใช้สร้างแอป ไม่ได้บังคับว่ามือถือต้องเป็น Android 15

สถานะการตรวจล่าสุด:

- ผู้ใช้ยืนยันว่า Android Studio Build/Run ได้ตามปกติ
- การตรวจจาก command line ของ Codex ถูกจำกัดด้วยสิทธิ์อ่าน Android SDK/license ของระบบ
- Android Studio รุ่นปัจจุบันมี JBR 25.0.2 แต่ Gradle 8.11.1 ใช้ Java 21 ได้แน่นอน; เครื่องมี `C:\Users\HP\.jdks\jbr-21.0.11`
- หาก Gradle ใน Android Studio มีปัญหา ให้ตั้ง Gradle JDK เป็น Java 21 หรือ JDK 17 แทน Java 25
- ฟีเจอร์ล่าสุด (แก้โน้ต, ค้นผ่านโน้ต, ข้อมูลงาน/โน้ต, Date filter, UI Sort/Filter ใหม่) ยังไม่ได้รับ command-line compile confirmation เพราะข้อจำกัด SDK ดังกล่าว จึงควร Run บนมือถือเป็น regression test ก่อนแก้ส่วนอื่น

## 8. สิทธิ์ Android และข้อจำกัด OPPO

Manifest ใช้:

- CAMERA
- FINE/COARSE LOCATION
- INTERNET
- READ_MEDIA_IMAGES (Android 13+)
- READ_EXTERNAL_STORAGE ถึง Android 12
- `usesCleartextTraffic=true` สำหรับ HTTP บน LAN

ข้อจำกัดสำคัญ:

- Android/OPPO อาจไม่ให้ DN ลบ URI จาก Photo Picker (`volume picker not found` หรือ `picker not found`)
- ห้ามสมมติว่า URI ทุกตัวลบได้ ต้องใช้ MediaStore delete request/approval หรือเอาเฉพาะแถว DN ออก
- กล้อง OPPO ทำ AI/processing ในแอปกล้องและ Gallery ของ OPPO; CameraX ไม่สามารถเรียก pipeline เดียวกันได้ จึงใช้กล้องระบบเป็นหลัก
- การออกจากกล้องด้วยการปัดปิดอาจไม่มี Activity result ระบบ recovery จึงใช้รายการรูปใหม่หลังเวลาเริ่มและต้องให้ผู้ใช้เลือก
- PDF ต้องบันทึกใต้ `Download` ไม่ใช่ `Documents` บนอุปกรณ์นี้

## 9. กฎข้อมูลที่ห้ามทำพัง

1. รูปปกติห้าม resize, recompress หรือแก้ EXIF
2. Timestamp เป็นข้อยกเว้นเพราะเป็นไฟล์ใหม่ที่ผู้ใช้ตั้งใจสร้าง
3. เปลี่ยน `UPLOADED` เฉพาะเมื่อ Server ตอบสำเร็จ
4. Sync ต้องเป็น Manual เท่านั้น
5. ห้ามลบ `server/data` หรือฐานข้อมูลมือถือโดยไม่แจ้งผู้ใช้
6. Schema Room เปลี่ยนเมื่อใด ต้องเพิ่ม Database version และ Migration
7. API เปลี่ยนเมื่อใด ต้องแก้ Android, Server, tests และ README พร้อมกัน
8. การลบ MediaStore ต้องรองรับ Android approval และ URI ที่แอปไม่มีสิทธิ์
9. ใช้ `contentUri` เปิดไฟล์ ห้ามสมมติว่ามีพาธ filesystem จริง
10. เวลาเก็บเป็น ISO-8601 พร้อม timezone เช่น `2026-08-05T14:32:18+07:00`

## 10. Test checklist ก่อนส่งงาน

### Android

- สร้างงานชื่อใหม่และชื่อซ้ำ
- สร้าง Template ที่มีโฟลเดอร์ซ้อน และแก้ Template
- เปิดโฟลเดอร์ซ้อนและกด Back
- ถ่ายหลายรูปด้วยกล้อง OPPO แล้วกลับ DN
- ปัดปิด DN ระหว่างกล้อง แล้วตรวจ recovery picker
- นำเข้ารูปจาก Gallery และตรวจ EXIF/GPS
- เปิด Viewer, swipe, pinch zoom และปุ่ม `ⓘ`
- สร้าง Timestamp ตอนถ่ายและภายหลัง
- ลบรูปจาก Gallery แล้วกลับ DN
- เลือกหลายรูปเพื่อแชร์และลบ
- สแกน PDF และตรวจใน `Download/MyPhotoApp`
- เพิ่ม/แก้/ลบโน้ต และค้นหางานด้วยข้อความในโน้ต
- ตรวจปุ่ม `ⓘ` งานและโน้ต
- ทดสอบ Sort และ Filter ทั้งสองประเภท/ช่วงวันที่
- Sync WAITING, ERROR, UPLOADED และทั้งหมด
- ลบงานที่มีไฟล์ยังไม่ Backup และตรวจคำเตือน

### Server

- เปิด START script แล้ว `/health` ตอบ `ok`
- Sync รูป, PDF, โน้ต และโฟลเดอร์ว่าง
- ส่ง hash เดิมแล้วได้ `already_exists`
- ย้ายไฟล์ออกจาก PhotoBackup แล้ว Sync UPLOADED/ทั้งหมด ต้องสร้างไฟล์กลับมา
- ตรวจ `_DN_INFO.json`, `_DN_NOTES.json`, `_DN_NOTES.md`
- รัน `pytest -q`

## 11. สิ่งที่ยังเป็นงานอนาคต

- Sync ผ่านอินเทอร์เน็ต/Tailscale
- API key และ HTTPS
- Browser gallery
- ค้นหาผ่านแผนที่
- Export ZIP และ PDF Report
- Backup/Restore Room database
- ย้ายรูปแบบถาวรแทนการอ้างอิง Gallery URI
- บันทึก `addedAt` แยกจาก `capturedAt` เพื่อกรองเวลานำเข้า DN จริง
- ปรับ versionCode/versionName และกระบวนการ Release APK
- รวมชื่อ EXE เก่าหลายเวอร์ชันให้เหลือไฟล์เดียวเมื่อยืนยัน V3 เสถียร

## 12. จุดเริ่มต้นสำหรับผู้ช่วยครั้งต่อไป

ก่อนทำงานต่อ:

1. อ่าน README นี้ทั้งหมด
2. อ่านไฟล์ที่เกี่ยวข้องจริง ห้ามอาศัยแชตเก่าอย่างเดียว
3. ตรวจว่า Android Studio มีการแก้ที่ยังไม่ Build หรือไม่
4. Preserve ข้อมูลใน `server/data` และการแก้เดิมของผู้ใช้
5. ทำการเปลี่ยนแปลงให้ครบ Android + Server + migration/tests ตามขอบเขต
6. ทดสอบตามความเสี่ยง
7. อัปเดตส่วนที่เกี่ยวข้องใน README และวันที่ด้านบน

