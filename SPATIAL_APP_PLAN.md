# GTA-Sixty Spatial (`com.gtasixty.spatial`) — Architecture & Recovery Plan

## 1. Side-by-Side App Separation

`GTA-Sixty Spatial` lives in its own isolated Android/Gradle module ([quest-spatial-app/](quest-spatial-app/)) and installs side-by-side with the stable flat 2D panel app ([quest-app/](quest-app/)). Neither shares a package ID, launcher tile, preferences file, or auto-updater feed:

| Property | Stable Flat App (`quest-app/`) | Spatial App (`quest-spatial-app/`) |
|---|---|---|
| **Launcher Label** | `GTA-Sixty` (green icon) | `GTA-Sixty Spatial` (cyan/teal icon) |
| **Package ID** | `com.gtasixty.quest` (frozen at `v1.4`) | `com.gtasixty.spatial` |
| **Host Activity** | `MainActivity` (native flat Horizon 2D panel) | `SpatialMainActivity` (`AppSystemActivity`, Meta Spatial SDK `0.15.0`) |
| **SharedPreferences** | `gta60` / `gta60_direct` | `gta60` / `gta60_direct` / `gta60_spatial` (isolated per-app sandbox) |
| **Broadcast Actions** | `com.gtasixty.quest.STOP_STREAM` | `com.gtasixty.spatial.STOP_STREAM`, `com.gtasixty.spatial.SET_DISPLAY` |
| **Updater Feed** | `releases/version.json` | `releases/spatial-version.json` |
| **Build Output** | `build/GTA-Sixty-Quest3.apk` | `build/GTA-Sixty-Spatial-Quest3.apk` |
| **Build Script** | [scripts/build-quest-apk.ps1](scripts/build-quest-apk.ps1) | `scripts/build-quest-spatial-apk.ps1` |
| **Install Script** | [scripts/install-quest-apk.ps1](scripts/install-quest-apk.ps1) | `scripts/install-quest-spatial-apk.ps1` |

**Mode priorities in `GTA-Sixty Spatial`:**
1. **Capture Mode** (default, `192.168.68.90`): HDMI capture via `mintbook-g11` VA-API / GT60 TCP 7660, low-latency `MediaCodec`, `CaptureGlRenderer` (EASU/RCAS/FG60/Looks), 48 kHz stereo PCM audio, `GTA60_UPDATE`.
2. **Direct Mode**: Headset -> Xbox Series X WebRTC (`XboxDirect` + `DirectController` + `direct.js`), 1440p60 HW decode, WebGL2 EASU/RCAS/FG60, Xbox-paired controller keepalive & Fix pad, persisted session cleanup, `GTA60_STOP`.
3. **PC Mode**: Kept compiling and functional in the `Capture -> Direct -> PC` mode cycle with minimal changes.

---

## 2. Root-Cause Postmortem of the Nov 21 Spatial Attempt

Decompiling the cached Meta Spatial SDK `0.15.0` jars (`PanelSurface.class`, `PanelShape.class`, `PanelConfigOptions.class`, `LayerFilters.class`, `SceneCylinderLayer.class`) alongside the Nov 21 Quest 3 logcat exposed six exact causes behind every failure:

