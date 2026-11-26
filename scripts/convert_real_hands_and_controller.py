#!/usr/bin/env python3
"""
Converts the downloaded real 3D models into lightweight, zero-GPU-cost
KHR_materials_unlit + linear COLOR_0 GLBs for Quest 3 (120 Hz):

  1. tools/gta-hands-src/xbox_elite_raw.glb (real Xbox Elite Series 2, 241k tris)
     -> quest-spatial-app/src/main/assets/scenes/xbox_controller.glb (~8k tris, ~300 KB)
  2. tools/gta-hands-src/oculus_hand_left.glb & oculus_hand_right.glb
     (official sculpted 25-joint skinned OpenXR hands, 2,314 tris each)
     -> quest-spatial-app/src/main/assets/scenes/gta_hand_left.glb & gta_hand_right.glb
"""

import io
import json
import struct
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = ROOT / "tools" / "gta-hands-src"
OUT_DIR = ROOT / "quest-spatial-app" / "src" / "main" / "assets" / "scenes"
TATTOO_LOGO_PATH = Path(r"C:\Users\derek\.gemini\antigravity\brain\3495d4ea-f1d3-4bcf-ac3c-d5b19d5ebc50\.user_uploaded\media_1795699101792_5c71d7de.png")


def read_glb(path: Path):
    raw = path.read_bytes()
    jlen, _ = struct.unpack_from("<II", raw, 12)
    gltf = json.loads(raw[20 : 20 + jlen].decode("utf-8"))
    blen, _ = struct.unpack_from("<II", raw, 20 + jlen)
    bin_buf = raw[28 + jlen : 28 + jlen + blen]
    return gltf, bin_buf


def read_accessor(gltf: dict, bin_buf: bytes, acc_idx: int) -> np.ndarray:
    acc = gltf["accessors"][acc_idx]
    bv = gltf["bufferViews"][acc["bufferView"]]
    off = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
    dt = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}[acc["componentType"]]
    nc = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[acc["type"]]
    stride = bv.get("byteStride", 0)
    elem_sz = np.dtype(dt).itemsize * nc
    if stride and stride != elem_sz:
        raw = np.frombuffer(bin_buf, dtype=np.uint8, count=acc["count"] * stride, offset=off).reshape(acc["count"], stride)
        arr = raw[:, :elem_sz].copy().view(dt).reshape(acc["count"], nc)
    else:
        arr = np.frombuffer(bin_buf, dtype=dt, count=acc["count"] * nc, offset=off).copy()
        if nc > 1:
            arr = arr.reshape(acc["count"], nc)
    return arr


def write_glb(path: Path, gltf: dict, bin_bytes: bytes):
    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4 != 0:
        json_bytes += b" "
    bin_arr = bytearray(bin_bytes)
    while len(bin_arr) % 4 != 0:
        bin_arr.append(0)
    total = 12 + 8 + len(json_bytes) + 8 + len(bin_arr)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        f.write(json_bytes)
        f.write(struct.pack("<II", len(bin_arr), 0x004E4942))
        f.write(bin_arr)


