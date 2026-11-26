#!/usr/bin/env python3
"""
Procedural glTF 2.0 (.glb) generator for Jimmy De Santa's bedroom (GTA V)
for GTA-Sixty Spatial on Meta Quest 3.

Coordinate system (right-handed, Y-up, +Z forward toward TV wall):
  - Origin (0, 0, 0): Floor directly beneath the seated viewer.
  - Seated eye height: y = 1.18 m
  - TV screen center:  (0.0, 1.18, 2.075)  [16:9 width = 1.66 m, height = 0.93375 m]
  - Room bounds:       x in [-2.35, +2.35], y in [0.0, 2.75], z in [-1.90, +2.30]

Uses KHR_materials_unlit with baked directional + ambient + room-corner shading
so the room renders consistently and cheaply at 120 Hz in Meta Spatial SDK.
"""

import io
import json
import math
import os
import struct
import numpy as np
from PIL import Image, ImageDraw, ImageFont

# Shared constants (must match SpatialMainActivity.kt)
ROOM_EYE_HEIGHT_M = 1.18
TV_SCREEN_W = 1.66
TV_SCREEN_H = TV_SCREEN_W / (16.0 / 9.0)  # 0.93375 m
TV_CENTER_Y = 1.18
TV_SCREEN_Z = 2.075
TV_BACK_Z = 2.105

ROOM_W = 4.70   # x: -2.35 .. +2.35
ROOM_H = 2.75   # y:  0.00 .. +2.75
ROOM_Z_BACK = -1.90
ROOM_Z_FRONT = 2.30


def _shade(color_rgb, normal, pos):
    """Simple warm bedroom + sunset window + ceiling light baking."""
    nx, ny, nz = normal
    # Key warm ceiling light slightly in front of viewer
    lx, ly, lz = 0.15, 0.88, 0.45
    ndotl = max(0.0, nx * lx + ny * ly + nz * lz)
    # Warm sunset window fill from +X side
    wx, wy, wz = 0.92, 0.25, 0.28
    win_fill = max(0.0, nx * wx + ny * wy + nz * wz) * 0.22
    # Subtle cool TV bounce from +Z toward -Z
    tv_fill = max(0.0, -nz) * 0.08
    # Ambient + height gradient (slightly darker near floor corners)
    y_norm = max(0.0, min(1.0, pos[1] / ROOM_H))
    edge_x = abs(pos[0]) / (ROOM_W * 0.5)
    corner_ao = 1.0 - 0.14 * (edge_x * edge_x) * (1.0 - 0.4 * y_norm)
    intensity = (0.42 + 0.48 * ndotl + win_fill + tv_fill) * corner_ao
    r = min(1.0, max(0.0, color_rgb[0] * intensity + win_fill * 0.18))
    g = min(1.0, max(0.0, color_rgb[1] * intensity + win_fill * 0.09))
    b = min(1.0, max(0.0, color_rgb[2] * intensity + tv_fill * 0.12))
    return (r, g, b)


