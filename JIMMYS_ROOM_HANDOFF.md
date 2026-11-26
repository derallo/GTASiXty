# Jimmy's Room 3D VR Scene — Agent Handoff Guide

## 1. Current Status (2026-11-25)
A real GTA V **Jimmy De Santa's Bedroom** (`V_Michael_1_Son` from `v_int_44`) 3D scene mode is live in `quest-spatial-app` (`com.gtasixty.spatial`), built and installed on Quest 3.

- **Output asset**: [jimmys_room.glb](quest-spatial-app/src/main/assets/scenes/jimmys_room.glb) (`5.61 MB`, `99` primitives, `81,111` verts, `66,637` tris, glTF 2.0 with `KHR_materials_unlit` + baked lighting/AO/micro-relief, 100% genuine vanilla GTA V Enhanced Gen9 models & BC1–BC7 textures).
- **Active GLB builder**: [scripts/build_real_jimmys_room_glb.py](scripts/build_real_jimmys_room_glb.py) *(Note: [scripts/generate_jimmys_room_glb.py](scripts/generate_jimmys_room_glb.py) was the superseded procedural blockout prototype and can be removed once no longer needed).*
- **Gen9 asset extractor & 6-angle preview renderer**:
  - [scripts/extract_gtav_enhanced_jimmys_room.py](scripts/extract_gtav_enhanced_jimmys_room.py) (uses `tools/gtav-enhanced-rpf` + keys from `GTA5_Enhanced.exe`)
  - [scripts/render_jimmys_room_preview.py](scripts/render_jimmys_room_preview.py) (renders 6 `960x600` camera angles to `tools/jimmys-room-src/renders/`)
- **Quest 3 Spatial host**:
  - [SpatialDisplayBridge.java](quest-spatial-app/src/main/java/com/gtasixty/spatial/SpatialDisplayBridge.java): Added `BACKDROP_JIMMYS_ROOM = 3`, `BACKDROP_COUNT = 4`, label `"Jimmy's Room"`.
  - [DisplayControlsView.java](quest-spatial-app/src/main/java/com/gtasixty/spatial/DisplayControlsView.java): Cycles `Passthrough → Dim → Dark → Jimmy's Room`. While in Jimmy's Room, dims/disables **Screen Size**, **Curve**, and **Tilt** sliders, relabels **Room Depth** to **Seat Distance to TV**, and displays room-specific guidance.
  - [SpatialMainActivity.kt](quest-spatial-app/src/main/java/com/gtasixty/spatial/SpatialMainActivity.kt): Loads `scenes/jimmys_room.glb`, disables passthrough, locks the live OpenXR display flat (`2.100 m` 16:9) inside Jimmy's TV bezel, disables corner resize / grab handle while in the room, and moves room + TV together on **Seat Distance**, **Height**, and **Recenter**.

---

## 2. Real GTA V Enhanced (Gen9) Source Assets & Extraction Pipeline