def srgb_to_linear(c: np.ndarray) -> np.ndarray:
    c = np.clip(c, 0.0, 1.0)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def weld_and_decimate(pos: np.ndarray, nrm: np.ndarray, idx: np.ndarray, cell_m: float):
    """Fast 3D spatial grid vertex clustering that preserves silhouettes and sharp creases."""
    q = np.floor(pos / cell_m).astype(np.int64)
    # Quantize normal coarsely (6 directions) so sharp edges don't weld across 90-deg corners
    qn = np.round(nrm * 1.5).astype(np.int64)
    keys = np.concatenate([q, qn], axis=1)
    # Pack 6 int64s into structured bytes for fast np.unique
    keys_view = np.ascontiguousarray(keys).view(np.dtype((np.void, keys.dtype.itemsize * 6))).ravel()
    _, first_idx, inv = np.unique(keys_view, return_index=True, return_inverse=True)
    n_new = len(first_idx)

    # Average positions & normals per cluster
    new_pos = np.zeros((n_new, 3), dtype=np.float32)
    new_nrm = np.zeros((n_new, 3), dtype=np.float32)
    cnt = np.zeros(n_new, dtype=np.float32)
    np.add.at(new_pos, inv, pos)
    np.add.at(new_nrm, inv, nrm)
    np.add.at(cnt, inv, 1.0)
    new_pos /= np.maximum(cnt[:, None], 1.0)
    nlen = np.linalg.norm(new_nrm, axis=1, keepdims=True)
    new_nrm = np.where(nlen > 1e-4, new_nrm / np.maximum(nlen, 1e-4), np.array([[0, 1, 0]], dtype=np.float32))

    # Remap triangles and drop degenerate ones
    t = inv[idx.reshape(-1, 3)]
    keep = (t[:, 0] != t[:, 1]) & (t[:, 1] != t[:, 2]) & (t[:, 2] != t[:, 0])
    t = t[keep]
    # Compact unused vertices
    used, inv2 = np.unique(t.reshape(-1), return_inverse=True)
    return new_pos[used].astype(np.float32), new_nrm[used].astype(np.float32), inv2.astype(np.uint32)


def bake_jimmys_room_lighting(pos: np.ndarray, nrm: np.ndarray, base_srgb: np.ndarray, glossy: float = 0.25, emissive: bool = False) -> np.ndarray:
    """Bakes Jimmy's Room studio lighting (cool TV top + warm bedside lamp + soft AO) into linear COLOR_0."""
    base_lin = srgb_to_linear(base_srgb)
    if emissive:
        return np.clip(base_lin, 0.0, 1.0).astype(np.float32)

    # Key light 1: Cool slate-blue TV screen in front & slightly above (-Z, +Y in controller local)
    l_tv = np.array([0.05, 0.72, -0.69], dtype=np.float32)
    l_tv /= np.linalg.norm(l_tv)
    nd_tv = np.clip(np.sum(nrm * l_tv[None, :], axis=1, keepdims=True), 0.0, 1.0)

    # Key light 2: Warm bedside/desk lamp from left-above
    l_warm = np.array([-0.68, 0.62, 0.39], dtype=np.float32)
    l_warm /= np.linalg.norm(l_warm)
    nd_warm = np.clip(np.sum(nrm * l_warm[None, :], axis=1, keepdims=True), 0.0, 1.0)

    # Specular sheen from viewer's eyes (+Y, +Z)
    v_eye = np.array([0.0, 0.78, 0.62], dtype=np.float32)
    h_tv = l_tv + v_eye
    h_tv /= np.linalg.norm(h_tv)
    spec = np.power(np.clip(np.sum(nrm * h_tv[None, :], axis=1, keepdims=True), 0.0, 1.0), 18.0) * glossy

    hemi = 0.5 + 0.5 * nrm[:, 1:2]
    amb = (0.14 + 0.14 * hemi) * np.array([[0.90, 0.94, 1.02]], dtype=np.float32)
    lit = (
        amb
        + nd_tv * np.array([[0.52, 0.60, 0.76]], dtype=np.float32)
        + nd_warm * np.array([[0.38, 0.30, 0.20]], dtype=np.float32)
    )
    out = base_lin * lit + spec * np.array([[0.32, 0.38, 0.48]], dtype=np.float32)
    return np.clip(out, 0.012, 1.0).astype(np.float32)


