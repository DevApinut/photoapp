# DN — Photo Work Manager + Photo Sync

เอกสารส่งต่องานหลักของโปรเจกต์ DN ครอบคลุม Android App และ Computer Server  
อัปเดตล่าสุด: 11 สิงหาคม 2026

> **คำสั่งสำหรับการทำงานครั้งต่อไป:** อ่านไฟล์นี้ทั้งหมดก่อนแก้โค้ด ตรวจไฟล์จริงประกอบเสมอ และอัปเดต README เมื่อมีการเปลี่ยนฟีเจอร์ ฐานข้อมูล API วิธี Build หรือพฤติกรรมสำคัญ

## สถานะส่งต่อล่าสุด — V5

- Android และ Server ใช้ `job_id` เป็นตัวตนงาน แยกจากชื่อที่ผู้ใช้มองเห็น
- งานมือถือและงาน Server ที่ชื่อเหมือนกันต้องแสดงพร้อมกัน ห้ามซ่อน Cloud ด้วยการเทียบชื่อ
- Server เก็บงานคนละ `job_id` แยกโฟลเดอร์เป็น `ชื่องาน`, `ชื่องาน (2)`, `ชื่องาน (3)` ไม่รวมข้อมูลเข้าด้วยกัน
- Cloud Catalog มีโฟลเดอร์ว่าง รูป PDF และโน้ต และต้องแสดงตาม hierarchy จริง
- PDF Cloud อ่านด้วย `PdfRenderer` และโน้ตอ่านใน DN ไม่ส่งไป Chrome
- ดาวน์โหลดกลับได้ทั้งรูปเดียว โฟลเดอร์ปัจจุบัน หรือทั้งงาน รวมโฟลเดอร์ย่อย รูป PDF และโน้ต
- การกู้กลับที่ชื่อชนกับงานมือถือสร้างชื่อวงเล็บใหม่ ไม่รวมกับงานเดิม
- Android `:app:compileDebugKotlin` ผ่านหลังการเปลี่ยนแปลงชุดนี้
- Server ที่ต้องใช้คือ `DN-Photo-Server-V5.exe`; START/STOP scripts รองรับ V5 แล้ว
- Compatibility สำคัญ: Backup เก่ามี `job_id=null`; Android ต้อง normalize ค่า JSON `null` เป็นค่าว่าง แล้วจัดกลุ่มด้วย `client_name + job_name` ห้ามใช้ข้อความ `"null"` เป็น ID เพราะจะทำให้งานเก่าทุกงานถูกรวมกัน
- Server Catalog สังเคราะห์โฟลเดอร์แม่จาก path ของรูป/PDF/โน้ต เพื่อซ่อม hierarchy ของ Backup เก่าที่ `_DN_INFO.json` ไม่ครบ
- งาน Local และ Server ที่มี `job_id` เดียวกันแสดงเป็นการ์ดเดียว; งานชื่อเหมือนแต่ ID ต่างกันยังต้องแยก
- ภายในโฟลเดอร์ รูป Local/Server จับคู่ด้วย SHA-256: Hash เดียวกันแสดง `CloudDone`, เฉพาะ Server แสดงโปร่งพร้อมก้อนเมฆ

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
│     │  ├─ AppDatabase.kt          Room DB version 3 + migration 1→2 และ 2→3
│     │  └─ PhotoRepository.kt      กฎงาน/ไฟล์/ลบ/PDF/วิดีโอ/โน้ต
│     ├─ media/MediaStoreManager.kt MediaStore, EXIF, SHA-256, Timestamp
│     └─ sync/SyncClient.kt          Manual Sync ด้วย OkHttp
├─ server/
│  ├─ app.py                        FastAPI API และ SQLite
│  ├─ launcher.py                   ตัวเปิด Server สำหรับ EXE
│  ├─ DN-Photo-Server-V5.exe        EXE ล่าสุดที่ START script ใช้ (job_id + Cloud Restore)
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
- หน้าจัดการ Template แสดง Preview แบบต้นไม้ขณะพิมพ์ และสร้างโฟลเดอร์แม่ที่ขาดให้อัตโนมัติ
- หากต้องการโฟลเดอร์พี่น้องหลายอันในแม่เดียวกัน ให้กรอก path เต็มแยกบรรทัด เช่น `จุดที่ 1/ก่อนทำ/ตู้ไฟ 1`, `จุดที่ 1/ก่อนทำ/ตู้ไฟ 2`, `จุดที่ 1/ก่อนทำ/ตู้ไฟ 3`; ระบบสร้าง `จุดที่ 1/ก่อนทำ` เพียงครั้งเดียว
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
- หน้า Settings เลือกเปิด/ปิดข้อความบนไฟล์ Timestamp แยกได้: ชื่องาน/โฟลเดอร์, วันที่, เวลา, พิกัด, accuracy และที่อยู่เต็ม
- ปรับขนาดฟอนต์ได้ 60–180% และใช้ค่ากับทั้งโหมดถ่าย Timestamp และสร้างภายหลัง
- ตัวเลือกที่อยู่ใช้ Android Geocoder จาก GPS และพยายามแสดงบ้านเลขที่ ซอย ถนน ตำบล/แขวง อำเภอ/เขต จังหวัด รหัสไปรษณีย์ และประเทศตามข้อมูลที่บริการมี
- ที่อยู่ยาวจะตัดขึ้นบรรทัดใหม่อัตโนมัติเพื่อไม่ให้ล้นรูป; หาก Geocoder/อินเทอร์เน็ตไม่มีข้อมูล จะข้ามบรรทัดที่อยู่โดยยังสร้าง Timestamp ส่วนอื่นตามปกติ

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

### 3.9 ย้ายรูป/โฟลเดอร์ข้ามงาน

- กดค้างเพื่อเลือกรูปหนึ่งหรือหลายรูป แล้วกดไอคอนย้าย
- เลือกงานและโฟลเดอร์ปลายทางได้
- ย้ายโฟลเดอร์ทั้งก้อนข้ามงานได้ รวมโฟลเดอร์ย่อย รูป PDF และโน้ต
- การย้ายเปลี่ยนโครงสร้างใน DN และปลายทาง Backup โดยอ้างอิงไฟล์รูปเดิม ไม่สร้างสำเนาและไม่บีบอัดรูป
- รายการที่ย้ายถูกตั้งกลับเป็น `WAITING` เพื่อส่งไปตำแหน่งใหม่ในการ Backup ครั้งถัดไป
- ป้องกันการย้ายโฟลเดอร์เข้าโฟลเดอร์ย่อยของตัวเอง

### 3.10 Cloud Gallery จาก Server

- หน้า “งานทั้งหมด” เรียก `/catalog` เมื่อ Server พร้อมใช้งาน โดย Catalog มีโฟลเดอร์ว่าง รูป PDF และโน้ต
- งานที่ถูกลบจากมือถือแต่ไฟล์ยังอยู่ Server แสดงเป็นการ์ดเทาอ่อนพร้อมไอคอนก้อนเมฆ
- งาน Cloud ไม่ถูกซ่อนเมื่อชื่อซ้ำกับงานมือถือ ทั้งสองรายการแสดงแยกกันพร้อมสถานะ Server
- รายการ Cloud ใช้พื้นเทาอ่อนทั้งก้อน; รูปแสดงโปร่งเล็กน้อยและมีก้อนเมฆมุมภาพโดยไม่มีป้ายสี่เหลี่ยมทับรูป
- แตะรูป Cloud เปิด Viewer เต็มจอจากไฟล์ต้นฉบับบน Server รองรับ swipe ซ้าย/ขวา, pinch zoom 1–8 เท่า, ปุ่ม +/− และรีเซ็ต
- Cloud Viewer มีปุ่มข้อมูล แสดงชื่อไฟล์ โฟลเดอร์ เวลา GPS/accuracy และดาวน์โหลดรูปปัจจุบันกลับเข้า DN
- หน้า Cloud เข้าโฟลเดอร์ทีละระดับเหมือนข้อมูลบน Server; รูป PDF และโน้ตแสดงเฉพาะในโฟลเดอร์ของตัวเอง
- ปุ่ม Back ภายในหน้า Cloud ย้อนขึ้นโฟลเดอร์ก่อนหน้า
- โน้ตเปิดอ่านใน Dialog ของ DN
- PDF ดาวน์โหลดเข้า cache ชั่วคราวและเรนเดอร์ทุกหน้าด้วย Android `PdfRenderer` ภายใน DN ไม่เปิด Chrome และไม่สร้างไฟล์ถาวรใน Download
- เปิดดูรายละเอียดรูปบน Server ได้เมื่อมือถือเชื่อมต่อ Server
- กด “ดาวน์โหลดกลับ” เพื่อสร้างงาน/โฟลเดอร์เดิมใน DN และบันทึกรูปกลับเข้า `Pictures/MyPhotoApp`
- ปุ่มดาวน์โหลดด้านบนกู้กลับได้ทั้งงานหรือเฉพาะโฟลเดอร์ปัจจุบัน รวมโฟลเดอร์ย่อย รูป PDF และโน้ต
- ถ้าชื่องานที่กู้กลับซ้ำกับงานมือถือ ระบบสร้างชื่อ `(2)`, `(3)` โดยไม่รวมข้อมูลเข้าด้วยกัน
- ตรวจ SHA-256 หลังดาวน์โหลดก่อนเพิ่มรูปใน Room
- รูปที่ดาวน์โหลดสำเร็จใช้สถานะ `UPLOADED`
- ถ้า Server ปิด งาน/รูป Cloud จะไม่โหลด แต่ข้อมูลในมือถือยังใช้งานได้ตามปกติ