### Direct Steam GTA V Enhanced Extraction (Current)
Extracted directly from `D:\Program Files (x86)\Steam\steamapps\common\Grand Theft Auto V Enhanced` into gitignored `tools/jimmys-room-src/`:
- `tools/gtav-enhanced-rpf/` — cloned `wjy000/gtav-enhanced-rpf` (with 32-bit unsigned wrap fix in `crypto.py:get_ng_key_index`).
- `tools/jimmys-room-src/keys/` — `gtav_aes_key.dat`, `gtav_ng_key.dat`, `gtav_ng_decrypt_tables.dat` extracted from `GTA5_Enhanced.exe`.
- `tools/jimmys-room-src/enhanced_assets/` — `manifest.json` + extracted Gen9 `.ydr` (`ver=159`), `.yft` (`ver=171`), `.ytd` (`ver=5`), and `v_int_44.ytyp` (`ver=32`):
  - **47 / 48** unique `v_int_44.ytyp` Room 6 + `v_michael_s_items` + shell/door entities resolved directly by JOAAT hash, plus `prop_tv_flat_01.ydr` (real TV bezel, speaker grill, and glowing cyan ring) from `v_electrical.rpf`.
  - **147 / 147** referenced textures resolved (zero missing textures), including `+hi`/`+hidr`/`+hifr` BC1–BC7 dictionaries (`1,314` textures across `863` `.ytd`s).
  - Includes real Rockstar props that replaced all earlier geometric stand-ins: `prop_tv_flat_01.ydr`, `prop_speaker_06.ydr`, `prop_console_01.ydr`, `prop_monitor_01a.yft`, `prop_keyboard_01b.ydr`, `prop_printer_02.ydr`, `v_corp_offchair.ydr`, `v_res_m_lampstand.ydr`, `v_res_desklamp.ydr`, `prop_bong_01.ydr`, `v_res_tt_pizzaplate.yft` (×3), `v_res_tt_can03.yft` (×5), `prop_laptop_01a.yft`, `prop_el_guitar_03.ydr`, `v_club_roc_gstand.ydr`, `prop_amp_01.ydr`, `v_club_roc_cabamp.ydr`/`cab3`/`eq1`/`eq2`, `v_res_skateboard.ydr`, `v_res_desktidy.yft`, `v_res_sketchpad.ydr`, `v_res_tissues.ydr`, `v_res_mousemat.ydr`, `v_res_pcheadset.ydr`, `v_res_pcspeaker.ydr` (×2), `v_res_pcwoofer.ydr`, `v_res_pctower.ydr`, `v_res_harddrive.ydr`, `v_res_ipoddock.ydr`, `v_res_fa_mag_motor.yft`, `v_res_fashmag1.ydr`, and `p_cs_lighter_01.ydr`.