| Prior Symptom | Verified Root Cause | Architectural Fix in `quest-spatial-app/` |
|---|---|---|
| **Hung on 3 pulsing dots (required Quest reboot)** | Setting `mips = PanelConfigOptions.MIPS_MAX` (`-1`) violated `PanelSurface`'s fast-path condition (`mips == 1 && fragmentShader == "" && !useTexture`), forcing `PanelSurface` onto `nativeCreateImageReaderSwapchain()` with a 12-level mipmap CPU/GPU copy every frame and deadlocking `horizon::hmi::Session::endFrame()` (`endFrameJob` stalled >22 s). | **Hard Invariant:** Always set `mips = 1`, `enableLayer = true`, `forceSceneTexture = false`, `fragmentShader = ""` so Meta Spatial SDK stays permanently on the zero-copy OpenXR `AndroidSurfaceSwapchain`. |
| **Pixelated / blurry video** | Two bugs stacked: (1) `filters = null` disabled Quest's OpenXR TimeWarp hardware super-sampling and sharpening (`XrCompositionLayerSettingsFlagsFB`), and (2) Capture (`3200x1800`) and Direct (`2400x1350`) rendered off-ratio into a `2560x1440` `VirtualDisplay`, causing a double resample before TimeWarp curved projection. | Keep `mips = 1` **and** enable compositor `LayerFilters.HIGHEST_QUALITY` (fallback `DEFAULT_QUALITY` if needed; `SceneLayer.setFilters()` runs on the compositor independently of `mips`). Clamp Capture (`CaptureGlRenderer`) and Direct (`direct.js`) outputs **1:1 to `2560x1440`** inside a `2560x1440` (`1600x900 dp @ 256 DPI`) panel. |
| **Curving shrank the screen tiny & broke dragging** | `SceneCylinderLayer` places the entity `Transform` at the **cylinder center axis**, placing the visible arc `+radius` meters forward (`7-11 m` away at shallow curves if uncompensated). Also, crossing `0% <-> >0%` swaps `QuadShapeOptions <-> CylinderShapeOptions` and destroys/recreates ISDK colliders mid-drag, cancelling pointer events. | Apply atomic cylinder-axis compensation `entityPos = visualCenter - fwd * radius` (and invert with `+ fwd * radius` when grabbed). Disable flat corner-resize handles while curved (`IsdkPanelResize.enabled = false`, grab-handle visuals hidden). Keep the Curve slider drag-safe across `0% <-> >0%` (commit on release when starting from flat), and provide `±10%` buttons plus one-tap **Flat / 25% / 50% / 80%** presets. |
| **Boundary redraw stranded the window** | Redrawing the Quest guardian/boundary shifts the OpenXR tracking origin while the spatial panel stayed anchored in the old origin. | Auto-recenter on `onResume()`, `onVRReady()`, or sudden tracking-origin jumps (`headJump > 0.85 m` / out-of-reach distance), plus a one-tap **Recenter** button directly on both HUD bars and inside the Display drawer. |
| **Size vs. Distance confusion & reclining tilt** | Both changed perceived angular scale without clear labels, and reclining/tilting up or down lacked vertical height adjustment. | Label controls clearly: **Screen Size (16:9)** (`1.0-4.5 m`), **Room Depth** (`1.0-5.0 m`), **Tilt** (`-45°` to `+45°`), **Height** (`-0.6 m` to `+0.8 m`), **Curve** (`0-100%`), and **Backdrop** (`Passthrough / Dim / Dark`). |
| **Direct disconnects / idle kicks** | The Nov 21 build predated v1.1-v1.4 fixes (`activeSessionId` cleanup on startup, PC auto-start suppression, `GTA60_STOP`, Xbox-paired virtual pad keepalive). | Port cleanly from the frozen `v1.4` [quest-app/](quest-app/) baseline so all Capture and Direct stability fixes are inherited from day one. |

---

## 3. Spatial Architecture & File Layout