## 4. การจัดเก็บข้อมูลในมือถือ

Room database: `photo-work.sqlite3`, version 3

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
| GET | `/catalog` | แสดงโฟลเดอร์ รูป PDF และโน้ตที่ยังอยู่บน Server |
| GET | `/photo/{sha256}` | เปิดหรือดาวน์โหลดรูปจาก Server |
| GET | `/document/{document_id}` | เปิด PDF จาก Server |

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
- Android ส่ง `job_id` ในการ Sync ทุกชนิด; Server เก็บ mapping ในตาราง `backup_jobs`
- งานคนละ `job_id` ที่ชื่อเหมือนกันถูกเก็บเป็นคนละโฟลเดอร์ เช่น `งาน A` และ `งาน A (2)`
- ฐานข้อมูล Server เดิมถูก migrate โดยเพิ่ม `uploaded_photos.job_id` โดยไม่ลบประวัติเดิม

## 6. วิธีเปิด Server

### วิธีใช้งานจริงบน Windows

1. เปิดโฟลเดอร์ `server`
2. ดับเบิลคลิก `START-DN-SERVER.cmd`
3. หน้าต่างจะแสดง `STATUS: RUNNING` และ IP เช่น `http://192.168.1.53:8080`
4. เปิดหน้าต่างนี้ค้างไว้ระหว่าง Backup
5. ปิดด้วย `STOP-DN-SERVER.cmd`

START script ใช้ `DN-Photo-Server-V5.exe` ซึ่งเป็น EXE ล่าสุดและรองรับการแยกงานชื่อซ้ำ/กู้ Cloud ทั้งชุด  
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
- การตรวจจาก command line ใช้ Java 21 และ `:app:compileDebugKotlin` ได้สำเร็จ
- Android Studio รุ่นปัจจุบันมี JBR 25.0.2 แต่ Gradle 8.11.1 ใช้ Java 21 ได้แน่นอน; เครื่องมี `C:\Users\HP\.jdks\jbr-21.0.11`
- หาก Gradle ใน Android Studio มีปัญหา ให้ตั้ง Gradle JDK เป็น Java 21 หรือ JDK 17 แทน Java 25
- Android `:app:compileDebugKotlin` ผ่านด้วย Java 21 หลังเพิ่ม Gesture viewer, PDF import/share, Local+Cloud merge และ UI compact
- Server test suite เดิมผ่านครบ และเพิ่ม test ยืนยันว่างานชื่อเหมือนกันแต่ต่าง `job_id` ถูกแยกเป็นคนละโฟลเดอร์
- สร้าง `DN-Photo-Server-V5.exe` สำเร็จแล้ว; ต้องใช้ V5 คู่กับ Android รุ่น `job_id`

## 8. สิทธิ์ Android และข้อจำกัด OPPO

### Internet / Port Forwarding

- Settings รับ URL เต็ม เช่น `http://192.168.1.53:8080` ไม่ใช่เฉพาะ path
- การทำ Port Forward แล้วเปลี่ยนเป็น `http://public-ip:port` อาจเชื่อมต่อทางเทคนิคได้ แต่ **ห้ามใช้กับ Server รุ่นปัจจุบันบนอินเทอร์เน็ต**
- API ปัจจุบันไม่มี authentication/API key และ HTTP ไม่เข้ารหัส รูป GPS PDF และโน้ตอาจถูกอ่านหรือแก้ระหว่างทาง
- หากต้องใช้นอก Wi-Fi เดียวกัน ให้ทำ Tailscale ก่อน หรือเพิ่ม API key + HTTPS และจำกัด Firewall

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
- เปิด Viewer, double-tap, pinch ตาม centroid, edge preview 50%, swipe และปุ่ม `ⓘ`
- สร้าง Timestamp ตอนถ่ายและภายหลัง
- ลบรูปจาก Gallery แล้วกลับ DN
- เลือกหลายรูปเพื่อแชร์และลบ
- สแกนและนำเข้า PDF ตรวจใน `Download/MyPhotoApp`, แชร์เดี่ยว/หลายไฟล์ และยืนยันก่อนลบ
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
- รวมชื่อ EXE เก่าหลายเวอร์ชันให้เหลือไฟล์เดียวเมื่อยืนยัน V5 เสถียร
- รวม cloud-only folder เข้า hierarchy ของงาน Local โดยไม่สร้างข้อมูล Room ปลอม

## 12. จุดเริ่มต้นสำหรับผู้ช่วยครั้งต่อไป

ก่อนทำงานต่อ:

1. อ่าน README นี้ทั้งหมด
2. อ่านไฟล์ที่เกี่ยวข้องจริง ห้ามอาศัยแชตเก่าอย่างเดียว
3. ตรวจว่า Android Studio มีการแก้ที่ยังไม่ Build หรือไม่
4. Preserve ข้อมูลใน `server/data` และการแก้เดิมของผู้ใช้
5. ทำการเปลี่ยนแปลงให้ครบ Android + Server + migration/tests ตามขอบเขต
6. ทดสอบตามความเสี่ยง
7. อัปเดตส่วนที่เกี่ยวข้องใน README และวันที่ด้านบน

## 13. รายละเอียดการเปลี่ยนแปลงล่าสุด — 10 สิงหาคม 2026

หัวข้อนี้เป็นบันทึกส่งต่อสำหรับ AI/ผู้พัฒนารอบถัดไป ต้องอ่านร่วมกับหัวข้อ 3–12 และตรวจโค้ดจริงก่อนแก้

### 13.1 Timestamp, GPS และชื่อที่อยู่

ไฟล์หลัก: `media/MediaStoreManager.kt`, `data/PhotoRepository.kt`, `MainActivity.kt`

- Settings มีสวิตช์แยกสำหรับชื่องาน/โฟลเดอร์, วันที่, เวลา, Lat/Lon, Accuracy และถนน/ซอย/ที่อยู่
- ขนาดตัวอักษร Timestamp ตั้งได้ 60–180%; ข้อความยาวถูกตัดบรรทัดตามความกว้างภาพ
- ชื่อที่อยู่ได้จาก Android `Geocoder` ภาษาไทย โดยอาศัย latitude/longitude ไม่ใช่การอ่านข้อความจาก Gallery OPPO
- ถ้าเปิดที่อยู่แต่รูปไม่มี GPS ต้องเขียน `ที่อยู่: ไม่มีพิกัด GPS ในรูป`; ถ้ามีพิกัดแต่ reverse-geocode ไม่สำเร็จต้องเขียน `ที่อยู่: ค้นหาชื่อสถานที่ไม่สำเร็จ` ห้ามหายเงียบ
- พิกัด `(0,0)` ถือว่าไม่ถูกต้องทั้งจาก EXIF และฐานข้อมูล ห้ามนำไปพิมพ์บนรูป
- การถ่าย/นำเข้าจาก OPPO ขอ GPS ปัจจุบันด้วย `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` แทนการเชื่อ `lastLocation` อย่างเดียว
- ปุ่มสร้าง Timestamp ภายหลังอ่าน EXIF ของไฟล์ต้นฉบับก่อน แล้วจึง fallback ไปข้อมูล Room ที่ผ่านการตรวจ `(0,0)`
- การตั้งค่า Timestamp มีผลเฉพาะไฟล์ Timestamp ที่สร้างใหม่ รูป Timestamp เดิมไม่เปลี่ยนย้อนหลัง ต้องลบ/สร้างใหม่เอง
- Gallery OPPO อาจแสดงสถานที่จากฐานข้อมูลเฉพาะของ OPPO โดยไม่ได้เขียนลง EXIF; กรณีนี้ DN/แอปอื่นไม่สามารถอ่านพิกัดนั้นได้

