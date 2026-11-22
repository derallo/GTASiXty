#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec "$REPO_DIR/linux-capture/build/gta60-capture" \
  --device /dev/v4l/by-id/usb-Elgato_Elgato_4K_S_AB2MB60110CB51-video-index0 \
  --audio hw:CARD=S,DEV=0 \
  --width 2560 --height 1440 --fps 60 \
  --codec hevc --bitrate 80 "$@"
