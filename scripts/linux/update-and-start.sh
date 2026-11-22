#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC_DIR="$REPO_DIR/linux-capture"
BUILD_DIR="$SRC_DIR/build"
BIN="$BUILD_DIR/gta60-capture"
BAK="$BUILD_DIR/gta60-capture.bak"

cd "$REPO_DIR"

OLD_HEAD="$(git rev-parse HEAD 2>/dev/null || echo none)"
if git pull --ff-only 2>&1; then
    NEW_HEAD="$(git rev-parse HEAD 2>/dev/null || echo none)"
else
    echo "[gta60-update] git pull failed or offline; continuing with existing checkout"
    NEW_HEAD="$OLD_HEAD"
fi

if [[ ! -x "$BIN" || "$OLD_HEAD" != "$NEW_HEAD" ]]; then
    echo "[gta60-update] building linux-capture ($OLD_HEAD -> $NEW_HEAD)..."
    if [[ -x "$BIN" ]]; then
        cp -f "$BIN" "$BAK"
    fi

    if cmake -B "$BUILD_DIR" -S "$SRC_DIR" && cmake --build "$BUILD_DIR" -j"$(nproc)" && "$BIN" --selftest; then
        echo "[gta60-update] build and selftest passed"
    else
        echo "[gta60-update] build or selftest FAILED!" >&2
        if [[ -x "$BAK" ]]; then
            echo "[gta60-update] rolling back to previous binary" >&2
            cp -f "$BAK" "$BIN"
        else
            exit 1
        fi
    fi
else
    "$BIN" --selftest
fi
