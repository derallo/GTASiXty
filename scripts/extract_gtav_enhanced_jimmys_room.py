#!/usr/bin/env python3
"""
Extracts 100% genuine vanilla GTA V Enhanced (Gen9) assets for Jimmy's Room
(V_Michael_1_Son) directly from the local Steam installation into
tools/jimmys-room-src/enhanced_assets/.
"""

import json
import mmap
import os
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools" / "gtav-enhanced-rpf"))

from rpf_enhanced.keys import GtaKeys
from rpf_enhanced.rpf import Archive, BinaryFileEntry, ResourceFileEntry

GTA_ENHANCED_DIR = Path(r"D:\Program Files (x86)\Steam\steamapps\common\Grand Theft Auto V Enhanced")
KEYS_PATH = ROOT / "tools" / "jimmys-room-src" / "keys" / "gtav_aes_key.dat"
OUT_DIR = ROOT / "tools" / "jimmys-room-src" / "enhanced_assets"


def joaat(s: str) -> int:
    h = 0
    for c in s.lower().encode("ascii", errors="ignore"):
        h = (h + c) & 0xFFFFFFFF
        h = (h + (h << 10)) & 0xFFFFFFFF
        h = (h ^ (h >> 6)) & 0xFFFFFFFF
    h = (h + (h << 3)) & 0xFFFFFFFF
    h = (h ^ (h >> 11)) & 0xFFFFFFFF
    h = (h + (h << 15)) & 0xFFFFFFFF
    return h


