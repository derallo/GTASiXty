#!/usr/bin/env python3
"""Renders 6 camera angles of jimmys_room.glb to tools/jimmys-room-src/renders/ for visual inspection."""

import io
import json
import math
import struct
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
GLB_PATH = ROOT / "quest-spatial-app" / "src" / "main" / "assets" / "scenes" / "jimmys_room.glb"
OUT_DIR = ROOT / "tools" / "jimmys-room-src" / "renders"
OUT_DIR.mkdir(parents=True, exist_ok=True)


def srgb_to_linear(c):
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def linear_to_srgb(c):
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def load_glb(path: Path):
    raw = path.read_bytes()
    _, _ver, _length = struct.unpack_from("<III", raw, 0)
    jlen, _jtype = struct.unpack_from("<II", raw, 12)
    gltf = json.loads(raw[20 : 20 + jlen].decode("utf-8"))
    blen, _btype = struct.unpack_from("<II", raw, 20 + jlen)
    bin_buf = raw[28 + jlen : 28 + jlen + blen]

    def read_acc(ai):
        acc = gltf["accessors"][ai]
        bv = gltf["bufferViews"][acc["bufferView"]]
        off = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
        dt = {5126: np.float32, 5125: np.uint32, 5123: np.uint16}[acc["componentType"]]
        nc = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
        arr = np.frombuffer(bin_buf, dtype=dt, count=acc["count"] * nc, offset=off)
        return arr.reshape(acc["count"], nc) if nc > 1 else arr

    imgs = []
    for im in gltf.get("images", []):
        bv = gltf["bufferViews"][im["bufferView"]]
        b = bin_buf[bv.get("byteOffset", 0) : bv.get("byteOffset", 0) + bv["byteLength"]]
        rgba = np.asarray(Image.open(io.BytesIO(b)).convert("RGBA"), dtype=np.float32) / 255.0
        # Exact glTF 2.0 / Meta Spatial SDK pipeline: sRGB albedo texture decoded to linear
        rgba[:, :, :3] = srgb_to_linear(rgba[:, :, :3])
        imgs.append(rgba)

    prims = []
    for p in gltf["meshes"][0]["primitives"]:
        pos = read_acc(p["attributes"]["POSITION"])
        uv = read_acc(p["attributes"]["TEXCOORD_0"])
        col = read_acc(p["attributes"]["COLOR_0"])
        idx = read_acc(p["indices"]).reshape(-1, 3)
        mat = gltf["materials"][p["material"]]
        ti = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        img = imgs[gltf["textures"][ti]["source"]]
        amode = mat.get("alphaMode", "OPAQUE")
        ds = mat.get("doubleSided", False)
        prims.append((pos, uv, col, idx, img, amode, ds, mat.get("name", "")))
    return prims


