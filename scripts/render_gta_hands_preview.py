#!/usr/bin/env python3
"""
render_gta_hands_preview.py
===========================

Software-rasterised preview renderer for the three procedural first-person
assets produced by scripts/build_gta_hands_and_controller_glb.py.

It parses the written .glb files, walks the node tree applying the authored
rest-pose TRS, decodes each primitive's POSITION / TEXCOORD_0 / COLOR_0 /
indices and its sRGB baseColorTexture, and rasterises the result with a simple
perspective camera + z-buffer.  Shading is the same unlit equation the Meta
Spatial SDK uses:

    linear_out = srgb_to_linear(texture_rgb) * COLOR_0

The output is converted linear -> sRGB for the final PNG (the Quest 3 OpenXR
sRGB swapchain).  No lights are used: all lighting is baked into COLOR_0.

Renders written to tools/gta-hands-preview/:
    hands_pair_front.png        both hands, dorsal view, rest pose
    hands_pair_palm.png         both hands, palm view, rest pose
    controller_top.png          controller face (+Y), shoulder at top
    controller_bottom.png       controller rear (-Y), grips at top
    controller_three_quarter.png
    hands_holding_controller.png  approximate two-handed grip

Also prints per-asset vertex/triangle counts and bounding boxes.

Run:  python scripts/render_gta_hands_preview.py
"""

from __future__ import annotations

import io
import json
import math
import struct
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SCENES = ROOT / "quest-spatial-app" / "src" / "main" / "assets" / "scenes"
OUT_DIR = ROOT / "tools" / "gta-hands-preview"
OUT_DIR.mkdir(parents=True, exist_ok=True)

HAND_R = SCENES / "gta_hand_right.glb"
HAND_L = SCENES / "gta_hand_left.glb"
CONTROLLER = SCENES / "xbox_controller.glb"


# ---------------------------------------------------------------------------
# Colour / math helpers
# ---------------------------------------------------------------------------