class MeshBuilder:
    """Accumulates triangles grouped by material name."""

    def __init__(self):
        self.primitives = {}  # mat_name -> dict of lists

    def _bucket(self, mat):
        if mat not in self.primitives:
            self.primitives[mat] = {"pos": [], "uv": [], "col": [], "idx": []}
        return self.primitives[mat]

    def add_quad(self, p0, p1, p2, p3, mat, color=(1.0, 1.0, 1.0),
                 uvs=((0, 0), (1, 0), (1, 1), (0, 1)), unlit_emissive=False):
        """Add a counter-clockwise quad (p0, p1, p2, p3)."""
        b = self._bucket(mat)
        # Compute face normal
        ux, uy, uz = p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2]
        vx, vy, vz = p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2]
        nx = uy * vz - uz * vy
        ny = uz * vx - ux * vz
        nz = ux * vy - uy * vx
        nlen = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
        normal = (nx / nlen, ny / nlen, nz / nlen)

        base = len(b["pos"])
        for p, uv in zip((p0, p1, p2, p3), uvs):
            b["pos"].append(p)
            b["uv"].append(uv)
            c = color if unlit_emissive else _shade(color, normal, p)
            b["col"].append(c)
        b["idx"].extend([base, base + 1, base + 2, base, base + 2, base + 3])

    def add_box(self, x0, y0, z0, x1, y1, z1, mat, color=(1.0, 1.0, 1.0),
                skip_faces=(), unlit_emissive=False, uv_scale=None):
        """Axis-aligned box from (x0,y0,z0) to (x1,y1,z1), outward normals."""
        if x0 > x1:
            x0, x1 = x1, x0
        if y0 > y1:
            y0, y1 = y1, y0
        if z0 > z1:
            z0, z1 = z1, z0

        def _uv(w, h):
            if uv_scale is None:
                return ((0, 0), (1, 0), (1, 1), (0, 1))
            return ((0, 0), (w * uv_scale, 0), (w * uv_scale, h * uv_scale), (0, h * uv_scale))

        w, h, d = x1 - x0, y1 - y0, z1 - z0
        # +Z face
        if "+z" not in skip_faces:
            self.add_quad((x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1),
                          mat, color, _uv(w, h), unlit_emissive)
        # -Z face
        if "-z" not in skip_faces:
            self.add_quad((x1, y0, z0), (x0, y0, z0), (x0, y1, z0), (x1, y1, z0),
                          mat, color, _uv(w, h), unlit_emissive)
        # +X face
        if "+x" not in skip_faces:
            self.add_quad((x1, y0, z1), (x1, y0, z0), (x1, y1, z0), (x1, y1, z1),
                          mat, color, _uv(d, h), unlit_emissive)
        # -X face
        if "-x" not in skip_faces:
            self.add_quad((x0, y0, z0), (x0, y0, z1), (x0, y1, z1), (x0, y1, z0),
                          mat, color, _uv(d, h), unlit_emissive)
        # +Y face
        if "+y" not in skip_faces:
            self.add_quad((x0, y1, z1), (x1, y1, z1), (x1, y1, z0), (x0, y1, z0),
                          mat, color, _uv(w, d), unlit_emissive)
        # -Y face
        if "-y" not in skip_faces:
            self.add_quad((x0, y0, z0), (x1, y0, z0), (x1, y0, z1), (x0, y0, z1),
                          mat, color, _uv(w, d), unlit_emissive)

    def add_cylinder(self, cx, y0, cz, radius, height, mat, color=(1.0, 1.0, 1.0),
                     segments=12, unlit_emissive=False):
        """Vertical cylinder along Y."""
        y1 = y0 + height
        for i in range(segments):
            a0 = 2.0 * math.pi * i / segments
            a1 = 2.0 * math.pi * (i + 1) / segments
            x0, z0 = cx + radius * math.cos(a0), cz + radius * math.sin(a0)
            x1, z1 = cx + radius * math.cos(a1), cz + radius * math.sin(a1)
            self.add_quad((x0, y0, z0), (x1, y0, z1), (x1, y1, z1), (x0, y1, z0),
                          mat, color, unlit_emissive=unlit_emissive)
        # Top cap
        b = self._bucket(mat)
        center_idx = len(b["pos"])
        b["pos"].append((cx, y1, cz))
        b["uv"].append((0.5, 0.5))
        b["col"].append(color if unlit_emissive else _shade(color, (0, 1, 0), (cx, y1, cz)))
        for i in range(segments):
            a0 = 2.0 * math.pi * i / segments
            x0, z0 = cx + radius * math.cos(a0), cz + radius * math.sin(a0)
            b["pos"].append((x0, y1, z0))
            b["uv"].append((0.5, 0.5))
            b["col"].append(color if unlit_emissive else _shade(color, (0, 1, 0), (x0, y1, z0)))
        for i in range(segments):
            i0 = center_idx + 1 + i
            i1 = center_idx + 1 + ((i + 1) % segments)
            b["idx"].extend([center_idx, i1, i0])


def _make_wood_floor_tex():
    img = Image.new("RGB", (256, 256), (92, 58, 36))
    draw = ImageDraw.Draw(img)
    rng = np.random.default_rng(42)
    plank_h = 32
    for row in range(8):
        y0 = row * plank_h
        offset = (row * 67) % 256
        for p in range(3):
            x0 = (offset + p * 96) % 256
            shade = int(rng.integers(-14, 15))
            col = (max(0, min(255, 96 + shade)),
                   max(0, min(255, 62 + int(shade * 0.7))),
                   max(0, min(255, 38 + int(shade * 0.5))))
            draw.rectangle([x0, y0, x0 + 94, y0 + plank_h - 2], fill=col)
            # Subtle wood grain lines
            for g in range(4):
                gy = y0 + 5 + g * 7
                draw.line([(x0, gy), (x0 + 94, gy)], fill=(max(0, col[0] - 12), max(0, col[1] - 9), max(0, col[2] - 6)), width=1)
        draw.line([(0, y0), (256, y0)], fill=(45, 28, 18), width=2)
    return img


