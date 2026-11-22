#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
USER_NAME="${SUDO_USER:-$USER}"

chmod +x "$REPO_DIR/scripts/linux/update-and-start.sh" "$REPO_DIR/scripts/linux/udp-updater.py" "$REPO_DIR/scripts/linux/run-capture.sh"

sudo tee /etc/systemd/system/gta60-capture.service >/dev/null <<EOF
[Unit]
Description=GTA60 Linux Capture Card Passthrough Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER_NAME
Group=video
SupplementaryGroups=audio render
WorkingDirectory=$REPO_DIR
ExecStartPre=$REPO_DIR/scripts/linux/update-and-start.sh
ExecStart=$REPO_DIR/scripts/linux/run-capture.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo tee /etc/systemd/system/gta60-updater.service >/dev/null <<EOF
[Unit]
Description=GTA60 Quest UDP Update Trigger Listener (UDP 7662)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER_NAME
WorkingDirectory=$REPO_DIR
ExecStart=/usr/bin/python3 $REPO_DIR/scripts/linux/udp-updater.py $REPO_DIR
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo tee /etc/sudoers.d/gta60-updater >/dev/null <<EOF
$USER_NAME ALL=(root) NOPASSWD: /bin/systemctl restart gta60-capture.service, /usr/bin/systemctl restart gta60-capture.service
EOF
sudo chmod 0440 /etc/sudoers.d/gta60-updater

sudo systemctl daemon-reload
sudo systemctl enable --now gta60-updater.service
sudo systemctl enable --now gta60-capture.service
echo "[gta60-install] services installed and enabled."