### 13.2 Viewer ความละเอียดเต็มและ Gesture

ไฟล์หลัก: `MainActivity.kt` — `PhotoViewer`, `CloudPhotoViewer`, `constrainedImageOffset`

- Viewer Local และ Cloud ใช้ Coil `ImageRequest.size(Size.ORIGINAL)` + `Precision.EXACT`; ห้ามเปลี่ยนกลับเป็นการ decode ตามขนาดจอ เพราะซูมแล้วตัวหนังสือจะแตก
- ไฟล์ต้นฉบับไม่ได้ถูก resize/recompress จากการดูรูป การโหลด Original ใช้ RAM และอาจเปิดครั้งแรกช้ากว่าเดิม
- แตะสองครั้งสลับระหว่าง 1× และ 2.5×; pinch รองรับ 1–8×
- Pinch ต้องยึด centroid ระหว่างสองนิ้ว ไม่ใช่กึ่งกลางหน้าจอ สูตร offset ชดเชยตาม zoom factor
- ขอบเขต pan คำนวณจากขนาดภาพหลัง `ContentScale.Fit` และ viewport จริง เพื่อไม่ให้ลากขึ้น/ลงไปเห็นพื้นที่ดำเกินภาพ
- ขณะ scale > 1 ปิด native pager swipe แล้วใช้ edge-preview ของ DN:
  - ลากภาพถึงขอบซ้าย/ขวาแล้วลากต่อ รูปข้างเคียงค่อย ๆ โผล่ตามนิ้ว
  - ลากย้อนกลับได้ก่อนปล่อย
  - ต้องผ่าน 50% ของความกว้างจอจึงยืนยันเปลี่ยนรูป
  - ไม่ถึงระยะให้ spring กลับอย่างนุ่มนวล
  - ผ่านระยะให้ preview เลื่อนปิดเต็มจอ แล้วรีเซ็ต scale/offset ก่อน `scrollToPage` เพื่อป้องกันรูปใหญ่แวบ
- Pager ตัด content นอกขอบ page จึงมี AsyncImage preview แยกนอก Pager; อย่าลบชั้นนี้ มิฉะนั้นเห็นพื้นดำแทนรูปข้างเคียง
- พฤติกรรมทั้งหมดต้องเหมือนกันใน Local และ Cloud viewer

### 13.3 การย้ายรูปและโฟลเดอร์

ไฟล์หลัก: `MainActivity.kt` — move picker, `PhotoRepository.movePhotos/moveFolder`

- การย้ายใช้ browser ปลายทางแบบ File Manager ไม่ใช้รายการ path ยาวแบบเดิม
- ขั้นตอน: เลือกงาน → เข้าโฟลเดอร์ซ้อนได้หลายชั้น → Back ถอยโฟลเดอร์แม่/รายการงาน → กด `ย้ายมาที่นี่`
- การแตะโฟลเดอร์เป็นเพียงการเข้าไปดู ห้ามย้ายทันที
- ปุ่ม Back ของ Android ขณะ dialog เปิดต้องย้อน hierarchy ก่อน ไม่ปิด dialog ตั้งแต่ครั้งแรก
- ห้ามย้ายโฟลเดอร์เข้า subtree ของตัวเอง; repository มี validation นี้อยู่
- `moveFolder` ย้ายรูป, PDF และโน้ตใน subtree พร้อมสร้างปลายทางที่ขาด

### 13.4 Template งาน

- Template รองรับ path ซ้อนด้วย `/` และขยาย parent path อัตโนมัติ เช่น `ก่อนทำ/ตู้ไฟ 1`
- กดใช้ Template ต้องเปิด dialog ตั้งชื่องานก่อน ห้ามสร้างทันที
- ช่องชื่อ prefill ด้วยชื่อ Template; ผู้ใช้แก้ได้ ถ้าปล่อยว่างใช้ default
- ใช้ `createQuickJobFromTemplate` เพื่อสร้างชื่อไม่ซ้ำเป็น `(2)`, `(3)` และคืน `actualName` สำหรับเปิดหน้าที่ถูกต้อง
- การสร้างงานเปล่าและ dialog ชื่อทั่วไปยังใช้ปุ่ม `บันทึก`; label `สร้างงาน` ใช้เฉพาะ flow สร้างงาน

### 13.5 PDF: สแกน, นำเข้า, แชร์ และลบ

ไฟล์หลัก: `PhotoRepository.saveScannedPdf/importPdf/removeDocument`, `PhotosPage`

- `สแกน PDF` ใช้ ML Kit Document Scanner และบันทึกใต้ `Download/MyPhotoApp/...`
- `นำเข้า PDF` ใช้ `ActivityResultContracts.OpenDocument()` MIME `application/pdf`
- การนำเข้าคัดลอกไฟล์เข้า storage ของ DN, คงชื่อเดิม, เปิดด้วย `PdfRenderer` เพื่อนับหน้า, คำนวณ SHA-256 และสร้าง `DocumentEntity` สถานะ `WAITING`
- PDF ที่นำเข้า/สแกนถูก Sync ขึ้น Server ด้วย flow เอกสารเดิมและปรากฏใน Cloud Catalog
- แชร์ PDF เดี่ยวใช้ `ACTION_SEND`, `application/pdf`, `ClipData` และ `FLAG_GRANT_READ_URI_PERMISSION`
- แชร์หลาย PDF: กดค้างไฟล์แรก → แตะเพิ่ม → ปุ่มแชร์ด้านบน ใช้ `ACTION_SEND_MULTIPLE` พร้อม URI/ClipData ทุกไฟล์
- ระหว่างเลือกหลาย PDF ปุ่มแชร์/ลบรายไฟล์ถูกซ่อน ลดการกดผิด
- ลบ PDF ต้องมี confirm dialog แสดงชื่อ, page count และเตือนถ้า status ไม่ใช่ `UPLOADED`; ห้ามลบทันทีจากไอคอน
- การลบ PDF จะลบทั้งแถว Room และไฟล์ในพื้นที่เอกสารที่ DN สร้าง

### 13.6 คำค้นหาและ UI รายการงาน

- `QuickJobsPage` เก็บคำค้นหาใน SharedPreferences `job_list/search`
- เข้าไปดูงานแล้ว Back คำค้นหาต้องคงอยู่ รวมถึงการ re-create composable; กด X จึงล้างค่า preference
- Sort เก็บใน `job_list/sort` ตามเดิม
- การ์ดงานจัดสองแถวแบบ compact เพื่อไม่ให้ไอคอนเบียดชื่อเป็นแนวตั้ง:
  - แถวบน: folder, ชื่องาน, สถานะ Local/Server
  - แถวล่าง: Cloud, info, rename, delete
  - vertical padding 6dp, folder 40dp, action touch area 36dp, icon 20dp
- หากจะเพิ่ม action ใหม่ ห้ามนำกลับไปวางต่อท้ายข้อความแถวบน; ใช้แถว action หรือ overflow menu

### 13.7 การรวม Local + Server

ไฟล์หลัก: `QuickJobsPage`, `PhotosPage`, `CloudClient.catalog`