def _make_rug_tex():
    img = Image.new("RGB", (256, 256), (112, 34, 34))
    draw = ImageDraw.Draw(img)
    draw.rectangle([10, 10, 246, 246], outline=(195, 165, 110), width=6)
    draw.rectangle([22, 22, 234, 234], outline=(42, 46, 56), width=4)
    for x in range(36, 220, 32):
        for y in range(36, 220, 32):
            draw.polygon([(x + 16, y), (x + 32, y + 16), (x + 16, y + 32), (x, y + 16)],
                         fill=(82, 26, 28), outline=(165, 130, 82))
    return img


def _make_poster_rs7():
    """Righteous Slaughter 7 poster (Jimmy's favorite game in GTA V!)."""
    img = Image.new("RGB", (256, 384), (18, 22, 30))
    draw = ImageDraw.Draw(img)
    # Dramatic orange/red explosion sky
    for y in range(240):
        t = y / 240.0
        r = int(40 + 195 * (1.0 - t))
        g = int(20 + 95 * (1.0 - t) * (1.0 - t))
        b = int(25 + 20 * t)
        draw.line([(0, y), (256, y)], fill=(r, g, b))
    # Soldier silhouette
    draw.polygon([(78, 340), (96, 175), (128, 150), (160, 175), (178, 340)], fill=(15, 16, 20))
    draw.ellipse([108, 112, 148, 154], fill=(15, 16, 20))
    # Rifle silhouette
    draw.rectangle([140, 190, 225, 204], fill=(22, 24, 28))
    # Banner
    draw.rectangle([0, 255, 256, 335], fill=(14, 16, 22))
    draw.rectangle([8, 262, 248, 328], outline=(235, 130, 30), width=3)
    draw.text((22, 272), "RIGHTEOUS", fill=(245, 245, 245))
    draw.text((22, 294), "SLAUGHTER 7", fill=(245, 120, 25))
    draw.text((22, 348), "BLOOD OPS - LS", fill=(180, 185, 195))
    return img


def _make_poster_vinewood():
    """Meltdown / Vinewood movie poster."""
    img = Image.new("RGB", (256, 384), (20, 14, 42))
    draw = ImageDraw.Draw(img)
    for y in range(280):
        t = y / 280.0
        r = int(235 * (1 - t) + 35 * t)
        g = int(95 * (1 - t) + 18 * t)
        b = int(145 * (1 - t) + 75 * t)
        draw.line([(0, y), (256, y)], fill=(r, g, b))
    # Sunset circle
    draw.ellipse([68, 60, 188, 180], fill=(255, 210, 90))
    # Palm silhouettes
    draw.line([(45, 280), (62, 130)], fill=(18, 12, 28), width=8)
    draw.line([(215, 280), (195, 145)], fill=(18, 12, 28), width=8)
    # City skyline
    for bx, bw, bh in [(10, 30, 70), (45, 26, 95), (78, 38, 110), (122, 32, 85), (160, 42, 105), (208, 36, 75)]:
        draw.rectangle([bx, 280 - bh, bx + bw, 280], fill=(20, 15, 32))
    draw.rectangle([0, 280, 256, 384], fill=(16, 12, 28))
    draw.text((38, 302), "MELTDOWN", fill=(255, 215, 95))
    draw.text((26, 330), "A SOLOMON RICHARDS", fill=(210, 200, 225))
    draw.text((54, 348), "PRODUCTION", fill=(210, 200, 225))
    return img