```text
quest-spatial-app/
├── build.gradle.kts                  # AGP 9.0.1, Meta Spatial SDK 0.15.0, appId com.gtasixty.spatial
├── settings.gradle.kts
├── gradle.properties
├── local.properties                  # gitignored; points to Android SDK
├── debug.keystore                    # gitignored; separate debug key
└── src/main/
    ├── AndroidManifest.xml           # SpatialMainActivity (VR launcher) + embedded MainActivity
    ├── res/
    │   ├── drawable/ic_launcher.xml  # Cyan/teal spatial variant icon
    │   └── values/{ids.xml, themes.xml}
    ├── assets/direct/
    │   ├── index.html
    │   └── direct.js                 # 1:1 2560x1440 output cap + WebGL2 EASU/RCAS/FG60
    └── java/com/gtasixty/spatial/
        ├── SpatialMainActivity.kt    # Meta Spatial SDK host: 120 Hz, Quad/Cylinder, Tilt, Height, Recenter, Backdrop
        ├── SpatialDisplayBridge.java # SharedPreferences("gta60_spatial"), cylinder math, input/extras bridge
        ├── DisplayControlsView.java  # Curve presets/slider, Tilt, Size, Depth, Height, Backdrop, Recenter, Reset
        ├── MainActivity.java         # Embedded 2560x1440 Capture/Direct/PC activity + HUD Display/Recenter buttons
        ├── CaptureGlRenderer.java    # Adreno 740 GLES3 EASU/RCAS/FG60 pipeline (1:1 2560x1440)
        ├── DirectController.java     # Direct WebView host + Display/Recenter HUD buttons
        ├── XboxDirect.java           # xhome auth, provisioning, persisted session cleanup
        ├── AppUpdater.java           # Checks releases/spatial-version.json
        └── ApkFileProvider.java      # com.gtasixty.spatial.apkprovider
```

### Key Technical Decisions

1. **Panel Resolution & DPI (`2560×1440` at `256 DPI`)**:
   - Using `DpDisplayOptions(1600f, 900f, 256)` with `layoutWidthInPx = 2560`, `layoutHeightInPx = 1440`, `layoutDpi = 256` maps 1:1 to a native `2560×1440` Android swapchain while making 2D HUD buttons ~28% larger and easier to point at with Quest controllers or hand tracking compared to `200 DPI`.
2. **Capture Video View (`SurfaceView` with `TextureView` Fallback)**:
   - `MainActivity` initially hosts Capture mode on `SurfaceView` (`SurfaceHolder.setFixedSize(outW, outH)` clamped to `<= 2560×1440`).
   - Because `ActivityPanelRegistration` renders into an Android `VirtualDisplay` surface, if Stage 3 verification reveals any `SurfaceView` hole-punch/Z-order or black-frame issue inside `VirtualDisplay`, the pre-planned fallback swaps the Capture view container in `quest-spatial-app`'s `MainActivity` to a `TextureView` feeding `new Surface(surfaceTexture)` into `CaptureGlRenderer` (keeping `CaptureGlRenderer` untouched).
3. **Cylinder Geometry & Pose Invariant**:
   - `cylinderArcDegrees(curvePct) = 18f + (curvePct / 100f) * 92f` (`18°`–`110°`).
   - `cylinderRadiusMeters(widthM, curvePct) = max(0.75f, widthM / toRadians(arcDeg))`.
   - `visualCenter = anchorCenter + (0, heightOffsetM, 0)`.
   - `rot = anchorYaw * Quaternion.fromAxisAngle(X, tiltDeg)`.
   - `entityPos = visualCenter - rot.forward() * radiusMeters`.
   - Corner resize handles (`IsdkPanelResize`) are enabled only when `curvePct == 0` (`QuadShapeOptions`) and disabled with edge grab visuals hidden when `curvePct > 0` (`CylinderShapeOptions`).

---

## 4. Staged Quest 3 Verification Plan (`192.168.68.89:5555`)