- งานจับคู่ด้วย `job_id` เท่านั้น งานชื่อเดียวกันแต่ ID ต่างกันต้องแสดงแยก
- `cloudOnlyJobs` คือ Cloud jobs ที่ไม่มี ID ใน local rows; แสดงแบบโปร่ง/Cloud เหมือนเดิม
- งานที่ ID มีทั้งสองฝั่งแสดงการ์ด Local เพียงใบเดียว พร้อม `CloudDone` และข้อความ `อยู่ในเครื่อง + Server`
- ไอคอน Cloud บนการ์ดเปิด Cloud view ของงานนั้น ส่วนแตะตัวการ์ดเปิด Local hierarchy
- `PhotosPage` โหลด Cloud Catalog ของ `place.jobId` แล้วกรองด้วย `locationName == place.name`
- รูปจับคู่ด้วย SHA-256 (`PhotoEntity.sha256` กับ `CloudPhoto.hash`) ไม่ใช้ filename
- Hash มีทั้งสองฝั่ง: แสดงรูป Local ครั้งเดียวพร้อม `CloudDone`
- เฉพาะ Server: แสดงรูปโปร่งพร้อมก้อนเมฆ เปิดด้วย `CloudPhotoViewer`; ไม่สร้างสำเนาในเครื่องจนกว่าผู้ใช้สั่งดาวน์โหลด
- ปัจจุบันการรวมรูปทำในโฟลเดอร์ Local ที่มีอยู่ หากมี folder ที่อยู่เฉพาะ Server ทั้งก้อน ผู้ใช้ยังเปิดผ่านปุ่ม Cloud ของการ์ดงาน; งานอนาคตคือรวม cloud-only folder เข้า local hierarchy โดยไม่สร้าง Room row ปลอม
- Catalog refresh ปัจจุบันเกิดเมื่อ `serverUrl`, `rows`, `jobId` หรือ composable เปลี่ยน หากลบข้อมูล Server นอกแอปอาจต้อง Back/เปิดแอปใหม่หรือ Sync

### 13.8 ข้อมูลทดสอบที่ลบออกจาก Server

- เคยมี artifact เก่า `server/data/PhotoBackup/ทดสอบ/ทดสอบ (2)/AA/_DN_INFO.json`
- ระบุ job_id `9a1428d9-81a3-4a23-88ae-ac233d5bd809` และไม่มีรูปจริง
- วันที่ 10 สิงหาคม 2026 ลบโฟลเดอร์นี้, stale `uploaded_photos` 1 row และ `backup_jobs` 1 row แล้ว
- ไม่พบ seed/hardcode ที่สร้าง `ทดสอบ/AA` ใน Android หรือ Server source; เป็นข้อมูลจริงเก่าที่ค้างใน `server/data`
- ห้ามเพิ่ม sample/test data ลง `server/data`; Server tests ต้องใช้ `tmp_path` เท่านั้น

### 13.9 การตรวจล่าสุด

- ทุกชุดแก้ Android ล่าสุดตรวจด้วย `:app:compileDebugKotlin` และ Java 21 ผ่าน
- คำสั่งที่ใช้บนเครื่องนี้:

```powershell
$env:JAVA_HOME='C:\Users\HP\.jdks\jbr-21.0.11'
cd android
.\gradlew.bat --no-daemon :app:compileDebugKotlin --console=plain
```

- Warning ปัจจุบันส่วนใหญ่เป็น Material icon deprecated (`AutoMirrored`), `LocalLifecycleOwner` ย้าย package และ Room `fallbackToDestructiveMigration` deprecated; ไม่ใช่ compile error แต่ควรเก็บเป็นงาน cleanup
- หลังแก้ UI/gesture ต้องทดสอบบน OPPO จริง เพราะ multi-touch, ColorOS picker, MediaStore permission และ memory ของรูป Original ต่างจาก emulator

### 13.10 Checklist เพิ่มเติมสำหรับรอบถัดไป

ก่อนทำ Checklist ให้ทราบการเปลี่ยนแปลงล่าสุดในหัวข้อ 13.11 ด้านล่างด้วย

- ค้นหางาน → เปิดงานผิด → Back: query/sort/filter ที่ควรคงอยู่ต้องไม่หาย
- ต่อ Server ที่มี job_id ซ้ำกับ Local: ต้องมีการ์ดเดียวและ `CloudDone`
- ทดสอบรูปสามสถานะ: Local only, Local+Server hash เดียวกัน, Server only
- Pinch ที่มุมภาพต้องซูมตามนิ้ว; double-tap ต้องกลับ 1×
- ลากขอบขณะซูมต่ำกว่า 50% ต้อง spring กลับ; เกิน 50% ต้องเปลี่ยนหน้าโดยไม่มีภาพใหญ่แวบ
- เปิดภาพตัวหนังสือเล็กและเทียบ Gallery ว่า DN ใช้ Original decode
- สร้างงานจาก Template โดยใช้ default, เปลี่ยนชื่อ และชื่อซ้ำ `(2)`
- ย้ายรูป/โฟลเดอร์ผ่าน hierarchy และกด Back หลายชั้นก่อนยืนยัน
- นำเข้า PDF หลายหน้า, Sync, แชร์เดี่ยว, แชร์หลายไฟล์ และยืนยันก่อนลบ
- เปิด Timestamp address กับรูปมี GPS/ไม่มี GPS/พิกัด 0,0 และตรวจข้อความ fallback

### 13.11 วิดีโอและชื่อเริ่มต้นจาก Template — 11 สิงหาคม 2026

ไฟล์หลัก: `MainActivity.kt`, `Entities.kt`, `AppDao.kt`, `AppDatabase.kt`, `PhotoRepository.kt`, `SyncClient.kt`, `CloudClient.kt`, `server/app.py`

- ปุ่ม `กล้อง OPPO` เปิดหน้ากล้องแบบเต็มซึ่งผู้ใช้สลับโหมดรูป/วิดีโอในกล้อง OPPO ได้เอง เมื่อย้อนกลับ DN จะค้นทั้งรูปและวิดีโอที่สร้างหลังเวลาเปิดกล้องแล้วนำเข้าในงาน
- มีปุ่ม `นำเข้าวิดีโอ` เปิดตัวเลือกไฟล์เดิมด้วย MIME `video/*`
- วิดีโอถูกคัดลอกแบบไฟล์ต้นฉบับโดยไม่แปลงหรือลดคุณภาพ ไปยัง `Movies/MyPhotoApp/<ลูกค้า>/<งาน>/<โฟลเดอร์>` ผ่าน MediaStore
- วิดีโอใช้รายการไฟล์ชุดเดียวกับ PDF แต่แยกชนิดด้วย `DocumentEntity.mimeType`; แสดงไอคอน Play, เปิดเล่นด้วยแอปวิดีโอในเครื่อง, แชร์เดี่ยว/หลายไฟล์ และลบโดยมีข้อความยืนยันที่ตรงกับชนิดไฟล์
- Sync ส่ง MIME ไปที่ `/upload-document`; Server บันทึก `mime_type` ใน `_DN_INFO.json` และตอบไฟล์กลับด้วย media type ที่ถูกต้อง
- Cloud Catalog อ่าน `mime_type`; วิดีโอบน Server แตะเพื่อเล่น และการดาวน์โหลดทั้งงาน/โฟลเดอร์กู้วิดีโอกลับเข้า `Movies` ได้
- Room database เพิ่มจาก version 2 เป็น version 3 โดย migration `2→3` เพิ่มคอลัมน์ `documents.mimeType TEXT NOT NULL DEFAULT 'application/pdf'`; PDF เก่าจึงยังเปิดได้ตามเดิมโดยข้อมูลไม่หาย
- Dialog สร้างงานจาก Template ไม่กรอกชื่อไว้ล่วงหน้าแล้ว แต่แสดงชื่อ Template เป็น placeholder: ถ้าปล่อยช่องว่างจะใช้ชื่อ Template, ถ้าพิมพ์จะใช้ชื่อที่พิมพ์ และระบบยังเติม `(2)`, `(3)` เมื่อชื่อซ้ำ
- สร้าง `DN-Photo-Server-V6.exe` แล้ว และ `START-DN-SERVER.cmd` ชี้ไป V6; V5 ที่เปิดอยู่ต้องปิดด้วย `STOP-DN-SERVER.cmd` ก่อนเปิดใหม่ จึงจะเริ่มใช้ระบบวิดีโอ

### 13.12 ประสิทธิภาพหน้ารวมรูป — 11 สิงหาคม 2026