### Reference Footage
- User's GTA V Enhanced walkthrough recording: `simplescreenrecorder-2026-11-25_22.37.43.mkv` (`1920x1200`, `99.3 s`).
- Earlier YouTube timelapse reference: `https://www.youtube.com/watch?v=eNSc_4K42P0` (*Jimmy's Room Timelapse | GTA V* by KatzenwagenTV).

---

## 3. Coordinate System, Quaternions & Shared Constants

### Critical GTA V RAGE Parsing Rules (Already Verified to ~2 mm)
1. **`CEntityDef.rotation` quaternion MUST be conjugated (`(-qx, -qy, -qz, qw)`)** when transforming local `.ydr` vertices into `v_int_44` world space (`quat_conj_rotate` in `build_real_jimmys_room_glb.py`). Verified against `v_res_msoncabinet` TV bay (`-4.651, -3.080, 6.077`) vs entity `[209] 0x283206EC` (`-4.653, -3.060, 6.082`).
2. **Vertex declarations**: Only 3 formats exist across all Jimmy's room `.ydr`s (`stride=36 / flags=0x0059`, `stride=40 / flags=0x0079`, `stride=52 / flags=0x4059`). Position is always `0..12`, Normal `12..24`, `Colour0` RGBA8 `24..28`, `TexCoord0` `32..40` if `flags & 0x20` else `28..36`.
3. **Right-handed coordinate map (`det = +1`, zero mirroring, preserves triangle winding)**:
   - GTA V `v_int_44` is Z-up; Jimmy's TV wall is on the West (`-X_gta`), facing East (`+X_gta`).
   - Meta Spatial SDK / glTF 2.0 is Y-up, with viewer facing `+Z_glb` (`+X_glb` is viewer's **LEFT**, `-X_glb` is viewer's **RIGHT**).
   - Mapping used in `gta_to_glb()`:
     ```python
     x_glb = -(y_gta - GTA_SEAT_Y)   # +X = South (desk, bed, red curtains on LEFT)
     y_glb = +(z_gta - GTA_FLOOR_Z)  # +Y = Up (floor = 0.0)
     z_glb = -(x_gta - GTA_SEAT_X)   # +Z = West (TV wall dead ahead)
     ```
   - Origin constants: `GTA_SEAT_X = -2.100`, `GTA_SEAT_Y = -3.080`, `GTA_FLOOR_Z = 4.577`.

### Shared Constants Between GLB & `SpatialMainActivity.kt` (MUST STAY IN SYNC!)
| Constant | Value | Meaning |
|---|---|---|
| `JIMMYS_ROOM_URI` | `Uri.parse("scenes/jimmys_room.glb")` | **NEVER** prepend `apk://` — Meta Spatial SDK 0.15.0 automatically prepends `apk:///` (passing `apk://` crashes `GLTFLoader.cpp` with `apk:///apk://...`!). |
| `JIMMYS_ROOM_TV_WIDTH_M` | `2.100f` | Exact 16:9 width (`2.100 m × 1.18125 m`) inside `v_res_msoncabinet`'s `2.24 m` TV bay (`local X ∈ [-1.23, +1.01]`). |
| `JIMMYS_ROOM_TV_LOCAL_POS` | `Vector3(0f, 1.750f, 2.420f)` | Center of OpenXR panel; TV bezel front sits at `z = 2.435` (`1.5 cm` behind panel, zero Z-fighting), dark TV backplate at `z = 2.450..2.505`. |
| `JIMMYS_ROOM_EYE_HEIGHT_M` | `1.56f` | Default viewer eye height above GLB floor (`0.0`). At `y = 1.56 m`, viewer looks gently up (`+4.5°`) at the `1.75 m` TV center. |

---

## 4. Texture Decoding, Micro-Relief & Lighting Notes

### Genuine Vanilla GTA V Enhanced Textures
All textures are decoded directly from Gen9 `TextureFormatG9` (`BC1`–`BC7` via `texture2ddecoder`, plus `RGBA8`/`BGRA8`/`R8` and `BC2`) at up to `1024 px` for hero surfaces (`JPEG quality=91` for opaque, `PNG` for alpha masks/blends). Companion `_n` normal maps (`0x1E77BF4C`) are baked into diffuse textures as subtle `±14%` cavity/slope micro-relief (`apply_normal_micro_relief()`). `style_vanilla_texture()` applies Rockstar shader-constant tints (`prop_base_black_01`, `prop_grey_plastic`, `prop_el_mouse_mat_01`, glowing `prop_base_blue_03` TV ring, `prop_green_glass` console LED, `km_v_lampshade1`, `prop_moniter_desktop_01`, `michealexterior`).

### Lighting Model
All materials use `KHR_materials_unlit` with vertex-baked lighting (`bake_enhanced_room_lighting()` combining Rockstar's baked `Colour0` `[R=artificial, G=natural, B=AO]` + overhead warm spotlights + golden sunlight shaft through the curtain slit + warm bedside floor lamp radial glow + crisp desk lamp spotlight + subtle cool TV/monitor bounce + gentle glossy specular). Large shell/decal/poster triangles are midpoint-subdivided (`subdivide_large_triangles()`) so light shafts and lamp pools interpolate smoothly at locked **120 Hz** on Quest 3.

---

## 5. Runtime Spatial App Invariants (`SpatialMainActivity.kt`)
1. **Swapchain invariant (DO NOT BREAK)**: Always keep `mips = 1`, `enableLayer = true`, `forceSceneTexture = false`, `fragmentShader = ""` in `buildPanelConfigOptions()` (see [SPATIAL_APP_PLAN.md](SPATIAL_APP_PLAN.md)).
2. **TV Lock Invariants in `BACKDROP_JIMMYS_ROOM`**:
   - `onSceneReady()` checks `startInRoom` BEFORE creating `panelEntity` and calls `applyJimmysRoomPoses()` AFTER `panelEntity` and `grabHandleEntity` exist.
   - `syncHandleComponentsForCurvature()` enforces `allowResize = !curved && !inRoom` and hides `IsdkPanelGrabHandle` while in the room so `onSceneTick()` never re-enables corner resize.
   - `onSceneTick()` returns early after `syncHandleComponentsForCurvature()` + `updateControllerVisibility()` when `isJimmysRoomActive()` is true, preventing any corner-drag, panel-grab, or bottom-handle trigger drag from popping the display out of the TV.
   - `reshapePanelNow()` checks `inRoom`: never calls `syncAnchorFromEntity()` or `applyCurrentPose()` while in Jimmy's Room; always re-locks via `applyJimmysRoomPoses()`.
   - **Seat Distance & Height**: `applyJimmysRoomPoses()` shifts `effectiveRoomOrigin` along `anchorYaw.forward()` by `(distanceMeters - 2.2f).coerceIn(-1.10f, 1.15f)` (`1.32 m – 3.57 m` from TV) and vertically by `heightOffsetM`, moving **both** the 3D room and the TV display together as a rigid unit.
   - Switching back to `Passthrough`, `Dim`, or `Dark` hides `jimmysRoomEntity`, re-enables grabbing/corner-resize/bottom handle, and restores the user's saved `widthMeters`, `curvePct`, and `tiltDeg`.

---

## 6. Ideas & High-Impact Next Steps for the Next Agent

### High Priority (Visual Fidelity & Comfort)
1. **Verify live on-headset look & feel with the user** — ask for feedback on:
   - Overall room brightness/mood (too dark or too bright while playing GTA 6?)
   - Seated eye height (`1.56 m`) and default seat distance (`2.42 m` from a `2.10 m` TV)
   - Whether they'd prefer sitting *on* the bed slightly angled vs dead-center perpendicular to the TV (currently dead-center perpendicular at `Y = -3.080` for zero neck strain, with Jimmy's bed immediately to the left).
   - Whether they want an option for **Screen Size** in Jimmy's Room to act as a **Room Scale** or **TV Zoom / Seat Distance** shortcut instead of being locked/dimmed.
2. **All Room 6 & `v_michael_s_items` props are now real GTA V Enhanced `.ydr`/`.yft` meshes** (no geometric stand-ins remain except the matte dark backplate & silver T-bar pedestal stand behind `prop_speaker_06`). Future optional polish: refine `V_Res_MouseMat_pal` / palette-driven props dynamically instead of per-texture curves.
3. **Time-of-Day / Lighting Mood Presets (Day / Sunset / Night)** — just like the user's reference timelapse (`eNSc_4K42P0`)! Since `build_real_jimmys_room_glb.py` bakes lighting in ~2 seconds, you can either offer a **Night / Cozy Dark** lighting pass (dimmer room + warm bedside lamp + glowing cyan PC/AV LEDs so the Xbox TV screen pops with cinema-level contrast!) or switch between two lightweight GLBs / shader lighting tint.
4. **Optional Ambient Light / Subtle Screen Glow or Real PBR Lighting** — test whether Meta Spatial SDK's IBL/point lights look even better on `v_res_msoncabinet` and the TV bezel (watch Quest 3 GPU budget at 120 Hz + 1800p SR + FG60!).
5. **Reclined Play Support** — if the user plays reclined on a couch/bed, consider whether **Tilt** should pitch the whole room + TV slightly OR stay level (pitching a full 3D room can feel disorienting in VR, so keep it opt-in if added!).
6. **Cleanup**:
   - Move `v_int_44_extracted` and `mh_retex` out of the conversation scratch directory into a gitignored local folder (e.g., `tools/jimmys-room-src/`) and update `SCRATCH` in `scripts/build_real_jimmys_room_glb.py`.
   - Delete obsolete `scripts/generate_jimmys_room_glb.py` once confirmed unneeded.

---

## 7. Quick Build, Preview & Quest 3 Test Commands

```powershell
# 0. Re-extract genuine GTA V Enhanced assets (only needed once or after game update)
python scripts/extract_gtav_enhanced_jimmys_room.py

# 1. Regenerate jimmys_room.glb from real GTA V Enhanced assets (~5s)
python scripts/build_real_jimmys_room_glb.py

# 2. Render 6 textured 3D VR camera previews (~12s) to tools/jimmys-room-src/renders/
python scripts/render_jimmys_room_preview.py

# 3. Build & sign Quest 3 APK (Gradle 9.6.0 is cached in ~/.gradle/wrapper/dists/gradle-9.6.0-bin)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-quest-apk.ps1

# 4. Find Quest 3 wireless ADB IP via mDNS (look for serial 2G0YC1ZF9S0RNL; note 192.168.68.82 is an onn TV box!)
adb mdns services
adb connect 192.168.68.78:5555

# 5. Install, launch, and jump directly into Jimmy's Room
adb -s 192.168.68.78:5555 install -r -d build\GTA-Sixty-Quest3.apk
adb -s 192.168.68.78:5555 shell am start -n com.gtasixty.spatial/.SpatialMainActivity
adb -s 192.168.68.78:5555 shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ei backdrop 3 --ez recenter true

# 6. Check logs (when headset is worn / awake)
adb -s 192.168.68.78:5555 logcat -d -s GTA60Spatial:I AndroidRuntime:E
```
