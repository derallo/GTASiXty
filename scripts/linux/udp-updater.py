#!/usr/bin/env python3
"""Lightweight UDP 7662 listener for Quest-triggered update checks (GTA60_UPDATE)."""

import socket
import subprocess
import sys
import time

PORT = 7662
REPO_DIR = sys.argv[1] if len(sys.argv) > 1 else "."
COOLDOWN_SEC = 10.0

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
sock.bind(("0.0.0.0", PORT))
print(f"[gta60-updater] listening on UDP {PORT} in {REPO_DIR}", flush=True)

last_check = 0.0
last_start = 0.0

while True:
    data, addr = sock.recvfrom(256)
    msg = data.decode("ascii", errors="ignore").strip()
    if msg == "GTA60_START":
        now = time.monotonic()
        if now - last_start < 5.0:
            sock.sendto(b"GTA60_COOLDOWN", addr)
            continue
        last_start = now
        try:
            print(f"[gta60-updater] received GTA60_START from {addr[0]}, restarting gta60-capture", flush=True)
            sock.sendto(b"GTA60_STARTING", addr)
            subprocess.run(["sudo", "systemctl", "restart", "gta60-capture.service"], check=True, timeout=60)
        except Exception as exc:
            print(f"[gta60-updater] restart failed: {exc}", file=sys.stderr, flush=True)
            sock.sendto(b"GTA60_START_ERR", addr)
        continue

    if msg != "GTA60_UPDATE":
        continue

    now = time.monotonic()
    if now - last_check < COOLDOWN_SEC:
        sock.sendto(b"GTA60_COOLDOWN", addr)
        continue
    last_check = now

    try:
        subprocess.run(["git", "-C", REPO_DIR, "fetch", "--quiet"], check=True, timeout=15)
        local_head = subprocess.check_output(["git", "-C", REPO_DIR, "rev-parse", "HEAD"], text=True).strip()
        upstream_head = subprocess.check_output(["git", "-C", REPO_DIR, "rev-parse", "@{u}"], text=True).strip()
        if local_head == upstream_head:
            print(f"[gta60-updater] already up to date ({local_head[:8]})", flush=True)
            sock.sendto(b"GTA60_UPTODATE", addr)
        else:
            print(f"[gta60-updater] update available ({local_head[:8]} -> {upstream_head[:8]}), restarting gta60-capture", flush=True)
            sock.sendto(b"GTA60_UPDATING", addr)
            subprocess.run(["sudo", "systemctl", "restart", "gta60-capture.service"], check=True, timeout=60)
    except Exception as exc:
        print(f"[gta60-updater] update check failed: {exc}", file=sys.stderr, flush=True)
        sock.sendto(b"GTA60_UPDATE_ERR", addr)