def convert_xbox_elite():
    gltf, bin_buf = read_glb(SRC_DIR / "xbox_elite_raw.glb")

    # Skip purely internal/hidden meshes and alternate cross d-pad
    SKIP = {
        "Mesh_internal-components",
        "Mesh_interposer-buttons",
        "Mesh_back-logo-decals",
        "Mesh_d-pad-decals-alphad",
        "Mesh_d-pad-cross",
    }

    # Per-part sRGB base color + glossiness + decimation cell size (m)
    PART_STYLE = {
        "Mesh_body-top-case":               ((0.18, 0.19, 0.21), 0.38, 0.0032, False),
        "Mesh_back-case-trans":             ((0.14, 0.15, 0.17), 0.20, 0.0038, False),
        "Mesh_grips-front":                 ((0.11, 0.115, 0.125), 0.14, 0.0035, False),
        "Mesh_grips-back":                  ((0.10, 0.105, 0.115), 0.12, 0.0038, False),
        "Mesh_triggers":                    ((0.26, 0.28, 0.31), 0.50, 0.0028, False),
        "Mesh_bumpers-trans":               ((0.24, 0.26, 0.29), 0.48, 0.0028, False),
        "Mesh_back-switches":               ((0.35, 0.37, 0.40), 0.45, 0.0035, False),
        "Mesh_view-menu-share-buttons":     ((0.32, 0.34, 0.37), 0.35, 0.0022, False),
        "Mesh_abxy-buttons-trans":          ((0.12, 0.12, 0.13), 0.55, 0.0018, False),
        "Mesh_abxy-buttons-exterior-trans": ((0.16, 0.17, 0.18), 0.40, 0.0020, False),
        "Mesh_lights-trans":                ((0.96, 0.98, 1.00), 0.00, 0.0018, True),   # Glowing white Xbox guide button!
        "Mesh_thumbstick-bases":            ((0.20, 0.21, 0.23), 0.30, 0.0025, False),
        "Mesh_thumbstick-domes":            ((0.15, 0.16, 0.18), 0.25, 0.0028, False),
        "Mesh_thumbstick-rings":            ((0.52, 0.55, 0.60), 0.65, 0.0022, False),  # Iconic silver Elite rings!
        "Mesh_thumbstick-toppers":          ((0.22, 0.23, 0.25), 0.22, 0.0022, False),
        "Mesh_combined-noncustom":          ((0.16, 0.17, 0.19), 0.25, 0.0035, False),
        "Mesh_d-pad-faceted":               ((0.46, 0.49, 0.54), 0.68, 0.0022, False),  # Iconic metallic Elite faceted D-pad!
    }

    pos_all, col_all, idx_all = [], [], []
    vert_off = 0

    for node in gltf["nodes"]:
        name = node.get("name", "")
        mi = node.get("mesh")
        if mi is None or name in SKIP:
            continue
        base_rgb, glossy, cell_m, is_emissive = PART_STYLE.get(name, ((0.20, 0.21, 0.23), 0.25, 0.0032, False))

        mesh = gltf["meshes"][mi]
        for pr in mesh["primitives"]:
            p_raw = read_accessor(gltf, bin_buf, pr["attributes"]["POSITION"]).astype(np.float32)
            n_raw = read_accessor(gltf, bin_buf, pr["attributes"]["NORMAL"]).astype(np.float32)
            i_raw = read_accessor(gltf, bin_buf, pr["indices"]).astype(np.uint32)

            # Transform cm -> m and rotate -90 deg around X: (x, y, z) -> (x, +z, -y) * 0.01
            # Result: +X right, +Y face UP, -Z triggers forward, +Z grips toward chest
            p = np.stack([p_raw[:, 0], p_raw[:, 2], -p_raw[:, 1]], axis=1) * 0.01
            n = np.stack([n_raw[:, 0], n_raw[:, 2], -n_raw[:, 1]], axis=1)

            dp, dn, di = weld_and_decimate(p, n, i_raw, cell_m=cell_m)
            if len(di) == 0:
                continue

            # Give A/B/X/Y buttons their signature subtle Xbox colors by local X/Z position!
            base_arr = np.tile(np.array(base_rgb, dtype=np.float32), (len(dp), 1))
            if name == "Mesh_abxy-buttons-trans":
                # In ControllerRoot (+X right, -Z top): A is bottom (+Z), B is right (+X), X is left (-X), Y is top (-Z)
                cx = dp[:, 0] - 0.039
                cz = dp[:, 2] - (-0.026)
                is_a = (cz > 0.004) & (np.abs(cx) < 0.010)
                is_b = (cx > 0.004) & (np.abs(cz) < 0.010)
                is_x = (cx < -0.004) & (np.abs(cz) < 0.010)
                is_y = (cz < -0.004) & (np.abs(cx) < 0.010)
                # Subtle glowing colored letters/rims on top face of each button
                top = dn[:, 1] > 0.55
                base_arr[is_a & top] = [0.22, 0.78, 0.30]   # Green A
                base_arr[is_b & top] = [0.86, 0.24, 0.22]   # Red B
                base_arr[is_x & top] = [0.22, 0.52, 0.92]   # Blue X
                base_arr[is_y & top] = [0.92, 0.78, 0.20]   # Yellow Y

            vc = bake_jimmys_room_lighting(dp, dn, base_arr, glossy=glossy, emissive=is_emissive)
            pos_all.append(dp)
            col_all.append(vc)
            idx_all.append(di + vert_off)
            vert_off += len(dp)

    pos = np.concatenate(pos_all, axis=0).astype(np.float32)
    col = np.concatenate(col_all, axis=0).astype(np.float32)
    idx = np.concatenate(idx_all, axis=0).astype(np.uint32)

    # Center horizontally/depth-wise cleanly on ControllerRoot
    pos[:, 0] -= (pos[:, 0].min() + pos[:, 0].max()) * 0.5
    pos[:, 1] -= (pos[:, 1].min() + pos[:, 1].max()) * 0.5
    pos[:, 2] -= (pos[:, 2].min() + pos[:, 2].max()) * 0.5

    bin_chunks = bytearray()
    bvs, accs = [], []

    def add_acc(arr, target, ctype, atype, mm=False):
        while len(bin_chunks) % 4 != 0:
            bin_chunks.append(0)
        off = len(bin_chunks)
        data = arr.tobytes()
        bin_chunks.extend(data)
        bv_i = len(bvs)
        bvs.append({"buffer": 0, "byteOffset": off, "byteLength": len(data), "target": target})
        a = {"bufferView": bv_i, "componentType": ctype, "count": len(arr), "type": atype}
        if mm:
            a["min"] = arr.min(axis=0).tolist()
            a["max"] = arr.max(axis=0).tolist()
        ai = len(accs)
        accs.append(a)
        return ai

    pos_a = add_acc(pos, 34962, 5126, "VEC3", mm=True)
    col_a = add_acc(col, 34962, 5126, "VEC3")
    idx_a = add_acc(idx, 34963, 5125, "SCALAR")

    out_gltf = {
        "asset": {"version": "2.0", "generator": "GTA-Sixty Real Xbox Elite Series 2 Converter"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "ControllerRoot", "mesh": 0}],
        "meshes": [{
            "name": "XboxEliteSeries2",
            "primitives": [{
                "attributes": {"POSITION": pos_a, "COLOR_0": col_a},
                "indices": idx_a,
                "material": 0,
            }],
        }],
        "materials": [{
            "name": "xbox_elite_unlit",
            "pbrMetallicRoughness": {"baseColorFactor": [1.0, 1.0, 1.0, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.9},
            "extensions": {"KHR_materials_unlit": {}},
        }],
        "accessors": accs,
        "bufferViews": bvs,
        "buffers": [{"byteLength": len(bin_chunks)}],
    }

    out_path = OUT_DIR / "xbox_controller.glb"
    write_glb(out_path, out_gltf, bytes(bin_chunks))
    print(f"Exported {out_path.name}: {out_path.stat().st_size/1024:.1f} KiB | {len(pos):,} verts | {len(idx)//3:,} tris | bounds min={np.round(pos.min(0),4)} max={np.round(pos.max(0),4)}")


