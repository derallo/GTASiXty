#!/usr/bin/env python3
"""
build_gta_hands_and_controller_glb.py
=====================================

Procedurally builds the three Meta Spatial SDK 0.15.0 first-person assets used by
the GTA-Sixty Quest 3 app:

    quest-spatial-app/src/main/assets/scenes/gta_hand_left.glb
    quest-spatial-app/src/main/assets/scenes/gta_hand_right.glb
    quest-spatial-app/src/main/assets/scenes/xbox_controller.glb

The runtime overrides every skeleton / socket node's local transform every frame.
The authored rest pose is therefore only a fallback, BUT the mesh geometry inside
each node is authored in that node's own local frame exactly as documented below.

--------------------------------------------------------------------------------
HAND LOCAL FRAMES  (both hands, identical node names -- no side suffix)
--------------------------------------------------------------------------------
Every bone / joint node origin sits at its own joint centre.

  local +Y : from this joint toward the NEXT joint down the chain.
             (*_DISTAL and *_TIP continue the previous bone direction;
              THUMB_METACARPAL points toward THUMB_PROXIMAL;
              PALM points wrist -> knuckles.)
  local +Z : DORSAL (back-of-hand / nail) side.
  local +X : +Y x +Z  (right-handed basis).

  FOREARM : origin at the wrist, mesh extrudes along local -Y
            (occupies y in [-0.100, +0.010]) so the runtime can aim -Y at elbow.
  PALM    : origin at the wrist, +Y toward the knuckles, +Z dorsal.
  WRIST   : no mesh (per animation spec); PALM/FOREARM carry the geometry.

Handedness: a right hand with this frame has its thumb on -X.  The left hand is
the *mirror across local X* of the right (mesh X negated, triangle winding
reversed, node frames mirrored).  Both hands share the same local mesh data and
the same baked linear COLOR_0; the left one is simply X-mirrored.

--------------------------------------------------------------------------------
CONTROLLER LOCAL FRAME
--------------------------------------------------------------------------------
  +X = player's RIGHT  (B-button / right-stick side)
  +Y = controller FACE normal (up at the player's face)
  +Z = toward the player's CHEST  (right-handed: Z = X x Y)

  shoulder / trigger / bumper edge : -Z  (faces the TV, z ~ -0.051)
  grips                            : +Z and -Y (down/back toward the player)
  rear / battery bump              : -Y
  origin                           : controller centre, mid-thickness

--------------------------------------------------------------------------------
GLTF / MATERIAL CONVENTIONS
--------------------------------------------------------------------------------
glTF 2.0 binary, little-endian, 4-byte aligned JSON+BIN chunks, KHR_materials_unlit
materials with a sRGB baseColorTexture, metallicFactor=0.0, roughnessFactor=0.9.
Vertex attributes POSITION/NORMAL/TEXCOORD_0/COLOR_0 (COLOR_0 = LINEAR baked
lighting that multiplies the sRGB texture, matching the room asset's fast unlit
path).  uint32 indices, CCW winding.  All RNG is seeded; output bytes are stable.

Run:  python scripts/build_gta_hands_and_controller_glb.py
      python scripts/render_gta_hands_preview.py
"""

from __future__ import annotations

import io
import json
import math
import struct
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SCENES_DIR = ROOT / "quest-spatial-app" / "src" / "main" / "assets" / "scenes"
OUT_LEFT = SCENES_DIR / "gta_hand_left.glb"
OUT_RIGHT = SCENES_DIR / "gta_hand_right.glb"
OUT_CONTROLLER = SCENES_DIR / "xbox_controller.glb"
PREVIEW_DIR = ROOT / "tools" / "gta-hands-preview"

SEED = 20251125
TAU = math.tau

HAND_TRI_BUDGET = 1600
CONTROLLER_TRI_BUDGET = 2200

# Texture atlas rectangles in UV space (u0, v0, u1, v1)
SKIN_UV = (0.160, 0.000, 0.995, 1.000)
NAIL_UV = (0.018, 0.500, 0.138, 0.965)

# Controller atlas rectangles
ATLAS_BODY = (0.010, 0.010, 0.470, 0.470)
ATLAS_GRIP = (0.500, 0.010, 0.980, 0.240)
ATLAS_BUMPER = (0.500, 0.260, 0.620, 0.360)
ATLAS_TRIGGER = (0.640, 0.260, 0.760, 0.360)
ATLAS_STICK_CAP = (0.780, 0.260, 0.980, 0.460)
ATLAS_STICK_SHAFT = (0.500, 0.380, 0.620, 0.460)
ATLAS_BTN_A = (0.500, 0.500, 0.620, 0.620)
ATLAS_BTN_B = (0.640, 0.500, 0.760, 0.620)
ATLAS_BTN_X = (0.500, 0.640, 0.620, 0.760)
ATLAS_BTN_Y = (0.640, 0.640, 0.760, 0.760)
ATLAS_DPAD_UP = (0.780, 0.500, 0.880, 0.600)
ATLAS_DPAD_DOWN = (0.900, 0.500, 1.000, 0.600)
ATLAS_DPAD_LEFT = (0.780, 0.620, 0.880, 0.720)
ATLAS_DPAD_RIGHT = (0.900, 0.620, 1.000, 0.720)
ATLAS_GUIDE = (0.500, 0.780, 0.620, 0.900)
ATLAS_VIEW = (0.640, 0.780, 0.740, 0.880)
ATLAS_MENU = (0.760, 0.780, 0.860, 0.880)

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------


def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, np.power((c + 0.055) / 1.055, 2.4))


def linear_to_srgb(c):
    c = np.clip(np.asarray(c, dtype=np.float64), 0.0, 1.0)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def luminance(c):
    c = np.asarray(c, dtype=np.float64)
    return 0.2126 * c[..., 0] + 0.7152 * c[..., 1] + 0.0722 * c[..., 2]


def norm(v, axis=-1):
    v = np.asarray(v, dtype=np.float64)
    n = np.linalg.norm(v, axis=axis, keepdims=True)
    return v / np.maximum(n, 1e-12)