def srgb_to_linear(c):
    c = np.clip(np.asarray(c, dtype=np.float32), 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def linear_to_srgb(c):
    c = np.clip(np.asarray(c, dtype=np.float32), 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def norm(v):
    v = np.asarray(v, dtype=np.float64)
    n = np.linalg.norm(v)
    return v / max(n, 1e-12)


def quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def node_matrix(n):
    M = np.eye(4)
    T = np.eye(4)
    if "translation" in n:
        T[:3, 3] = n["translation"]
    R = np.eye(4)
    if "rotation" in n:
        R[:3, :3] = quat_mat(n["rotation"])
    S = np.eye(4)
    if "scale" in n:
        S[:3, :3] = np.diag(n["scale"])
    return T @ R @ S


def frame_from_axes(y_dir, z_hint):
    y = norm(y_dir)
    z = np.asarray(z_hint, dtype=np.float64) - np.dot(z_hint, y) * y
    z = norm(z)
    x = np.cross(y, z)
    return np.stack([x, y, z], axis=1)


def rot_x(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    M = np.eye(4)
    M[1, 1], M[1, 2], M[2, 1], M[2, 2] = c, -s, s, c
    return M


def rot_z(deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    M = np.eye(4)
    M[0, 0], M[0, 1], M[1, 0], M[1, 1] = c, -s, s, c
    return M


def grip_overrides(mirror=False):
    """Extra local joint rotations used only for the holding preview.

    The authored rest pose is a relaxed open hand; a real pad grip curls the
    fingers around the grips and lays the thumb forward along the face.
    Rotations about local Z/Y must flip sign on the mirrored left hand (its
    local X axis points the other way).
    """
    ov = {}
    for f in ("INDEX", "MIDDLE", "RING", "LITTLE"):
        ov[f + "_PROXIMAL"] = rot_x(-14.0)
        ov[f + "_INTERMEDIATE"] = rot_x(-34.0)
        ov[f + "_DISTAL"] = rot_x(-28.0)
    az = -10.0 if mirror else 10.0
    ov["THUMB_METACARPAL"] = rot_x(28.0) @ rot_z(az)
    ov["THUMB_PROXIMAL"] = rot_x(-20.0)
    ov["THUMB_DISTAL"] = rot_x(-15.0)
    return ov


def translation_matrix(t):
    M = np.eye(4)
    M[:3, 3] = t
    return M


def placement(R, t):
    M = np.eye(4)
    M[:3, :3] = R
    M[:3, 3] = t
    return M


def mirror_frame_x(R):
    M = np.diag([-1.0, 1.0, 1.0])
    y = M @ R[:, 1]
    z = M @ R[:, 2]
    x = np.cross(y, z)
    return np.stack([x, y, z], axis=1)


# ---------------------------------------------------------------------------
# GLB loading (rest pose)
# ---------------------------------------------------------------------------


def load_glb(path: Path, overrides=None):
    """Load a GLB and bake the authored rest pose into world-space primitives.

    overrides: optional {node_name: 4x4 local matrix} applied AFTER the node's
    authored TRS (used only to pose the holding preview like the runtime would).
    """
    raw = path.read_bytes()
    magic, ver, length = struct.unpack_from("<III", raw, 0)
    if magic != 0x46546C67:
        raise ValueError(f"{path}: not a GLB")
    jlen, jtype = struct.unpack_from("<II", raw, 12)
    gltf = json.loads(raw[20: 20 + jlen].decode("utf-8"))
    blen, btype = struct.unpack_from("<II", raw, 20 + jlen)
    bin_buf = raw[28 + jlen: 28 + jlen + blen]

    def read_acc(ai):
        acc = gltf["accessors"][ai]
        bv = gltf["bufferViews"][acc["bufferView"]]
        off = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
        dt = {5126: np.float32, 5125: np.uint32, 5123: np.uint16, 5121: np.uint8}[
            acc["componentType"]
        ]
        nc = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[acc["type"]]
        arr = np.frombuffer(bin_buf, dtype=dt, count=acc["count"] * nc, offset=off)
        return arr.reshape(acc["count"], nc) if nc > 1 else arr

    imgs = []
    for im in gltf.get("images", []):
        bv = gltf["bufferViews"][im["bufferView"]]
        off = bv.get("byteOffset", 0)
        blob = bin_buf[off: off + bv["byteLength"]]
        rgba = np.asarray(Image.open(io.BytesIO(blob)).convert("RGBA"), dtype=np.float32) / 255.0
        rgba[:, :, :3] = srgb_to_linear(rgba[:, :, :3])
        imgs.append(rgba)

    root_index = gltf["scenes"][gltf.get("scene", 0)]["nodes"][0]
    prims = []
    node_world = {}
    ov = overrides or {}

    def rec(i, parent_M):
        n = gltf["nodes"][i]
        M = parent_M @ node_matrix(n) @ ov.get(n["name"], np.eye(4))
        node_world[n["name"]] = M
        if "mesh" in n:
            for prim in gltf["meshes"][n["mesh"]]["primitives"]:
                pos = read_acc(prim["attributes"]["POSITION"]).astype(np.float64)
                uv = read_acc(prim["attributes"]["TEXCOORD_0"]).astype(np.float64)
                col = read_acc(prim["attributes"]["COLOR_0"]).astype(np.float64)
                idx = read_acc(prim["indices"]).reshape(-1, 3).astype(np.int64)
                mat = gltf["materials"][prim["material"]]
                ti = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
                img = imgs[gltf["textures"][ti]["source"]]
                world = pos @ M[:3, :3].T + M[:3, 3]
                prims.append({
                    "node": n["name"],
                    "pos": world,
                    "uv": uv,
                    "col": col,
                    "idx": idx,
                    "img": img,
                    "double_sided": mat.get("doubleSided", False),
                })
        for c in n.get("children", []):
            rec(c, M)

    rec(root_index, np.eye(4))
    return gltf, prims, node_world


def apply_placement(prims, M):
    out = []
    for p in prims:
        q = dict(p)
        q["pos"] = p["pos"] @ M[:3, :3].T + M[:3, 3]
        out.append(q)
    return out


def scene_bbox(prims):
    lo = np.full(3, 1e9)
    hi = np.full(3, -1e9)
    for p in prims:
        if len(p["pos"]) == 0:
            continue
        lo = np.minimum(lo, p["pos"].min(axis=0))
        hi = np.maximum(hi, p["pos"].max(axis=0))
    return lo, hi


def asset_stats(name, prims):
    verts = sum(len(p["pos"]) for p in prims)
    tris = sum(len(p["idx"]) for p in prims)
    lo, hi = scene_bbox(prims)
    print(f"  {name:<22} prims={len(prims):3d}  verts={verts:5d}  tris={tris:5d}  "
          f"bbox=({lo[0]:+.3f},{lo[1]:+.3f},{lo[2]:+.3f})..({hi[0]:+.3f},{hi[1]:+.3f},{hi[2]:+.3f})")
    return tris


# ---------------------------------------------------------------------------
# Rasteriser
# ---------------------------------------------------------------------------


def camera_basis(eye, target, up_world):
    eye = np.asarray(eye, dtype=np.float64)
    target = np.asarray(target, dtype=np.float64)
    fwd = norm(target - eye)
    right = norm(np.cross(fwd, np.asarray(up_world, dtype=np.float64)))
    up = np.cross(right, fwd)
    return eye, fwd, right, up


def render_view(prims, eye, target, up_world, fov_deg, W, H, bg_top, bg_bottom):
    eye, fwd, right, up = camera_basis(eye, target, up_world)
    f = (W * 0.5) / math.tan(math.radians(fov_deg * 0.5))

    # background: vertical gradient + soft vignette
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    t = yy / max(H - 1, 1)
    fb = (np.array(bg_top, dtype=np.float32)[None, None, :] * (1.0 - t[..., None]) +
          np.array(bg_bottom, dtype=np.float32)[None, None, :] * t[..., None])
    r = np.sqrt(((xx - W * 0.5) / (W * 0.5)) ** 2 + ((yy - H * 0.5) / (H * 0.5)) ** 2)
    fb *= (1.0 - 0.35 * np.clip(r / 1.4, 0.0, 1.0))[..., None]
    zb = np.full((H, W), 1e9, dtype=np.float32)

    for p in prims:
        pos = p["pos"]
        uv = p["uv"]
        col = p["col"]
        idx = p["idx"]
        img = p["img"]
        ds = p["double_sided"]

        rel = pos - eye[None, :]
        cx = rel @ right
        cy = rel @ up
        cz = rel @ fwd
        z0, z1, z2 = cz[idx[:, 0]], cz[idx[:, 1]], cz[idx[:, 2]]
        valid = (z0 > 0.08) & (z1 > 0.08) & (z2 > 0.08)
        if not np.any(valid):
            continue
        t_idx = idx[valid]
        x0, x1, x2 = cx[t_idx[:, 0]], cx[t_idx[:, 1]], cx[t_idx[:, 2]]
        y0, y1, y2 = cy[t_idx[:, 0]], cy[t_idx[:, 1]], cy[t_idx[:, 2]]
        z0, z1, z2 = cz[t_idx[:, 0]], cz[t_idx[:, 1]], cz[t_idx[:, 2]]

        sx0 = x0 / z0 * f + W * 0.5
        sy0 = -y0 / z0 * f + H * 0.5
        sx1 = x1 / z1 * f + W * 0.5
        sy1 = -y1 / z1 * f + H * 0.5
        sx2 = x2 / z2 * f + W * 0.5
        sy2 = -y2 / z2 * f + H * 0.5

        area = (sx1 - sx0) * (sy2 - sy0) - (sy1 - sy0) * (sx2 - sx0)
        keep = (np.abs(area) > 0.20) if ds else (area < -0.20)
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
            u = (pw0 * uv[i0, 0] / z0[k] + pw1 * uv[i1, 0] / z1[k] +
                 pw2 * uv[i2, 0] / z2[k]) * pz
            v = (pw0 * uv[i0, 1] / z0[k] + pw1 * uv[i1, 1] / z1[k] +
                 pw2 * uv[i2, 1] / z2[k]) * pz
            tx = (np.floor(u * tw).astype(np.int32)) % tw
            ty = (np.floor(v * th).astype(np.int32)) % th
            tex = img[ty, tx]
            vc = pw0[:, None] * col[i0] + pw1[:, None] * col[i1] + pw2[:, None] * col[i2]
            rgb = tex[:, :3] * vc
            zb[py_i, px_i] = pz
            fb[py_i, px_i] = rgb

    out = np.clip(linear_to_srgb(fb) * 255.0, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGB")


def render_supersampled(prims, eye, target, up_world, fov_deg, W, H,
                        ss=1.5, bg_top=(0.022, 0.026, 0.034), bg_bottom=(0.005, 0.006, 0.008)):
    w2 = int(round(W * ss))
    h2 = int(round(H * ss))
    im = render_view(prims, eye, target, up_world, fov_deg, w2, h2, bg_top, bg_bottom)
    if (w2, h2) != (W, H):
        im = im.resize((W, H), Image.LANCZOS)
    return im


# ---------------------------------------------------------------------------
# Labels
# ---------------------------------------------------------------------------


def _font(size):
    for p in ("C:/Windows/Fonts/arialbd.ttf", "C:/Windows/Fonts/segoeuib.ttf",
              "C:/Windows/Fonts/arial.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def label(im, text, xy, size=17, fill=(210, 218, 228), anchor="la", bg=True):
    draw = ImageDraw.Draw(im, "RGBA")
    font = _font(size)
    bb = draw.textbbox(xy, text, font=font, anchor=anchor)
    if bg:
        draw.rounded_rectangle((bb[0] - 5, bb[1] - 3, bb[2] + 5, bb[3] + 3),
                               radius=4, fill=(8, 10, 14, 150))
    draw.text(xy, text, font=font, fill=fill, anchor=anchor)


def project_point(eye, target, up_world, fov_deg, W, H, p):
    eye, fwd, right, up = camera_basis(eye, target, up_world)
    f = (W * 0.5) / math.tan(math.radians(fov_deg * 0.5))
    rel = np.asarray(p, dtype=np.float64) - eye
    z = float(rel @ fwd)
    if z <= 1e-6:
        return None
    x = float(rel @ right)
    y = float(rel @ up)
    return (x / z * f + W * 0.5, -y / z * f + H * 0.5)


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main():
    if not (HAND_R.exists() and HAND_L.exists() and CONTROLLER.exists()):
        print("Missing GLBs. Run scripts/build_gta_hands_and_controller_glb.py first.")
        return 1

    print("Loading GLBs...")
    _, prims_r, nodes_r = load_glb(HAND_R)
    _, prims_l, nodes_l = load_glb(HAND_L)
    _, prims_c, nodes_c = load_glb(CONTROLLER)

    print("\nAsset statistics (authored rest pose, world/root space):")
    asset_stats("gta_hand_right.glb", prims_r)
    asset_stats("gta_hand_left.glb", prims_l)
    asset_stats("xbox_controller.glb", prims_c)
    print(f"  hand node count: {len(nodes_r)}  controller node count: {len(nodes_c)}")

    # ------------------------------------------------------------------
    # 1/2. Pair, dorsal + palm
    # ------------------------------------------------------------------
    pair_front = (apply_placement(prims_r, translation_matrix([0.076, 0.0, 0.0])) +
                  apply_placement(prims_l, translation_matrix([-0.076, 0.0, 0.0])))
    pair_palm = (apply_placement(prims_r, translation_matrix([0.076, 0.0, 0.0])) +
                 apply_placement(prims_l, translation_matrix([-0.076, 0.0, 0.0])))

    up = (0.0, 1.0, 0.0)
    eye_f = (0.0, 0.042, 0.51)
    tgt_f = (0.0, 0.042, 0.0)
    im = render_supersampled(pair_front, eye_f, tgt_f, up, 48.0, 1000, 700, ss=1.5)
    label(im, "GTA-Sixty procedural hands - dorsal (back-of-hand) rest pose", (24, 20), 18)
    for name, x in (("RIGHT HAND", 0.076), ("LEFT HAND", -0.076)):
        sp = project_point(eye_f, tgt_f, up, 48.0, 1000, 700, (x, -0.078, 0.0))
        if sp:
            label(im, name, (sp[0], 676), 16, anchor="ma")
    im.save(OUT_DIR / "hands_pair_front.png")
    print("saved", OUT_DIR / "hands_pair_front.png")

    eye_p = (0.0, 0.042, -0.51)
    tgt_p = (0.0, 0.042, 0.0)
    im = render_supersampled(pair_palm, eye_p, tgt_p, up, 48.0, 1000, 700, ss=1.5)
    label(im, "GTA-Sixty procedural hands - palm side", (24, 20), 18)
    for name, x in (("RIGHT HAND", 0.076), ("LEFT HAND", -0.076)):
        sp = project_point(eye_p, tgt_p, up, 48.0, 1000, 700, (x, -0.078, 0.0))
        if sp:
            label(im, name, (sp[0], 676), 16, anchor="ma")
    im.save(OUT_DIR / "hands_pair_palm.png")
    print("saved", OUT_DIR / "hands_pair_palm.png")

    # 2b. Thumb-side profile of the right hand (shows the rest-pose curl)
    side_prims = apply_placement(prims_r, np.eye(4))
    eye_s = (-0.30, 0.030, 0.0)
    tgt_s = (0.0, 0.030, 0.0)
    im = render_supersampled(side_prims, eye_s, tgt_s, up, 48.0, 560, 700, ss=1.5)
    label(im, "right hand - thumb-side profile", (20, 18), 16)
    im.save(OUT_DIR / "hands_side.png")
    print("saved", OUT_DIR / "hands_side.png")

    # ------------------------------------------------------------------
    # 3/4/5. Controller top / bottom / three-quarter
    # ------------------------------------------------------------------
    ctrl_top = apply_placement(prims_c, np.eye(4))
    im = render_supersampled(ctrl_top, (0.0, 0.33, 0.004), (0.0, 0.0, 0.004),
                             (0.0, 0.0, -1.0), 42.0, 900, 760, ss=1.5,
                             bg_top=(0.030, 0.034, 0.042), bg_bottom=(0.008, 0.009, 0.011))
    label(im, "xbox_controller.glb - face (+Y), shoulder/triggers at top", (22, 18), 16)
    im.save(OUT_DIR / "controller_top.png")
    print("saved", OUT_DIR / "controller_top.png")

    im = render_supersampled(ctrl_top, (0.0, -0.33, 0.0), (0.0, 0.0, 0.0),
                             (0.0, 0.0, 1.0), 42.0, 900, 760, ss=1.5,
                             bg_top=(0.026, 0.030, 0.038), bg_bottom=(0.007, 0.008, 0.010))
    label(im, "xbox_controller.glb - rear (-Y), grips at top", (22, 18), 16)
    im.save(OUT_DIR / "controller_bottom.png")
    print("saved", OUT_DIR / "controller_bottom.png")

    im = render_supersampled(ctrl_top, (0.225, 0.245, 0.265), (0.0, -0.002, 0.0),
                             up, 42.0, 1000, 760, ss=1.5,
                             bg_top=(0.032, 0.036, 0.044), bg_bottom=(0.008, 0.009, 0.012))
    label(im, "xbox_controller.glb - three-quarter (player side, +X right)", (22, 18), 16)
    im.save(OUT_DIR / "controller_three_quarter.png")
    print("saved", OUT_DIR / "controller_three_quarter.png")

    # ------------------------------------------------------------------
    # 6. Holding pose (posed like the runtime: extra joint rotations)
    # ------------------------------------------------------------------
    _, prims_r_pose, _ = load_glb(HAND_R, grip_overrides(mirror=False))
    _, prims_l_pose, _ = load_glb(HAND_L, grip_overrides(mirror=True))

    # Right hand: palm faces inward (-X), dorsal outward/up (+X/+Y), fingers
    # forward/down over the grip (-Z/-Y), thumb flexed up over the face.
    y_r = norm(np.array([0.0, -0.25, -0.97]))
    z_r = norm(np.array([0.82, 0.52, 0.06]))
    R_r = frame_from_axes(y_r, z_r)
    M_r = placement(R_r, np.array([0.086, 0.002, 0.052]))
    y_l = norm(np.array([0.0, -0.25, -0.97]))
    z_l = norm(np.array([-0.82, 0.52, 0.06]))
    R_l = frame_from_axes(y_l, z_l)
    M_l = placement(R_l, np.array([-0.086, 0.002, 0.052]))

    holding = (apply_placement(prims_c, np.eye(4)) +
               apply_placement(prims_r_pose, M_r) +
               apply_placement(prims_l_pose, M_l))
    eye_h = (0.0, 0.245, 0.35)
    tgt_h = (0.0, -0.005, 0.012)
    im = render_supersampled(holding, eye_h, tgt_h, up, 45.0, 1080, 760, ss=1.5,
                             bg_top=(0.030, 0.034, 0.042), bg_bottom=(0.006, 0.007, 0.009))
    label(im, "GTA-Sixty hands + xbox_controller - approximate two-handed grip", (22, 18), 16)
    im.save(OUT_DIR / "hands_holding_controller.png")
    print("saved", OUT_DIR / "hands_holding_controller.png")

    print("\nDone.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