def render_view(prims, cam_pos, look_at, fov_deg=82.0, W=960, H=600, draw_tv_overlay=True):
    cam = np.array(cam_pos, dtype=np.float32)
    tgt = np.array(look_at, dtype=np.float32)
    fwd = tgt - cam
    fwd /= np.linalg.norm(fwd)
    up_w = np.array([0.0, 1.0, 0.0], dtype=np.float32)
    right = np.cross(fwd, up_w)
    right /= np.linalg.norm(right)
    up = np.cross(right, fwd)

    f = (W * 0.5) / math.tan(math.radians(fov_deg * 0.5))
    fb = np.zeros((H, W, 3), dtype=np.float32)
    zb = np.full((H, W), 1e9, dtype=np.float32)

    all_prims = list(prims)
    if draw_tv_overlay:
        hw, hh = 2.10 * 0.5, (2.10 / (16 / 9)) * 0.5
        cy, cz = 1.750, 2.420
        tv_pos = np.array([[hw, cy - hh, cz], [-hw, cy - hh, cz], [-hw, cy + hh, cz], [hw, cy + hh, cz]], dtype=np.float32)
        tv_uv = np.array([[0, 1], [1, 1], [1, 0], [0, 0]], dtype=np.float32)
        tv_col = np.ones((4, 3), dtype=np.float32)
        tv_idx = np.array([[0, 1, 2], [0, 2, 3]], dtype=np.uint32)
        tv_tex = np.zeros((32, 64, 4), dtype=np.float32)
        tv_tex[:, :, :] = [0.01, 0.56, 0.82, 1.0]
        tv_tex[2:30, 2:62, :] = [0.005, 0.04, 0.11, 1.0]
        tv_tex[14:18, 12:52, :] = [0.04, 0.82, 0.34, 1.0]
        all_prims.append((tv_pos, tv_uv, tv_col, tv_idx, tv_tex, "OPAQUE", True, "TEST_OPENXR_PANEL"))

    all_prims.sort(key=lambda x: 1 if x[5] == "BLEND" else 0)

    for pos, uv, col, idx, img, amode, ds, _mname in all_prims:
        rel = pos - cam[None, :]
        cx = rel @ right
        cy = rel @ up
        cz = rel @ fwd

        z0, z1, z2 = cz[idx[:, 0]], cz[idx[:, 1]], cz[idx[:, 2]]
        valid = (z0 > 0.12) & (z1 > 0.12) & (z2 > 0.12)
        if not np.any(valid):
            continue
        t_idx = idx[valid]
        x0, x1, x2 = cx[t_idx[:, 0]], cx[t_idx[:, 1]], cx[t_idx[:, 2]]
        y0, y1, y2 = cy[t_idx[:, 0]], cy[t_idx[:, 1]], cy[t_idx[:, 2]]
        z0, z1, z2 = cz[t_idx[:, 0]], cz[t_idx[:, 1]], cz[t_idx[:, 2]]

        sx0, sy0 = x0 / z0 * f + W * 0.5, -y0 / z0 * f + H * 0.5
        sx1, sy1 = x1 / z1 * f + W * 0.5, -y1 / z1 * f + H * 0.5
        sx2, sy2 = x2 / z2 * f + W * 0.5, -y2 / z2 * f + H * 0.5

        area = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0)
        keep = (np.abs(area) > 0.25) if ds else (area < -0.25)
        if not np.any(keep):
            continue

        t_idx = t_idx[keep]
        sx0, sy0, z0 = sx0[keep], sy0[keep], z0[keep]
        sx1, sy1, z1 = sx1[keep], sy1[keep], z1[keep]
        sx2, sy2, z2 = sx2[keep], sy2[keep], z2[keep]
        area = area[keep]
        th, tw = img.shape[0], img.shape[1]

        for k in range(len(t_idx)):
            a = area[k]
            minx = max(0, int(math.floor(min(sx0[k], sx1[k], sx2[k]))))
            maxx = min(W - 1, int(math.ceil(max(sx0[k], sx1[k], sx2[k]))))
            miny = max(0, int(math.floor(min(sy0[k], sy1[k], sy2[k]))))
            maxy = min(H - 1, int(math.ceil(max(sy0[k], sy1[k], sy2[k]))))
            if minx > maxx or miny > maxy:
                continue
            xs = np.arange(minx, maxx + 1, dtype=np.float32) + 0.5
            ys = np.arange(miny, maxy + 1, dtype=np.float32) + 0.5
            gx, gy = np.meshgrid(xs, ys)
            w0 = ((sx1[k] - gx) * (sy2[k] - gy) - (sy1[k] - gy) * (sx2[k] - gx)) / a
            w1 = ((sx2[k] - gx) * (sy0[k] - gy) - (sy2[k] - gy) * (sx0[k] - gx)) / a
            w2 = 1.0 - w0 - w1
            inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
            if not np.any(inside):
                continue
            iy, ix = np.where(inside)
            pw0, pw1, pw2 = w0[iy, ix], w1[iy, ix], w2[iy, ix]
            invz = pw0 / z0[k] + pw1 / z1[k] + pw2 / z2[k]
            pz = 1.0 / invz
            py_i = miny + iy
            px_i = minx + ix
            zpass = pz < zb[py_i, px_i]
            if not np.any(zpass):
                continue
            py_i, px_i, pz = py_i[zpass], px_i[zpass], pz[zpass]
            pw0, pw1, pw2 = pw0[zpass], pw1[zpass], pw2[zpass]

            i0, i1, i2 = t_idx[k]
            u = (pw0 * uv[i0, 0] / z0[k] + pw1 * uv[i1, 0] / z1[k] + pw2 * uv[i2, 0] / z2[k]) * pz
            v = (pw0 * uv[i0, 1] / z0[k] + pw1 * uv[i1, 1] / z1[k] + pw2 * uv[i2, 1] / z2[k]) * pz
            tx = (np.floor(u * tw).astype(np.int32)) % tw
            ty = (np.floor(v * th).astype(np.int32)) % th
            tex_rgba = img[ty, tx]
            if amode == "MASK":
                mp = tex_rgba[:, 3] >= 0.45
                if not np.any(mp):
                    continue
                py_i, px_i, pz, tex_rgba = py_i[mp], px_i[mp], pz[mp], tex_rgba[mp]
                pw0, pw1, pw2 = pw0[mp], pw1[mp], pw2[mp]
            vc = pw0[:, None] * col[i0] + pw1[:, None] * col[i1] + pw2[:, None] * col[i2]
            rgb = tex_rgba[:, :3] * vc
            if amode == "BLEND":
                al = tex_rgba[:, 3:4]
                fb[py_i, px_i] = fb[py_i, px_i] * (1.0 - al) + rgb * al
            else:
                zb[py_i, px_i] = pz
                fb[py_i, px_i] = rgb

    # Exact Quest 3 sRGB swapchain output (linear -> sRGB)
    out = np.clip(linear_to_srgb(fb) * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGB")


def main():
    prims = load_glb(GLB_PATH)
    print(f"Loaded {len(prims)} primitives from {GLB_PATH}")
    views = [
        ("view1_seated_tv.png",          (0.0, 1.56, 0.0),    (0.0, 1.75, 2.42),  80.0),
        ("view2_wide_room.png",          (-0.9, 1.50, -1.6),  (1.2, 1.25, 2.0),   92.0),
        ("view3_right_side.png",         (0.8, 1.45, -0.4),   (-2.0, 1.35, 1.3),  92.0),
        ("view4_desk_closeup.png",       (2.8, 1.55, 1.3),    (4.9, 1.05, 0.55),  75.0),
        ("view5_bed_guitar_lamp.png",    (1.2, 1.45, 1.3),    (3.3, 1.15, -1.5),  82.0),
        ("view6_tv_console_closeup.png", (0.2, 1.25, 0.7),    (0.0, 1.25, 2.42),  68.0),
    ]
    for fn, cp, la, fov in views:
        im = render_view(prims, cp, la, fov_deg=fov)
        p = OUT_DIR / fn
        im.save(p)
        print("Saved", p)


if __name__ == "__main__":
    main()