- Grid รูป Local และ Server ใช้ Coil `ImageRequest` ขนาดตัวอย่างสูงสุด 512×512 px พร้อม cache key คงที่ แทนการถอดรหัสรูปต้นฉบับหลายล้านพิกเซลทุกช่อง
- หน้ากู้รูปจากกล้องใช้ภาพตัวอย่าง 384×384 px
- ปิด crossfade ใน Grid เพื่อลดงานวาดซ้ำขณะเลื่อน และใช้ `Precision.INEXACT` เพื่อให้ decoder เลือก sample size ที่ประหยัดหน่วยความจำ
- การเปลี่ยนแปลงนี้มีผลเฉพาะ Thumbnail ในหน้ารวม; `PhotoViewer` และ `CloudPhotoViewer` ยังคงใช้ `Size.ORIGINAL` กับ `Precision.EXACT` เพื่อดูและซูมไฟล์เต็มความละเอียด
- Lazy grid โหลดเฉพาะรายการที่อยู่ใกล้หน้าจอ จึงรองรับจำนวนรูปมากขึ้นโดยไม่สร้าง bitmap ของทุกรูปพร้อมกัน

### 13.13 เปลี่ยนชื่องานหลัง Backup และล้างงานทดสอบเก่า — 11 สิงหาคม 2026

- เดิม Server ตาราง `backup_jobs` จำ `storage_name` ครั้งแรกตาม `job_id` ตลอดไป จึงทำให้งานที่เปลี่ยนจาก `A (2)` เป็น `A` ยัง Backup ลง `A (2)`
- `storage_job_name()` ตรวจ `requested_name` และ client ทุกครั้ง หากชื่อเปลี่ยนจะย้ายโฟลเดอร์เดิมไปชื่อใหม่เมื่อปลายทางว่าง อัปเดต `backup_jobs` และแก้ `uploaded_photos.stored_path/client_name/job_name` ให้ตรงกัน
- ถ้าชื่อปลายทางมีโฟลเดอร์ของงานอื่นอยู่จริง ระบบยังเติม `(2)`, `(3)` เพื่อไม่รวมข้อมูลคนละ `job_id` หรือเขียนทับไฟล์เก่า
- กรณีเปลี่ยนชื่องานใหม่กลับไปใช้ชื่อของงานเก่าที่ลบจากมือถือแล้ว แต่ Server ยังมี backup อยู่: Server จะเก็บงานเก่าโดยย้ายเป็น `<ชื่อ> (เดิม)` และให้งานที่เพิ่งเปลี่ยนชื่อใช้ `<ชื่อ>` ตามที่ผู้ใช้ตั้ง จึงไม่สูญเสียหรือรวมข้อมูลสอง job_id
- งานทดสอบเก่ารหัส `9a1428d9-81a3-4a23-88ae-ac233d5bd809` ยังค้างใน Room บนมือถือ จึงส่ง `/folder` มาสร้าง `ทดสอบ/ทดสอบ/AA` ใหม่ทุก Sync แม้ลบฝั่ง Server
- Android เรียก `cleanupLegacyEmptyTestJob()` ครั้งเดียวเมื่อเปิดแอป และลบเฉพาะเมื่อ ID/ชื่อ/โฟลเดอร์ตรงกับ artifact เก่าและไม่มีรูป เอกสาร วิดีโอ หรือโน้ต จึงไม่แตะงานจริง
- วันที่ 11 สิงหาคม 2026 ลบโฟลเดอร์ metadata-only และแถว `backup_jobs` ของ artifact นี้ออกจาก `server/data` แล้ว

### 13.14 โหมดค้นหาไฟล์แยกจากค้นหางาน — 11 สิงหาคม 2026

- หน้า `งานทั้งหมด` ยังใช้ช่องค้นหางาน/ข้อความในโน้ตและตัวกรองเดิมเหมือนเดิม
- เพิ่มปุ่ม `ค้นหาไฟล์ รูปภาพ PDF หรือวิดีโอ` เปิดหน้า `FileSearchPage` แยกต่างหาก
- `AppDao.searchLocalFiles()` ใช้ UNION ค้นชื่อไฟล์จากตาราง `photos` และ `documents` ทั่วทุกงาน จำกัดผลล่าสุด 200 รายการ
- ผลลัพธ์แสดงชื่อไฟล์ ชื่องาน โฟลเดอร์ วันเวลา และ Thumbnail สำหรับรูป โดย Thumbnail ใช้ decode 192×192 พร้อม memory cache
- มีตัวกรองในโหมดค้นหาไฟล์: ทั้งหมด, รูป, PDF และวิดีโอ
- แตะรูปเพื่อเปิดใน `PhotoViewer` ของ DN ด้วยไฟล์ต้นฉบับ; แตะ PDF/วิดีโอเพื่อเปิดด้วยตัวอ่านหรือเครื่องเล่นที่ Android รองรับ
- คำค้นหาไฟล์เก็บแยกใน SharedPreferences `file_search/query` ไม่กระทบคำค้นหาและ sort/filter ของหน้างาน

### 13.15 ป้องกันหน้าจอล็อกระหว่าง Sync — 11 สิงหาคม 2026

- `PhotoWorkApp` ใช้ `LocalView.current.keepScreenOn` ตามค่า `syncing`
- ตั้งเป็น `true` เฉพาะตั้งแต่ผู้ใช้กด Sync จนกระทั่งสำเร็จหรือผิดพลาด แล้วคืนเป็น `false` อัตโนมัติผ่าน `DisposableEffect`
- หากออกจาก composable ระหว่างทำงาน `onDispose` จะคืนค่าเพื่อไม่ให้หน้าจอเปิดค้าง
- พฤติกรรมนี้ไม่ใช่ Background/Auto Sync: แอปยัง Sync เฉพาะเมื่อผู้ใช้กด และเพียงป้องกัน timeout ของหน้าจอระหว่างงานที่กำลังทำ

### 13.16 ลดอาการกระตุกเพิ่มเติมใน Grid รูป — 11 สิงหาคม 2026

- เพิ่ม `LocalGridThumbnail()` ซึ่งเรียก `ContentResolver.loadThumbnail()` บน `Dispatchers.IO` เพื่อใช้ Thumbnail ที่ MediaStore จัดการ แทนการเปิดไฟล์ต้นฉบับผ่าน Coil ทุกครั้ง
- หาก ContentProvider ไม่รองรับ `loadThumbnail()` จะ fallback ไป Coil request ขนาดย่อ จึงยังรองรับ URI จาก Photo Picker
- ขนาด Thumbnail ปรับตามจำนวนรูปต่อแถว: 1=720, 2=480, 3=360, 4=280 และ 5=224 px ลด RAM/decode โดยเฉพาะโหมด 3–5 รูปต่อแถว
- การ์ดรูป Local และ Cloud เปลี่ยนจาก `ElevatedCard` เป็น `Card` เพื่อตัดงานวาดเงาจำนวนมากระหว่างเลื่อน
- Cloud grid ภายในหน้าดู Server ใช้ Thumbnail 360 px และ cache key แยกตามขนาด
- การโหลด Thumbnail ทำงานนอก UI thread; หน้าดูเต็มจอและซูมยังใช้ Original เหมือนเดิม

### 13.17 ปรับ Overlay ให้สมส่วนตามจำนวนรูปต่อแถว — 11 สิงหาคม 2026

- ไอคอน Cloud, CloudDone, Check และปุ่มถังขยะใน Grid ใช้ขนาดร่วมจาก `gridColumns` ไม่ยึดขนาดเดิมเมื่อช่องรูปเล็กลง
- ขนาดพื้นหลังไอคอนสำหรับ 1–5 รูปต่อแถวคือ 40, 32, 28, 24 และ 20 dp
- ขนาดตัวไอคอนคือ 23, 18, 16, 14 และ 12 dp พร้อมลดขอบนอกจาก 7 dp เหลือ 2 dp ตามลำดับ
- พื้นที่แตะถังขยะจึงไม่บังรูปมากเกินไปในโหมด 4–5 รูป และโหมด 1–2 รูปยังมองเห็น/กดได้สะดวก

### 13.18 วิดีโอใช้ไฟล์เดียวกับ Gallery และรับกลับจาก OPPO อัตโนมัติ — 11 สิงหาคม 2026

