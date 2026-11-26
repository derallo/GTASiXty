#!/usr/bin/env python3
"""Validates the three GTA hands / Xbox controller GLBs against what the Quest runtime expects.

The runtime (`GtaHandsControllerSystem.kt`) drives these assets by *node name* through
`SceneObject.getNodeIndex("<name>")` + `setLocalNodeTransformsBatch(...)`. If a name is missing or the
hierarchy is wrong the rig silently stops animating, so this script is the guard rail: run it after
`scripts/build_gta_hands_and_controller_glb.py` and before shipping an APK.

Checks per file
---------------
* every required node name exists exactly once, under the required parent,
* every primitive has POSITION / NORMAL / TEXCOORD_0 / COLOR_0 and an index buffer,
* no NaN/Inf vertices, indices in range, COLOR_0 within [0, 1.6],
* triangle budgets,
* prints the controller socket node rest translations so the Kotlin constants
  (STICK_L_LOCAL / STICK_R_LOCAL / DPAD_LOCAL / BTN_TARGETS) can be confirmed or updated.

Usage
-----
    python scripts/validate_gta_hands_assets.py
    python scripts/validate_gta_hands_assets.py --verbose
"""

from __future__ import annotations

import argparse
import json
import struct
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parent.parent
SCENES = ROOT / "quest-spatial-app" / "src" / "main" / "assets" / "scenes"

# --- Expected hierarchy -------------------------------------------------------------------------
# name -> parent name ("HandRoot" means the glTF scene root node)
HAND_NODES = {
    "HandRoot": None,
    "WRIST": "HandRoot",
    "FOREARM": "WRIST",
    "PALM": "WRIST",
    "THUMB_METACARPAL": "WRIST",
    "THUMB_PROXIMAL": "THUMB_METACARPAL",
    "THUMB_DISTAL": "THUMB_PROXIMAL",
    "THUMB_TIP": "THUMB_DISTAL",
}
for _finger in ("INDEX", "MIDDLE", "RING", "LITTLE"):
    HAND_NODES[f"{_finger}_METACARPAL"] = "WRIST"
    HAND_NODES[f"{_finger}_PROXIMAL"] = f"{_finger}_METACARPAL"
    HAND_NODES[f"{_finger}_INTERMEDIATE"] = f"{_finger}_PROXIMAL"
    HAND_NODES[f"{_finger}_DISTAL"] = f"{_finger}_INTERMEDIATE"
    HAND_NODES[f"{_finger}_TIP"] = f"{_finger}_DISTAL"

# Nodes the runtime animates, i.e. they must be present.
HAND_REQUIRED = set(HAND_NODES)

CONTROLLER_NODES = {
    "ControllerRoot": None,
    "Body": "ControllerRoot",
    "LB": "ControllerRoot",
    "RB": "ControllerRoot",
    "LT": "ControllerRoot",
    "RT": "ControllerRoot",
    "LS_Stick": "ControllerRoot",
    "RS_Stick": "ControllerRoot",
    "DpadUp": "ControllerRoot",
    "DpadDown": "ControllerRoot",
    "DpadLeft": "ControllerRoot",
    "DpadRight": "ControllerRoot",
    "BtnA": "ControllerRoot",
    "BtnB": "ControllerRoot",
    "BtnX": "ControllerRoot",
    "BtnY": "ControllerRoot",
    "BtnView": "ControllerRoot",
    "BtnMenu": "ControllerRoot",
    "Guide": "ControllerRoot",
}
CONTROLLER_REQUIRED = set(CONTROLLER_NODES)

MAX_TRIS_HAND = 1900
MAX_TRIS_CONTROLLER = 2400

COMPONENT_DTYPES = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
TYPE_COUNTS = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