def _make_window_sunset_tex():
    """Rockford Hills / Los Santos golden-hour sunset outside Jimmy's window."""
    img = Image.new("RGB", (256, 256), (40, 30, 60))
    draw = ImageDraw.Draw(img)
    for y in range(256):
        t = y / 255.0
        if t < 0.55:
            u = t / 0.55
            r = int(55 + 195 * u)
            g = int(48 + 110 * u)
            b = int(115 - 30 * u)
        else:
            u = (t - 0.55) / 0.45
            r = int(250 - 165 * u)
            g = int(158 - 115 * u)
            b = int(85 - 50 * u)
        draw.line([(0, y), (256, y)], fill=(r, g, b))
    # Sun glow
    draw.ellipse([135, 92, 195, 152], fill=(255, 232, 155))
    # Distant Vinewood hills
    draw.polygon([(0, 256), (0, 175), (65, 152), (140, 168), (210, 148), (256, 165), (256, 256)],
                 fill=(48, 32, 48))
    # Palm tree silhouettes
    draw.line([(55, 256), (72, 115)], fill=(26, 18, 28), width=7)
    for dx, dy in [(-32, -8), (-24, 14), (28, -10), (32, 12), (4, -24)]:
        draw.line([(72, 115), (72 + dx, 115 + dy)], fill=(26, 18, 28), width=4)
    draw.line([(208, 256), (196, 132)], fill=(26, 18, 28), width=6)
    for dx, dy in [(-26, -6), (-20, 12), (24, -8), (26, 10), (0, -20)]:
        draw.line([(196, 132), (196 + dx, 132 + dy)], fill=(26, 18, 28), width=4)
    # Horizontal window blinds overlay (semi-open)
    for by in range(6, 256, 16):
        draw.rectangle([0, by, 256, by + 3], fill=(215, 200, 180))
    return img


def _make_pc_screen_tex():
    img = Image.new("RGB", (256, 160), (16, 24, 38))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, 256, 22], fill=(28, 42, 66))
    draw.text((10, 5), "LIFEINVADER - JIZZLE", fill=(215, 230, 255))
    draw.rectangle([12, 32, 165, 142], fill=(24, 36, 56), outline=(56, 189, 248), width=2)
    draw.rectangle([175, 32, 244, 142], fill=(30, 26, 48), outline=(167, 139, 250), width=2)
    draw.text((22, 48), "RS7 LOBBY", fill=(52, 211, 153))
    draw.text((22, 72), "PING: 18ms", fill=(203, 213, 225))
    draw.text((22, 96), "STATUS: ONLINE", fill=(250, 204, 21))
    return img