- เดิม `importVideo()` คัดลอกวิดีโอเข้า `Movies/MyPhotoApp` ทำให้มีต้นฉบับใน Gallery กับสำเนาใน DN; ลบ DN จึงไม่ลบต้นฉบับ
- วิดีโอใหม่จาก Gallery และกล้อง OPPO ใช้ `attachVideo()` บันทึก URI ของ MediaStore โดยตรงเหมือนรูป ไม่สร้างสำเนา และกันนำเข้า URI เดิมซ้ำด้วย `documentUriExists()`
- `removeDocument()` ลบ MediaStore URI จริง หาก Android ไม่ให้สิทธิ์ทันทีจะส่ง `MediaDeleteApproval` เพื่อเปิดหน้าต่างยืนยันของระบบ แล้วจึงลบแถว Room
- หลังย้อนจากกล้อง OPPO ระบบรอ MediaStore 2.5 วินาที แล้วค้นรูปและวิดีโอทั้งหมดจากเวลาที่เปิดกล้องเพื่อนำเข้าอัตโนมัติ
- เอา fallback ที่เด้ง Photo Picker ให้ผู้ใช้เลือกรูป/วิดีโอหลังย้อนจากกล้องออกแล้ว; ถ้าไม่มีสิทธิ์หรือสแกนไม่พบจะแจ้งสาเหตุโดยไม่บังคับเลือก
- วิดีโอที่เคยนำเข้าก่อนการเปลี่ยนแปลงนี้อาจยังมีสองไฟล์ เพราะเป็นสำเนาที่สร้างไว้แล้ว การแก้มีผลกับวิดีโอที่นำเข้าหลังติดตั้งรุ่นใหม่

### 13.19 ลบงาน/โฟลเดอร์ต้องรวมไฟล์ทุกประเภท — 11 สิงหาคม 2026

- เดิม `jobDeleteApproval()`, `jobsDeleteApproval()` และ `folderDeleteApproval()` รวบรวมเฉพาะ URI จาก `photos` จึงลบวิดีโอ/PDF ออกจาก Room ตาม cascade แต่ไฟล์จริงยังค้างใน Gallery หรือ Downloads
- ขั้นตอนลบแบบกลุ่มรวบรวม URI จากทั้ง `photos` และ `documents`; `documents` ครอบคลุม PDF และวิดีโอ
- `MediaStoreManager.canRequestDelete()` รองรับ URI ของ images, video และ downloads โดยยังปฏิเสธ URI แบบ picker proxy
- ผู้ใช้ต้องยืนยันใน AlertDialog ของ DN ก่อนเสมอ จากนั้น Android แสดงหน้าต่าง `MediaStore.createDeleteRequest()` สำหรับไฟล์จริง และเมื่อตอบตกลงจึงลบแถวงานทั้งหมด
- โน้ตไม่มีไฟล์ MediaStore จึงถูกลบพร้อม Room cascade หลังการยืนยันเดียวกัน
- `jobBackupSummary()` นับสถานะของรูป เอกสาร วิดีโอ และโน้ต รวมเป็น “รายการ” เพื่อให้คำเตือนก่อนลบงานไม่ตกหล่นไฟล์ที่ยังไม่ Backup
- ข้อความยืนยันลบงานหลายงาน งานเดียว และโฟลเดอร์ ระบุรูป วิดีโอ PDF และโน้ตอย่างชัดเจน

### 13.23 ตัวอ่านและเครื่องมือ PDF + เปลี่ยนชื่อไฟล์ — 12 สิงหาคม 2026

