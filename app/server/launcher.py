from __future__ import annotations

import os
import socket
import sys
from pathlib import Path

import uvicorn


def application_dir() -> Path:
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


def local_addresses() -> list[str]:
    addresses: set[str] = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = info[4][0]
            if not address.startswith("127."):
                addresses.add(address)
    except OSError:
        pass
    return sorted(addresses)


def main() -> None:
    root = application_dir()
    data_dir = root / "data"
    os.environ.setdefault("PHOTO_SYNC_DATA", str(data_dir))

    # Import after PHOTO_SYNC_DATA is set so app.py stores data beside the EXE.
    from app import app

    print("=" * 58)
    print(" DN Photo Sync Server")
    print("=" * 58)
    print(f" Photos : {data_dir / 'PhotoBackup'}")
    print(f" Database: {data_dir / 'photo_sync.sqlite3'}")
    print("\n Enter this address in the DN Android app:")
    addresses = local_addresses()
    if addresses:
        for address in addresses:
            print(f"   http://{address}:8080")
    else:
        print("   http://<computer-ip>:8080")
    print("\n Keep this window open while backing up photos.")
    print(" Close this window to stop the server.\n")

    try:
        uvicorn.run(app, host="0.0.0.0", port=8080, log_level="info")
    except OSError as error:
        print(f"\n Cannot start server: {error}")
        input("Press Enter to close...")


if __name__ == "__main__":
    main()