def build_room():
    mb = MeshBuilder()

    hx = ROOM_W * 0.5  # 2.35
    zf = ROOM_Z_FRONT  # 2.30
    zb = ROOM_Z_BACK   # -1.90
    H = ROOM_H         # 2.75

    # Colors
    wall_col = (0.84, 0.78, 0.69)       # Warm Rockford Hills plaster beige
    wall_accent = (0.76, 0.70, 0.61)    # Subtle accent wall behind TV
    trim_col = (0.93, 0.91, 0.87)       # White baseboards & crown molding
    ceil_col = (0.91, 0.89, 0.86)
    dark_wood = (0.24, 0.16, 0.11)
    med_wood = (0.36, 0.24, 0.15)
    black_mat = (0.09, 0.09, 0.11)
    charcoal = (0.16, 0.17, 0.20)

    # ---- 1. FLOOR, CEILING, WALLS ----
    # Floor (+Y normal)
    mb.add_quad((-hx, 0, zb), (-hx, 0, zf), (hx, 0, zf), (hx, 0, zb),
                "wood_floor", (1, 1, 1), uvs=((0, 0), (0, 4), (4, 4), (4, 0)))
    # Area Rug slightly above floor
    mb.add_quad((-1.35, 0.008, -0.75), (-1.35, 0.008, 1.45), (1.35, 0.008, 1.45), (1.35, 0.008, -0.75),
                "rug", (1, 1, 1))
    # Ceiling (-Y normal)
    mb.add_quad((-hx, H, zf), (-hx, H, zb), (hx, H, zb), (hx, H, zf),
                "solid", ceil_col)
    # Front TV Wall (z = zf, facing -Z)
    mb.add_quad((hx, 0, zf), (-hx, 0, zf), (-hx, H, zf), (hx, H, zf),
                "solid", wall_accent)
    # Back Wall (z = zb, facing +Z)
    mb.add_quad((-hx, 0, zb), (hx, 0, zb), (hx, H, zb), (-hx, H, zb),
                "solid", wall_col)
    # Left Wall (x = -hx, facing +X)
    mb.add_quad((-hx, 0, zf), (-hx, 0, zb), (-hx, H, zb), (-hx, H, zf),
                "solid", wall_col)
    # Right Wall (x = +hx, facing -X)
    mb.add_quad((hx, 0, zb), (hx, 0, zf), (hx, H, zf), (hx, H, zb),
                "solid", wall_col)

    # Baseboards & Crown molding
    mb.add_box(-hx, 0, zf - 0.03, hx, 0.11, zf, "solid", trim_col)
    mb.add_box(-hx, 0, zb, hx, 0.11, zb + 0.03, "solid", trim_col)
    mb.add_box(-hx, 0, zb, -hx + 0.03, 0.11, zf, "solid", trim_col)
    mb.add_box(hx - 0.03, 0, zb, hx, 0.11, zf, "solid", trim_col)
    mb.add_box(-hx, H - 0.09, zf - 0.04, hx, H, zf, "solid", trim_col)
    mb.add_box(-hx, H - 0.09, zb, hx, H, zb + 0.04, "solid", trim_col)

    # ---- 2. MEDIA CONSOLE & FLAT-SCREEN TV (FRONT WALL) ----
    # Media console
    mb.add_box(-1.12, 0.0, 1.82, 1.12, 0.56, 2.28, "solid", dark_wood)
    # Console drawer fronts
    for dx in (-0.72, 0.0, 0.72):
        mb.add_box(dx - 0.31, 0.07, 1.805, dx + 0.31, 0.31, 1.825, "solid", med_wood)
        mb.add_box(dx - 0.08, 0.18, 1.795, dx + 0.08, 0.20, 1.808, "solid", (0.72, 0.72, 0.75))
    # Open console shelf recess
    mb.add_box(-1.04, 0.35, 1.815, 1.04, 0.51, 1.84, "solid", (0.08, 0.06, 0.05))

    # Xbox-style console on left of media stand top + glowing green power LED
    mb.add_box(-0.96, 0.56, 1.90, -0.58, 0.64, 2.16, "solid", black_mat)
    mb.add_box(-0.65, 0.59, 1.895, -0.61, 0.615, 1.902, "solid", (0.20, 0.95, 0.35), unlit_emissive=True)

    # Stacked game cases
    for i, col in enumerate([(0.15, 0.58, 0.20), (0.15, 0.42, 0.78), (0.80, 0.22, 0.18)]):
        y = 0.56 + i * 0.022
        mb.add_box(0.62, y, 1.92, 0.80, y + 0.018, 2.12, "solid", col)

    # Jimmy's green glass bong on right side of console
    mb.add_cylinder(0.94, 0.56, 1.96, 0.055, 0.09, "solid", (0.22, 0.72, 0.42))
    mb.add_cylinder(0.94, 0.65, 1.96, 0.024, 0.22, "solid", (0.28, 0.82, 0.48))

    # Open pizza box on left edge of rug / floor near console
    mb.add_box(-1.05, 0.01, 1.15, -0.68, 0.055, 1.52, "solid", (0.86, 0.76, 0.60))
    mb.add_box(-1.02, 0.056, 1.18, -0.71, 0.065, 1.49, "solid", (0.88, 0.62, 0.22))  # Pizza crust/cheese

    # Sprunk green soda cans
    mb.add_cylinder(-0.48, 0.56, 1.92, 0.032, 0.115, "solid", (0.18, 0.78, 0.28))
    mb.add_cylinder(-0.39, 0.56, 1.95, 0.032, 0.115, "solid", (0.82, 0.16, 0.16))  # eCola can

    # Flanking bookshelf speakers
    for sx in (-1.34, 1.34):
        mb.add_box(sx - 0.13, 0.0, 1.92, sx + 0.13, 0.82, 2.22, "solid", charcoal)
        mb.add_box(sx - 0.10, 0.10, 1.91, sx + 0.10, 0.74, 1.925, "solid", black_mat)

    # TV Stand Base & Neck
    mb.add_box(-0.28, 0.56, 2.02, 0.28, 0.59, 2.22, "solid", charcoal)
    mb.add_box(-0.07, 0.59, 2.11, 0.07, 0.72, 2.17, "solid", charcoal)

    # Flat-Screen TV Bezel (framed tightly around exact 16:9 screen opening)
    sw = TV_SCREEN_W * 0.5   # 0.83
    sh = TV_SCREEN_H * 0.5   # 0.466875
    cy = TV_CENTER_Y         # 1.18
    bezel = 0.038
    z_front = TV_SCREEN_Z + 0.015  # 2.090 (just behind the OpenXR panel at 2.075)
    z_back = TV_BACK_Z + 0.045     # 2.150

    # Solid dark TV backplate (behind the OpenXR panel)
    mb.add_box(-sw - bezel, cy - sh - bezel, TV_BACK_Z, sw + bezel, cy + sh + bezel, z_back,
               "solid", black_mat)
    # Top bezel bar
    mb.add_box(-sw - bezel, cy + sh, z_front, sw + bezel, cy + sh + bezel, TV_BACK_Z, "solid", black_mat)
    # Bottom bezel bar (slightly thicker chin with tiny red/white power LED)
    mb.add_box(-sw - bezel, cy - sh - bezel - 0.015, z_front, sw + bezel, cy - sh, TV_BACK_Z, "solid", black_mat)
    mb.add_box(sw - 0.08, cy - sh - 0.032, z_front - 0.003, sw - 0.05, cy - sh - 0.022, z_front,
               "solid", (0.2, 0.85, 1.0), unlit_emissive=True)
    # Left & Right bezel bars
    mb.add_box(-sw - bezel, cy - sh, z_front, -sw, cy + sh, TV_BACK_Z, "solid", black_mat)
    mb.add_box(sw, cy - sh, z_front, sw + bezel, cy + sh, TV_BACK_Z, "solid", black_mat)

    # ---- 3. POSTERS & WALL DECOR (FRONT WALL) ----
    # Righteous Slaughter 7 poster (left of TV)
    mb.add_quad((-1.32, 1.28, zf - 0.012), (-2.02, 1.28, zf - 0.012),
                (-2.02, 2.26, zf - 0.012), (-1.32, 2.26, zf - 0.012), "poster_rs7", (1, 1, 1))
    # Meltdown / Vinewood poster (right of TV)
    mb.add_quad((2.02, 1.28, zf - 0.012), (1.32, 1.28, zf - 0.012),
                (1.32, 2.26, zf - 0.012), (2.02, 2.26, zf - 0.012), "poster_vinewood", (1, 1, 1))
    # Skateboard deck mounted above TV
    mb.add_box(-0.42, 1.98, zf - 0.04, 0.42, 2.16, zf - 0.015, "solid", (0.85, 0.35, 0.14))
    mb.add_box(-0.34, 2.02, zf - 0.045, 0.34, 2.12, zf - 0.038, "solid", (0.14, 0.14, 0.18))

    # ---- 4. ROCKFORD HILLS WINDOW (RIGHT WALL, x = +hx) ----
    # Window frame
    mb.add_box(hx - 0.06, 0.85, -0.35, hx, 2.25, 1.25, "solid", trim_col)
    # Sunset view with palm trees & blinds (emissive warm glow)
    mb.add_quad((hx - 0.065, 0.92, -0.28), (hx - 0.065, 0.92, 1.18),
                (hx - 0.065, 2.18, 1.18), (hx - 0.065, 2.18, -0.28),
                "window_sunset", (1, 1, 1), unlit_emissive=True)

    # ---- 5. JIMMY'S BED (BACK-RIGHT CORNER) ----
    # Bed frame & dark wood headboard against right/back corner
    mb.add_box(0.85, 0.0, -1.78, 2.26, 0.34, -0.30, "solid", dark_wood)
    mb.add_box(0.85, 0.34, -1.84, 2.26, 1.18, -1.74, "solid", dark_wood)
    # Mattress & rumpled navy comforter
    mb.add_box(0.90, 0.34, -1.72, 2.22, 0.56, -0.34, "solid", (0.88, 0.88, 0.90))
    mb.add_box(0.88, 0.40, -1.35, 2.24, 0.60, -0.32, "solid", (0.18, 0.25, 0.40))
    # Pillows
    mb.add_box(0.96, 0.56, -1.68, 1.50, 0.70, -1.38, "solid", (0.90, 0.90, 0.92))
    mb.add_box(1.58, 0.56, -1.68, 2.14, 0.70, -1.38, "solid", (0.86, 0.87, 0.90))
    # Nightstand & warm lamp
    mb.add_box(0.28, 0.0, -1.82, 0.78, 0.54, -1.36, "solid", dark_wood)
    mb.add_cylinder(0.53, 0.54, -1.59, 0.04, 0.20, "solid", charcoal)
    mb.add_cylinder(0.53, 0.74, -1.59, 0.13, 0.18, "solid", (1.0, 0.86, 0.58), unlit_emissive=True)

    # ---- 6. GAMER DESK & PC SETUP (LEFT WALL, x = -hx) ----
    # Desk top & legs
    mb.add_box(-2.30, 0.72, -0.55, -1.55, 0.77, 0.95, "solid", med_wood)
    mb.add_box(-2.28, 0.0, -0.52, -1.58, 0.72, -0.45, "solid", dark_wood)
    mb.add_box(-2.28, 0.0, 0.88, -1.58, 0.72, 0.95, "solid", dark_wood)
    # PC Monitor + glowing Lifeinvader/RS7 screen
    mb.add_box(-2.18, 0.77, 0.15, -2.06, 0.88, 0.25, "solid", black_mat)
    mb.add_box(-2.18, 0.86, -0.15, -2.12, 1.24, 0.55, "solid", black_mat)
    mb.add_quad((-2.115, 0.89, 0.52), (-2.115, 0.89, -0.12),
                (-2.115, 1.21, -0.12), (-2.115, 1.21, 0.52),
                "pc_screen", (1, 1, 1), unlit_emissive=True)
    # PC Tower with blue LED strip
    mb.add_box(-2.22, 0.0, 0.58, -1.78, 0.48, 0.84, "solid", black_mat)
    mb.add_box(-1.775, 0.08, 0.62, -1.77, 0.42, 0.65, "solid", (0.15, 0.72, 1.0), unlit_emissive=True)
    # Keyboard & Mousepad
    mb.add_box(-1.88, 0.77, -0.02, -1.68, 0.79, 0.42, "solid", charcoal)
    # Desk Chair
    mb.add_box(-1.45, 0.42, -0.05, -0.98, 0.49, 0.45, "solid", (0.18, 0.18, 0.22))
    mb.add_box(-1.05, 0.49, -0.03, -0.98, 1.02, 0.43, "solid", (0.18, 0.18, 0.22))
    mb.add_cylinder(-1.22, 0.0, 0.20, 0.04, 0.42, "solid", charcoal)

    # ---- 7. BEDROOM DOOR & COUCH BEHIND VIEWER (BACK WALL) ----
    # Door on back-left
    mb.add_box(-1.85, 0.0, zb + 0.01, -0.95, 2.15, zb + 0.06, "solid", trim_col)
    mb.add_box(-1.78, 0.04, zb + 0.04, -1.02, 2.08, zb + 0.08, "solid", med_wood)
    # Low gaming couch behind viewer (z in [-1.15, -0.45]) so it never clips viewer at z=0
    mb.add_box(-0.85, 0.0, -1.05, 0.65, 0.42, -0.45, "solid", (0.28, 0.24, 0.22))
    mb.add_box(-0.85, 0.42, -1.05, 0.65, 0.86, -0.88, "solid", (0.26, 0.22, 0.20))

    # ---- 8. CEILING FAN & WARM DOME LIGHT ----
    mb.add_cylinder(0.0, H - 0.24, 0.25, 0.05, 0.24, "solid", charcoal)
    mb.add_cylinder(0.0, H - 0.34, 0.25, 0.15, 0.10, "solid", (1.0, 0.94, 0.80), unlit_emissive=True)
    # 4 Fan blades
    mb.add_box(-0.62, H - 0.23, 0.18, 0.62, H - 0.21, 0.32, "solid", dark_wood)
    mb.add_box(-0.07, H - 0.23, -0.37, 0.07, H - 0.21, 0.87, "solid", dark_wood)

    return mb