def cw_size2(flags: int) -> int:
    s = 0x2000 << (flags & 0xF)
    return (
        ((flags >> 4) & 0x1) * (s * 16)
        + ((flags >> 5) & 0x3) * (s * 8)
        + ((flags >> 7) & 0xF) * (s * 4)
        + ((flags >> 11) & 0x3F) * (s * 2)
        + ((flags >> 17) & 0x7F) * (s * 1)
        + ((flags >> 24) & 0x1) * (s // 2)
        + ((flags >> 25) & 0x1) * (s // 4)
        + ((flags >> 26) & 0x1) * (s // 8)
        + ((flags >> 27) & 0x1) * (s // 16)
    )


def rsc7_version(sf: int, gf: int) -> int:
    return (((sf >> 28) & 0xF) << 4) | ((gf >> 28) & 0xF)


def save_rsc7(out_path: Path, data: bytes, sf: int, gf: int) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    ver = rsc7_version(sf, gf)
    comp = zlib.compressobj(level=1, wbits=-15)
    compressed = comp.compress(data) + comp.flush()
    hdr = struct.pack("<4sIII", b"RSC7", ver, sf, gf)
    out_path.write_bytes(hdr + compressed)


def parse_v_int_44_ytyp(ytyp_data: bytes):
    """Parse Room 6 (V_Michael_1_Son), shell from Room 0, and EntitySet v_michael_s_items."""
    si_ptr, ei_ptr, db_ptr, name_ptr = struct.unpack_from("<QQQQ", ytyp_data, 0x20)
    si_cnt, ei_cnt, db_cnt = struct.unpack_from("<HHH", ytyp_data, 0x48)
    db_off = db_ptr & 0x0FFFFFFF

    datablocks = []
    for i in range(db_cnt):
        shash, dlen, dptr = struct.unpack_from("<IiQ", ytyp_data, db_off + i * 16)
        datablocks.append((shash, dlen, dptr & 0x0FFFFFFF))

    def deref(ptr: int) -> int:
        bidx = (ptr & 0xFFF) - 1
        boff = (ptr >> 12) & 0xFFFFF
        return datablocks[bidx][2] + boff

    mlo_off = datablocks[13][2]
    ep_val, e_cnt, _ = struct.unpack_from("<QHH", ytyp_data, mlo_off + 0x98)
    arr_base = deref(ep_val)
    mlo_entities = []
    for i in range(e_cnt):
        ptr, = struct.unpack_from("<Q", ytyp_data, arr_base + i * 8)
        eb = deref(ptr)
        ah, = struct.unpack_from("<I", ytyp_data, eb + 0x08)
        pos = list(struct.unpack_from("<3f", ytyp_data, eb + 0x20))
        rot = list(struct.unpack_from("<4f", ytyp_data, eb + 0x30))
        sxy, sz = struct.unpack_from("<ff", ytyp_data, eb + 0x40)
        mlo_entities.append({
            "index": i,
            "hash": ah,
            "hash_hex": f"0x{ah:08X}",
            "pos": pos,
            "rot": rot,
            "scale_xy": sxy,
            "scale_z": sz,
        })

    # Room 6 = V_Michael_1_Son
    r6 = datablocks[12][2] + 6 * 112
    ap_val, a_cnt, _ = struct.unpack_from("<QHH", ytyp_data, r6 + 0x60)
    att_ids = struct.unpack_from(f"<{a_cnt}I", ytyp_data, deref(ap_val))
    room6_entities = [mlo_entities[idx] for idx in att_ids]

    # Room 0 shell entities (v_44_shell, v_44_shell2)
    r0 = datablocks[12][2] + 0 * 112
    ap0, ac0, _ = struct.unpack_from("<QHH", ytyp_data, r0 + 0x60)
    att0 = struct.unpack_from(f"<{ac0}I", ytyp_data, deref(ap0))
    shell_hashes = {joaat("v_44_shell"), joaat("v_44_shell2")}
    shell_entities = [mlo_entities[idx] for idx in att0 if mlo_entities[idx]["hash"] in shell_hashes]

    # EntitySet v_michael_s_items (NOT swap)
    es_target = joaat("v_michael_s_items")
    esp_val, es_cnt, _ = struct.unpack_from("<QHH", ytyp_data, mlo_off + 0xC8)
    es_base = deref(esp_val)
    es_entities = []
    for esi in range(es_cnt):
        eb = es_base + esi * 48
        nm_h, = struct.unpack_from("<I", ytyp_data, eb + 0x08)
        if nm_h != es_target:
            continue
        loc_p, loc_cnt, _ = struct.unpack_from("<QHH", ytyp_data, eb + 0x10)
        ent_p, e2_cnt, _ = struct.unpack_from("<QHH", ytyp_data, eb + 0x20)
        locs = struct.unpack_from(f"<{loc_cnt}I", ytyp_data, deref(loc_p))
        e2_o = deref(ent_p)
        for j in range(e2_cnt):
            if locs[j] == 6:
                ptr, = struct.unpack_from("<Q", ytyp_data, e2_o + j * 8)
                eob = deref(ptr)
                ah, = struct.unpack_from("<I", ytyp_data, eob + 0x08)
                pos = list(struct.unpack_from("<3f", ytyp_data, eob + 0x20))
                rot = list(struct.unpack_from("<4f", ytyp_data, eob + 0x30))
                sxy, sz = struct.unpack_from("<ff", ytyp_data, eob + 0x40)
                es_entities.append({
                    "index": j,
                    "hash": ah,
                    "hash_hex": f"0x{ah:08X}",
                    "pos": pos,
                    "rot": rot,
                    "scale_xy": sxy,
                    "scale_z": sz,
                })
    return shell_entities, room6_entities, es_entities


def inspect_gen9_drawable_textures(data: bytes, slen: int, is_yft: bool) -> tuple[set[str], set[str]]:
    """Returns (embedded_tex_names_lower, referenced_tex_names_lower) from a Gen9 YDR/YFT."""
    sbuf = data[:slen]
    gbuf = data[slen:]

    def res(ptr: int):
        seg = ptr >> 28
        off = ptr & 0x0FFFFFFF
        if seg == 5:
            return sbuf, off
        if seg == 6:
            return gbuf, off
        return None, 0

    def cstr(ptr: int) -> str:
        b, o = res(ptr)
        if b is None or o >= len(b):
            return ""
        end = b.find(b"\x00", o)
        return b[o:end].decode("ascii", errors="replace")

    embedded = set()
    referenced = set()
    draw_off = 0
    if is_yft:
        dp, = struct.unpack_from("<Q", sbuf, 0x30)
        if (dp >> 28) != 5:
            return embedded, referenced
        draw_off = dp & 0x0FFFFFFF

    sg_ptr, = struct.unpack_from("<Q", sbuf, draw_off + 0x10)
    if (sg_ptr >> 28) != 5:
        return embedded, referenced
    sg_off = sg_ptr & 0x0FFFFFFF
    td_ptr, sh_ptr, sh_cnt, _ = struct.unpack_from("<QQHH", sbuf, sg_off + 0x08)

    if (td_ptr >> 28) == 5:
        td_off = td_ptr & 0x0FFFFFFF
        tx_ptr, tx_cnt, _ = struct.unpack_from("<QHH", sbuf, td_off + 0x30)
        _, tx_arr = res(tx_ptr)
        if tx_arr:
            for i in range(tx_cnt):
                tp, = struct.unpack_from("<Q", sbuf, tx_arr + i * 8)
                tb, to = res(tp)
                if tb is not None:
                    np_, = struct.unpack_from("<Q", tb, to + 0x28)
                    tn = cstr(np_).lower()
                    if tn:
                        embedded.add(tn)

    if (sh_ptr >> 28) == 5:
        sh_arr = sh_ptr & 0x0FFFFFFF
        for i in range(sh_cnt):
            so = struct.unpack_from("<Q", sbuf, sh_arr + i * 8)[0] & 0x0FFFFFFF
            texrefs_ptr = struct.unpack_from("<Q", sbuf, so + 0x10)[0]
            pinfos_ptr = struct.unpack_from("<Q", sbuf, so + 0x20)[0]
            pi_b, pi_o = res(pinfos_ptr)
            tr_b, tr_o = res(texrefs_ptr)
            if pi_b is None or tr_b is None:
                continue
            np_ = pi_b[pi_o + 4]
            for k in range(np_):
                _ph, pd = struct.unpack_from("<II", pi_b, pi_o + 8 + k * 8)
                if (pd & 0x3) == 0:
                    tidx = (pd >> 2) & 0xFF
                    tp, = struct.unpack_from("<Q", tr_b, tr_o + tidx * 8)
                    tb, to = res(tp)
                    if tb is not None:
                        np2, = struct.unpack_from("<Q", tb, to + 0x28)
                        tn = cstr(np2).lower()
                        if tn:
                            referenced.add(tn)
    return embedded, referenced


def inspect_gen9_ytd_names(data: bytes, slen: int) -> set[str]:
    sbuf = data[:slen]
    names = set()
    tx_ptr, tx_cnt, _ = struct.unpack_from("<QHH", sbuf, 0x30)
    if (tx_ptr >> 28) != 5:
        return names
    tx_arr = tx_ptr & 0x0FFFFFFF
    for i in range(tx_cnt):
        tp, = struct.unpack_from("<Q", sbuf, tx_arr + i * 8)
        if (tp >> 28) != 5:
            continue
        to = tp & 0x0FFFFFFF
        np_, = struct.unpack_from("<Q", sbuf, to + 0x28)
        if (np_ >> 28) == 5:
            no = np_ & 0x0FFFFFFF
            end = sbuf.find(b"\x00", no)
            tn = sbuf[no:end].decode("ascii", errors="replace").lower()
            if tn:
                names.add(tn)
    return names


def walk_archive(arc: Archive, prefix: str, on_file, skip_nested=()):
    for fe in arc.list_files():
        rel = fe.path
        low = fe.name.lower()
        if isinstance(fe, BinaryFileEntry) and low.endswith(".rpf"):
            if any(sk in low for sk in skip_nested):
                continue
            sub = arc.extract_nested(fe)
            if sub is not None:
                walk_archive(sub, f"{prefix}/{rel}", on_file, skip_nested=skip_nested)
        else:
            on_file(arc, fe, f"{prefix}/{rel}")


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    keys = GtaKeys.from_aes_key(KEYS_PATH.read_bytes())

    # Step 1: Extract v_int_44.rpf directly from x64h.rpf
    x64h_path = GTA_ENHANCED_DIR / "x64h.rpf"
    with open(x64h_path, "rb") as f:
        mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
        arc = Archive(x64h_path, keys=keys, data=mm, name="x64h.rpf", file_size=x64h_path.stat().st_size)
        v44_entry = arc.find_file("levels/gta5/interiors/v_int_44.rpf")
        v44_sub = arc.extract_nested(v44_entry)
        ytyp_entry = v44_sub.find_file("v_int_44.ytyp")
        ytyp_data = v44_sub.extract(ytyp_entry)
        save_rsc7(OUT_DIR / "v_int_44" / "v_int_44.ytyp", ytyp_data, ytyp_entry.system_flags, ytyp_entry.graphics_flags)
        mm.close()

    shell_ents, room6_ents, es_ents = parse_v_int_44_ytyp(ytyp_data)
    all_ents = shell_ents + room6_ents + es_ents
    target_hashes = {e["hash"] for e in all_ents}
    # Also ensure v_ilev_mm_doorson is included
    target_hashes.add(joaat("v_ilev_mm_doorson"))
    print(f"Target unique entity hashes from v_int_44.ytyp: {len(target_hashes)} "
          f"(shell={len(shell_ents)}, room6={len(room6_ents)}, v_michael_s_items={len(es_ents)})")

    # Collect top-level RPF archives (x64a..x64w + update/update.rpf + update/update2.rpf)
    rpf_paths = sorted(GTA_ENHANCED_DIR.glob("x64*.rpf"))
    for extra in [GTA_ENHANCED_DIR / "update" / "update.rpf", GTA_ENHANCED_DIR / "update" / "update2.rpf"]:
        if extra.exists():
            rpf_paths.append(extra)

    found_models = {}       # hash -> dict(name, ext, path, rpf_chain, parent_rpf_key)
    all_ytd_index = []      # list of (top_rpf_path, nested_path, file_name, low_stem)
    # Also track same-nested-rpf YTDs for every matched model
    model_parent_dirs = set()

    # Pass 1: Scan all archives for matching .ydr/.yft hashes and index all .ytd locations
    for rpf_p in rpf_paths:
        print(f"Scanning {rpf_p.name}...")
        with open(rpf_p, "rb") as f:
            mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            top_arc = Archive(rpf_p, keys=keys, data=mm, name=rpf_p.name, file_size=rpf_p.stat().st_size)

            def on_file(cur_arc: Archive, fe, full_rel: str):
                low = fe.name.lower()
                if not isinstance(fe, ResourceFileEntry):
                    return
                if low.endswith((".ydr", ".yft")):
                    stem = low.rsplit(".", 1)[0]
                    h = joaat(stem)
                    if h in target_hashes:
                        # Prefer update/x64 over base, and .ydr over .yft if same stem unless only yft
                        prev = found_models.get(h)
                        is_update = "update" in str(rpf_p).lower()
                        if prev is None or (is_update and not prev["is_update"]):
                            data = cur_arc.extract(fe)
                            slen = cw_size2(fe.system_flags)
                            is_yft = low.endswith(".yft")
                            emb, ref = inspect_gen9_drawable_textures(data, slen, is_yft)
                            out_sub = OUT_DIR / "models" / fe.name
                            save_rsc7(out_sub, data, fe.system_flags, fe.graphics_flags)
                            parent_key = full_rel.rsplit("/", 1)[0]
                            model_parent_dirs.add(parent_key)
                            found_models[h] = {
                                "hash": h,
                                "hash_hex": f"0x{h:08X}",
                                "name": fe.name,
                                "stem": stem,
                                "is_yft": is_yft,
                                "is_update": is_update,
                                "source": full_rel,
                                "local_path": str(out_sub.relative_to(OUT_DIR)),
                                "embedded_textures": sorted(emb),
                                "referenced_textures": sorted(ref),
                            }
                            print(f"  [MODEL] {fe.name:28s} <- {full_rel} (refs={len(ref)}, emb={len(emb)})")
                elif low.endswith(".ytd"):
                    parent_key = full_rel.rsplit("/", 1)[0]
                    all_ytd_index.append((str(rpf_p), full_rel, fe.name, parent_key))

            walk_archive(top_arc, rpf_p.name, on_file, skip_nested=("peds", "vehicles", "weapons", "cutscene"))
            mm.close()

    print(f"\nResolved {len(found_models)} / {len(target_hashes)} unique entity hashes!")
    missing_hashes = target_hashes - set(found_models.keys())
    if missing_hashes:
        print("  Missing hashes:", [f"0x{h:08X}" for h in sorted(missing_hashes)])

    # Collect all referenced texture names and see which are already embedded
    needed_textures = set()
    embedded_all = set()
    for info in found_models.values():
        embedded_all.update(info["embedded_textures"])
        needed_textures.update(info["referenced_textures"])

    # Also add v_int_44 YTDs explicitly
    v44_dir_key = None
    for info in found_models.values():
        if info["stem"] == "v_44_1_son_deta":
            v44_dir_key = info["source"].rsplit("/", 1)[0]
            break

    # Prioritize YTDs in same nested RPFs as extracted models, especially v_int_44 and init_residential/v_electrical/etc.
    extracted_ytds = {}  # out_rel -> set(tex_names)
    available_tex = set(embedded_all)

    # Group YTDs by top_rpf_path for efficient extraction
    ytd_by_top = {}
    for top_p, full_rel, fname, pkey in all_ytd_index:
        low = fname.lower()
        is_local = (pkey in model_parent_dirs) or ("v_44" in low) or ("v_res" in low) or ("prop_" in low) or ("v_corp" in low) or ("v_club" in low)
        if is_local:
            ytd_by_top.setdefault(top_p, []).append((full_rel, fname, pkey))

    print(f"\nExtracting companion YTDs from {len(ytd_by_top)} top-level archives...")
    for top_p, items in ytd_by_top.items():
        want_rels = {it[0] for it in items}
        with open(top_p, "rb") as f:
            mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
            top_arc = Archive(top_p, keys=keys, data=mm, name=Path(top_p).name, file_size=Path(top_p).stat().st_size)

            def on_ytd(cur_arc: Archive, fe, full_rel: str):
                if full_rel not in want_rels:
                    return
                if not isinstance(fe, ResourceFileEntry):
                    return
                data = cur_arc.extract(fe)
                slen = cw_size2(fe.system_flags)
                names = inspect_gen9_ytd_names(data, slen)
                # Keep if in same dir as v_int_44/models OR shares any needed texture
                in_model_dir = (full_rel.rsplit("/", 1)[0] in model_parent_dirs)
                useful = bool(names & needed_textures) or in_model_dir
                if useful:
                    out_sub = OUT_DIR / "textures" / fe.name
                    # Disambiguate if collision
                    if out_sub.exists():
                        tag = full_rel.replace("/", "_").replace(".rpf", "")[-24:]
                        out_sub = OUT_DIR / "textures" / f"{fe.name[:-4]}__{tag}.ytd"
                    save_rsc7(out_sub, data, fe.system_flags, fe.graphics_flags)
                    extracted_ytds[str(out_sub.relative_to(OUT_DIR))] = sorted(names)
                    available_tex.update(names)
                    print(f"  [YTD] {fe.name:30s} ({len(names):3d} tex) <- {full_rel}")

            walk_archive(top_arc, Path(top_p).name, on_ytd, skip_nested=("peds", "vehicles", "weapons", "cutscene"))
            mm.close()

    still_missing = sorted(needed_textures - available_tex)
    print(f"\nTexture coverage: {len(needed_textures) - len(still_missing)} / {len(needed_textures)} referenced textures found!")
    if still_missing:
        print("  Missing textures (first 30):", still_missing[:30])
        # Pass 3: search ALL remaining YTDs in x64*.rpf if any referenced textures are still missing
        missing_set = set(still_missing)
        rem_by_top = {}
        already_checked = {it[0] for items in ytd_by_top.values() for it in items}
        for top_p, full_rel, fname, pkey in all_ytd_index:
            if full_rel not in already_checked:
                rem_by_top.setdefault(top_p, set()).add(full_rel)
        if rem_by_top:
            print(f"  Scanning remaining YTDs across {len(rem_by_top)} archives for {len(missing_set)} missing textures...")
            for top_p, want_rels in rem_by_top.items():
                if not missing_set:
                    break
                with open(top_p, "rb") as f:
                    mm = mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ)
                    top_arc = Archive(top_p, keys=keys, data=mm, name=Path(top_p).name, file_size=Path(top_p).stat().st_size)

                    def on_extra_ytd(cur_arc: Archive, fe, full_rel: str):
                        if full_rel not in want_rels or not isinstance(fe, ResourceFileEntry):
                            return
                        data = cur_arc.extract(fe)
                        slen = cw_size2(fe.system_flags)
                        names = inspect_gen9_ytd_names(data, slen)
                        hit = names & missing_set
                        if hit:
                            out_sub = OUT_DIR / "textures" / fe.name
                            if out_sub.exists():
                                tag = full_rel.replace("/", "_").replace(".rpf", "")[-24:]
                                out_sub = OUT_DIR / "textures" / f"{fe.name[:-4]}__{tag}.ytd"
                            save_rsc7(out_sub, data, fe.system_flags, fe.graphics_flags)
                            extracted_ytds[str(out_sub.relative_to(OUT_DIR))] = sorted(names)
                            available_tex.update(names)
                            missing_set.difference_update(hit)
                            print(f"  [EXTRA YTD] {fe.name:30s} hit={sorted(hit)} <- {full_rel}")

                    walk_archive(top_arc, Path(top_p).name, on_extra_ytd, skip_nested=("peds", "vehicles", "weapons", "cutscene"))
                    mm.close()
            still_missing = sorted(needed_textures - available_tex)
            print(f"  Final texture coverage: {len(needed_textures) - len(still_missing)} / {len(needed_textures)}")
            if still_missing:
                print("  Still missing:", still_missing)

    manifest = {
        "source_dir": str(GTA_ENHANCED_DIR),
        "shell_entities": shell_ents,
        "room6_entities": room6_ents,
        "v_michael_s_items": es_ents,
        "models": {str(k): v for k, v in found_models.items()},
        "ytds": extracted_ytds,
        "missing_hashes": [f"0x{h:08X}" for h in sorted(missing_hashes)],
        "missing_textures": still_missing,
    }
    manifest_path = OUT_DIR / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"\nWrote manifest: {manifest_path}")


if __name__ == "__main__":
    main()