class Glb:
    def __init__(self, path: Path):
        self.path = path
        raw = path.read_bytes()
        magic, _version, _length = struct.unpack_from("<III", raw, 0)
        if magic != 0x46546C67:
            raise ValueError(f"{path.name}: not a GLB (bad magic)")
        jlen, jtype = struct.unpack_from("<II", raw, 12)
        if jtype != 0x4E4F534A:
            raise ValueError(f"{path.name}: first chunk is not JSON")
        self.gltf = json.loads(raw[20 : 20 + jlen].decode("utf-8").rstrip("\x00 "))
        blen, btype = struct.unpack_from("<II", raw, 20 + jlen)
        if btype != 0x004E4942:
            raise ValueError(f"{path.name}: second chunk is not BIN")
        self.bin = raw[28 + jlen : 28 + jlen + blen]
        self.nodes = self.gltf.get("nodes", [])
        self.parent_of = {}
        for i, node in enumerate(self.nodes):
            for child in node.get("children", []):
                self.parent_of[child] = i
        self.name_to_index = {n.get("name", f"#{i}"): i for i, n in enumerate(self.nodes)}

    def read_accessor(self, index: int) -> np.ndarray:
        acc = self.gltf["accessors"][index]
        bv = self.gltf["bufferViews"][acc["bufferView"]]
        offset = bv.get("byteOffset", 0) + acc.get("byteOffset", 0)
        dtype = COMPONENT_DTYPES[acc["componentType"]]
        comps = TYPE_COUNTS[acc["type"]]
        count = acc["count"]
        arr = np.frombuffer(self.bin, dtype=dtype, count=count * comps, offset=offset)
        return arr.reshape(count, comps) if comps > 1 else arr

    def node_translation(self, name: str) -> np.ndarray:
        node = self.nodes[self.name_to_index[name]]
        if "matrix" in node:
            m = np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
            return m[:3, 3]
        return np.array(node.get("translation", [0.0, 0.0, 0.0]), dtype=np.float64)

    def node_scale(self, name: str) -> np.ndarray:
        node = self.nodes[self.name_to_index[name]]
        return np.array(node.get("scale", [1.0, 1.0, 1.0]), dtype=np.float64)


def check_hierarchy(glb: Glb, expected: dict[str, str | None], required: set[str], errors: list[str]):
    for name in required:
        if name not in glb.name_to_index:
            errors.append(f"{glb.path.name}: missing required node '{name}'")
    for name in required:
        if name not in glb.name_to_index:
            continue
        idx = glb.name_to_index[name]
        want_parent = expected[name]
        parent_idx = glb.parent_of.get(idx)
        if want_parent is None:
            # Root node: must be a scene root.
            roots = set()
            for scene in glb.gltf.get("scenes", []):
                roots.update(scene.get("nodes", []))
            if idx not in roots and parent_idx is not None:
                got = glb.nodes[parent_idx].get("name", parent_idx)
                errors.append(f"{glb.path.name}: '{name}' should be a scene root but is a child of '{got}'")
        else:
            if parent_idx is None:
                errors.append(f"{glb.path.name}: '{name}' should be a child of '{want_parent}' but is a root")
            else:
                got = glb.nodes[parent_idx].get("name", str(parent_idx))
                if got != want_parent:
                    errors.append(
                        f"{glb.path.name}: '{name}' parent is '{got}', expected '{want_parent}'"
                    )


def check_geometry(glb: Glb, max_tris: int, errors: list[str], verbose: bool) -> tuple[int, int, np.ndarray]:
    total_tris = 0
    total_verts = 0
    lo = np.array([1e9, 1e9, 1e9])
    hi = np.array([-1e9, -1e9, -1e9])
    for node in glb.nodes:
        if "mesh" not in node:
            continue
        mesh = glb.gltf["meshes"][node["mesh"]]
        for prim in mesh.get("primitives", []):
            attrs = prim.get("attributes", {})
            for needed in ("POSITION", "NORMAL", "TEXCOORD_0", "COLOR_0"):
                if needed not in attrs:
                    errors.append(f"{glb.path.name}: node '{node.get('name')}' primitive missing {needed}")
            if "indices" not in prim:
                errors.append(f"{glb.path.name}: node '{node.get('name')}' primitive has no indices")
                continue
            if "POSITION" not in attrs:
                continue
            pos = glb.read_accessor(attrs["POSITION"]).astype(np.float64)
            if not np.isfinite(pos).all():
                errors.append(f"{glb.path.name}: node '{node.get('name')}' has non-finite vertices")
            if "COLOR_0" in attrs:
                col = glb.read_accessor(attrs["COLOR_0"]).astype(np.float64)
                if not np.isfinite(col).all():
                    errors.append(f"{glb.path.name}: node '{node.get('name')}' has non-finite COLOR_0")
                elif col.max() > 1.6 or col.min() < -0.01:
                    errors.append(
                        f"{glb.path.name}: node '{node.get('name')}' COLOR_0 out of range "
                        f"[{col.min():.3f}, {col.max():.3f}]"
                    )
            idx = glb.read_accessor(prim["indices"]).reshape(-1)
            if idx.size % 3 != 0:
                errors.append(f"{glb.path.name}: node '{node.get('name')}' index count not a multiple of 3")
            if idx.size and (idx.max() >= len(pos) or idx.min() < 0):
                errors.append(f"{glb.path.name}: node '{node.get('name')}' indices out of range")
            total_tris += idx.size // 3
            total_verts += len(pos)
            translation = glb.node_translation(node.get("name"))
            scale = glb.node_scale(node.get("name"))
            world = pos * scale + translation
            lo = np.minimum(lo, world.min(axis=0))
            hi = np.maximum(hi, world.max(axis=0))
            if verbose:
                print(
                    f"    {node.get('name', '?'):22s} verts={len(pos):5d} tris={idx.size // 3:5d} "
                    f"len_y={float(world[:, 1].max() - world[:, 1].min()):.4f}"
                )
    if total_tris > max_tris:
        errors.append(f"{glb.path.name}: {total_tris} triangles exceeds budget {max_tris}")
    return total_tris, total_verts, np.concatenate([lo, hi])