- PDF ที่อยู่ในเครื่องเปิดอ่านภายใน DN ด้วย `LocalPdfReader` ไม่ส่งไป Chrome/แอปภายนอกอีก ส่วนวิดีโอยังเปิดผ่านโปรแกรมเล่นของเครื่อง
- ตัวอ่านแสดงเลขหน้าปัจจุบันและบันทึกหน้าล่าสุดแยกตาม `document.id` ใน SharedPreferences `pdf_reading`; เปิดไฟล์เดิมครั้งต่อไปจะกลับสู่หน้าที่อ่านค้างไว้
- ช่องค้นข้อความใช้ `pdfbox-android` และค้นแบบไม่สนตัวพิมพ์เล็ก/ใหญ่ทีละหน้า จากนั้นแสดงหมายเลขหน้าที่พบและเลื่อนไปผลแรก
- PDF แบบภาพสแกนที่ไม่มี text layer จะค้นข้อความไม่พบ ฟังก์ชันนี้ยังไม่ใช่ OCR; PDF ที่สร้างจากเอกสารหรือผ่าน OCR แล้วค้นได้
- เมนูเครื่องมือรับช่วง `หน้าแรก`–`หน้าสุดท้าย` และทำได้สองแบบ: สร้าง PDF ใหม่เฉพาะช่วงหน้าด้วยการ import หน้าโดยไม่ลดคุณภาพ หรือ render แต่ละหน้าเป็น PNG ความกว้างอย่างน้อย 1800 px
- PDF ช่วงหน้าใหม่ถูกเก็บใน `Downloads/DN` และเพิ่มเป็นเอกสารใหม่ในโฟลเดอร์งานเดิม ส่วน PNG ถูกเก็บใน `Pictures/MyPhotoApp/PDF` และเพิ่มเป็นรูปในงานเดิม จึงแสดงใน Gallery และ Sync ได้
- เพิ่มการเปลี่ยนชื่อรูปจากเมนูจัดการรูป และเพิ่มปุ่มดินสอให้ PDF/วิดีโอ ชื่อใหม่รักษานามสกุลเดิม แทนตัวอักษรที่ระบบไฟล์ไม่อนุญาต และอัปเดตทั้ง MediaStore กับ Room
- ปุ่ม `เปลี่ยนชื่อรูป` แสดงเฉพาะรูปที่ DN สร้างและเป็นเจ้าของใน path `MyPhotoApp` เช่นรูป Timestamp และรูปจาก PDF; รูปที่กล้องเครื่อง/OPPO หรือ Gallery เป็นเจ้าของถูกซ่อนปุ่ม เพราะ Android อาจไม่อนุญาตให้ DN เปลี่ยนชื่อ MediaStore URI นั้นโดยตรง
- หน้า PhotoViewer แบบเต็มจอเพิ่มปุ่มเมนูใต้ไอคอน `i` สำหรับเปลี่ยนชื่อ (เฉพาะรูปที่ DN เป็นเจ้าของ), ย้ายรูปด้วย file-browser เลือกงาน/โฟลเดอร์หลายระดับ และลบรูปพร้อมคำยืนยัน/MediaStore approval โดยไม่ต้องย้อนกลับไปหา thumbnail
- หลังย้ายหรือลบสำเร็จ Viewer กลับหน้าโฟลเดอร์เพื่อโหลดรายการ Room ใหม่ ป้องกัน snapshot เดิมชี้ URI ที่ถูกย้าย/ลบจนเกิดหน้าดำ; เปลี่ยนชื่อแล้วยังคงอยู่ใน Viewer
- หลังเปลี่ยนชื่อ สถานะรายการกลับเป็น `WAITING` เพื่อให้การ Sync ครั้งถัดไปส่งชื่อปัจจุบันไป Server
- แก้ตัวอ่าน PDF เด้งออกจากหน่วยความจำเต็ม: เดิม render ทุกหน้าเป็น Bitmap พร้อมกัน รุ่นใหม่ `PdfPageBitmap` เปิดและ render เฉพาะหน้าที่ LazyColumn กำลังแสดง และ recycle Bitmap ทันทีเมื่อหน้าออกจาก composition จึงรองรับเอกสารหลายหน้าโดยใช้หน่วยความจำน้อยลงมาก
- ช่องค้นข้อความไม่แสดงตลอดเวลาแล้ว ใช้ไอคอนแว่นขยายบนแถบด้านบน กดจึงเปิดช่องค้นหา และกดกากบาทเพื่อปิด/ล้างผลค้นหา
- เพิ่มความละเอียดหน้า PDF ในตัวอ่านสูงสุด 2200 px พร้อมซูมสองนิ้ว 1–6 เท่า และแตะสองครั้งเพื่อซูม 2.5 เท่าตามตำแหน่งที่แตะ/แตะซ้ำเพื่อกลับขนาดปกติ; gesture นิ้วเดียวที่ขนาดปกติยังส่งให้รายการเลื่อนหน้า
- แก้สาเหตุภาพยังแตก: `PdfRenderer.Page.width` เป็นหน่วย PDF point (A4 มักประมาณ 595) และโค้ดเดิมใช้ `coerceAtMost(2200)` จึงยัง render เพียง ~595 px; รุ่นใหม่กำหนดฐานจริง 2200 px และเมื่อซูมจะ render หน้าเดิมใหม่สูงสุด 3600 px แทนการยืด Bitmap ต่ำ
- เอาข้อความเลขหน้าระหว่างกระดาษออก เหลือสถานะหน้าปัจจุบันเฉพาะแถบด้านบน
- ปรับตามการใช้งานจริงให้ระดับซูมเป็นค่าร่วมทั้งเอกสาร: ซูมหน้าแรกแล้วหน้าถัดไปใช้ระดับเดียวกันโดยไม่ต้องถ่างใหม่ แต่ตำแหน่งเลื่อนของแต่ละหน้าจะกลับกึ่งกลางเพื่อไม่ให้หน้าถัดไปเอียงตาม
- เมื่อซูมมากกว่า 1 เท่า ใช้นิ้วเดียวลากหน้า PDF ได้ทั้งซ้าย–ขวาและขึ้น–ลง; สองนิ้วยังคงใช้ถ่าง/หุบและลากพร้อมกัน
- เพิ่ม fast scrollbar ด้านขวาสำหรับ PDF มากกว่าหนึ่งหน้า แสดงตำแหน่งโดยประมาณและลากหัวแถบเพื่อกระโดดไปหน้าต่าง ๆ ได้รวดเร็ว
- แก้ gesture PDF ให้ใช้ตำแหน่ง centroid ระหว่างสองนิ้วเป็นจุดยึดในการซูม ไม่บังคับขยายจากกลางหน้า จึงไม่กระโดดผิดตำแหน่งขณะถ่าง/หุบ
- ทุกหน้าในเอกสารใช้ทั้ง `pdfScale` และ `pdfOffset` ร่วมกัน พร้อมเอาระยะห่างระหว่างหน้าออก; เมื่อซูมและเลื่อนหน้าแรกไปซ้าย หน้าถัดไปคงระดับซูมและตำแหน่งแนวนอนเดียวกันเหมือนกระดาษที่ต่อกัน
- ปรับสถาปัตยกรรม gesture ให้เหมือนโปรแกรมอ่านเอกสารแบบ WPS: ย้ายตัวรับแตะสองครั้ง/ถ่างสองนิ้ว/ลากแนวนอนไปไว้ที่ container ครอบ `LazyColumn` เพียงจุดเดียว หน้า PDF ไม่รับ gesture แยกกันอีก จึงไม่แย่ง event หรือกระโดดเมื่อข้ามรอยต่อหน้า
- ความสูง layout ของแต่ละหน้าขยายตาม `pdfScale` ทำให้ระยะเลื่อนแนวตั้งเพิ่มตามขนาดเอกสารจริง ไม่ใช่ขยายภาพอยู่ในกล่องความสูงเดิม; การลากแนวตั้งส่งให้ LazyColumn อ่านต่อข้ามหน้า ส่วนลากแนวนอนเมื่อซูมจะเลื่อนทุกหน้าพร้อมกัน
- แก้อักษรยืดและขอบล่างถูกตัดให้เหมือน Chrome PDF Viewer: รูปหน้าใช้ `ContentScale.Fit` และรักษา aspect ratio เดิม จากนั้นขยายสม่ำเสมอทั้งแกน X/Y ด้วย `TransformOrigin` กึ่งกลางขอบบน ไม่ใช้ `fillMaxSize + FillBounds` ที่เคยยืดภาพตามกล่องซึ่งสูงตาม zoom ซ้ำอีกครั้ง
- เพิ่มพื้นที่ว่างท้ายรายการ 48 dp ทำให้เลื่อนหน้าสุดท้ายพ้น navigation/system bar ได้และมองเห็นขอบกระดาษล่างครบ
- เพิ่ม Android intent filters สำหรับ `ACTION_VIEW` และ `ACTION_SEND` MIME `application/pdf` พร้อม `BROWSABLE` ทำให้ DN ปรากฏในเมนู “เปิดด้วย/แชร์ไปยัง” จาก Chrome, Downloads, LINE และแอปอื่นที่ส่ง URI ของ PDF
- เลขหน้าใต้ชื่อ PDF บน TopAppBar กดได้ เมื่อกดจะแสดงช่องกรอก `1–จำนวนหน้าทั้งหมด`; กดยืนยันแล้ว `scrollToItem()` ไปยังหน้าที่ระบุทันที เหมาะกับเอกสารยาว
- เพิ่มไอคอนคัดลอกข้อความในตัวอ่าน PDF กดเพื่อเปิดโหมดข้อความของหน้าปัจจุบันผ่าน `PDFTextStripper`; ข้อความอยู่ใน Compose `SelectionContainer` จึงกดค้าง ลากเคอร์เซอร์คลุมข้อความ และใช้เมนู Copy ของ Android ได้ แล้วกดไอคอน PDF เพื่อกลับหน้าเอกสาร
- โหมดเลือกข้อความรองรับ PDF ที่มี text layer เท่านั้น เอกสารสแกนเป็นภาพล้วนจะแจ้งว่าต้อง OCR ก่อน และไม่แสดงข้อความปลอม
- การค้นหา PDF ใช้สองชั้น: `PDFTextStripper` ค้น text layer ก่อน หากไม่พบผลเลยจึง Render ทุกหน้าเป็นภาพกว้าง 1600 px และใช้ ML Kit Text Recognition แบบ on-device OCR จึงค้นรหัส ตัวเลข และข้อความ Latin ใน PDF สแกนได้โดยไม่ส่งไฟล์ขึ้นอินเทอร์เน็ต
- ระหว่าง OCR แสดงสถานะ `กำลังค้นหาข้อความและตรวจ OCR ทีละหน้า…`; เอกสารยาวจะใช้เวลา และ ML Kit Latin recognizer ไม่รับประกันภาษาไทยทุกฟอนต์หรือภาพที่ไม่ชัด
- `MainActivity` รองรับ intent ทั้งตอนเปิดแอปใหม่และ `onNewIntent` ด้วย `launchMode=singleTop`; เมื่อรับ PDF จะแสดง dialog เลือกงาน/โฟลเดอร์พร้อมช่องค้นหา แล้วเรียก `importPdf()` คัดลอกไฟล์เข้าพื้นที่ DN ทันที
- หลังนำเข้าสำเร็จ แอปพาไปยังโฟลเดอร์ปลายทางและแสดง PDF ในรายการ; ระหว่างคัดลอกมี progress และป้องกันกดปลายทางซ้ำ หากยังไม่มีงานจะแจ้งให้สร้างงานก่อน
- เปลี่ยนตัวเลือกปลายทาง PDF จากรายการแบนเป็น file-browser แบบเดียวกับเมนูย้ายไฟล์: เลือกงาน → เข้าโฟลเดอร์ย่อยได้ไม่จำกัดระดับ → ใช้ปุ่มย้อนขึ้นหนึ่งระดับ → กด `เก็บไว้ที่นี่` เพื่อยืนยันโฟลเดอร์ปัจจุบัน รวมถึงเลือกโฟลเดอร์หลักของงานได้
- แก้ PNG จาก PDF เป็นพื้นดำโดยเติม Bitmap เป็นสีขาวก่อน `PdfRenderer.render()` เนื่องจากพื้นหลังโปร่งใสของ PDF เคยถูกโปรแกรม Gallery แสดงเป็นสีดำ
- เพิ่ม dependency `com.tom-roush:pdfbox-android:2.0.27.0`; การแสดงหน้าใช้ Android `PdfRenderer` ส่วนการอ่านข้อความและคัดลอกช่วงหน้าใช้ PDFBox
- โหมดค้นหาไฟล์แยกคำด้วยช่องว่างแบบไม่สนลำดับ โดยทุกคำต้องอยู่ในชื่อไฟล์ เช่นทั้ง `CV 185`, `185 CV` และ `ทองแดง 185 CV` พบ `ทองแดง CV ขนาด 185 sqmm.jpg`
- รองรับพิมพ์คลาดเคลื่อนเล็กน้อยด้วย edit distance รายคำ เช่น `ทองแดด` พบ `ทองแดง`; คำยาว 4–7 ตัวอักษรยอมผิด 1 ตำแหน่ง คำตั้งแต่ 8 ตัวอักษรยอมผิด 2 ตำแหน่ง ส่วนคำสั้นและตัวเลขต้องตรงเพื่อลดผลลัพธ์มั่ว

### 13.22 แยกระบบกล้อง OPPO กลับมาเป็นรูปอย่างเดียว — 11 สิงหาคม 2026

