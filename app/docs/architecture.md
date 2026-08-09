# Architecture and data rules

```text
Android UI -> Room metadata -> MediaStore original image
                 |                    |
                 +---- manual Sync ---+
                           |
                     FastAPI /upload
                           |
               SQLite hash ledger + HDD files
```

## Invariants

1. `PhotoEntity.sha256` is unique on the phone and `uploaded_photos.hash` is the primary key on the server.
2. A phone row becomes `UPLOADED` only after a successful server response containing the same SHA-256.
3. The server ledger is independent from the photo directory. Moving uploaded folders to another HDD does not erase upload history.
4. Camera and Gallery bytes are never decoded by the app, so the app does not resize, recompress, or rewrite EXIF.
5. Sync has no scheduler, service, receiver, or WorkManager dependency; the user action is the only entry point.

## Version 1 security boundary

Cleartext HTTP is enabled only to satisfy LAN operation. Bind the server to a trusted home/work network and do not port-forward TCP 8080. Add authentication and HTTPS before internet access.