1. **Build & Unit Tests**: `scripts\build-quest-spatial-apk.ps1` (`BUILD SUCCESSFUL` + `apksigner verify`) and `node tests/direct-js-test.js` pass.
2. **Stage 1 — Side-by-Side & Zero-Stall Gate**: Install `com.gtasixty.spatial` alongside untouched `com.gtasixty.quest`, confirm `120.0 Hz`, run 15+ s verifying **zero** `FRAME_STALL_TIMEOUT_DECISION` / `endFrameJob` stalls and **zero** `AndroidRuntime` crashes. (If `LayerFilters.HIGHEST_QUALITY` triggers any stall, fall back to `DEFAULT_QUALITY` / `QUALITY_SUPER_SAMPLING` and re-verify.)
3. **Stage 2 — Tilt, Curve, Height, Size, Depth, Backdrop, Recenter Gate**: Exercise `com.gtasixty.spatial.SET_DISPLAY` across `curve=0/25/50/80/100/0`, `tilt=-30/+30`, `height=0.35`, `width=3.0`, `distance=2.6`, `backdrop=0/1/2`, `recenter`, and `reset`, confirming `screenCenter` remains pinned and zero `AetherCylinderLayer` errors occur.
4. **Stage 3 — Capture Mode Gate**: Verify Capture mode (`MainActivity` + `CaptureGlRenderer`) inside the `VirtualDisplay` panel (switching to `TextureView` if `SurfaceView` has composition issues).
5. **Stage 4 — Direct Mode Gate**: Launch `--ez direct true`, verify `WebView` + `direct.js` 1:1 `2560×1440` setup and clean `STOP_STREAM` teardown.
6. **Stage 5 — Record Results**: Update this document and `AGENTS.md` with measured Quest 3 results.

---

## 5. Verification Results (Quest 3 `192.168.68.89:5555`, 2026-11-22)

- **Build & Signing**: `scripts\build-quest-spatial-apk.ps1` succeeded (`BUILD SUCCESSFUL in 27s`), producing signed `build/GTA-Sixty-Spatial-Quest3.apk` (`apksigner verify` v2 passed).
- **Direct JS Protocol Tests**: `tests/direct-js-test.js` passed all 49 checks (`direct.js protocol test PASS`), and `quest-spatial-app/src/main/assets/direct/direct.js` passed the identical suite.
- **Stage 1 — Side-by-Side & Zero-Stall Gate**:
  - Verified both `com.gtasixty.quest` (`v1.4` flat) and `com.gtasixty.spatial` (`1.0-spatial`) co-installed with `git diff quest-app/` empty.
  - Confirmed `120.0Hz` (`exact=true, confirmed=120.0`), `VirtualDevice_2` `2560 x 1440 @ 256 dpi`, zero-copy `CreateAndroidSurfaceSwapChainGLES` (`AttachSurfaceSwapChainGLES`), Passthrough ON, and `LayerFilters.HIGHEST_QUALITY` with `mips = 1` — **zero** `endFrameJob` / `FRAME_STALL` / `ImageReader` / `FATAL` errors.
- **Stage 2 — Geometry & Controls Gate**:
  - Exercised `com.gtasixty.spatial.SET_DISPLAY` across `curve=25% (R=3.07m)`, `50% (R=1.97m)`, `80% (R=1.38m)`, `100% (R=1.15m)`, and `0% (flat)`: `screenCenter` stayed pinned at `(-1.7474242, 0.46150857, 0.50158775)` with zero `AetherCylinderLayer` errors.
  - Verified `tilt=-30° / +30°`, `height=+0.35 m`, `width=3.0 m` (`height=1.6875 m`), `distance=2.6 m`, `backdrop=Dim / Dark / Passthrough`, `recenter`, and `reset`.
- **Stage 3 — Capture Mode Live Gate (`192.168.68.90:7660`)**:
  - Connected live to `mintbook-g11`, initialized `c2.qti.hevc.decoder` + `CaptureGlRenderer` at `2560x1440` (`mode=4 FG60+SR`, `out=2560x1440`), and streamed `2560x1440 @ 59.8-59.9 fps | 79.2-82.2 Mbps | Feed 0.55-1.17 ms` over `SurfaceView` without needing the `TextureView` fallback.
  - Applied `curve=50% tilt=-20° height=0.2m` and `reset` mid-stream while sustaining `59.9/59.9 fps | 79.8 Mbps`, followed by clean `STOP_STREAM` (`CaptureGlRenderer released`).
- **Stage 4 — Direct Mode Gate**:
  - Launched `SpatialMainActivity --ez direct true`, verified `TriChromeWebView` `156.0.0.38.16` loaded inside the `2560x1440` spatial panel, verified `curve=50% tilt=-15°` and `reset`, and confirmed clean `STOP_STREAM` before restoring Capture default.