def smoothstep(a, b, x):
    t = np.clip((np.asarray(x, dtype=np.float64) - a) / (b - a), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def dedup_uv_region(reg, a, b, inset=0.004):
    """Map (a,b) in [0,1]^2 into a UV atlas rectangle with a safety inset."""
    u0, v0, u1, v1 = reg
    return np.stack(
        [
            u0 + inset + a * (u1 - u0 - 2 * inset),
            v0 + inset + b * (v1 - v0 - 2 * inset),
        ],
        axis=-1,
    )


# ---------------------------------------------------------------------------
# Rotation helpers
# ---------------------------------------------------------------------------


def frame_from_y_dorsal(y_dir, dorsal):
    y = norm(y_dir)
    z = np.asarray(dorsal, dtype=np.float64) - np.dot(np.asarray(dorsal, dtype=np.float64), y) * y
    if np.linalg.norm(z) < 1e-6:
        z = np.array([0.0, 0.0, 1.0]) - y[2] * y
    z = norm(z)
    x = np.cross(y, z)
    return np.stack([x, y, z], axis=1)


def quat_from_mat(R):
    """Return glTF quaternion (x,y,z,w) for a proper rotation matrix."""
    R = np.asarray(R, dtype=np.float64)
    tr = R[0, 0] + R[1, 1] + R[2, 2]
    if tr > 0.0:
        s = math.sqrt(tr + 1.0) * 2.0
        w = 0.25 * s
        x = (R[2, 1] - R[1, 2]) / s
        y = (R[0, 2] - R[2, 0]) / s
        z = (R[1, 0] - R[0, 1]) / s
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = math.sqrt(1.0 + R[0, 0] - R[1, 1] - R[2, 2]) * 2.0
        w = (R[2, 1] - R[1, 2]) / s
        x = 0.25 * s
        y = (R[0, 1] + R[1, 0]) / s
        z = (R[0, 2] + R[2, 0]) / s
    elif R[1, 1] > R[2, 2]:
        s = math.sqrt(1.0 + R[1, 1] - R[0, 0] - R[2, 2]) * 2.0
        w = (R[0, 2] - R[2, 0]) / s
        x = (R[0, 1] + R[1, 0]) / s
        y = 0.25 * s
        z = (R[1, 2] + R[2, 1]) / s
    else:
        s = math.sqrt(1.0 + R[2, 2] - R[0, 0] - R[1, 1]) * 2.0
        w = (R[1, 0] - R[0, 1]) / s
        x = (R[0, 2] + R[2, 0]) / s
        y = (R[1, 2] + R[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w], dtype=np.float64)
    q /= np.linalg.norm(q)
    if q[3] < 0:
        q = -q
    return q


def mirror_frame_x(R):
    """Mirror a right-hand frame across local X, keeping a proper rotation.

    Y' = M Y, Z' = M Z, X' = Y' x Z' = -M X.  The resulting local mesh is the
    source mesh with local X negated (and winding reversed).
    """
    M = np.diag([-1.0, 1.0, 1.0])
    y = M @ R[:, 1]
    z = M @ R[:, 2]
    x = np.cross(y, z)
    return np.stack([x, y, z], axis=1)


def transform_point(R, t, p):
    return p @ R.T + t


# ---------------------------------------------------------------------------
# GLB writer (bufferView/accessor conventions copied from
# scripts/build_real_jimmys_room_glb.py :: export_glb)
# ---------------------------------------------------------------------------


def _f(x):
    """Deterministic, -0-clean float for JSON."""
    x = float(x)
    if abs(x) < 5e-10:
        return 0.0
    return round(x, 9)


class GlbBuilder:
    def __init__(self, generator: str):
        self.generator = generator
        self.bin = bytearray()
        self.buffer_views = []
        self.accessors = []
        self.images = []
        self.textures = []
        self.materials = []
        self.meshes = []
        self.nodes = []
        self._texture_cache = {}

    # -- binary ------------------------------------------------------------
    def _append(self, data: bytes):
        while len(self.bin) % 4 != 0:
            self.bin.append(0)
        off = len(self.bin)
        self.bin.extend(data)
        return off, len(data)

    def add_accessor(self, arr, target, comp_type, acc_type, with_minmax=False):
        arr = np.ascontiguousarray(arr)
        if not np.all(np.isfinite(arr)):
            raise ValueError("non-finite accessor data")
        off, length = self._append(arr.tobytes())
        bv_i = len(self.buffer_views)
        self.buffer_views.append(
            {"buffer": 0, "byteOffset": off, "byteLength": length, "target": target}
        )
        acc = {
            "bufferView": bv_i,
            "componentType": comp_type,
            "count": int(arr.shape[0]),
            "type": acc_type,
        }
        if with_minmax:
            acc["min"] = [_f(v) for v in arr.min(axis=0)]
            acc["max"] = [_f(v) for v in arr.max(axis=0)]
        self.accessors.append(acc)
        return len(self.accessors) - 1

    # -- textures / materials ---------------------------------------------
    def add_png_texture(self, pil_img: Image.Image, name: str):
        buf = io.BytesIO()
        pil_img.convert("RGB").save(buf, format="PNG", optimize=True)
        raw = buf.getvalue()
        off, length = self._append(raw)
        bv_i = len(self.buffer_views)
        self.buffer_views.append({"buffer": 0, "byteOffset": off, "byteLength": length})
        self.images.append({"bufferView": bv_i, "mimeType": "image/png", "name": name})
        self.textures.append({"source": len(self.images) - 1, "sampler": 0})
        return len(self.textures) - 1

    def add_unlit_material(self, name: str, texture_index: int, double_sided: bool = False):
        self.materials.append(
            {
                "name": name,
                "pbrMetallicRoughness": {
                    "baseColorTexture": {"index": texture_index},
                    "metallicFactor": 0.0,
                    "roughnessFactor": 0.9,
                },
                "extensions": {"KHR_materials_unlit": {}},
                "doubleSided": bool(double_sided),
            }
        )
        return len(self.materials) - 1

    # -- meshes / nodes ----------------------------------------------------
    def add_mesh(self, name, pos, nrm, uv, col, idx, material_index):
        attrs = {
            "POSITION": self.add_accessor(
                np.asarray(pos, dtype=np.float32), 34962, 5126, "VEC3", with_minmax=True
            ),
            "NORMAL": self.add_accessor(
                np.asarray(nrm, dtype=np.float32), 34962, 5126, "VEC3"
            ),
            "TEXCOORD_0": self.add_accessor(
                np.asarray(uv, dtype=np.float32), 34962, 5126, "VEC2"
            ),
            "COLOR_0": self.add_accessor(
                np.asarray(col, dtype=np.float32), 34962, 5126, "VEC3"
            ),
        }
        idx_acc = self.add_accessor(
            np.asarray(idx, dtype=np.uint32).reshape(-1), 34963, 5125, "SCALAR"
        )
        self.meshes.append(
            {
                "name": name,
                "primitives": [
                    {"attributes": attrs, "indices": idx_acc, "material": material_index}
                ],
            }
        )
        return len(self.meshes) - 1

    def add_node(self, name, mesh=None, children=None, translation=None, rotation=None):
        n = {"name": name}
        if mesh is not None:
            n["mesh"] = mesh
        if children:
            n["children"] = list(children)
        if translation is not None:
            t = np.asarray(translation, dtype=np.float64)
            if np.linalg.norm(t) > 1e-9:
                n["translation"] = [_f(v) for v in t]
        if rotation is not None:
            q = np.asarray(rotation, dtype=np.float64)
            if abs(abs(q[3]) - 1.0) > 1e-9 or np.linalg.norm(q[:3]) > 1e-9:
                n["rotation"] = [_f(v) for v in q]
        self.nodes.append(n)
        return len(self.nodes) - 1

    def write(self, out_path: Path, root_index: int):
        while len(self.bin) % 4 != 0:
            self.bin.append(0)
        gltf = {
            "asset": {"version": "2.0", "generator": self.generator},
            "extensionsUsed": ["KHR_materials_unlit"],
            "scene": 0,
            "scenes": [{"nodes": [root_index]}],
            "nodes": self.nodes,
            "meshes": self.meshes,
            "materials": self.materials,
            "textures": self.textures,
            "images": self.images,
            "samplers": [
                {"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}
            ],
            "accessors": self.accessors,
            "bufferViews": self.buffer_views,
            "buffers": [{"byteLength": len(self.bin)}],
        }
        json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
        while len(json_bytes) % 4 != 0:
            json_bytes += b" "
        total_len = 12 + 8 + len(json_bytes) + 8 + len(self.bin)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "wb") as f:
            f.write(struct.pack("<III", 0x46546C67, 2, total_len))
            f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
            f.write(json_bytes)
            f.write(struct.pack("<II", len(self.bin), 0x004E4942))
            f.write(self.bin)
        return total_len


# ---------------------------------------------------------------------------
# Mesh accumulation / geometry primitives
# ---------------------------------------------------------------------------


class MeshData:
    """Accumulates local-space components (already oriented) for one node."""

    def __init__(self):
        self.pos = []
        self.tri = []
        self.uv = []
        self.tag = []
        self.n = 0

    def add(self, pos, tri, uv, tag):
        pos = np.asarray(pos, dtype=np.float64)
        tri = np.asarray(tri, dtype=np.int64)
        uv = np.asarray(uv, dtype=np.float64)
        if tri.size == 0:
            return
        self.pos.append(pos)
        self.tri.append(tri + self.n)
        self.uv.append(uv)
        self.tag.extend([tag] * len(pos))
        self.n += len(pos)

    def arrays(self):
        if not self.pos:
            return (
                np.zeros((0, 3)),
                np.zeros((0, 3), np.int64),
                np.zeros((0, 2)),
                [],
            )
        return (
            np.concatenate(self.pos, axis=0),
            np.concatenate(self.tri, axis=0),
            np.concatenate(self.uv, axis=0),
            list(self.tag),
        )


def orient_tris(pos, tri, refs):
    """Flip individual triangles whose geometric normal disagrees with refs."""
    if len(tri) == 0:
        return tri
    p0 = pos[tri[:, 0]]
    p1 = pos[tri[:, 1]]
    p2 = pos[tri[:, 2]]
    fn = np.cross(p1 - p0, p2 - p0)
    flip = np.einsum("ij,ij->i", fn, refs) < 0.0
    tri = tri.copy()
    tri[flip] = tri[flip][:, ::-1]
    return tri


def compute_normals(pos, tri):
    n = np.zeros_like(pos, dtype=np.float64)
    if len(tri):
        fn = np.cross(pos[tri[:, 1]] - pos[tri[:, 0]], pos[tri[:, 2]] - pos[tri[:, 0]])
        for k in range(3):
            np.add.at(n, tri[:, k], fn)
    ln = np.linalg.norm(n, axis=1, keepdims=True)
    n = np.divide(n, np.maximum(ln, 1e-12))
    n[ln[:, 0] < 1e-12] = np.array([0.0, 0.0, 1.0])
    return n


def surface_from_rings(rings, ring_uvs, cap_start=None, cap_end=None, side_ref_mode="radial"):
    """Build a tube/loft surface from rings of points.

    rings      : list of (C+1,3) point arrays; last column duplicates first (UV seam).
    ring_uvs   : list of (C+1,2)
    cap_start  : ('pole'|'flat', centre_point, uv) or None
    cap_end    : same
    Returns pos, tri, uv with per-triangle winding fixed to face outward.
    """
    rings = [np.asarray(r, dtype=np.float64) for r in rings]
    ring_uvs = [np.asarray(u, dtype=np.float64) for u in ring_uvs]
    nring = len(rings)
    C = rings[0].shape[0] - 1
    centres = np.array([r[:-1].mean(axis=0) for r in rings])
    pos_list = [r for r in rings]
    uv_list = [u for u in ring_uvs]
    caps = []
    for cap, ring_i, other_i, sgn in (
        (cap_start, 0, 1, 1.0),
        (cap_end, nring - 1, nring - 2, 1.0),
    ):
        if cap is None:
            caps.append(None)
            continue
        kind, centre, cuv = cap
        ci = sum(len(p) for p in pos_list)
        pos_list.append(np.asarray(centre, dtype=np.float64)[None, :])
        uv_list.append(np.asarray(cuv, dtype=np.float64)[None, :])
        caps.append((ci, ring_i, other_i, kind, sgn))
    pos = np.concatenate(pos_list, axis=0)
    uv = np.concatenate(uv_list, axis=0)
    tri = []
    refs = []
    # sides
    for i in range(nring - 1):
        a0 = i * (C + 1)
        a1 = (i + 1) * (C + 1)
        for j in range(C):
            a = a0 + j
            b = a + 1
            c = a1 + j
            d = c + 1
            for (t0, t1, t2) in ((a, c, b), (b, c, d)):
                tri.append((t0, t1, t2))
                fc = (pos[t0] + pos[t1] + pos[t2]) / 3.0
                ctr = (centres[i] + centres[i + 1]) * 0.5
                refs.append(fc - ctr)
    # caps
    for cap in caps:
        if cap is None:
            continue
        ci, ring_i, other_i, kind, sgn = cap
        axis = norm(centres[ring_i] - centres[other_i])
        for j in range(C):
            t0 = ci
            t1 = ring_i * (C + 1) + j
            t2 = ring_i * (C + 1) + j + 1
            tri.append((t0, t1, t2))
            fc = (pos[t0] + pos[t1] + pos[t2]) / 3.0
            if kind == "pole":
                refs.append(fc - centres[ring_i])
            else:
                refs.append(axis * sgn)
    tri = np.asarray(tri, dtype=np.int64)
    refs = np.asarray(refs, dtype=np.float64)
    tri = orient_tris(pos, tri, refs)
    return pos, tri, uv


def vertical_loft(rings, nseg, exp, u_region, v0=0.0, v1=1.0, cap_start=None, cap_end=None):
    """rings: list of (y, half_x, half_z, cx, cz). Returns pos,tri,uv."""
    vals = np.linspace(0.0, TAU, nseg + 1)
    c = np.cos(vals)
    s = np.sin(vals)
    px = np.sign(c) * np.abs(c) ** (2.0 / exp)
    pz = np.sign(s) * np.abs(s) ** (2.0 / exp)
    ring_pts = []
    ring_uvs = []
    n = len(rings)
    for i, (y, hx, hz, cx, cz) in enumerate(rings):
        p = np.stack(
            [cx + hx * px, np.full_like(px, y), cz + hz * pz], axis=1
        )
        vv = v0 + (v1 - v0) * (i / max(n - 1, 1))
        uu = np.linspace(0.0, 1.0, nseg + 1)
        uvv = dedup_uv_region(u_region, uu, np.full_like(uu, vv))
        ring_pts.append(p)
        ring_uvs.append(uvv)
    cs = None
    ce = None
    if cap_start is not None:
        y, hx, hz, cx, cz = rings[0]
        kind, off = cap_start
        if kind == "pole":
            cs = ("pole", np.array([cx, y + off, cz]), ring_uvs[0][0])
        else:
            cs = ("flat", np.array([cx, y, cz]), ring_uvs[0][0])
    if cap_end is not None:
        y, hx, hz, cx, cz = rings[-1]
        kind, off = cap_end
        if kind == "pole":
            ce = ("pole", np.array([cx, y + off, cz]), ring_uvs[-1][0])
        else:
            ce = ("flat", np.array([cx, y, cz]), ring_uvs[-1][0])
    return surface_from_rings(ring_pts, ring_uvs, cs, ce)


def tube_along(stations, nseg, u_region, v0=0.0, v1=1.0, cap_start=None, cap_end=None):
    """stations: list of (centre(3), rx, rz). Cross-section perpendicular to the
    local tangent; one cross-section axis is kept roughly horizontal (X)."""
    stations = [(np.asarray(c, dtype=np.float64), float(rx), float(rz)) for c, rx, rz in stations]
    n = len(stations)
    centres = np.array([s[0] for s in stations])
    tangents = []
    for i in range(n):
        if i == 0:
            t = centres[1] - centres[0]
        elif i == n - 1:
            t = centres[-1] - centres[-2]
        else:
            t = centres[i + 1] - centres[i - 1]
        tangents.append(norm(t))
    vals = np.linspace(0.0, TAU, nseg + 1)
    ca = np.cos(vals)
    sa = np.sin(vals)
    ring_pts = []
    ring_uvs = []
    for i, ((c, rx, rz), t) in enumerate(zip(stations, tangents)):
        side = norm(np.cross(t, np.array([0.0, 1.0, 0.0])))
        if np.linalg.norm(side) < 1e-5:
            side = np.array([1.0, 0.0, 0.0])
        up = norm(np.cross(side, t))
        p = c[None, :] + ca[:, None] * rx * side[None, :] + sa[:, None] * rz * up[None, :]
        vv = v0 + (v1 - v0) * (i / max(n - 1, 1))
        uu = np.linspace(0.0, 1.0, nseg + 1)
        ring_pts.append(p)
        ring_uvs.append(dedup_uv_region(u_region, uu, np.full_like(uu, vv)))
    cs = ce = None
    if cap_start is not None:
        off, kind = cap_start
        cs = (kind, centres[0] - tangents[0] * off, ring_uvs[0][0])
    if cap_end is not None:
        off, kind = cap_end
        ce = (kind, centres[-1] + tangents[-1] * off, ring_uvs[-1][0])
    return surface_from_rings(ring_pts, ring_uvs, cs, ce)


def make_dome(centre, radii, naz, nlat, u_region, up_axis=np.array([0.0, 0.0, 1.0]), tag_u_off=0.0):
    """Low-poly ellipsoid cap (open base), oriented along up_axis."""
    centre = np.asarray(centre, dtype=np.float64)
    rx, ry, rz = radii
    up = norm(up_axis)
    a = norm(np.cross(up, np.array([1.0, 0.0, 0.0])))
    if np.linalg.norm(a) < 1e-5:
        a = np.array([1.0, 0.0, 0.0])
    b = np.cross(up, a)
    rings = []
    uvs = []
    uu = np.linspace(0.0, 1.0, naz + 1)
    for k in range(nlat):
        phi = (math.pi / 2.0) * (k / nlat)
        rr = math.cos(phi)
        hh = math.sin(phi)
        pts = []
        for j in range(naz + 1):
            th = TAU * j / naz
            p = centre + a * (rx * rr * math.cos(th)) + b * (ry * rr * math.sin(th)) + up * (rz * hh)
            pts.append(p)
        rings.append(np.array(pts))
        uvs.append(dedup_uv_region(u_region, uu, np.full_like(uu, 1.0 - k / max(nlat, 1))))
    pos, tri, uv = surface_from_rings(
        rings, uvs, cap_start=None,
        cap_end=("pole", centre + up * rz,
                 dedup_uv_region(u_region, np.array([0.5]), np.array([1.0]))[0]),
    )
    return pos, tri, uv


# ---------------------------------------------------------------------------
# Procedural textures
# ---------------------------------------------------------------------------


def _cosine_field(size, rng, octaves=9, u_lo=0.0, u_hi=1.0):
    """Smooth, u-periodic low-frequency field in [-1,1]-ish."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size
    f = np.zeros((size, size), dtype=np.float64)
    for k in range(octaves):
        fu = int(rng.integers(1, 8))
        fv = float(rng.uniform(0.5, 3.8))
        ph = float(rng.uniform(0.0, TAU))
        amp = float(rng.uniform(0.3, 1.0)) / (1.0 + 0.38 * fu)
        f += amp * np.sin(TAU * fu * u + ph) * np.sin(math.pi * fv * v + ph * 0.71)
    f -= f.mean()
    f /= f.std() + 1e-9
    return f


def make_skin_texture(size=512):
    """Warm beige adult skin: mottling, pores, freckles, palmar red tint,
    knuckle creases, plus a dedicated pale-pink fingernail swatch."""
    rng = np.random.default_rng(SEED ^ 0x51A7C0DE)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    u = (xx + 0.5) / size
    v = (yy + 0.5) / size

    # -- base skin --------------------------------------------------------
    base = np.array([0.792, 0.608, 0.492])
    warm = np.array([0.836, 0.616, 0.452])
    cool = np.array([0.722, 0.565, 0.505])
    f = _cosine_field(size, rng, 11)
    f2 = _cosine_field(size, rng, 7)
    warm_mix = np.clip(0.5 + 0.25 * f2, 0.0, 1.0)[..., None]
    tone = base[None, None, :] * (1.0 - warm_mix) + (
        warm[None, None, :] * 0.55 + cool[None, None, :] * 0.45
    ) * warm_mix
    albedo = tone + f[..., None] * np.array([0.050, 0.040, 0.032])

    # -- broad palmar / crease redness ------------------------------------
    red = np.array([0.780, 0.470, 0.405])
    palmar = np.exp(-((u - 0.775) / 0.085) ** 2)
    palmar += 0.45 * np.exp(-((u - 0.640) / 0.130) ** 2)
    palmar = np.clip(palmar, 0.0, 1.0)
    albedo = albedo * (1.0 - 0.22 * palmar[..., None]) + red * (0.22 * palmar[..., None])

    # soft palm creases (only in the palmar band)
    crease = np.zeros((size, size), dtype=np.float64)
    for vc in (0.285, 0.435, 0.575, 0.705, 0.825):
        wiggle = 0.016 * np.sin(TAU * 2.0 * u + vc * 11.0)
        crease += np.exp(-(((v - (vc + wiggle)) / 0.0105) ** 2))
    crease *= np.exp(-((u - 0.775) / 0.095) ** 2)
    crease = np.clip(crease, 0.0, 1.2)
    albedo *= 1.0 - 0.10 * crease[..., None]
    albedo[..., 0] += 0.012 * crease

    # -- dorsal knuckle blush + faint subcutaneous veins -------------------
    blush = np.exp(-((u - 0.28) / 0.075) ** 2) * np.exp(-((v - 0.52) / 0.28) ** 2) * 0.35
    albedo = albedo * (1.0 - 0.10 * blush[..., None]) + np.array([0.80, 0.47, 0.40]) * (0.10 * blush[..., None])
    vein = np.zeros((size, size), dtype=np.float64)
    for vc, amp, freq in ((0.18, 1.0, 3.0), (0.47, 0.8, 4.0), (0.72, 0.7, 2.0)):
        line = np.exp(-(((v - (vc + 0.020 * np.sin(TAU * freq * u + vc * 9.0))) / 0.0035) ** 2))
        line *= np.exp(-((u - 0.80) / 0.10) ** 2)
        vein += amp * line
    albedo[..., 0] -= 0.010 * vein
    albedo[..., 1] += 0.004 * vein
    albedo[..., 2] += 0.012 * vein

    # -- freckles / pores -------------------------------------------------
    for _ in range(260):
        cu = float(rng.uniform(0.165, 0.995))
        cv = float(rng.uniform(0.01, 0.99))
        rad = float(rng.uniform(0.8, 2.9))
        amp = float(rng.uniform(0.028, 0.085))
        cx = cu * size
        cy = cv * size
        x0 = max(0, int(cx - rad * 3.0) - 1)
        x1 = min(size, int(cx + rad * 3.0) + 2)
        y0 = max(0, int(cy - rad * 3.0) - 1)
        y1 = min(size, int(cy + rad * 3.0) + 2)
        gx = xx[y0:y1, x0:x1] - cx
        gy = yy[y0:y1, x0:x1] - cy
        g = np.exp(-(gx * gx + gy * gy) / (2.0 * rad * rad))
        albedo[y0:y1, x0:x1] -= g[..., None] * amp * np.array([0.95, 0.80, 0.68])

    pore = rng.normal(0.0, 1.0, (size, size))
    albedo += pore[..., None] * np.array([0.0055, 0.0048, 0.0042])
    # fine wrinkle modulation
    fine = 0.5 + 0.5 * np.sin(TAU * (u * 38.0 + v * 9.0) + 1.7)
    albedo += (fine - 0.5)[..., None] * np.array([0.006, 0.004, 0.003])

    # -- fingernail swatch -------------------------------------------------
    u0, v0, u1, v1 = NAIL_UV
    nx0, nx1 = int(u0 * size), int(u1 * size)
    ny0, ny1 = int(v0 * size), int(v1 * size)
    nu = (xx[ny0:ny1, nx0:nx1] + 0.5 - nx0) / max(nx1 - nx0 - 1, 1)
    nv = (yy[ny0:ny1, nx0:nx1] + 0.5 - ny0) / max(ny1 - ny0 - 1, 1)
    nail = np.zeros((nv.shape[0], nv.shape[1], 3), dtype=np.float64)
    nail[:] = np.array([0.845, 0.700, 0.655])
    # pinker toward the free edge (v high), whiter at the lunula (v low)
    nail *= (0.93 + 0.10 * nv)[..., None]
    nail += np.array([0.030, -0.006, -0.012])[None, None, :] * nv[..., None]
    lun = np.exp(-(((nv - 0.16) / 0.085) ** 2)) * np.exp(-(((nu - 0.5) / 0.42) ** 2))
    nail = nail * (1.0 - 0.55 * lun[..., None]) + np.array([0.905, 0.825, 0.800]) * (0.55 * lun[..., None])
    # longitudinal micro-ridges
    nail += (0.012 * np.sin(TAU * (nu * 7.0 + 0.2)) * np.sin(math.pi * nv))[..., None]
    # cuticle / rim shadow near the swatch edges
    edge = np.minimum(np.minimum(nu, 1.0 - nu), np.minimum(nv, 1.0 - nv))
    rim = 1.0 - smoothstep(0.0, 0.075, edge)
    nail *= (1.0 - 0.22 * rim[..., None])
    # feather swatch border to surrounding skin
    feather = smoothstep(0.0, 0.035, edge)
    skin_patch = albedo[ny0:ny1, nx0:nx1]
    nail = nail * feather[..., None] + skin_patch * (1.0 - feather[..., None])
    albedo[ny0:ny1, nx0:nx1] = nail

    albedo = np.clip(albedo, 0.0, 1.0)
    img = Image.fromarray((albedo * 255.0 + 0.5).astype(np.uint8), "RGB")
    lin = srgb_to_linear(albedo).astype(np.float32)
    return img, lin


def _font(size):
    for p in (
        "C:/Windows/Fonts/arialbd.ttf",
        "C:/Windows/Fonts/seguisb.ttf",
        "C:/Windows/Fonts/segoeuib.ttf",
        "C:/Windows/Fonts/arial.ttf",
    ):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    try:
        return ImageFont.load_default(size=size)
    except Exception:
        return ImageFont.load_default()


def _draw_text_center(draw, cx, cy, text, font, fill):
    bb = draw.textbbox((0, 0), text, font=font)
    w = bb[2] - bb[0]
    h = bb[3] - bb[1]
    draw.text((cx - w * 0.5 - bb[0], cy - h * 0.5 - bb[1]), text, font=font, fill=fill)


def _swatch_px(reg, size, inset=0.0):
    u0, v0, u1, v1 = reg
    x0 = int((u0 + inset) * size)
    x1 = int((u1 - inset) * size)
    y0 = int((v0 + inset) * size)
    y1 = int((v1 - inset) * size)
    return x0, y0, x1, y1


def make_controller_texture(size=512):
    """Series X|S-esque atlas: near-black shell, grey bumpers/triggers, sticks,
    coloured ABXY with baked white letters, D-pad arrows, view/menu icons and a
    near-white glowing guide button."""
    rng = np.random.default_rng(SEED ^ 0x0C0B7A11)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float64)
    img = np.zeros((size, size, 3), dtype=np.float64)
    img[:] = np.array([0.10, 0.105, 0.115])

    def noise_field(scale=1.0):
        n = rng.normal(0.0, 1.0, (size, size))
        # cheap blur by box-summing a few shifted copies
        out = n.copy()
        for s in (1, 2, 4):
            out += np.roll(n, s, axis=0) * 0.5 + np.roll(n, s, axis=1) * 0.5
            out += np.roll(n, -s, axis=0) * 0.5 + np.roll(n, -s, axis=1) * 0.5
        out /= out.std() + 1e-9
        return out * scale

    # ---- main body swatch -------------------------------------------------
    bx0, by0, bx1, by1 = _swatch_px(ATLAS_BODY, size)
    body = np.empty((by1 - by0, bx1 - bx0, 3), dtype=np.float64)
    body[:] = np.array([0.115, 0.123, 0.138])
    n = noise_field(1.0)[by0:by1, bx0:bx1]
    body += n[..., None] * np.array([0.0035, 0.0038, 0.0042])
    # broad soft mottling so the big flat shell is not dead flat
    bu = np.linspace(0, 1, body.shape[1])[None, :]
    bv = np.linspace(0, 1, body.shape[0])[:, None]
    body += (0.005 * np.sin(bu * 9.0 + bv * 4.0))[..., None]
    # moulding seam at mid-shell (v=0.5 in this swatch)
    seam = np.exp(-(((bv - 0.5) / 0.006) ** 2))
    body *= 1.0 - 0.45 * seam[..., None]
    body += np.exp(-(((bv - 0.514) / 0.004) ** 2))[..., None] * np.array([0.008, 0.009, 0.010])
    # screws on the rear shell (lower half); a couple of faint scratches
    for (su, sv) in ((0.12, 0.27), (0.38, 0.27), (0.62, 0.27), (0.88, 0.27)):
        cx = int(su * body.shape[1])
        cy = int(sv * body.shape[0])
        for rr, col in ((4.0, 0.030), (2.6, 0.060), (1.4, 0.016)):
            gx = np.arange(max(0, cx - 6), min(body.shape[1], cx + 7)) - cx
            gy = np.arange(max(0, cy - 6), min(body.shape[0], cy + 7)) - cy
            mx, my = np.meshgrid(gx, gy)
            g = np.exp(-((mx * mx + my * my) / (2.0 * rr * rr)))
            body[cy - 6 : cy + 7, cx - 6 : cx + 7] += g[..., None] * np.array([col, col, col * 1.05])
    for _ in range(5):
        x0 = int(rng.uniform(0.02, 0.98) * (bx1 - bx0))
        y0 = int(rng.uniform(0.55, 0.95) * (by1 - by0))
        ln = int(rng.uniform(3, 10))
        ang = rng.uniform(-0.4, 0.4)
        for k in range(ln):
            px = int(x0 + k * math.cos(ang))
            py = int(y0 + k * math.sin(ang))
            if 0 <= px < body.shape[1] and 0 <= py < body.shape[0]:
                body[py, px] += 0.008
    img[by0:by1, bx0:bx1] = body

    # ---- grip swatch ------------------------------------------------------
    gx0, gy0, gx1, gy1 = _swatch_px(ATLAS_GRIP, size)
    grip = np.empty((gy1 - gy0, gx1 - gx0, 3), dtype=np.float64)
    grip[:] = np.array([0.095, 0.103, 0.116])
    gu = (xx[gy0:gy1, gx0:gx1] - gx0) / (gx1 - gx0)
    gv = (yy[gy0:gy1, gx0:gx1] - gy0) / (gy1 - gy0)
    dots = 0.5 + 0.5 * np.sin(TAU * gu * 46.0) * np.sin(TAU * gv * 30.0)
    grip += (dots ** 6)[..., None] * np.array([0.014, 0.016, 0.019])
    grip += noise_field(1.0)[gy0:gy1, gx0:gx1, None] * 0.004
    img[gy0:gy1, gx0:gx1] = grip

    # ---- bumper / trigger swatches ---------------------------------------
    for reg, scale in ((ATLAS_BUMPER, 1.0), (ATLAS_TRIGGER, 1.0)):
        x0, y0, x1, y1 = _swatch_px(reg, size)
        patch = np.empty((y1 - y0, x1 - x0, 3), dtype=np.float64)
        patch[:] = np.array([0.205, 0.216, 0.236])
        patch += noise_field(1.0)[y0:y1, x0:x1, None] * 0.006
        img[y0:y1, x0:x1] = np.clip(patch, 0, 1)

    # ---- stick cap --------------------------------------------------------
    x0, y0, x1, y1 = _swatch_px(ATLAS_STICK_CAP, size)
    patch = np.empty((y1 - y0, x1 - x0, 3), dtype=np.float64)
    patch[:] = np.array([0.235, 0.251, 0.278])
    su = (xx[y0:y1, x0:x1] - x0) / (x1 - x0) * 2.0 - 1.0
    sv = (yy[y0:y1, x0:x1] - y0) / (y1 - y0) * 2.0 - 1.0
    sr = np.sqrt(su * su + sv * sv)
    ang = np.arctan2(sv, su)
    # concentric machined rings on the rim + slightly darker dished centre
    rings = 0.5 + 0.5 * np.cos(sr * 44.0)
    patch += ((rings ** 3) * smoothstep(0.55, 0.95, sr))[..., None] * np.array([0.030, 0.032, 0.036])
    patch *= (1.0 - 0.18 * (1.0 - smoothstep(0.0, 0.7, sr)))[..., None]
    # radial grip ticks
    ticks = (0.5 + 0.5 * np.cos(ang * 24.0)) ** 8
    patch += (ticks * smoothstep(0.55, 0.9, sr))[..., None] * np.array([0.016, 0.017, 0.019])
    # rim highlight
    patch += np.exp(-(((sr - 0.94) / 0.045) ** 2))[..., None] * np.array([0.040, 0.042, 0.046])
    patch *= 1.0 - 0.35 * smoothstep(1.02, 1.12, sr)[..., None]
    img[y0:y1, x0:x1] = np.clip(patch, 0, 1)

    # ---- stick shaft ------------------------------------------------------
    x0, y0, x1, y1 = _swatch_px(ATLAS_STICK_SHAFT, size)
    img[y0:y1, x0:x1] = np.array([0.133, 0.145, 0.165])

    im = Image.fromarray((np.clip(img, 0, 1) * 255.0 + 0.5).astype(np.uint8), "RGB")
    draw = ImageDraw.Draw(im)

    # ---- ABXY buttons -----------------------------------------------------
    letters = (("A", ATLAS_BTN_A, (0.227, 0.651, 0.333)), ("B", ATLAS_BTN_B, (0.847, 0.267, 0.235)),
               ("X", ATLAS_BTN_X, (0.243, 0.498, 0.816)), ("Y", ATLAS_BTN_Y, (0.878, 0.702, 0.227)))
    font_big = _font(44)
    for letter, reg, rgb in letters:
        x0, y0, x1, y1 = _swatch_px(reg, size)
        cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
        rad = (x1 - x0) * 0.44
        rgb255 = tuple(int(round(c * 255)) for c in rgb)
        # soft ambient occlusion ring in the shell around the button
        for k in range(5, 0, -1):
            rr = rad + 2.0 + k * 2.0
            draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(24, 26, 30))
        draw.ellipse((cx - rad - 2, cy - rad - 2, cx + rad + 2, cy + rad + 2), fill=(18, 20, 23))
        draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=rgb255)
        # subtle top-light gradient, kept inside the disc so the sidewall stays dark
        for k in range(3):
            rr = rad * (0.80 - k * 0.18)
            shade = tuple(min(255, int(c + 14 + k * 5)) for c in rgb255)
            draw.ellipse((cx - rr, cy - rr - rad * 0.10, cx + rr, cy + rr - rad * 0.10), fill=shade)
        draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=(14, 16, 19), width=3)
        _draw_text_center(draw, cx, cy + 1, letter, font_big, (248, 248, 250))

    # ---- D-pad arrow swatches (all point UP in texture space) --------------
    arrow_pts = [(0.50, 0.22), (0.74, 0.46), (0.60, 0.46), (0.60, 0.80),
                 (0.40, 0.80), (0.40, 0.46), (0.26, 0.46)]
    for reg in (ATLAS_DPAD_UP, ATLAS_DPAD_DOWN, ATLAS_DPAD_LEFT, ATLAS_DPAD_RIGHT):
        x0, y0, x1, y1 = _swatch_px(reg, size)
        draw.rectangle((x0, y0, x1, y1), fill=(42, 45, 51))
        pts = [(x0 + px * (x1 - x0), y0 + py * (y1 - y0)) for px, py in arrow_pts]
        draw.polygon(pts, fill=(104, 110, 120))
        pts2 = [(x0 + (0.5 + (px - 0.5) * 0.72) * (x1 - x0),
                 y0 + (0.5 + (py - 0.5) * 0.72) * (y1 - y0)) for px, py in arrow_pts]
        draw.polygon(pts2, fill=(126, 132, 142))

    # ---- Guide button (near-white, glowing) --------------------------------
    x0, y0, x1, y1 = _swatch_px(ATLAS_GUIDE, size)
    cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
    rad = (x1 - x0) * 0.45
    draw.ellipse((cx - rad - 4, cy - rad - 4, cx + rad + 4, cy + rad + 4), fill=(20, 22, 26))
    draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=(226, 232, 238))
    for k in range(8):
        rr = rad * (1.0 - k * 0.09)
        draw.ellipse((cx - rr, cy - rr, cx + rr, cy + rr), fill=(236 + k, 240 + k, 246))
    draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), outline=(160, 168, 178), width=2)
    # stylised Xbox X
    d = rad * 0.55
    for a, b in (((-d, -d), (d, d)), ((-d, d), (d, -d))):
        draw.line((cx + a[0], cy + a[1], cx + b[0], cy + b[1]), fill=(84, 92, 104), width=4)
    draw.ellipse((cx - rad * 0.20, cy - rad * 0.20, cx + rad * 0.20, cy + rad * 0.20),
                 outline=(120, 128, 140), width=2)

    # ---- View / Menu icons -------------------------------------------------
    x0, y0, x1, y1 = _swatch_px(ATLAS_VIEW, size)
    cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
    rad = (x1 - x0) * 0.43
    draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=(36, 39, 45), outline=(90, 96, 106), width=2)
    draw.rectangle((cx - rad * 0.55, cy - rad * 0.38, cx - rad * 0.05, cy + rad * 0.38), fill=(200, 205, 214))
    draw.rectangle((cx + rad * 0.05, cy - rad * 0.38, cx + rad * 0.55, cy + rad * 0.38), fill=(200, 205, 214))
    x0, y0, x1, y1 = _swatch_px(ATLAS_MENU, size)
    cx, cy = (x0 + x1) * 0.5, (y0 + y1) * 0.5
    rad = (x1 - x0) * 0.43
    draw.ellipse((cx - rad, cy - rad, cx + rad, cy + rad), fill=(36, 39, 45), outline=(90, 96, 106), width=2)
    for k in (-1, 0, 1):
        draw.rectangle((cx - rad * 0.55, cy + k * rad * 0.34 - 2, cx + rad * 0.55,
                        cy + k * rad * 0.34 + 2), fill=(200, 205, 214))

    return im


# ---------------------------------------------------------------------------
# HAND RIG + MESHES
# ---------------------------------------------------------------------------

FINGER_DEFS = {
    # name : (CMC base, MCP knuckle, (L1,L2,L3), (w_mcp,w_pip,w_dip,w_tip), splay deg, flex offset)
    "INDEX": ((-0.0215, 0.0185, -0.0020), (-0.0315, 0.0925, 0.0005),
              (0.042, 0.026, 0.021), (0.0190, 0.0175, 0.0155, 0.0123), -5.0, (2.0, 0.0, 2.0)),
    "MIDDLE": ((-0.0055, 0.0195, 0.0010), (-0.0105, 0.0950, 0.0015),
               (0.046, 0.029, 0.022), (0.0195, 0.0182, 0.0162, 0.0128), -1.0, (0.0, 0.0, 0.0)),
    "RING": ((0.0085, 0.0190, 0.0000), (0.0110, 0.0930, 0.0005),
             (0.043, 0.027, 0.021), (0.0185, 0.0172, 0.0152, 0.0122), 3.0, (-1.0, -1.0, -1.0)),
    "LITTLE": ((0.0215, 0.0165, -0.0010), (0.0310, 0.0865, -0.0005),
               (0.034, 0.021, 0.019), (0.0165, 0.0152, 0.0136, 0.0108), 7.0, (-2.0, -2.0, -2.0)),
}

THUMB_CMC = np.array([-0.0320, 0.0170, -0.0080])
THUMB_MCP = THUMB_CMC + 0.044 * norm(np.array([-0.550, 0.800, -0.240]))
THUMB_IP = THUMB_MCP + 0.031 * norm(np.array([-0.050, 0.940, -0.340]))
THUMB_TIP = THUMB_IP + 0.026 * norm(np.array([0.100, 0.940, -0.320]))
THUMB_DORSAL = norm(np.array([-0.450, 0.100, 0.880]))


def finger_dir(splay_deg, flex_deg):
    s = math.radians(splay_deg)
    f = math.radians(flex_deg)
    return np.array([math.sin(s), math.cos(s) * math.cos(f), -math.cos(s) * math.sin(f)])


def phalanx_hw(L, w0, w1, y):
    t = 0.0 if L <= 1e-9 else min(max(y / L, 0.0), 1.0)
    return 0.5 * (w0 + (w1 - w0) * t)


def make_phalanx(L, w0, w1, u_off=0.0, v_off=0.0, nseg=8, exp=2.25, end_prof=0.78):
    """Tapered elliptical capsule in the joint's local frame.

    local Y : 0 -> L along the bone.  Mesh spans [-0.004, L+0.006].
    local Z : dorsal.  local X : Y x Z.  4 rings + 2 poles = 64 triangles.
    """
    ring_spec = [
        (-0.0025, 0.78),
        (0.0020, 0.98),
        (0.45 * L, 1.02),
        (L + 0.0025, end_prof),
    ]
    rings = []
    uvs = []
    vals = np.linspace(0.0, TAU, nseg + 1)
    c = np.cos(vals)
    s = np.sin(vals)
    px = np.sign(c) * np.abs(c) ** (2.0 / exp)
    pz = np.sign(s) * np.abs(s) ** (2.0 / exp)
    for (y, prof) in ring_spec:
        t = 0.0 if L <= 1e-9 else min(max(y / L, 0.0), 1.0)
        hw = 0.5 * (w0 + (w1 - w0) * t) * prof
        ht = hw * 0.82
        p = np.stack([hw * px, np.full_like(px, y), ht * pz], axis=1)
        uu = (u_off + np.linspace(0.0, 1.0, nseg + 1)) % 1.0
        vv = v_off + np.clip((y + 0.004) / (L + 0.010), 0.0, 1.0) * 0.80
        rings.append(p)
        uvs.append(dedup_uv_region(SKIN_UV, uu, np.full_like(uu, vv)))
    pos, tri, uv = surface_from_rings(
        rings,
        uvs,
        cap_start=("pole", np.array([0.0, -0.004, 0.0]), np.array([0.50, 0.50])),
        cap_end=("pole", np.array([0.0, L + 0.006, 0.0]), np.array([0.50, 0.50])),
    )
    uv[:, 0] = np.clip(uv[:, 0], SKIN_UV[0] + 0.002, SKIN_UV[2] - 0.002)
    uv[:, 1] = np.clip(uv[:, 1], SKIN_UV[1] + 0.002, SKIN_UV[3] - 0.002)
    return pos, tri, uv


def make_nail(L, w0, w1, u_off=0.0, nseg=12):
    """Elliptical, slightly domed nail patch hugging the dorsal surface near the
    distal end of a distal phalanx.  12 triangles."""
    y_nail = 0.62 * L
    ry = 0.31 * L
    hw_at = max(phalanx_hw(L, w0, w1, y_nail), 0.002)
    rx = min(0.55 * hw_at, 0.0064)
    exp = 2.25
    ht_at = 0.82 * hw_at
    vals = np.linspace(0.0, TAU, nseg, endpoint=False)
    rim = []
    for a in vals:
        x = rx * math.cos(a)
        y = y_nail + ry * math.sin(a)
        hw = max(phalanx_hw(L, w0, w1, y), 0.002)
        ht = 0.82 * hw
        q = min(abs(x) / hw, 0.995)
        z = ht * (1.0 - q ** exp) ** (1.0 / exp) + 0.00035
        rim.append([x, y, z])
    rim = np.array(rim)
    centre = np.array([[0.0, y_nail, ht_at + 0.0011]])
    pos = np.concatenate([centre, rim], axis=0)
    tri = []
    uv = []
    u0, v0, u1, v1 = NAIL_UV
    cu = 0.5 * (u0 + u1) + u_off
    cv = 0.5 * (v0 + v1)
    uv.append([cu, cv])
    for a in vals:
        nu = 0.5 + 0.42 * math.cos(a)
        nv = 0.5 + 0.42 * math.sin(a)
        uv.append([u0 + 0.02 + nu * (u1 - u0 - 0.04), v0 + 0.02 + nv * (v1 - v0 - 0.04)])
    for j in range(nseg):
        tri.append((0, 1 + j, 1 + (j + 1) % nseg))
    tri = np.array(tri, dtype=np.int64)
    refs = np.tile(np.array([0.0, 0.0, 1.0]), (len(tri), 1))
    tri = orient_tris(pos, tri, refs)
    return pos, tri, np.array(uv)


def make_palm_mesh():
    md = MeshData()
    rings = [
        (-0.006, 0.0270, 0.0155, 0.0, 0.0),
        (0.010, 0.0335, 0.0172, 0.0, 0.0),
        (0.030, 0.0385, 0.0180, 0.0, 0.0),
        (0.052, 0.0415, 0.0177, 0.0, 0.0),
        (0.074, 0.0425, 0.0170, 0.0, 0.0),
        (0.090, 0.0405, 0.0160, 0.0, 0.0),
        (0.098, 0.0345, 0.0135, 0.0, 0.0),
    ]
    pos, tri, uv = vertical_loft(
        rings, 20, 2.4, SKIN_UV,
        cap_start=("flat", 0.0),
        cap_end=("pole", 0.004),
    )
    md.add(pos, tri, uv, "palm")

    # Four knuckle mounds on the dorsal side, at the finger bases.
    for name, f in FINGER_DEFS.items():
        mcp = np.array(f[1], dtype=np.float64)
        c = np.array([mcp[0], mcp[1] - 0.011, 0.0085])
        p, t, u = make_dome(
            c, (0.0165, 0.0115, 0.0105), 8, 2,
            (0.175, 0.10, 0.355, 0.48), up_axis=np.array([0.0, 0.0, 1.0]),
        )
        md.add(p, t, u, "mound")

    # Thenar (thumb) mound on the palmar side near the thumb CMC.
    p, t, u = make_dome(
        np.array([-0.0270, 0.0330, -0.0080]),
        (0.0300, 0.0205, 0.0145), 10, 2,
        (0.705, 0.28, 0.865, 0.62), up_axis=np.array([0.0, 0.0, -1.0]),
    )
    md.add(p, t, u, "thenar")
    return md


def make_forearm_mesh():
    md = MeshData()
    rings = [
        (0.010, 0.0260, 0.0175, 0.0, 0.0),
        (0.000, 0.0280, 0.0185, 0.0, 0.0),
        (-0.018, 0.0310, 0.0210, 0.0, 0.0),
        (-0.045, 0.0350, 0.0240, 0.0, 0.0),
        (-0.075, 0.0390, 0.0270, 0.0, 0.0),
        (-0.099, 0.0410, 0.0280, 0.0, 0.0),
    ]
    pos, tri, uv = vertical_loft(
        rings, 12, 2.2, SKIN_UV,
        cap_start=("flat", 0.0),
        cap_end=("flat", 0.0),
    )
    md.add(pos, tri, uv, "forearm")
    return md


class HandRig:
    def __init__(self):
        self.joints = {}
        self.order = []
        self.meshes = {}
        self._build_joints()

    def _add(self, name, parent, pos, R, length=None, w0=None, w1=None,
             kind=None, u_off=0.0, v_off=0.0, nail=False):
        self.joints[name] = {
            "parent": parent,
            "pos": np.asarray(pos, dtype=np.float64),
            "R": np.asarray(R, dtype=np.float64),
            "length": length,
            "w0": w0,
            "w1": w1,
            "kind": kind,
            "u_off": u_off,
            "v_off": v_off,
            "nail": nail,
        }
        self.order.append(name)

    def _build_joints(self):
        hand_R = np.eye(3)
        self._add("WRIST", "HandRoot", np.zeros(3), hand_R)
        self._add("PALM", "WRIST", np.zeros(3), hand_R)
        self._add("FOREARM", "WRIST", np.zeros(3), hand_R)

        # ---- thumb chain -------------------------------------------------
        d_meta = norm(THUMB_MCP - THUMB_CMC)
        d_prox = norm(THUMB_IP - THUMB_MCP)
        d_dist = norm(THUMB_TIP - THUMB_IP)
        R = frame_from_y_dorsal(d_meta, THUMB_DORSAL)
        self._add("THUMB_METACARPAL", "WRIST", THUMB_CMC, R,
                  length=0.044, w0=0.0240, w1=0.0210, kind="phalanx",
                  u_off=0.37, v_off=0.02)
        R = frame_from_y_dorsal(d_prox, THUMB_DORSAL)
        self._add("THUMB_PROXIMAL", "THUMB_METACARPAL", THUMB_MCP, R,
                  length=0.031, w0=0.0210, w1=0.0195, kind="phalanx",
                  u_off=0.53, v_off=0.31)
        R = frame_from_y_dorsal(d_dist, THUMB_DORSAL)
        self._add("THUMB_DISTAL", "THUMB_PROXIMAL", THUMB_IP, R,
                  length=0.026, w0=0.0195, w1=0.0160, kind="phalanx",
                  u_off=0.19, v_off=0.58, nail=True)
        Rtip = frame_from_y_dorsal(d_dist, THUMB_DORSAL)
        self._add("THUMB_TIP", "THUMB_DISTAL", THUMB_TIP, Rtip)

        # ---- four fingers ------------------------------------------------
        for i, (name, f) in enumerate(FINGER_DEFS.items()):
            cmc = np.array(f[0], dtype=np.float64)
            mcp = np.array(f[1], dtype=np.float64)
            L1, L2, L3 = f[2]
            w0, w1, w2, w3 = f[3]
            splay = f[4]
            fo = f[5]
            d_meta = norm(mcp - cmc)
            Rmeta = frame_from_y_dorsal(d_meta, np.array([0.0, 0.0, 1.0]))
            self._add(f"{name}_METACARPAL", "WRIST", cmc, Rmeta,
                      u_off=0.08 + 0.04 * i)
            d1 = finger_dir(splay, 12.0 + fo[0])
            d2 = finger_dir(splay, 30.0 + fo[1])
            d3 = finger_dir(splay, 42.0 + fo[2])
            R1 = frame_from_y_dorsal(d1, np.array([0.0, 0.0, 1.0]))
            R2 = frame_from_y_dorsal(d2, np.array([0.0, 0.0, 1.0]))
            R3 = frame_from_y_dorsal(d3, np.array([0.0, 0.0, 1.0]))
            pip = mcp + L1 * d1
            dip = pip + L2 * d2
            tip = dip + L3 * d3
            self._add(f"{name}_PROXIMAL", f"{name}_METACARPAL", mcp, R1,
                      length=L1, w0=w0, w1=w1, kind="phalanx",
                      u_off=0.11 + 0.07 * i, v_off=0.03)
            self._add(f"{name}_INTERMEDIATE", f"{name}_PROXIMAL", pip, R2,
                      length=L2, w0=w1, w1=w2, kind="phalanx",
                      u_off=0.31 + 0.05 * i, v_off=0.29)
            self._add(f"{name}_DISTAL", f"{name}_INTERMEDIATE", dip, R3,
                      length=L3, w0=w2, w1=w3, kind="phalanx",
                      u_off=0.49 + 0.06 * i, v_off=0.55, nail=True)
            self._add(f"{name}_TIP", f"{name}_DISTAL", tip, R3)

        assert abs(self.joints["MIDDLE_PROXIMAL"]["pos"][1] - 0.095) < 1e-9

    # ------------------------------------------------------------------
    def build_meshes(self, skin_lin):
        for name, j in self.joints.items():
            if j["kind"] == "phalanx":
                md = MeshData()
                p, t, u = make_phalanx(
                    j["length"], j["w0"], j["w1"],
                    u_off=j["u_off"], v_off=j["v_off"],
                    end_prof=0.64 if j["nail"] else 0.78,
                )
                md.add(p, t, u, "skin")
                if j["nail"]:
                    pn, tn, un = make_nail(j["length"], j["w0"], j["w1"], u_off=0.0)
                    md.add(pn, tn, un, "nail")
                self.meshes[name] = md
        self.meshes["PALM"] = make_palm_mesh()
        self.meshes["FOREARM"] = make_forearm_mesh()
        self.skin_lin = skin_lin
        self._bake_colors()

    # ------------------------------------------------------------------
    def _segments(self):
        segs = []
        for name, j in self.joints.items():
            if j["kind"] != "phalanx":
                continue
            p0 = j["pos"]
            p1 = p0 + j["R"] @ np.array([0.0, j["length"], 0.0])
            group = name.split("_")[0]
            segs.append((p0, p1, 0.5 * max(j["w0"], j["w1"]), group))
        return segs

    def _bake_colors(self):
        entries = []
        for name, md in self.meshes.items():
            pos_l, tri, uv, tags = md.arrays()
            nrm_l = compute_normals(pos_l, tri)
            j = self.joints[name]
            pos_h = pos_l @ j["R"].T + j["pos"]
            nrm_h = nrm_l @ j["R"].T
            entries.append((name, md, pos_l, tri, uv, tags, nrm_l, nrm_h, pos_h))
        all_pos = np.concatenate([e[8] for e in entries], axis=0)
        all_nrm = np.concatenate([e[7] for e in entries], axis=0)
        all_uv = np.concatenate([e[4] for e in entries], axis=0)
        all_tags = sum([e[5] for e in entries], [])
        n = len(all_pos)

        # ---- lighting in hand space (Y fingers, Z dorsal, X pinky) -------
        Lkey = norm(np.array([-0.26, 0.38, 0.89]))
        Ltv = norm(np.array([0.06, 0.70, -0.71]))
        Lbounce = norm(np.array([0.02, -0.10, -0.995]))
        Lside = norm(np.array([0.88, 0.18, 0.28]))
        c_key = np.array([1.00, 0.775, 0.545]) * 0.66
        c_tv = np.array([0.28, 0.55, 1.00]) * 0.34
        c_bounce = np.array([0.42, 0.27, 0.145]) * 0.20
        c_side = np.array([0.55, 0.38, 0.22]) * 0.11
        c_amb = np.array([0.050, 0.046, 0.043])

        def wrap(nl, w, p):
            return np.clip((nl + w) / (1.0 + w), 0.0, 1.0) ** p

        nd_key = wrap(all_nrm @ Lkey, 0.18, 1.05)
        nd_tv = wrap(all_nrm @ Ltv, 0.30, 0.95)
        nd_b = wrap(all_nrm @ Lbounce, 0.50, 1.0)
        nd_s = wrap(all_nrm @ Lside, 0.40, 1.0)
        lin = (
            c_amb[None, :]
            + nd_key[:, None] * c_key[None, :]
            + nd_tv[:, None] * c_tv[None, :]
            + nd_b[:, None] * c_bounce[None, :]
            + nd_s[:, None] * c_side[None, :]
        )
        sheen = (np.clip(all_nrm @ Lkey, 0.0, 1.0) ** 10) * 0.06
        lin += sheen[:, None] * np.array([1.0, 0.86, 0.72])[None, :]

        # ---- ambient occlusion ------------------------------------------
        ao = np.ones(n)
        ao *= 1.0 - 0.34 * smoothstep(0.10, 0.92, -all_nrm[:, 2])
        crease = np.zeros(n)
        for name, j in self.joints.items():
            if name in ("WRIST", "PALM", "FOREARM"):
                continue
            d = np.linalg.norm(all_pos - j["pos"][None, :], axis=1)
            crease = np.maximum(crease, np.exp(-((d / 0.0075) ** 2)))
        d = np.linalg.norm(all_pos, axis=1)
        crease = np.maximum(crease, 0.85 * np.exp(-((d / 0.016) ** 2)))
        ao *= 1.0 - 0.18 * crease

        segs = self._segments()
        occ = np.zeros(n)
        groups = np.array([t.split("_")[0] if "_" in t else "x" for t in all_tags], dtype=object)
        for (p0, p1, rad, grp) in segs:
            ab = p1 - p0
            L2 = float(ab @ ab) + 1e-12
            ap = all_pos - p0[None, :]
            t = np.clip((ap @ ab) / L2, 0.0, 1.0)
            d = np.linalg.norm(ap - t[:, None] * ab[None, :], axis=1)
            field = np.exp(-((np.maximum(d - rad, 0.0) / 0.0072) ** 2))
            field[groups == grp] = 0.0
            occ = np.maximum(occ, field)
        ao *= 1.0 - 0.32 * occ

        # nail bed shadow + nail highlight
        nail_mult = np.ones(n)
        idx = 0
        for (name, md, pos_l, tri, uv, tags, nrm_l, nrm_h, pos_h) in entries:
            j = self.joints[name]
            cnt = len(pos_l)
            if j["nail"]:
                L = j["length"]
                w0, w1 = j["w0"], j["w1"]
                y_nail = 0.62 * L
                ry = 0.34 * L
                hw_at = max(phalanx_hw(L, w0, w1, y_nail), 0.002)
                rx = min(0.62 * hw_at, 0.0068)
                q = np.sqrt((pos_l[:, 0] / max(rx, 1e-5)) ** 2 +
                            ((pos_l[:, 1] - y_nail) / max(ry, 1e-5)) ** 2)
                ring = np.exp(-(((q - 1.02) / 0.14) ** 2))
                local_idx = np.arange(idx, idx + cnt)
                tag_arr = np.array(tags, dtype=object)
                skin_sel = tag_arr == "skin"
                ao[local_idx[skin_sel]] *= 1.0 - 0.14 * ring[skin_sel]
                nail_sel = tag_arr == "nail"
                nail_mult[local_idx[nail_sel]] = 1.05
            idx += cnt

        lin *= ao[:, None]
        lin *= nail_mult[:, None]
        lin = np.clip(lin, 0.004, 1.35)

        target = 0.21
        for _ in range(3):
            ml = float(luminance(lin).mean())
            if ml <= 1e-9:
                break
            lin = np.clip(lin * (target / ml), 0.004, 1.35)
        self.color_mean = float(luminance(lin).mean())
        self.lin_colors = lin.astype(np.float32)
        self.skin_mean = float((luminance(self._sample_skin(all_uv) * lin)).mean())

        self.colors = {}
        idx = 0
        for (name, md, pos_l, tri, uv, tags, nrm_l, nrm_h, pos_h) in entries:
            cnt = len(pos_l)
            self.colors[name] = self.lin_colors[idx: idx + cnt]
            idx += cnt

    def _sample_skin(self, uv):
        size = self.skin_lin.shape[0]
        ix = np.clip((uv[:, 0] * size).astype(np.int64), 0, size - 1)
        iy = np.clip((uv[:, 1] * size).astype(np.int64), 0, size - 1)
        return self.skin_lin[iy, ix]

    # ------------------------------------------------------------------
    def node_arrays(self, name, mirror=False):
        md = self.meshes.get(name)
        if md is None:
            return None
        pos, tri, uv, tags = md.arrays()
        if mirror:
            pos = pos.copy()
            pos[:, 0] *= -1.0
            tri = tri[:, [0, 2, 1]]
        nrm = compute_normals(pos, tri)
        col = self.colors[name]
        return (
            np.asarray(pos, dtype=np.float32),
            nrm.astype(np.float32),
            np.asarray(uv, dtype=np.float32),
            np.asarray(col, dtype=np.float32),
            np.asarray(tri, dtype=np.uint32).reshape(-1),
        )

    def local_trs(self, name, mirror=False):
        if name == "HandRoot":
            return np.zeros(3), quat_from_mat(np.eye(3))
        j = self.joints[name]
        parent = j["parent"]
        if parent == "HandRoot":
            return np.zeros(3), quat_from_mat(np.eye(3))
        pj = self.joints[parent]
        pos = j["pos"].copy()
        R = j["R"]
        ppos = pj["pos"].copy()
        Rp = pj["R"]
        if mirror:
            pos = pos * np.array([-1.0, 1.0, 1.0])
            ppos = ppos * np.array([-1.0, 1.0, 1.0])
            R = mirror_frame_x(j["R"])
            Rp = mirror_frame_x(pj["R"])
        t = Rp.T @ (pos - ppos)
        Rl = Rp.T @ R
        q = quat_from_mat(Rl)
        t[np.abs(t) < 1e-10] = 0.0
        return t, q

    def tree(self):
        children = {"HandRoot": ["WRIST"]}
        wrist_order = ["FOREARM", "PALM", "THUMB_METACARPAL", "INDEX_METACARPAL",
                       "MIDDLE_METACARPAL", "RING_METACARPAL", "LITTLE_METACARPAL"]
        children["WRIST"] = wrist_order
        for name in self.order:
            if name in ("WRIST", "PALM", "FOREARM") or name == "HandRoot":
                continue
            if name == "THUMB_METACARPAL":
                children[name] = ["THUMB_PROXIMAL"]
            elif name == "THUMB_PROXIMAL":
                children[name] = ["THUMB_DISTAL"]
            elif name == "THUMB_DISTAL":
                children[name] = ["THUMB_TIP"]
            elif name == "THUMB_TIP":
                children[name] = []
            elif name.endswith("_METACARPAL"):
                children[name] = [name.replace("_METACARPAL", "_PROXIMAL")]
            elif name.endswith("_PROXIMAL"):
                children[name] = [name.replace("_PROXIMAL", "_INTERMEDIATE")]
            elif name.endswith("_INTERMEDIATE"):
                children[name] = [name.replace("_INTERMEDIATE", "_DISTAL")]
            elif name.endswith("_DISTAL"):
                children[name] = [name.replace("_DISTAL", "_TIP")]
            elif name.endswith("_TIP"):
                children[name] = []
        return children


def build_hand_glb(rig: HandRig, mirror: bool, out_path: Path, skin_img):
    gb = GlbBuilder("GTA-Sixty procedural hand (left, mirror-X)" if mirror
                    else "GTA-Sixty procedural hand (right)")
    tex = gb.add_png_texture(skin_img, "gta_hand_skin_512")
    mat = gb.add_unlit_material("gta_hand_skin", tex, double_sided=False)
    children = rig.tree()

    def add_node(name):
        mesh_id = None
        arr = rig.node_arrays(name, mirror=mirror)
        verts, tris = 0, 0
        if arr is not None:
            mesh_id = gb.add_mesh(name + "_Mesh", *arr, material_index=mat)
            verts = len(arr[0])
            tris = len(arr[4]) // 3
        t, q = rig.local_trs(name, mirror=mirror)
        node_id = gb.add_node(name, mesh=mesh_id, translation=t, rotation=q)
        child_ids = [add_node(c)[0] for c in children.get(name, [])]
        if child_ids:
            gb.nodes[node_id]["children"] = child_ids
        return node_id, verts, tris

    root, rv, rt = add_node("HandRoot")
    assert gb.nodes[root].get("mesh") is None
    total = gb.write(out_path, root)
    # total mesh counts
    verts = 0
    tris = 0
    for name in rig.meshes:
        arr = rig.node_arrays(name, mirror=mirror)
        verts += len(arr[0])
        tris += len(arr[4]) // 3
    return {
        "path": out_path,
        "size": total,
        "verts": verts,
        "tris": tris,
        "node_names": [n["name"] for n in gb.nodes],
        "tree": children,
        "glb": gb,
    }


# ---------------------------------------------------------------------------
# XBOX CONTROLLER
# ---------------------------------------------------------------------------

CTRL_FACE_Y = 0.018
CTRL_GIMBAL_Y = 0.006
CTRL_DPAD = np.array([-0.036, CTRL_FACE_Y, 0.014])
CTRL_ABXY = np.array([0.049, CTRL_FACE_Y, 0.001])
CTRL_ABXY_SPACING = 0.019
CTRL_STICK_L = np.array([-0.045, CTRL_GIMBAL_Y, -0.022])
CTRL_STICK_R = np.array([-0.003, CTRL_GIMBAL_Y, 0.014])
CTRL_GUIDE = np.array([0.0, CTRL_FACE_Y, -0.026])
CTRL_VIEW = np.array([-0.016, CTRL_FACE_Y, -0.006])
CTRL_MENU = np.array([0.016, CTRL_FACE_Y, -0.006])

CTRL_NODE_ORDER = [
    "Body", "LB", "RB", "LT", "RT", "LS_Stick", "RS_Stick",
    "DpadUp", "DpadDown", "DpadLeft", "DpadRight",
    "BtnA", "BtnB", "BtnX", "BtnY", "BtnView", "BtnMenu", "Guide",
]


def make_deck():
    """Rounded-rectangle plan-view slab: 5 Y levels, 20-point superellipse."""
    W, D = 0.0765, 0.049
    n = 20
    exp = 4.0
    t = np.linspace(0.0, TAU, n, endpoint=False)
    c, s = np.cos(t), np.sin(t)
    ox = np.sign(c) * np.abs(c) ** (2.0 / exp)
    oz = np.sign(s) * np.abs(s) ** (2.0 / exp)
    # near-vertical side wall + flat face plane at y=+0.018
    levels = [(-0.014, 0.90), (-0.012, 0.995), (0.010, 1.00), (0.0165, 1.00), (0.018, 0.996)]
    rings, uvs = [], []
    for y, sc in levels:
        pts = np.stack([ox * W * sc, np.full_like(ox, y), oz * D * sc], axis=1)
        pts = np.vstack([pts, pts[0]])
        uu = np.linspace(0.0, 1.0, n + 1)
        vv = 0.035 + 0.93 * ((y + 0.014) / 0.032)
        uvs.append(dedup_uv_region(ATLAS_BODY, uu, np.full_like(uu, vv)))
        rings.append(pts)
    cap_uv = dedup_uv_region(ATLAS_BODY, np.array([0.5]), np.array([0.78]))[0]
    pos, tri, uv = surface_from_rings(
        rings, uvs,
        cap_start=("flat", np.array([0.0, -0.014, 0.0]), cap_uv),
        cap_end=("flat", np.array([0.0, 0.018, 0.0]), cap_uv),
    )
    return pos, tri, uv


def make_grip(sign):
    """sign=+1 -> player's right grip (+X); mirrored for the left."""
    stations = [
        (np.array([sign * 0.050, 0.003, 0.006]), 0.019, 0.016),
        (np.array([sign * 0.054, 0.000, 0.016]), 0.020, 0.018),
        (np.array([sign * 0.055, -0.007, 0.026]), 0.021, 0.019),
        (np.array([sign * 0.055, -0.015, 0.033]), 0.020, 0.018),
        (np.array([sign * 0.052, -0.023, 0.038]), 0.017, 0.015),
        (np.array([sign * 0.047, -0.030, 0.040]), 0.011, 0.010),
    ]
    return tube_along(stations, 10, ATLAS_GRIP, v0=0.04, v1=0.96,
                      cap_start=(0.004, "pole"), cap_end=(0.003, "pole"))


def make_battery_bump():
    return make_dome(
        np.array([0.0, -0.012, 0.004]),
        (0.034, 0.044, 0.014), 12, 2,
        (0.040, 0.540, 0.440, 0.930), up_axis=np.array([0.0, -1.0, 0.0]),
    )


def make_bumper(sign):
    """sign=-1 -> LB, +1 -> RB.  Local frame origin at the hinge."""
    sx = sign
    stations = [
        (np.array([0.0, 0.002, -0.001]), 0.0055, 0.0050),
        (np.array([sx * 0.012, 0.003, 0.000]), 0.0060, 0.0052),
        (np.array([sx * 0.024, 0.001, -0.002]), 0.0055, 0.0050),
        (np.array([sx * 0.034, -0.004, -0.003]), 0.0045, 0.0045),
        (np.array([sx * 0.040, -0.010, -0.002]), 0.0035, 0.0038),
    ]
    return tube_along(stations, 8, ATLAS_BUMPER, v0=0.06, v1=0.94,
                      cap_start=(0.002, "pole"), cap_end=(0.003, "pole"))


def make_trigger(sign):
    """sign=-1 -> LT, +1 -> RT.  Local frame origin at the top hinge."""
    stations = [
        (np.array([0.0, 0.000, 0.000]), 0.0145, 0.0045),
        (np.array([0.0, -0.002, -0.002]), 0.0148, 0.0046),
        (np.array([0.0, -0.007, -0.0035]), 0.0142, 0.0044),
        (np.array([0.0, -0.013, -0.005]), 0.0128, 0.0040),
        (np.array([0.0, -0.019, -0.004]), 0.0100, 0.0032),
    ]
    return tube_along(stations, 8, ATLAS_TRIGGER, v0=0.06, v1=0.94,
                      cap_start=(0.001, "pole"), cap_end=(0.001, "pole"))


def make_bezel(cx, cz):
    """Raised dark collar around a thumbstick well (static Body geometry)."""
    naz, nsec = 12, 4
    R, rr, rv = 0.0172, 0.0025, 0.00075
    yc = 0.0187
    pos = []
    for i in range(naz):
        a = TAU * i / naz
        radial = np.array([math.cos(a), 0.0, math.sin(a)])
        c = np.array([cx, yc, cz]) + R * radial
        for j in range(nsec):
            b = TAU * j / nsec
            pos.append(c + radial * (rr * math.cos(b)) + np.array([0.0, rv * math.sin(b), 0.0]))
    pos = np.array(pos)
    tri = []
    refs = []
    for i in range(naz):
        am = TAU * (i + 0.5) / naz
        tube_c = np.array([cx, yc, cz]) + R * np.array([math.cos(am), 0.0, math.sin(am)])
        for j in range(nsec):
            a = i * nsec + j
            b = i * nsec + (j + 1) % nsec
            c = ((i + 1) % naz) * nsec + j
            d = ((i + 1) % naz) * nsec + (j + 1) % nsec
            for (t0, t1, t2) in ((a, c, b), (b, c, d)):
                tri.append((t0, t1, t2))
                refs.append((pos[t0] + pos[t1] + pos[t2]) / 3.0 - tube_c)
    tri = np.array(tri, dtype=np.int64)
    tri = orient_tris(pos, tri, np.array(refs))
    uu = np.full(len(pos), 0.24)
    vv = 0.62 + 0.22 * np.sin(np.arctan2(pos[:, 2] - cz, pos[:, 0] - cx))
    uv = dedup_uv_region(ATLAS_BODY, uu, vv)
    return pos, tri, uv


def make_stick():
    md = MeshData()
    shaft_rings = [
        (0.000, 0.0060, 0.0060, 0.0, 0.0),
        (0.0185, 0.0058, 0.0058, 0.0, 0.0),
    ]
    p, t, u = vertical_loft(shaft_rings, 10, 2.0, ATLAS_STICK_SHAFT,
                            cap_start=None, cap_end=None)
    md.add(p, t, u, "stick_shaft")
    # cap: 7.8 mm above the face, 4 mm of visible shaft below it
    cap_rings = [
        (0.0160, 0.0122, 0.0122, 0.0, 0.0),
        (0.0188, 0.0120, 0.0120, 0.0, 0.0),
        (0.0193, 0.0090, 0.0090, 0.0, 0.0),
        (0.0196, 0.0045, 0.0045, 0.0, 0.0),
    ]
    p, t, u = vertical_loft(cap_rings, 12, 2.0, ATLAS_STICK_CAP,
                            cap_start=("flat", 0.0), cap_end=("pole", 0.0002))
    u = planar_uv(p, ATLAS_STICK_CAP, 0.0122)
    md.add(p, t, u, "stick_cap")
    return md


def planar_uv(pos, reg, radius, u_scale=0.42):
    u0, v0, u1, v1 = reg
    u = 0.5 + u_scale * np.clip(pos[:, 0] / radius, -1.2, 1.2)
    v = 0.5 + u_scale * np.clip(pos[:, 2] / radius, -1.2, 1.2)
    return np.stack([u0 + u * (u1 - u0), v0 + v * (v1 - v0)], axis=1)


def make_dpad_arm(direction):
    """2D plus-arm outline extruded in Y.  Top cap fan carries arrow UVs."""
    outline = [
        (0.0006, 0.0040), (0.0055, 0.0044), (0.0100, 0.0052),
        (0.0126, 0.0042), (0.0136, 0.0000), (0.0126, -0.0042),
        (0.0100, -0.0052), (0.0055, -0.0044), (0.0006, -0.0040),
    ]
    if direction == "up":
        mapper = lambda a, c: (c, a)
    elif direction == "down":
        mapper = lambda a, c: (-c, -a)
    elif direction == "left":
        mapper = lambda a, c: (-a, c)
    else:
        mapper = lambda a, c: (a, c)
    bottom = np.array([[mapper(a, c)[0], 0.0, mapper(a, c)[1]] for a, c in outline])
    top = np.array([[mapper(a, c)[0], 0.0050, mapper(a, c)[1]] for a, c in outline])
    tri = []
    refs = []
    n = len(outline)
    for i in range(n):
        j = (i + 1) % n
        tri.append((i, j, n + i))
        tri.append((j, n + j, n + i))
        fc = (bottom[i] + bottom[j] + top[i]) / 3.0
        axis = np.array([0.0, 0.0025, 0.0])
        refs.append(fc - axis)
        fc = (bottom[j] + top[j] + top[i]) / 3.0
        refs.append(fc - axis)
    pos = np.concatenate([bottom, top], axis=0)
    tri = np.array(tri, dtype=np.int64)
    tri = orient_tris(pos, tri, np.array(refs))
    uvs = np.tile(np.array([ATLAS_DPAD_UP[0] + 0.006, ATLAS_DPAD_UP[1] + 0.006]),
                  (len(pos), 1))

    # top fan with its own rim vertices (crisp edge) and arrow UVs
    top_uvs = []
    for a, c in outline:
        u = 0.5 + 0.42 * (c / 0.0055)
        v = 0.92 - 0.84 * (a / 0.0136)
        top_uvs.append([ATLAS_DPAD_UP[0] + 0.01 + u * (ATLAS_DPAD_UP[2] - ATLAS_DPAD_UP[0] - 0.02),
                        ATLAS_DPAD_UP[1] + 0.01 + v * (ATLAS_DPAD_UP[3] - ATLAS_DPAD_UP[1] - 0.02)])
    cen = np.array([[mapper(0.0068, 0.0)[0], 0.00555, mapper(0.0068, 0.0)[1]]])
    pos2 = np.concatenate([pos, top, cen], axis=0)
    fan = []
    for i in range(n):
        j = (i + 1) % n
        fan.append((2 * n + i, 2 * n + j, 3 * n))
    fan = np.array(fan, dtype=np.int64)
    fan = orient_tris(pos2, fan, np.tile(np.array([0.0, 1.0, 0.0]), (len(fan), 1)))
    tri2 = np.concatenate([tri, fan], axis=0)
    centre_uv = np.array([[0.5 * (ATLAS_DPAD_UP[0] + ATLAS_DPAD_UP[2]),
                           0.5 * (ATLAS_DPAD_UP[1] + ATLAS_DPAD_UP[3])]])
    uv2 = np.concatenate([uvs, np.array(top_uvs), centre_uv], axis=0)
    return pos2, tri2, uv2


def make_button(reg, radius=0.0058, height=0.0032, segments=12):
    """Low cylindrical face button: crisp domed coloured top, dark sidewall.

    The top fan has its own vertices so the letter texture is not smeared down
    the side, and the side wall samples the dark rim of the swatch.
    """
    n = segments
    vals = np.linspace(0.0, TAU, n + 1)
    c, s = np.cos(vals), np.sin(vals)
    r_side = radius * 0.93
    bottom = np.stack([r_side * c, np.zeros_like(c), r_side * s], axis=1)
    top_side = np.stack([radius * c, np.full_like(c, height), radius * s], axis=1)
    top_rim = np.stack([radius * 0.995 * c, np.full_like(c, height), radius * 0.995 * s], axis=1)
    centre = np.array([[0.0, height + 0.0006, 0.0]])
    bottom_c = np.array([[0.0, 0.0, 0.0]])
    pos = np.concatenate([bottom, top_side, top_rim, centre, bottom_c], axis=0)
    n0 = 0                      # bottom ring   [0 .. n]
    n1 = n + 1                  # side top ring [n+1 .. 2n+1]
    n2 = 2 * (n + 1)            # top fan rim   [2n+2 .. 3n+2]
    n3 = 3 * (n + 1)            # top centre
    n4 = n3 + 1                 # bottom centre
    tri = []
    refs = []
    for j in range(n):
        a, b = n0 + j, n0 + j + 1
        c2, d = n1 + j, n1 + j + 1
        for (t0, t1, t2) in ((a, c2, b), (b, c2, d)):
            tri.append((t0, t1, t2))
            fc = (pos[t0] + pos[t1] + pos[t2]) / 3.0
            refs.append(np.array([fc[0], 0.0, fc[2]]))
        tri.append((n2 + j, n2 + j + 1, n3))
        refs.append(np.array([0.0, 1.0, 0.0]))
        tri.append((n4, a, b))
        refs.append(np.array([0.0, -1.0, 0.0]))
    tri = np.array(tri, dtype=np.int64)
    tri = orient_tris(pos, tri, np.array(refs))
    u0, v0, u1, v1 = reg
    uv = np.zeros((len(pos), 2))
    for j in range(n + 1):
        ang = TAU * j / n
        uu = 0.5 + 0.475 * math.cos(ang)
        vv = 0.5 + 0.475 * math.sin(ang)
        uv[n0 + j] = [u0 + uu * (u1 - u0), v0 + vv * (v1 - v0)]
        uv[n1 + j] = [u0 + uu * (u1 - u0), v0 + vv * (v1 - v0)]
        uu2 = 0.5 + 0.405 * math.cos(ang)
        vv2 = 0.5 + 0.405 * math.sin(ang)
        uv[n2 + j] = [u0 + uu2 * (u1 - u0), v0 + vv2 * (v1 - v0)]
    uv[n3] = [u0 + 0.5 * (u1 - u0), v0 + 0.5 * (v1 - v0)]
    uv[n4] = [u0 + 0.5 * (u1 - u0), v0 + 0.5 * (v1 - v0)]
    return pos, tri, uv


def _grip_occluders():
    return [
        (np.array([0.050, 0.004, 0.010]), 0.030),
        (np.array([-0.050, 0.004, 0.010]), 0.030),
    ]


class ControllerRig:
    def __init__(self):
        self.mesh = {}
        self.translation = {}
        self._build()

    def _put(self, name, md, translation):
        self.mesh[name] = md
        self.translation[name] = np.asarray(translation, dtype=np.float64)

    def _build(self):
        body = MeshData()
        p, t, u = make_deck()
        body.add(p, t, u, "body")
        p, t, u = make_grip(+1)
        body.add(p, t, u, "grip")
        p, t, u = make_grip(-1)
        body.add(p, t, u, "grip")
        p, t, u = make_battery_bump()
        body.add(p, t, u, "battery")
        for sx, sz in ((CTRL_STICK_L[0], CTRL_STICK_L[2]),
                       (CTRL_STICK_R[0], CTRL_STICK_R[2])):
            p, t, u = make_bezel(sx, sz)
            body.add(p, t, u, "bezel")
        self._put("Body", body, np.zeros(3))

        for name, sign, kind in (("LB", -1, "bumper"), ("RB", +1, "bumper")):
            md = MeshData()
            p, t, u = make_bumper(sign)
            md.add(p, t, u, "bumper")
            hinge = np.array([sign * 0.020, 0.022, -0.042])
            self._put(name, md, hinge)

        for name, sign in (("LT", -1), ("RT", +1)):
            md = MeshData()
            p, t, u = make_trigger(sign)
            md.add(p, t, u, "trigger")
            hinge = np.array([sign * 0.036, 0.016, -0.043])
            self._put(name, md, hinge)

        for name, pos in (("LS_Stick", CTRL_STICK_L), ("RS_Stick", CTRL_STICK_R)):
            self._put(name, make_stick(), pos)

        for name, d in (("DpadUp", "up"), ("DpadDown", "down"),
                        ("DpadLeft", "left"), ("DpadRight", "right")):
            md = MeshData()
            p, t, u = make_dpad_arm(d)
            md.add(p, t, u, "dpad")
            self._put(name, md, CTRL_DPAD)

        btn_defs = (
            ("BtnA", ATLAS_BTN_A, np.array([0.0, 0.0, CTRL_ABXY_SPACING])),
            ("BtnB", ATLAS_BTN_B, np.array([CTRL_ABXY_SPACING, 0.0, 0.0])),
            ("BtnX", ATLAS_BTN_X, np.array([0.0, 0.0, -CTRL_ABXY_SPACING])),
            ("BtnY", ATLAS_BTN_Y, np.array([-CTRL_ABXY_SPACING, 0.0, 0.0])),
        )
        for name, reg, off in btn_defs:
            md = MeshData()
            p, t, u = make_button(reg)
            md.add(p, t, u, "button")
            self._put(name, md, CTRL_ABXY + off)

        for name, reg, pos, rad, h in (
            ("BtnView", ATLAS_VIEW, CTRL_VIEW, 0.0043, 0.0034),
            ("BtnMenu", ATLAS_MENU, CTRL_MENU, 0.0043, 0.0034),
            ("Guide", ATLAS_GUIDE, CTRL_GUIDE, 0.0080, 0.0048),
        ):
            md = MeshData()
            p, t, u = make_button(reg, radius=rad, height=h, segments=12 if name == "Guide" else 10)
            md.add(p, t, u, "guide" if name == "Guide" else "button_small")
            self._put(name, md, pos)

    def bake(self):
        entries = []
        for name, md in self.mesh.items():
            pos_l, tri, uv, tags = md.arrays()
            nrm = compute_normals(pos_l, tri)
            t = self.translation[name]
            entries.append((name, pos_l + t[None, :], nrm, uv, tags))
        all_pos = np.concatenate([e[1] for e in entries], axis=0)
        all_nrm = np.concatenate([e[2] for e in entries], axis=0)
        all_tags = sum([e[4] for e in entries], [])
        n = len(all_pos)

        Lkey = norm(np.array([0.12, 0.94, 0.32]))
        Ltv = norm(np.array([-0.05, 0.22, -0.97]))
        Lwarm = norm(np.array([-0.35, -0.55, 0.60]))
        Lrim = norm(np.array([0.0, 0.10, -0.995]))
        c_key = np.array([0.64, 0.74, 0.86]) * 1.25
        c_tv = np.array([0.25, 0.45, 0.75]) * 0.45
        c_warm = np.array([0.42, 0.27, 0.15]) * 0.42
        c_rim = np.array([0.34, 0.46, 0.64]) * 0.55
        c_amb = np.array([0.040, 0.045, 0.052])

        def wrap(nl, w, p):
            return np.clip((nl + w) / (1.0 + w), 0.0, 1.0) ** p

        lin = (
            c_amb[None, :]
            + wrap(all_nrm @ Lkey, 0.20, 1.0)[:, None] * c_key[None, :]
            + wrap(all_nrm @ Ltv, 0.35, 1.0)[:, None] * c_tv[None, :]
            + wrap(all_nrm @ Lwarm, 0.6, 1.0)[:, None] * c_warm[None, :]
            + wrap(all_nrm @ Lrim, 0.2, 2.0)[:, None] * c_rim[None, :]
        )
        sheen = smoothstep(0.25, 0.95, all_nrm[:, 1]) ** 1.5
        lin += sheen[:, None] * np.array([0.13, 0.15, 0.18])[None, :]
        # rim sheen on the shoulder / grip edges
        rim_hi = smoothstep(0.1, 0.9, -all_nrm[:, 2]) ** 2
        lin += rim_hi[:, None] * np.array([0.10, 0.13, 0.18])[None, :]

        ao = np.ones(n)
        ao *= 1.0 - 0.42 * smoothstep(0.05, 0.85, -all_nrm[:, 1])
        # grip/body creases
        for c, rad in _grip_occluders():
            d = np.linalg.norm(all_pos - c[None, :], axis=1)
            ao *= 1.0 - 0.36 * np.exp(-((np.maximum(d - rad, 0.0) / 0.013) ** 2))
        # shell split line
        seam = np.exp(-((all_pos[:, 1] - 0.0022) / 0.0016) ** 2)
        ao *= 1.0 - 0.22 * seam
        # recessed face sockets around each control
        controls = [
            (CTRL_STICK_L, 0.0135), (CTRL_STICK_R, 0.0135),
            (CTRL_DPAD, 0.0155), (CTRL_ABXY, 0.032),
            (CTRL_GUIDE, 0.0105), (CTRL_VIEW, 0.0062), (CTRL_MENU, 0.0062),
        ]
        face = all_pos[:, 1] > 0.013
        for c, rad in controls:
            d = np.sqrt((all_pos[:, 0] - c[0]) ** 2 + (all_pos[:, 2] - c[2]) ** 2)
            recess = 1.0 - smoothstep(rad, rad + 0.005, d)
            ao -= 0.20 * recess * face
        ao = np.clip(ao, 0.10, 1.0)
        lin *= ao[:, None]

        tags = np.array(all_tags, dtype=object)
        guide = tags == "guide"
        lin[guide] = np.array([1.38, 1.32, 1.20])
        cap = tags == "stick_cap"
        lin[cap] *= 1.18
        shaft = tags == "stick_shaft"
        lin[shaft] *= 0.90
        bezel = tags == "bezel"
        lin[bezel] *= 0.55
        lin = np.clip(lin, 0.0, 1.6)
        self.color_mean = float(luminance(lin).mean())
        self.lin_colors = lin.astype(np.float32)
        self.colors = {}
        idx = 0
        for (name, pos_w, nrm, uv, tgs) in entries:
            cnt = len(pos_w)
            self.colors[name] = self.lin_colors[idx: idx + cnt]
            idx += cnt

    def node_arrays(self, name):
        md = self.mesh[name]
        pos, tri, uv, tags = md.arrays()
        nrm = compute_normals(pos, tri)
        col = self.colors[name]
        return (
            np.asarray(pos, dtype=np.float32),
            nrm.astype(np.float32),
            np.asarray(uv, dtype=np.float32),
            np.asarray(col, dtype=np.float32),
            np.asarray(tri, dtype=np.uint32).reshape(-1),
        )


def build_controller_glb(rig: ControllerRig, out_path: Path, ctrl_img):
    gb = GlbBuilder("GTA-Sixty procedural Xbox Series X|S controller")
    tex = gb.add_png_texture(ctrl_img, "xbox_controller_atlas_512")
    mat = gb.add_unlit_material("xbox_controller_plastic", tex, double_sided=False)
    child_ids = []
    for name in CTRL_NODE_ORDER:
        arr = rig.node_arrays(name)
        mesh_id = gb.add_mesh(name + "_Mesh", *arr, material_index=mat)
        t = rig.translation[name]
        node_id = gb.add_node(name, mesh=mesh_id, translation=t)
        child_ids.append(node_id)
    root = gb.add_node("ControllerRoot", children=child_ids)
    total = gb.write(out_path, root)
    verts = sum(len(rig.mesh[n].arrays()[0]) for n in CTRL_NODE_ORDER)
    tris = sum(len(rig.mesh[n].arrays()[1]) for n in CTRL_NODE_ORDER)
    tree = {"ControllerRoot": list(CTRL_NODE_ORDER)}
    return {
        "path": out_path,
        "size": total,
        "verts": verts,
        "tris": tris,
        "node_names": [n["name"] for n in gb.nodes],
        "tree": tree,
        "glb": gb,
    }


# ---------------------------------------------------------------------------
# VALIDATION (re-parses the written GLBs, not the in-memory data)
# ---------------------------------------------------------------------------

HAND_EXPECTED_TREE = {
    "HandRoot": ["WRIST"],
    "WRIST": ["FOREARM", "PALM", "THUMB_METACARPAL", "INDEX_METACARPAL",
              "MIDDLE_METACARPAL", "RING_METACARPAL", "LITTLE_METACARPAL"],
}
for _fing in ("THUMB", "INDEX", "MIDDLE", "RING", "LITTLE"):
    if _fing == "THUMB":
        HAND_EXPECTED_TREE["THUMB_METACARPAL"] = ["THUMB_PROXIMAL"]
        HAND_EXPECTED_TREE["THUMB_PROXIMAL"] = ["THUMB_DISTAL"]
        HAND_EXPECTED_TREE["THUMB_DISTAL"] = ["THUMB_TIP"]
        HAND_EXPECTED_TREE["THUMB_TIP"] = []
    else:
        HAND_EXPECTED_TREE[f"{_fing}_METACARPAL"] = [f"{_fing}_PROXIMAL"]
        HAND_EXPECTED_TREE[f"{_fing}_PROXIMAL"] = [f"{_fing}_INTERMEDIATE"]
        HAND_EXPECTED_TREE[f"{_fing}_INTERMEDIATE"] = [f"{_fing}_DISTAL"]
        HAND_EXPECTED_TREE[f"{_fing}_DISTAL"] = [f"{_fing}_TIP"]
        HAND_EXPECTED_TREE[f"{_fing}_TIP"] = []
HAND_EXPECTED_TREE["FOREARM"] = []
HAND_EXPECTED_TREE["PALM"] = []

HAND_MESH_NODES = {"FOREARM", "PALM", "THUMB_METACARPAL"}
for _fing in ("INDEX", "MIDDLE", "RING", "LITTLE"):
    for _s in ("PROXIMAL", "INTERMEDIATE", "DISTAL"):
        HAND_MESH_NODES.add(f"{_fing}_{_s}")
for _s in ("PROXIMAL", "DISTAL"):
    HAND_MESH_NODES.add(f"THUMB_{_s}")

CTRL_EXPECTED_TREE = {"ControllerRoot": list(CTRL_NODE_ORDER)}
for _n in CTRL_NODE_ORDER:
    CTRL_EXPECTED_TREE[_n] = []


def load_glb_raw(path: Path):
    raw = path.read_bytes()
    magic, ver, length = struct.unpack_from("<III", raw, 0)
    assert magic == 0x46546C67, "bad GLB magic"
    assert ver == 2, "bad GLB version"
    assert length == len(raw), "GLB length mismatch"
    jlen, jtype = struct.unpack_from("<II", raw, 12)
    assert jtype == 0x4E4F534A, "bad JSON chunk"
    assert jlen % 4 == 0, "JSON chunk not 4-byte aligned"
    gltf = json.loads(raw[20: 20 + jlen].decode("utf-8"))
    blen, btype = struct.unpack_from("<II", raw, 20 + jlen)
    assert btype == 0x004E4942, "bad BIN chunk"
    assert blen % 4 == 0, "BIN chunk not 4-byte aligned"
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

    return gltf, read_acc, bin_buf


def _quat_mat(q):
    x, y, z, w = q
    return np.array([
        [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
        [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
        [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
    ], dtype=np.float64)


def node_matrix(n):
    M = np.eye(4)
    if "translation" in n:
        M[:3, 3] = n["translation"]
    R = np.eye(3)
    if "rotation" in n:
        R = _quat_mat(n["rotation"])
    S = np.eye(3)
    if "scale" in n:
        S = np.diag(n["scale"])
    M[:3, :3] = R @ S
    return M


def walk_nodes(gltf, root_index):
    """Yield (node_index, name, world_matrix, parent_name) in pre-order."""
    out = []

    def rec(i, M, parent):
        n = gltf["nodes"][i]
        W = M @ node_matrix(n)
        out.append((i, n["name"], W, parent))
        for c in n.get("children", []):
            rec(c, W, n["name"])

    rec(root_index, np.eye(4), None)
    return out


def format_tree(gltf, root_index):
    lines = []
    nodes = gltf["nodes"]
    depth_of = {}

    def rec(i, depth):
        n = nodes[i]
        lines.append("  " * depth + n["name"])
        depth_of[n["name"]] = depth
        for c in n.get("children", []):
            rec(c, depth + 1)

    rec(root_index, 0)
    return lines


def preorder_names(gltf, root_index):
    names = []

    def rec(i):
        names.append(gltf["nodes"][i]["name"])
        for c in gltf["nodes"][i].get("children", []):
            rec(c)

    rec(root_index)
    return names


def validate_glb(path: Path, kind: str, hand_lengths=None):
    """Re-parse a written GLB and assert every hard requirement we can check."""
    gltf, read_acc, bin_buf = load_glb_raw(path)
    assert "KHR_materials_unlit" in gltf.get("extensionsUsed", []), "missing KHR_materials_unlit"
    scene = gltf["scenes"][gltf.get("scene", 0)]
    assert len(scene["nodes"]) == 1, f"{path.name}: expected exactly one root node"
    root_index = scene["nodes"][0]
    expected_tree = HAND_EXPECTED_TREE if kind == "hand" else CTRL_EXPECTED_TREE

    # node names unique + exact hierarchy
    names = [n["name"] for n in gltf["nodes"]]
    assert len(names) == len(set(names)), f"{path.name}: duplicate node names"
    assert set(names) == set(expected_tree), (
        f"{path.name}: node set mismatch: extra={set(names) - set(expected_tree)} "
        f"missing={set(expected_tree) - set(names)}"
    )
    for i, n in enumerate(gltf["nodes"]):
        kids = [gltf["nodes"][c]["name"] for c in n.get("children", [])]
        assert kids == expected_tree[n["name"]], (
            f"{path.name}: children of {n['name']} = {kids}, expected {expected_tree[n['name']]}"
        )
    root_name = gltf["nodes"][root_index]["name"]
    assert root_name == ("HandRoot" if kind == "hand" else "ControllerRoot")
    for n in gltf["nodes"]:
        if n["name"] == "HandRoot":
            assert "mesh" not in n, "HandRoot must not have a mesh"
        if kind == "hand" and n["name"] == "WRIST":
            assert "mesh" not in n, "WRIST must not have a mesh"
        if kind == "controller" and n["name"] == "Body":
            assert "translation" not in n and "rotation" not in n and "scale" not in n, \
                "Body must be identity local transform"

    # materials
    assert len(gltf["materials"]) >= 1
    for mat in gltf["materials"]:
        ext = mat.get("extensions", {})
        assert "KHR_materials_unlit" in ext, f"{path.name}: material not unlit"
        pbr = mat.get("pbrMetallicRoughness", {})
        assert abs(pbr.get("metallicFactor", -1.0) - 0.0) < 1e-9, "metallicFactor must be 0"
        assert abs(pbr.get("roughnessFactor", -1.0) - 0.9) < 1e-9, "roughnessFactor must be 0.9"
        assert "baseColorTexture" in pbr, "material must use a baseColorTexture"
    assert gltf.get("images"), "missing images"
    for im in gltf["images"]:
        assert im.get("mimeType") == "image/png"
        bv = gltf["bufferViews"][im["bufferView"]]
        off = bv.get("byteOffset", 0)
        raw = bin_buf[off: off + bv["byteLength"]]
        pil = Image.open(io.BytesIO(raw))
        assert pil.size == (512, 512), f"{path.name}: expected 512x512 texture"

    # meshes / accessors
    total_tris = 0
    total_verts = 0
    mesh_nodes = set()
    max_col = 0.0
    min_col = 1e9
    for n in gltf["nodes"]:
        if "mesh" not in n:
            continue
        mesh_nodes.add(n["name"])
        mesh = gltf["meshes"][n["mesh"]]
        assert len(mesh["primitives"]) == 1
        tri_count = 0
        vert_count = 0
        for prim in mesh["primitives"]:
            attrs = prim["attributes"]
            for a in ("POSITION", "NORMAL", "TEXCOORD_0", "COLOR_0"):
                assert a in attrs, f"{n['name']}: primitive missing {a}"
            pos = read_acc(attrs["POSITION"]).astype(np.float64)
            nrm = read_acc(attrs["NORMAL"]).astype(np.float64)
            uv = read_acc(attrs["TEXCOORD_0"]).astype(np.float64)
            col = read_acc(attrs["COLOR_0"]).astype(np.float64)
            idx = read_acc(prim["indices"]).reshape(-1)
            assert np.all(np.isfinite(pos)), f"{n['name']}: NaN POSITION"
            assert np.all(np.isfinite(nrm)), f"{n['name']}: NaN NORMAL"
            assert np.all(np.isfinite(uv)), f"{n['name']}: NaN TEXCOORD_0"
            assert np.all(np.isfinite(col)), f"{n['name']}: NaN COLOR_0"
            assert idx.min() >= 0 and idx.max() < len(pos), f"{n['name']}: index out of range"
            assert len(idx) % 3 == 0
            assert col.min() >= -1e-6 and col.max() <= 1.6 + 1e-5, (
                f"{n['name']}: COLOR_0 out of [0,1.6] ({col.min():.4f}..{col.max():.4f})"
            )
            max_col = max(max_col, float(col.max()))
            min_col = min(min_col, float(col.min()))
            acc_pos = gltf["accessors"][attrs["POSITION"]]
            assert "min" in acc_pos and "max" in acc_pos, f"{n['name']}: POSITION min/max missing"
            assert np.allclose(acc_pos["min"], pos.min(axis=0), atol=1e-6)
            assert np.allclose(acc_pos["max"], pos.max(axis=0), atol=1e-6)
            # local-frame checks for phalanges
            if kind == "hand" and hand_lengths and n["name"] in hand_lengths:
                L = hand_lengths[n["name"]]
                assert abs(pos[:, 1].min() - (-0.004)) < 2e-4, (
                    f"{n['name']}: mesh should start at local y=-0.004, got {pos[:,1].min():.5f}"
                )
                assert abs(pos[:, 1].max() - (L + 0.006)) < 2e-4, (
                    f"{n['name']}: mesh should end at local y=L+0.006, got {pos[:,1].max():.5f}"
                )
            vert_count += len(pos)
            tri_count += len(idx) // 3
        total_tris += tri_count
        total_verts += vert_count
        assert tri_count <= (420 if n["name"] == "PALM" else
                            160 if n["name"] == "FOREARM" else 100 if kind == "hand" else 10000), \
            f"{n['name']}: local tri budget exceeded ({tri_count})"

    budget = HAND_TRI_BUDGET if kind == "hand" else CONTROLLER_TRI_BUDGET
    assert total_tris <= budget, f"{path.name}: {total_tris} > budget {budget}"
    expected_mesh_nodes = HAND_MESH_NODES if kind == "hand" else set(CTRL_NODE_ORDER)
    assert mesh_nodes == expected_mesh_nodes, (
        f"{path.name}: mesh nodes {sorted(mesh_nodes)} != {sorted(expected_mesh_nodes)}"
    )

    # bbox in world/rest coordinates
    world = walk_nodes(gltf, root_index)
    pts = []
    for i, name, W, parent in world:
        n = gltf["nodes"][i]
        if "mesh" not in n:
            continue
        for prim in gltf["meshes"][n["mesh"]]["primitives"]:
            pos = read_acc(prim["attributes"]["POSITION"]).astype(np.float64)
            ph = pos @ W[:3, :3].T + W[:3, 3]
            pts.append(ph)
    allp = np.concatenate(pts, axis=0)
    bbox = (allp.min(axis=0), allp.max(axis=0))

    return {
        "path": path,
        "size": len(path.read_bytes()),
        "verts": total_verts,
        "tris": total_tris,
        "bbox": bbox,
        "color_range": (min_col, max_col),
        "gltf": gltf,
        "root_index": root_index,
        "preorder": preorder_names(gltf, root_index),
        "tree_lines": format_tree(gltf, root_index),
    }


def print_tree(result):
    print(f"  node tree ({result['path'].name}):")
    for line in result["tree_lines"]:
        print("    " + line)
    print("  tree-order names: " + ", ".join(result["preorder"]))


def main():
    t0 = time.perf_counter()
    print("=" * 78)
    print("GTA-Sixty procedural hands + Xbox controller -> glTF 2.0 binary")
    print("=" * 78)
    print("[1/5] Generating procedural textures...")
    skin_img, skin_lin = make_skin_texture(512)
    ctrl_img = make_controller_texture(512)

    print("[2/5] Building hand rig + meshes...")
    rig = HandRig()
    rig.build_meshes(skin_lin)
    print(f"      hand bake: mean COLOR_0 luminance = {rig.color_mean:.3f}, "
          f"mean textured luminance = {rig.skin_mean:.3f}")

    print("[3/5] Writing gta_hand_right.glb / gta_hand_left.glb...")
    hand_lengths = {n: j["length"] for n, j in rig.joints.items() if j["kind"] == "phalanx"}
    res_r = build_hand_glb(rig, False, OUT_RIGHT, skin_img)
    res_l = build_hand_glb(rig, True, OUT_LEFT, skin_img)

    print("[4/5] Building Xbox controller...")
    ctrl = ControllerRig()
    ctrl.bake()
    print(f"      controller bake: mean COLOR_0 luminance = {ctrl.color_mean:.3f}")
    res_c = build_controller_glb(ctrl, OUT_CONTROLLER, ctrl_img)

    print("[5/5] Re-parsing + validating written GLBs...")
    val_r = validate_glb(OUT_RIGHT, "hand", hand_lengths)
    val_l = validate_glb(OUT_LEFT, "hand", hand_lengths)
    val_c = validate_glb(OUT_CONTROLLER, "controller")

    rows = [
        ("gta_hand_right.glb", val_r),
        ("gta_hand_left.glb", val_l),
        ("xbox_controller.glb", val_c),
    ]
    print("\n" + "=" * 78)
    print("SUMMARY")
    print("=" * 78)
    print(f"{'asset':<24} {'size':>10} {'verts':>7} {'tris':>7}  {'COLOR_0 range':>18}")
    for name, r in rows:
        print(f"{name:<24} {r['size']/1024.0:9.1f}K {r['verts']:7d} {r['tris']:7d}  "
              f"{r['color_range'][0]:7.3f}..{r['color_range'][1]:<7.3f}")
        bmin, bmax = r["bbox"]
        print(f"    bbox min=({bmin[0]:+.4f},{bmin[1]:+.4f},{bmin[2]:+.4f})  "
              f"max=({bmax[0]:+.4f},{bmax[1]:+.4f},{bmax[2]:+.4f})")
    print("\nNode trees (target names for getNodeIndex):")
    for name, r in rows:
        print("-" * 78)
        print_tree(r)
    print("\nAll assertions passed.")
    print(f"Total generation + validation time: {time.perf_counter() - t0:.2f}s")


if __name__ == "__main__":
    main()