def check_materials(glb: Glb, errors: list[str]):
    if "KHR_materials_unlit" not in glb.gltf.get("extensionsUsed", []):
        errors.append(f"{glb.path.name}: KHR_materials_unlit missing from extensionsUsed")
    for mat in glb.gltf.get("materials", []):
        name = mat.get("name", "?")
        if "KHR_materials_unlit" not in mat.get("extensions", {}):
            errors.append(f"{glb.path.name}: material '{name}' is not unlit")
        pbr = mat.get("pbrMetallicRoughness", {})
        if "baseColorTexture" not in pbr:
            errors.append(f"{glb.path.name}: material '{name}' has no baseColorTexture")
        if pbr.get("metallicFactor", 1.0) > 0.05:
            errors.append(f"{glb.path.name}: material '{name}' metallicFactor should be 0")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()

    errors: list[str] = []
    jobs = [
        ("gta_hand_left.glb", HAND_NODES, HAND_REQUIRED, MAX_TRIS_HAND, 0.34),
        ("gta_hand_right.glb", HAND_NODES, HAND_REQUIRED, MAX_TRIS_HAND, 0.34),
        ("xbox_controller.glb", CONTROLLER_NODES, CONTROLLER_REQUIRED, MAX_TRIS_CONTROLLER, 0.30),
    ]

    summary = []
    for filename, expected, required, max_tris, max_extent in jobs:
        path = SCENES / filename
        if not path.exists():
            errors.append(f"{filename}: file missing ({path})")
            continue
        print(f"\n=== {filename} ({path.stat().st_size / 1024.0:.1f} KiB) ===")
        try:
            glb = Glb(path)
        except Exception as exc:  # noqa: BLE001 - we want a readable report
            errors.append(f"{filename}: parse failed: {exc}")
            continue
        check_hierarchy(glb, expected, required, errors)
        check_materials(glb, errors)
        tris, verts, bounds = check_geometry(glb, max_tris, errors, args.verbose)
        lo, hi = bounds[:3], bounds[3:]
        extent = hi - lo
        print(f"  nodes={len(glb.nodes)} verts={verts} tris={tris}")
        print(
            "  bounds min=({:.4f}, {:.4f}, {:.4f}) max=({:.4f}, {:.4f}, {:.4f}) extent=({:.3f}, {:.3f}, {:.3f}) m".format(
                lo[0], lo[1], lo[2], hi[0], hi[1], hi[2], extent[0], extent[1], extent[2]
            )
        )
        if extent.max() > max_extent:
            errors.append(
                f"{filename}: rest-pose extent {extent.max():.3f} m exceeds {max_extent:.2f} m "
                f"(check units / stray vertices)"
            )
        if filename.startswith("xbox_controller"):
            print("  controller sockets (ControllerRoot space, metres):")
            for socket in ("LS_Stick", "RS_Stick", "DpadUp", "BtnA", "BtnB", "BtnX", "BtnY", "LT", "RT", "LB", "RB"):
                if socket in glb.name_to_index:
                    t = glb.node_translation(socket)
                    print(f"    {socket:10s} = ({t[0]:+.4f}, {t[1]:+.4f}, {t[2]:+.4f})")
        summary.append((filename, tris, verts))

    print("\n=== Summary ===")
    for name, tris, verts in summary:
        print(f"  {name:22s} verts={verts:6d} tris={tris:5d}")
    if summary:
        print(f"  TOTAL tris={sum(s[1] for s in summary)}")

    if errors:
        print("\nFAILED:")
        for err in errors:
            print(f"  - {err}")
        return 1
    print("\nAll GTA hands / controller asset checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