def export_glb(mb: MeshBuilder, out_path: str):
    textures_pil = {
        "wood_floor": _make_wood_floor_tex(),
        "rug": _make_rug_tex(),
        "poster_rs7": _make_poster_rs7(),
        "poster_vinewood": _make_poster_vinewood(),
        "window_sunset": _make_window_sunset_tex(),
        "pc_screen": _make_pc_screen_tex(),
    }

    bin_chunks = bytearray()

    def append_bytes(data: bytes) -> tuple[int, int]:
        # 4-byte align
        while len(bin_chunks) % 4 != 0:
            bin_chunks.append(0)
        offset = len(bin_chunks)
        bin_chunks.extend(data)
        return offset, len(data)

    buffer_views = []
    accessors = []
    images = []
    textures = []
    materials = []
    mat_index = {}

    # 1. Solid (vertex-colored) material
    mat_index["solid"] = len(materials)
    materials.append({
        "name": "solid",
        "pbrMetallicRoughness": {"baseColorFactor": [1, 1, 1, 1], "metallicFactor": 0.0, "roughnessFactor": 0.9},
        "extensions": {"KHR_materials_unlit": {}}
    })

    # 2. Textured materials
    for name, pil_img in textures_pil.items():
        buf = io.BytesIO()
        pil_img.save(buf, format="PNG", optimize=True)
        png_bytes = buf.getvalue()
        off, length = append_bytes(png_bytes)
        bv_idx = len(buffer_views)
        buffer_views.append({"buffer": 0, "byteOffset": off, "byteLength": length})
        img_idx = len(images)
        images.append({"bufferView": bv_idx, "mimeType": "image/png", "name": name})
        tex_idx = len(textures)
        textures.append({"source": img_idx, "sampler": 0})
        mat_index[name] = len(materials)
        materials.append({
            "name": name,
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": tex_idx},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.9,
            },
            "extensions": {"KHR_materials_unlit": {}}
        })

    # 3. Mesh primitives
    gltf_primitives = []
    for mat_name, data in mb.primitives.items():
        pos_arr = np.array(data["pos"], dtype=np.float32)
        uv_arr = np.array(data["uv"], dtype=np.float32)
        col_arr = np.array(data["col"], dtype=np.float32)
        idx_arr = np.array(data["idx"], dtype=np.uint32)

        def add_accessor(arr, target, comp_type, acc_type, with_minmax=False):
            off, length = append_bytes(arr.tobytes())
            bv = {"buffer": 0, "byteOffset": off, "byteLength": length, "target": target}
            bv_i = len(buffer_views)
            buffer_views.append(bv)
            acc = {
                "bufferView": bv_i,
                "componentType": comp_type,
                "count": len(arr),
                "type": acc_type,
            }
            if with_minmax:
                acc["min"] = arr.min(axis=0).tolist()
                acc["max"] = arr.max(axis=0).tolist()
            acc_i = len(accessors)
            accessors.append(acc)
            return acc_i

        pos_acc = add_accessor(pos_arr, 34962, 5126, "VEC3", with_minmax=True)
        uv_acc = add_accessor(uv_arr, 34962, 5126, "VEC2")
        col_acc = add_accessor(col_arr, 34962, 5126, "VEC3")
        idx_acc = add_accessor(idx_arr, 34963, 5125, "SCALAR")

        gltf_primitives.append({
            "attributes": {
                "POSITION": pos_acc,
                "TEXCOORD_0": uv_acc,
                "COLOR_0": col_acc,
            },
            "indices": idx_acc,
            "material": mat_index[mat_name],
        })

    while len(bin_chunks) % 4 != 0:
        bin_chunks.append(0)

    gltf = {
        "asset": {"version": "2.0", "generator": "GTA-Sixty Jimmy's Room Generator"},
        "extensionsUsed": ["KHR_materials_unlit"],
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "JimmysRoom", "mesh": 0}],
        "meshes": [{"name": "JimmysRoomMesh", "primitives": gltf_primitives}],
        "materials": materials,
        "textures": textures,
        "images": images,
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}],
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(bin_chunks)}],
    }

    json_bytes = json.dumps(gltf, separators=(",", ":")).encode("utf-8")
    while len(json_bytes) % 4 != 0:
        json_bytes += b" "

    total_len = 12 + 8 + len(json_bytes) + 8 + len(bin_chunks)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        # GLB Header: magic, version=2, length
        f.write(struct.pack("<III", 0x46546C67, 2, total_len))
        # JSON chunk
        f.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        f.write(json_bytes)
        # BIN chunk
        f.write(struct.pack("<II", len(bin_chunks), 0x004E4942))
        f.write(bin_chunks)

    print(f"Wrote {out_path} ({total_len / 1024:.1f} KB, {len(gltf_primitives)} primitives)")


if __name__ == "__main__":
    mb = build_room()
    out = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "quest-spatial-app", "src", "main", "assets", "scenes", "jimmys_room.glb"
    )
    export_glb(mb, out)