- ตามการทดสอบบนเครื่อง OPPO ระบบรับรูปเดิมทำงานถูกต้องกว่าหลังรวมวิดีโอ จึงแยกเส้นทางกล้อง OPPO กลับมาอ่านเฉพาะ `MediaStore.Images`
- เมื่อกดย้อนจากกล้อง รอ 2.5 วินาทีแล้วค้นหารูปทั้งหมดจากเวลาเปิดกล้อง นำเข้ารูปโดยอัตโนมัติเหมือนพฤติกรรมก่อนเพิ่มวิดีโอ
- การเปิดกล้องขอเฉพาะสิทธิ์กล้อง ตำแหน่ง และรูป ไม่ขอ `READ_MEDIA_VIDEO` และสิทธิ์วิดีโอไม่สามารถขัดขวางการรับรูปได้อีก
- ระบบนำเข้าวิดีโอจาก Gallery และวิดีโอเดิมในงานยังคงอยู่ แต่ยังไม่เชื่อมกับรอบถ่ายผ่านกล้อง OPPO จนกว่าจะออกแบบใหม่แยกจากระบบรูป
- หน้ากู้รอบกล้อง OPPO ตรวจและแสดงเฉพาะรูป เพื่อคงพฤติกรรมเดิมที่ผ่านการใช้งานจริง
- พบความต่างสำคัญจาก commit ก่อนเพิ่มวิดีโอ: รุ่นหลังเคยแปลง `INTENT_ACTION_STILL_IMAGE_CAMERA` เป็น `getLaunchIntentForPackage()` ซึ่งเปิดหน้าหลักของแอปกล้องและอาจไม่คืน Activity result ให้ DN; คืนเป็นการ launch `INTENT_ACTION_STILL_IMAGE_CAMERA` โดยตรงแล้ว
- หากค้นหาอัตโนมัติหลังย้อนกลับไม่พบ จะเปิด Photo Picker แบบ `ImageOnly` ให้เลือกเฉพาะรูปที่เพิ่งถ่ายเป็นทางสำรองเหมือนรุ่นก่อนเพิ่มวิดีโอ ไม่แสดงวิดีโอในขั้นตอนนี้
- แก้อาการย้อนกลับแล้วรูปไม่แสดงทันที: เดิม `importOppoPhotos()` รอ `getCurrentLocation(PRIORITY_HIGH_ACCURACY)` ก่อน insert ลง Room ซึ่งอาจรอนานหรือไม่ตอบบน OPPO; รุ่นใหม่อ่านเวลา/GPS จาก EXIF แล้วลงทะเบียนรูปต้นฉบับทันที ไม่ให้คำขอ GPS ขวางการแสดงรูป
- เมื่อกลับจากกล้องจะ query รูปทันที หาก MediaStore ยังไม่พร้อมจึง retry ทุก 500 มิลลิวินาทีสูงสุด 5 ครั้ง แทนการบังคับรอ 2.5 วินาทีก่อนเริ่มค้นหา
- ระหว่างรับรูปและค้น GPS แสดง AlertDialog `กำลังรับรูปจากกล้อง OPPO` แบบปิดไม่ได้ เพื่อกันผู้ใช้กดเปลี่ยนงานหรือเมนูอื่นจนกระบวนการจบ
- เอา Toast คำแนะนำ `ถ่ายต่อเนื่องได้เลย...` ออกจากจังหวะเปิดกล้อง เพราะ Toast ลอยทับปุ่มควบคุมของกล้อง OPPO; คำแนะนำจะแสดงเฉพาะในหน้าต่างเลือกโหมดก่อนเข้ากล้อง
- อ่าน GPS จาก EXIF ของแต่ละรูปก่อน หากบางรูปไม่มีพิกัดจึงขอพิกัดปัจจุบันแบบ High Accuracy โดยรอสูงสุด 8 วินาที และใช้พิกัดนี้เติมเฉพาะรูปที่ EXIF ขาด
- หากยังหา GPS ไม่ได้ จะไม่บันทึกหรือทิ้งรูปเอง แต่แสดงตัวเลือก `ลองหา GPS ใหม่` และ `บันทึกโดยไม่มี GPS`; การลองใหม่เริ่มค้น GPS รอบใหม่ ส่วนการบันทึกไม่มี GPS ยังคงใช้ EXIF ของรูปที่มีพิกัดอยู่แล้ว

### 13.21 กู้ไฟล์จากกล้อง OPPO หลังปิดแอปหรือกลับมาไม่ครบ — 11 สิงหาคม 2026

- ตอนเปิดกล้อง OPPO แอปบันทึก `oppo_recovery_location`, เวลาเริ่มเปิดกล้อง และสถานะโหมด Timestamp ไว้ใน Settings จึงไม่หายแม้ผู้ใช้ปัดปิด DN ก่อนย้อนกลับ
- การนำเข้าอัตโนมัติครั้งแรกจะไม่รีบล้างข้อมูลกู้คืน เพราะ OPPO อาจลงทะเบียนรูปชุดต่อเนื่องหรือวิดีโอใน MediaStore ช้ากว่าไฟล์แรก
- เมื่อเปิด DN และกลับสู่งานเดิม แอปจะค้นหาทั้งรูปและวิดีโอที่สร้างหลังเวลาเปิดกล้อง แล้วแสดงหน้าต่างให้เลือกว่าจะกู้ไฟล์ใดเข้างาน
- หน้ากู้คืนกรองไฟล์ที่มีอยู่ในงานแล้วด้วย `contentUri` จึงถามเฉพาะไฟล์ที่ยังขาด และไม่สร้างรายการซ้ำจากไฟล์ที่นำเข้าอัตโนมัติสำเร็จแล้ว
- รูปที่เลือกสามารถสร้าง Timestamp ตามโหมดเดิมได้ ส่วนวิดีโอจะนำเข้าเป็นไฟล์ต้นฉบับโดยไม่ประทับ Timestamp
- เมื่อไม่มีไฟล์ตกค้างจริง หรือผู้ใช้ยืนยัน/ปฏิเสธรายการกู้คืนแล้ว แอปจึงล้างข้อมูลกู้คืนของรอบนั้น
- แก้กรณี ColorOS ปิด process ของ DN ระหว่างเปิดกล้อง: callback หลังย้อนกลับอ่านงาน เวลาเริ่ม และโหมด Timestamp จาก Settings แทนการพึ่ง state ในหน่วยความจำ จึงยังนำเข้าอัตโนมัติได้หลัง Activity ถูกสร้างใหม่
- หลังย้อนกลับ แอปตรวจ MediaStore ซ้ำทุก 1 วินาที สูงสุด 12 ครั้ง และรอให้จำนวนไฟล์นิ่ง 5 ครั้ง เพื่อไม่สรุปเร็วจากรูปแรกที่ OPPO ลงทะเบียนมาก่อนรูปอื่น
- ระหว่างการนำเข้าอัตโนมัติใช้ `oppo_recovery_returning` กันหน้าต่างกู้คืนเด้งแทรกตอนย่อ/คืนค่า Activity; หน้าต่างเลือกกู้ใช้เฉพาะรอบที่การกลับจากกล้องไม่สามารถทำงานจนจบจริง

### 13.20 รับรูปต่อเนื่องจากกล้อง OPPO ด้วยเวลาเปิดกล้อง — 11 สิงหาคม 2026

- จำ `pendingOppoStartedAt` ทันทีก่อนเปิดกล้อง OPPO
- เมื่อย้อนกลับ DN รอ MediaStore/ColorOS 2.5 วินาที แล้ว query รูปและวิดีโอทั้งหมดที่มี `DATE_ADDED` หลังเวลาเปิดกล้องในครั้งเดียว
- ไม่ใช้ polling หรือเงื่อนไข “จำนวนไฟล์นิ่ง” เพราะอาจตัดสินว่าเสร็จก่อน ColorOS ลงทะเบียนไฟล์ครบ
- ตัด URI ซ้ำ เรียงตามเวลาถ่าย และนำเข้าทั้งชุด รองรับการถ่ายต่อเนื่อง
- สิทธิ์รูป (`READ_MEDIA_IMAGES`) และวิดีโอ (`READ_MEDIA_VIDEO`) ต้องตรวจแยกกัน ห้ามใช้เงื่อนไข AND เพราะหากผู้ใช้อนุญาตเพียงชนิดเดียวจะทำให้การค้นหาทั้งหมดถูกข้าม; ชนิดใดมีสิทธิ์ต้องนำเข้าชนิดนั้นได้ทันที