def build_right_hand_tattoo_texture(logo_path: Path) -> bytes:
    """Builds a 512x512 sRGB PNG with white skin-passthrough margin and centered R* tattoo."""
    size = 512
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    if logo_path.exists():
        logo = Image.open(logo_path).convert("RGBA")
        # Gently soften pure-black/vivid colors into ink-on-skin feel while keeping it crisp & vivid
        pad = int(size * 0.14)
        inner = size - 2 * pad
        logo = logo.resize((inner, inner), Image.Resampling.LANCZOS)
        # Blend logo at 90% over white so COLOR_0 skin lighting shades through naturally
        l_arr = np.asarray(logo, dtype=np.float32)
        rgb = l_arr[..., :3] * 0.90 + 255.0 * 0.10
        # Keep pure-white corners outside the rounded badge 100% white
        l_arr[..., :3] = np.clip(rgb, 0, 255)
        logo = Image.fromarray(l_arr.astype(np.uint8), "RGBA")
        canvas.alpha_composite(logo, (pad, pad))
    buf = io.BytesIO()
    canvas.convert("RGB").save(buf, format="PNG", optimize=True)
    return buf.getvalue()


def convert_oculus_hand(src_name: str, dst_name: str, with_tattoo: bool = False):
    """
    Preserves Meta's official sculpted 25-joint skinned OpenXR hand GLB intact
    (all skinning weights, inverseBindMatrices, and 25 joint nodes) while
    upgrading the material to opaque doubleSided KHR_materials_unlit + warm GTA V
    skin shading in COLOR_0 (and optional dorsal tattoo texture on the right hand).
    """
    gltf, bin_buf = read_glb(SRC_DIR / src_name)
    mesh = gltf["meshes"][0]
    pr = mesh["primitives"][0]
    pos = read_accessor(gltf, bin_buf, pr["attributes"]["POSITION"]).astype(np.float32)
    nrm = read_accessor(gltf, bin_buf, pr["attributes"]["NORMAL"]).astype(np.float32)

    # Warm GTA V Jimmy De Santa skin tone (sRGB) with subtle knuckle/palm variation
    # In Oculus hand bind pose, Y runs from wrist (+0.07) to fingertips (-0.13)
    y_norm = np.clip((pos[:, 1:2] - (-0.13)) / 0.21, 0.0, 1.0)
    skin_srgb = np.concatenate([
        0.78 + 0.05 * (1.0 - y_norm),
        0.58 + 0.03 * y_norm,
        0.46 + 0.03 * y_norm,
    ], axis=1).astype(np.float32)
    vcol = bake_jimmys_room_lighting(pos, nrm, skin_srgb, glossy=0.12, emissive=False)

    # Append COLOR_0 (and optional dorsal UV + tattoo PNG) to existing binary buffer
    bin_arr = bytearray(bin_buf)

    def append_bv(raw_bytes: bytes, target: int | None = None) -> int:
        while len(bin_arr) % 4 != 0:
            bin_arr.append(0)
        off = len(bin_arr)
        bin_arr.extend(raw_bytes)
        bv = {"buffer": 0, "byteOffset": off, "byteLength": len(raw_bytes)}
        if target is not None:
            bv["target"] = target
        idx = len(gltf["bufferViews"])
        gltf["bufferViews"].append(bv)
        return idx

    col_bv = append_bv(vcol.tobytes(), target=34962)
    col_acc = len(gltf["accessors"])
    gltf["accessors"].append({
        "bufferView": col_bv,
        "componentType": 5126,
        "count": len(vcol),
        "type": "VEC3",
    })
    pr["attributes"]["COLOR_0"] = col_acc

    mat_def = {
        "name": "gta_hand_skin_unlit",
        "pbrMetallicRoughness": {
            "baseColorFactor": [1.0, 1.0, 1.0, 1.0],
            "metallicFactor": 0.0,
            "roughnessFactor": 0.9,
        },
        "alphaMode": "OPAQUE",
        "doubleSided": True,
        "extensions": {"KHR_materials_unlit": {}},
    }

    if with_tattoo and TATTOO_LOGO_PATH.exists():
        # Dorsal back of oculus_hand_right.glb has +X normal (colY = +X) and x > 0.030:
        #   y in [-0.020 (knuckles = top), +0.030 (wrist = bottom)]
        #   z in [-0.014 (thumb/index = left), +0.034 (pinky = right)]
        # Project continuous UVs across the entire dorsal surface and rely on CLAMP_TO_EDGE
        # (wrapS/wrapT = 33071) with the white PNG border so border triangles never wrap/smear
        # back to (0.01, 0.01) and create a second duplicate sliver.
        uv = np.full((len(pos), 2), -0.5, dtype=np.float32)
        dorsal = (nrm[:, 0] > 0.15) & (pos[:, 0] > 0.028)
        u_all = (pos[:, 2] - (-0.014)) / 0.048
        v_all = (pos[:, 1] - (-0.020)) / 0.050
        uv[dorsal, 0] = u_all[dorsal]
        uv[dorsal, 1] = v_all[dorsal]

        # Slightly lift skin COLOR_0 under the tattoo badge so yellow/orange pops in dim Jimmy's Room
        inside = dorsal & (u_all >= 0.08) & (u_all <= 0.92) & (v_all >= 0.08) & (v_all <= 0.92)
        in_idx = np.where(inside)[0]
        vcol[in_idx] = np.clip(vcol[in_idx] * 1.45, 0.12, 0.95)
        # Rewrite updated COLOR_0 bytes in place
        col_off = gltf["bufferViews"][col_bv]["byteOffset"]
        bin_arr[col_off : col_off + len(vcol) * 12] = vcol.tobytes()

        uv_bv = append_bv(uv.tobytes(), target=34962)
        uv_acc = len(gltf["accessors"])
        gltf["accessors"].append({
            "bufferView": uv_bv,
            "componentType": 5126,
            "count": len(uv),
            "type": "VEC2",
        })
        pr["attributes"]["TEXCOORD_0"] = uv_acc

        png_bytes = build_right_hand_tattoo_texture(TATTOO_LOGO_PATH)
        img_bv = append_bv(png_bytes, target=None)
        gltf["images"] = [{"bufferView": img_bv, "mimeType": "image/png", "name": "right_hand_tattoo"}]
        gltf["samplers"] = [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}]
        gltf["textures"] = [{"source": 0, "sampler": 0}]
        mat_def["pbrMetallicRoughness"]["baseColorTexture"] = {"index": 0}

    exts = list(gltf.get("extensionsUsed", []))
    if "KHR_materials_unlit" not in exts:
        exts.append("KHR_materials_unlit")
    gltf["extensionsUsed"] = exts
    gltf["materials"] = [mat_def]
    pr["material"] = 0
    gltf["buffers"][0]["byteLength"] = len(bin_arr)

    out_path = OUT_DIR / dst_name
    write_glb(out_path, gltf, bytes(bin_arr))
    tris = gltf["accessors"][pr["indices"]]["count"] // 3
    print(f"Exported {out_path.name}: {out_path.stat().st_size/1024:.1f} KiB | {len(pos):,} verts | {tris:,} tris | skin_joints={len(gltf['skins'][0]['joints'])} | tattoo={with_tattoo}")


if __name__ == "__main__":
    convert_xbox_elite()
    convert_oculus_hand("oculus_hand_left.glb", "gta_hand_left.glb", with_tattoo=False)
    convert_oculus_hand("oculus_hand_right.glb", "gta_hand_right.glb", with_tattoo=True)
