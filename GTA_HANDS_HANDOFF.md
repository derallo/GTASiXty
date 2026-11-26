# GTA V Hands + Auto-Spawning Xbox Controller — Implementation Handoff

Companion document to [GTA_HANDS_CONTROLLER_PLAN.md](GTA_HANDS_CONTROLLER_PLAN.md). That file is the
*design*; this one is *what actually shipped, what is verified, and what still needs a headset on
your head*.

---

# 0. LIVE DEBUG SESSION — 2026-11-26 07:00–07:30 (READ THIS FIRST)

**Bottom line: the whole pipeline is built and wired, but Horizon OS is not delivering hand joints to
this app, so nothing can render yet. One confirmed bug was found and fixed (Meta's own hands were being
hidden); the blocker is the runtime's hands-vs-controllers input mode.**

## 0.1 The two distinct problems

### Problem A — CONFIRMED BUG, FIXED — "hands invisible but pointer rays still visible"

The user reported hands vanishing while hand-pointer rays kept working. That was **our** bug, not the
runtime's:

`SpatialMainActivity.updateControllerVisibility()` hides Quest controller *and* hand visuals after 5 s
of controller inactivity, and `setControllerVisualsVisible(false)` called
`avatarSystem.setShowHands(false)`. Hand tracking was alive the whole time — we had simply switched
Meta's hand meshes off, which is also exactly what made the feature look dead.

Fix (already in the tree, already installed): **all** hand-visibility changes now funnel through
`syncAvatarHandVisibility()`, which keeps Meta's translucent hands visible whenever the GTA rig is not
being drawn, and the 5 s idle timer no longer clobbers it:

```kotlin
private fun syncAvatarHandVisibility() {
    val gtaActive = gtaHands?.isDrawingHands() == true
    val desired = !gtaActive && (currentSettings.gtaHandsEnabled || controllerVisualsVisible)
    setAvatarHandsVisible(desired)
}
```

`setControllerVisualsVisible()` now just sets `controllerVisualsVisible` and calls
`syncAvatarHandVisibility()` — do **not** reintroduce a direct `setShowHands(...)` call anywhere else.

### Problem B — NOT RESOLVED — the runtime returns no hand joints at all

`xrLocateHandJointsEXT` returns `XR_SUCCESS` but `isActive = false` with **all 26 joints invalid**, in
**every** reference space, continuously. `act=0 valid=0` for STAGE, LOCAL and LOCAL_FLOOR, every frame,
for the entire session — i.e. the runtime accepts our calls and has nothing to give us.

## 0.2 Evidence collected (all reproducible)

```
GTA60Hands: nativeInit mask=0x3F ready=1                       <- trackers + all 3 spaces + XR_EXT_hand_tracking entry points
GTA60Hands: Created left hand tracker (XrHandTrackerEXT=0x7c)  <- xrCreateHandTrackerEXT succeeds
GTA60Hands: Hand frame #1800 ... stats=1833,0,0,0              <- 1833 locate calls, 0 failures
GTA60Hands: space probe: | STAGE rc=0 act=0 valid=0 Lwrist=(+0.000,+0.000,+0.000) | LOCAL rc=0 act=0 valid=0 ... | LOCAL_FLOOR rc=0 act=0 valid=0 ...
GTA60Hands: No hand joints for 3 probes; recreated trackers (attempt 1, mask=0x03)
GTA60Hands: multimodal supported=0 (system=Meta Quest 3)
GTA60Hands: multimodal: xrResumeSimultaneousHandsAndControllersTrackingMETA unavailable
GTA60Hands: Hand reference space confirmed: STAGE (viewerY=0.617 floorOrigin=true)   <- head pose IS tracked
```

So: session is `XR_SESSION_STATE_FOCUSED`, the head pose is live, permissions are granted
(`com.oculus.permission.HAND_TRACKING: granted=true`), the manifest is correct in the **merged**
manifest, and our entry points all resolve — but no joints arrive.

Runtime-side corroboration that Horizon OS thinks the app is in **controller** mode:

```
D/TrackingService: [HANDTRACKING] Controller ab7728412f886b4b in hand: Y, valid: Y   <- toggles Y/N continuously
I/[SEO] ShellFrameAnalytics: ... handTrackingAutoTransition: 1 ...                    <- auto transition is enabled system-wide
I/OSHandDataQuerier(<pid>): OSSDKTRACKING shutting down handsTracker_                  <- the SDK's own hand source also stops
```

Meta's docs say simultaneous hands + controllers ("multimodal") is the only way to override that
either/or switch, but this runtime reports **`supportsSimultaneousHandsAndControllers = 0`** and does
not expose `xrResumeSimultaneousHandsAndControllersTrackingMETA` through
`xrGetInstanceProcAddr` (the extension was not enabled on the SDK's `XrInstance`). So multimodal is
not available to us on Quest 3 + Spatial SDK 0.15.0.

## 0.3 What the next agent should try, in order

1. **Get the runtime into hands mode before/while the app runs.** The most likely success path:
   take **both Touch controllers out of the room** (or let them sleep untouched for a few minutes),
   then relaunch the app. Watch for
   `space probe: ... valid=N` with `N > 0`, then `First GTA hand frame drawn (L=true R=true)`.
   `adb -s 192.168.68.78:5555 logcat -s GTA60Hands:I` shows the probe every 2 s.
2. **Double-tap the side of the headset** — that is Meta's manual hands↔controllers toggle and it can
   be quicker than waiting for the auto transition.
3. If hands become active but the rig is still invisible, look for the rig warnings instead:
   `Hand rig is missing nodes: [...]`, `setLocalNodeTransformsBatch(hand) failed: ...`,
   `Hand reference space confirmed: LOCAL (...)` (a wrong space puts the hands at your feet / eye
   height away).
4. If `act` stays 0 even with the Touch controllers gone, the remaining untested app-side knob is
   `<uses-feature android:name="oculus.software.handtracking" android:required="true" />` (currently
   `required="false"`). That is the only manifest field not yet A/B tested and it plausibly controls
   whether Horizon OS is willing to run the app in hands mode.
5. `nativeEnableMultimodal()` already exists and is re-asserted every 6 s by the probe loop
   (`requestMultimodal("probe")`). It will start working for free if a future Spatial SDK enables
   `XR_META_simultaneous_hands_and_controllers` on its instance.

## 0.4 Diagnostics that are now built in (keep them)

| What | Where | Notes |
|---|---|---|
| Reference-space probe, every 2 s | `HandTrackingSource.probeSpacesIfDue()` | Logs `act` + valid-joint count + left-wrist position for STAGE / LOCAL / LOCAL_FLOOR, and adopts whichever space actually produces joints. |
| Tracker re-creation | `HandTrackerJni.nativeRecreateTrackers()` | Fires after 3 empty probes, max 5 times, in case the trackers were created before the session was focusable. |
| Multimodal request | `HandTrackerJni.nativeEnableMultimodal()` / `nativeMultimodalSupported()` | Reports support and the `XrResult` of the resume call. |
| Asset-existence guard | `GtaHandsControllerSystem.tryCreateMeshEntity()` | The SDK's GLTF loader throws on a *worker coroutine* for a missing asset and kills the process — a Java `try/catch` cannot save you, so the asset is checked first. |
| Meta-hands fallback | `syncAvatarHandVisibility()` | While the GTA rig is not drawing, Meta's own hands stay visible as a "tracking is alive" indicator. |

Useful one-liners:

```powershell
# all hand diagnostics
adb -s 192.168.68.78:5555 logcat -d -v time -t 600 GTA60Hands:V *:S
# toggle the feature off/on without rebuilding
adb -s 192.168.68.78:5555 shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ez hands false
adb -s 192.168.68.78:5555 shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ez hands true
# nudge the virtual pad once hands work (metres, controller-local)
adb -s 192.168.68.78:5555 shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ef handsUp -0.012 --ef handsBack 0.015
```

## 0.5 Device-side state left behind

* **Android NDK r26d installed** at `%LOCALAPPDATA%\Android\Sdk\ndk\android-ndk-r26d`. Required for
  `scripts/build-hand-tracker.ps1`; `scripts/build-quest-apk.ps1` now runs it automatically.
* `svc power stayon true`, `settings put system screen_off_timeout 2147483647`,
  `settings put global stay_on_while_plugged_in 7` — keeps the headset awake while iterating.
  Harmless; revert with `settings put system screen_off_timeout 60000` if desired.
* `debug.oculus.disableProximitySensor` and `debug.oculus.proximity` were briefly set to `1` during
  debugging and **have been set back to `0`**. Do not leave them at 1.
* `metavr` is **not** on PATH; use `npx -y metavr@latest ...`. There is a stale `emulator-5562` entry
  in `adb devices` that makes metavr's screenshot command fail with "more than one
  device/emulator" — `adb -s 192.168.68.78:5555 kill-server`-style cleanup or passing `--device` is
  needed before `metavr capture screenshot` works.
* The manifest change that **removed** `com.oculus.permission.BODY_TRACKING` and
  `oculus.software.body_tracking` is deliberate: Meta's docs state multimodal/IOBT are mutually
  exclusive, and the SDK's body-tracking path throws
  `Native Assert FILE=utHSR/Body.cpp:59 MSG=Failed to initialize session for body` on this build.
  Do not add them back.

## 0.6 Root-cause summary, one paragraph

Everything on our side works: the manifest is right, the native shim loads and resolves
`XR_EXT_hand_tracking`, hand trackers are created, `xrLocateHandJointsEXT` is called ~90×/s and never
fails, the reference space is resolvable, and the GLB rigs bind 27/27 + 27/27 + 18/18 nodes. What is
missing is joint *data*, because Horizon OS keeps the app in controller input mode while the Touch
controllers are detected as in-hand, and this runtime refuses simultaneous hands+controllers. Fixing
the input mode — not the code — is what will make the hands appear.

---

## 1. Status at a glance

| Phase | Plan item | State |
|---|---|---|
| 1 | Manifest hand-tracking opt-in | **Done** — `com.oculus.permission.HAND_TRACKING`, `oculus.software.handtracking` (`required=false`), `handtracking.frequency=HIGH`, `handtracking.version=V2.0`. Verified in the *merged* manifest and as granted permissions on device. |
| 1 | 26-joint hand poses every frame | **Plumbing done, data BLOCKED.** Native `XR_EXT_hand_tracking` via `libgtahandtracker.so`; `nativeInit mask=0x3F` proves both trackers, all three reference spaces and the extension entry points resolve, and `stats=N,0,0,0` proves `xrLocateHandJointsEXT` is called ~90×/s with zero failures. But the runtime returns `isActive=false` with every joint invalid — see **§0**. |
| 2 | Wrist/thumb hold detector + 6-DoF controller spawn | **Implemented** — needs on-headset tuning (see §6). |
| 2 | One-Euro jitter filtering | **Done** — 3× `OneEuroFilter(1.8 Hz, β=0.04)` on translation, adaptive SLERP on rotation. |
| 3 | Live gamepad → stick/trigger/button animation | **Done** — CV-first inverse kinematics, with `GamepadVisualState` layered on whenever a pad really is talking to the headset. |
| 4 | Skinned GTA V hands | **Partially** — the runtime driving (`setLocalNodeTransformsBatch`) is done and verified (27/27 hand nodes bound). The GLB art is generated procedurally by `scripts/build_gta_hands_and_controller_glb.py` rather than ripped from `ig_jimmydisanto`; the rig accepts a swap-in GTA ped mesh as long as the node names in §4.3 are kept. |
| 5 | Polish / calibration | **Partially** — deterministic reference-space inference + persisted controller nudge offsets; needs a real "in your lap" calibration pass. |

**What is verified on device**

* The APK builds and installs (`scripts\build-quest-apk.ps1`), the spatial app still renders Jimmy's
  Room, the TV stays locked inside the bezel, and the display rate request is honoured.
* `libgtahandtracker.so` loads (12.2 KiB, zero non-system dependencies), OpenXR entry points resolve
  through the Spatial SDK's `xrGetInstanceProcAddr`, and every locate call succeeds.
* All three GLBs parse in the SDK's own loader (`GLTF: Loading GLTF: 'apk:///scenes/gta_hand_left.glb'`)
  and their materials bind (`gta_hand_skin`, `xbox_controller_plastic`).
* `SceneObject.getNodeIndex("<name>")` resolves **27/27** left-hand, **27/27** right-hand and **18/18**
  controller nodes, so the rig is correctly wired.
* The assets are 1570 / 1570 / 1648 triangles (4788 total) — inside the plan's < 5,000 budget.

**What is NOT verified yet — currently BLOCKED on Problem B (§0)**

Nothing below can be exercised until the runtime starts delivering joints. As of 2026-11-26 07:30 the
app has never received a single valid hand joint, so the rig has never drawn a frame on a real hand.

* Whether the inferred reference space (STAGE vs LOCAL) matches the Spatial SDK's world space.
* Whether the dorsal sign in `computeHandBasis()` is right (nails must be on the back of the hand).
* Whether the 250 ms hold detector fires on a real grip (`dPalm` / `dThumb` / `thumbRise` are logged
  every 4 s).
* Whether the virtual controller lands 1:1 on top of the physical pad (`CTRL_UP_OFFSET_M`,
  `CTRL_BACK_OFFSET_M`).
* Whether `setLocalNodeTransformsBatch` produces a visually correct hand (it has only been proven to
  bind all nodes, never to run with real data — the calls are `try/catch`-guarded and log on failure,
  so a failure will be visible as `setLocalNodeTransformsBatch(hand) failed: …`).

### Shipped asset provenance

Sizes and hashes of the three committed GLBs (the generator is deterministic — a clean rebuild
reproduces these byte for byte):

| File | Bytes | Verts | Tris | SHA-256 (first 16 hex) |
|---|---|---|---|---|
| `gta_hand_left.glb` | 320,204 | 963 | 1,570 | `90F02967042E9969` |
| `gta_hand_right.glb` | 320,196 | 963 | 1,570 | `9303C96612308809` |
| `xbox_controller.glb` | 175,364 | 1,093 | 1,648 | `67C1B6D37F2DAE6F` |

Preview renders (gitignored, regenerate with `scripts/render_gta_hands_preview.py`) live in
`tools/gta-hands-preview/`: `hands_pair_front.png`, `hands_pair_palm.png`, `hands_side.png`,
`controller_top.png`, `controller_bottom.png`, `controller_three_quarter.png`,
`hands_holding_controller.png`.

---

## 2. Build & deploy

```powershell
# 1. Generate the three unlit GLB assets (hands + Xbox Series X|S controller) and preview renders
python scripts\build_gta_hands_and_controller_glb.py
python scripts\render_gta_hands_preview.py

# 2. Validate the rig contract the runtime depends on (node names, hierarchy, tri budget, COLOR_0)
python scripts\validate_gta_hands_assets.py

# 3. Compile the native OpenXR shim (needs the Android NDK; auto-run by step 4)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-hand-tracker.ps1

# 4. Build + sign the APK (also rebuilds the shim when the NDK is present)
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-quest-apk.ps1

# 5. Install, launch, jump straight into Jimmy's Room
adb -s 192.168.68.78:5555 install -r -d build\GTA-Sixty-Quest3.apk
adb -s 192.168.68.78:5555 shell am start -n com.gtasixty.spatial/.SpatialMainActivity
adb -s 192.168.68.78:5555 shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ei backdrop 3 --ez recenter true

# 6. Watch the hand-tracking pipeline
adb -s 192.168.68.78:5555 logcat -s GTA60Hands:I GTA60Spatial:I AndroidRuntime:E
```

The NDK is installed at `%LOCALAPPDATA%\Android\Sdk\ndk\android-ndk-r26d` (`android-ndk-r26d-windows`,
665 MB). `scripts\build-hand-tracker.ps1` finds it via `ANDROID_NDK_HOME`, `ANDROID_NDK_ROOT`, or the
SDK's `ndk/` folder. The `.so` lands in `quest-spatial-app/src/main/jniLibs/arm64-v8a/`, which is
gitignored — a fresh clone needs step 3 (and the NDK) to get hand tracking.

---

## 3. File map

### New runtime code

| File | Purpose |
|---|---|
| `quest-spatial-app/src/main/cpp/hand_tracker_jni.cpp` | Thin JNI shim over `xrCreateHandTrackerEXT` / `xrLocateHandJointsEXT` / `xrCreateReferenceSpace`. Resolves every entry point through the SDK's `xrGetInstanceProcAddr` (no link against `libopenxr_loader.so`), so it cannot introduce a second OpenXR loader. `-static-libstdc++` is mandatory: the APK does not ship `libc++_shared.so`. |
| `quest-spatial-app/src/main/cpp/openxr.h`, `openxr_platform_defines.h` | Vendored Khronos OpenXR 1.0.34 headers (needed to compile the shim). |
| `scripts/build-hand-tracker.ps1` | Compiles the shim with NDK clang into `jniLibs/arm64-v8a/libgtahandtracker.so` and asserts the JNI symbols are exported. |
| `HandTrackerJni.kt` | Kotlin binding: joint-index constants, `XrSpaceLocationFlags`, `nativeInit/nativeCreateSpace/nativeLocate/nativeStats`. |
| `HandTrackingSource.kt` | Backend selection (native OpenXR → SDK body tracking), reference-space inference, per-frame joint snapshot in SDK world space. |
| `GamepadVisualState.java` | Thread-safe volatile snapshot of the last physical gamepad event. |
| `GtaHandsControllerSystem.kt` | The whole rig: hold detector, 6-DoF controller solve, One-Euro filter, grip blending, node animation, `OneEuroFilter`. |
| `scripts/build_gta_hands_and_controller_glb.py` | Generates `gta_hand_left.glb`, `gta_hand_right.glb`, `xbox_controller.glb` (unlit, baked `COLOR_0`). |
| `scripts/validate_gta_hands_assets.py` | Asserts the GLB node names/hierarchy/budgets the runtime depends on. Prints the controller socket rest positions. |

### Modified files

* `AndroidManifest.xml` — hand/body-tracking permissions, features and metadata.
* `SpatialMainActivity.kt` — `initGtaHands()`, `updateGtaHands()`, `syncAvatarHandVisibility()`,
  `setAvatarHandsVisible()`, `assetExists()`, `sceneObjectFor()`; gamepad events are mirrored into
  `GamepadVisualState`; `onDestroy()` shuts the rig down; the settings-changed callback reacts to the
  GTA Hands toggle.
* `SpatialDisplayBridge.java` — `gtaHandsEnabled`, `gtaHandsCtrlUpM`, `gtaHandsCtrlBackM` persisted in
  `SharedPreferences("gta60_spatial")`.
* `DisplayControlsView.java` — `GTA Hands: On/Off` button plus Jimmy's-Room guidance text.
* `scripts/build-quest-apk.ps1` — builds the native shim first (soft-fails to the SDK path).
* `.gitignore` — `quest-spatial-app/src/main/jniLibs/`.

### Debug broadcast extras

```powershell
# toggle the whole feature
adb shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ez hands false
# nudge the virtual controller (metres, controller-local: +Y up toward your face, +Z toward your chest)
adb shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ef handsUp -0.010 --ef handsBack 0.012
```

Both values persist in SharedPreferences and clamp to ±0.10 m.

---

## 4. How it works

### 4.1 Two hand-data backends

`HandTrackingSource` tries, in order:

1. **`XR_EXT_hand_tracking` (native, primary).** The Spatial SDK exposes
   `Scene.getOpenXrInstanceHandle/getOpenXrSessionHandle/getOpenXrGetInstanceProcAddrHandle`, so
   `hand_tracker_jni.cpp` can create its own hand trackers and reference spaces. This gives all 26
   joints per hand with per-joint `XrSpaceLocationFlags` and joint radii, needs no `BODY_TRACKING`
   permission, and keeps working while `avatarSystem.setShowHands(false)` hides Meta's translucent
   hands.
2. **`Scene.updateBodyTrackingBuffersAtTime` (zero-JNI fallback).** `JointType` exposes all 52 hand
   joints, so the mapping is built from enum *names* at runtime (`LEFT_HAND_*` / `RIGHT_HAND_*`), not
   from hard-coded ordinals. **This path is currently dead on device:** the SDK throws
   `Native Assert FILE=utHSR/Body.cpp:59 MSG=Failed to initialize session for body`, so
   `HandTrackingSource` gives up after 3 consecutive exceptions instead of paying the cost every
   frame. Do not rely on it.

### 4.2 Reference-space alignment

`HandTrackingSource.refineSpace()` runs once, the first time the SDK reports a real viewer pose:

* eye height ≈ 0 → the SDK's world space behaves like `XR_REFERENCE_SPACE_TYPE_LOCAL`
  → switch to `LOCAL`;
* eye height ≈ 1.2–2.0 m → local-floor/origin semantics → keep `STAGE` (or `LOCAL_FLOOR`).

The choice is logged (`Hand reference space confirmed: …` / `… switched to …`). If the hands appear
consistently offset from your real hands, this inference is the first thing to check; the fallback is
`spaceCalibration` on `HandTrackingSource` plus `handsUp`/`handsBack` for the controller only.

Note `System.nanoTime()` is passed straight through as `XrTime` — Meta's runtime uses CLOCK_MONOTONIC
nanoseconds, and 900/900 locate calls succeeded on device with zero `XR_ERROR_TIME_INVALID`.

### 4.3 Rig contract (what the GLB must contain)

Hands (`gta_hand_left.glb` / `gta_hand_right.glb`), one root `HandRoot`:

```
HandRoot → WRIST → { FOREARM, PALM,
                     THUMB_METACARPAL → THUMB_PROXIMAL → THUMB_DISTAL → THUMB_TIP,
                     INDEX_METACARPAL  → INDEX_PROXIMAL  → INDEX_INTERMEDIATE  → INDEX_DISTAL  → INDEX_TIP,
                     MIDDLE_…, RING_…, LITTLE_… (same chain) }
```

Per-node local frame: **`+Y` points at the next joint**, **`+Z` is dorsal (back of the hand)**,
`+X = +Y × +Z`. `PALM`'s origin is the wrist joint; `FOREARM`'s mesh runs along **`-Y`** from the
wrist. Every phalanx carries its own rigid mesh — no skinning — which is what makes the zero-cost
`setLocalNodeTransformsBatch` path possible.

Controller (`xbox_controller.glb`), root `ControllerRoot` with children
`Body, LB, RB, LT, RT, LS_Stick, RS_Stick, DpadUp, DpadDown, DpadLeft, DpadRight, BtnA, BtnB, BtnX,
BtnY, BtnView, BtnMenu, Guide`. Frame: **`+X` = player's right, `+Y` = face normal (up at your
face), `+Z` = toward your chest** — so LT/RT/LB/RB sit at `-Z` and the grips reach toward `+Z`/`-Y`.

Orientation is rebuilt from joint **positions** (`shortestArc` between consecutive bones, roll carried
down the chain from the palm's dorsal normal), so Meta's joint-orientation convention never matters.

### 4.4 Hold detection (thumb/wrist only)

For each hand we derive a palm frame from `WRIST`, the four proximal knuckles and the index/little
metacarpals. `evaluateGrip()` then computes:

| Metric | Meaning | Gate |
|---|---|---|
| `dPalm` | distance between the two `PALM` joints | enter `0.100–0.265 m`, exit `> 0.300 m` |
| `dThumb` | distance between the two `THUMB_TIP`s | `0.030–0.190 m` |
| `inward` | mean of `dot(-dorsal, palm→other palm)` | soft (`> 0.05` scores a point) |
| `thumbRise` | thumb midpoint height above the wrist midpoint, along the mean dorsal | soft (`> -0.030 m`) |

Entering `HOLDING_CONTROLLER` needs `dPalm` **and** `dThumb` inside their bands **and** a score ≥ 3 of
4, sustained for **250 ms**. Exits: `dPalm > 0.300 m`, `thumbRise < -0.090 m`, or both hands lost for
**650 ms**. Single-hand coasting keeps the controller frozen in place (the plan's "1.0 s" behaviour)
while the blend fades out over **140 ms** (in over **180 ms**).

### 4.5 Controller pose

```
anchorL = lerp(PALM_L, THUMB_METACARPAL_L, 0.45)     anchorR = lerp(PALM_R, THUMB_METACARPAL_R, 0.45)
+X = normalize(anchorR - anchorL)                    // player's right
upHint = 0.60*thumbPlane + 0.80*meanDorsal + 0.45*(0,1,0)   // orthogonalised against +X
+Y = normalize(upHint)                               +Z = +X × +Y   // toward the player's chest
center = mid(anchorL, anchorR) + Y*CTRL_UP_OFFSET_M + Z*CTRL_BACK_OFFSET_M + nudge
```

`thumbPlane` is `mean(thumb tips) - mean(wrists)`, i.e. the live "operating posture" vector; the mean
dorsal reinforces it; the world-up term stops roll from flipping when your hands tilt steeply.

### 4.6 Occlusion handling (per finger)

| Part | Free hands (`controllerBlend == 0`) | Holding the pad |
|---|---|---|
| Forearm / wrist | 100 % CV | 100 % CV (sockets follow the smoothed pose implicitly) |
| Middle / ring / little | 100 % CV | blended (`0.95–1.0 × blend`) toward an ergonomic grip curl |
| Index | 100 % CV | `0.45 × blend` toward the grip pose (it rests on the trigger) |
| Thumbs | 100 % CV | **100 % CV** — they stay visible on the controller face |
| Index knuckle → `LT`/`RT` | — | driven by visible `INDEX_PROXIMAL` flex only |
| Sticks | hidden | tipped toward the real thumb tip when it is within ~6 cm of the cap |
| ABXY / D-pad | hidden | depressed when `THUMB_TIP` is within ~1.6 cm of the button |

When a real gamepad *is* sending events (`GamepadVisualState.hasRecentInput(15_000)`), the analog
axes, triggers, bumpers and buttons win over the CV estimates.

---

## 5. Performance invariants preserved

* All three assets use `KHR_materials_unlit`, `metallicFactor = 0`, a small sRGB `baseColorTexture`
  and linear `COLOR_0` — the same single-texture-fetch path Jimmy's Room uses. No dynamic lights, no
  shadow casters, no PBR.
* Triangle budget: hands ≤ 1,900 each, controller ≤ 2,400 (validator-enforced). The shipped assets are
  **1,570 / 1,570 / 1,648 = 4,788 triangles total**.
* `setLocalNodeTransformsBatch` is called once per rig per frame (3 native calls), writing into
  preallocated `IntArray`/`FloatArray` slot tables built at rig-bind time — **no per-node JNI and no
  per-frame array allocation**.
* `mips = 1`, `enableLayer = true`, `forceSceneTexture = false`, `fragmentShader = ""` on the OpenXR
  panel are untouched, and no per-frame `reshape()` happens because of this feature.

---

## 6. On-headset verification & calibration checklist

1. **Enable hand tracking on the headset**: Settings → Movement Tracking → Hand Tracking **On**, and
   put the Touch controllers down (they suspend hand tracking unless simultaneous mode is on).
2. Launch into Jimmy's Room and watch:
   ```
   GTA60Hands: OpenXR hand tracking init mask=0x3F (left=true right=true stage=true local=true floor=true ext=true)
   GTA60Hands: Hand reference space confirmed: STAGE (viewerY=1.7xx floorOrigin=true)
   GTA60Hands: Bound left hand rig: 27/27 nodes
   GTA60Hands: First GTA hand frame drawn (L=true R=true)
   ```
   If `Hand reference space confirmed` shows the wrong space you will see the hands offset by roughly
   your eye height in Y (or sitting at your feet).
3. **Check the hands track 1:1.** Open the settings panel and toggle to **Passthrough** — the GTA
   hands should sit exactly on your real hands while free. If they are rotated 180°, the dorsal-normal
   sign in `computeHandBasis()` is the knob (`handSign` is `+1` for the right hand, `-1` for the left).
4. **Check the controller spawn.** Hold the physical pad with both hands; within ~250 ms the 3D pad
   appears. The 4-second log line shows the detector's view:
   ```
   GTA60Hands: GTA Hands: OpenXR STAGE L=true R=true holding blend=1.00 dPalm=0.180 dThumb=0.096 viewer=1.68
   ```
   * Not entering: `dPalm`/`dThumb` outside the bands, or `thumbRise` negative because the frame is
     upside down. Loosen `PALM_SPAN_*` / `THUMB_SPAN_*` at the top of `GtaHandsControllerSystem.kt`.
   * Entering but never exiting: tighten `PALM_SPAN_EXIT_M`.
5. **Align the pad with the real one.** Still in Passthrough, move the virtual pad with:
   ```powershell
   adb shell am broadcast -a com.gtasixty.spatial.SET_DISPLAY --ef handsUp -0.012 --ef handsBack 0.015
   ```
   `CTRL_UP_OFFSET_M` (default `-0.018`) sets how far below your thumb tips the pad's mid-plane sits;
   `CTRL_BACK_OFFSET_M` (default `+0.012`) slides it toward your chest so the grips land in your palms.
6. **Verify the sticks/buttons react.** Rest your left thumb near the left stick cap and push — the 3D
   stick should tilt with it (clamped ±22°). Put your right thumb on A — the button should depress.
   Bumpers/triggers follow the index knuckle flex in pure-CV mode.
7. **Watch the frame time.** At 120 Hz + 1800p SR + FG60 the rig must stay under ~0.15 ms. If
   `onSceneTick` regresses, the usual suspect is `thumbInControllerSpace()`/`blendHandsToGrip()` being
   called when `controllerBlend` is 0 — both already early-out.

---

## 7. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `libgtahandtracker.so unavailable (UnsatisfiedLinkError: library "libc++_shared.so" not found)` | The shim was linked dynamically against libc++. Run `scripts\build-hand-tracker.ps1` (it passes `-static-libstdc++ -static-libgcc`). |
| `nativeInit mask=0x00` / `XR_EXT_hand_tracking entry points unavailable` | Hand tracking not enabled on the headset, or `oculus.software.handtracking` missing from the manifest. The SDK only exposes `xrCreateHandTrackerEXT` when the extension was enabled on its `XrInstance`. |
| App dies with `Native Assert … GLTFLoader.cpp: Failed to load 'apk:///scenes/gta_hand_left.glb'` | The GLB is missing from the APK. `GtaHandsControllerSystem.assetExists()` now refuses to create the entity in that case, but the real fix is to run the asset builder before `build-quest-apk.ps1`. Note the SDK throws this on a coroutine worker, so a Java `try/catch` around `Entity.create` does **not** save you. |
| `Failed to initialize session for body` | Expected: the SDK body-tracking fallback is unusable on this build. The native path is the supported one. Also: **never re-add** `BODY_TRACKING` — Meta shares the body-tracking slot with multimodal, and the two are mutually exclusive. |
| `space probe: … act=0 valid=0` for all three spaces, `stats=N,0,0,0` | The runtime is delivering no hand joints. This is an **input-mode** problem, not a code problem — see §0. Get Horizon OS into hands mode (park or power off both Touch controllers, or double-tap the side of the headset), then relaunch. |
| `multimodal supported=0` / `xrResumeSimultaneousHandsAndControllersTrackingMETA unavailable` | This runtime does not support simultaneous hands + controllers for this app, so hands and Touch controllers are strictly either/or and the controllers win. |
| Hand meshes vanished while pointer rays kept working | Our own `setShowHands(false)` after 5 s of controller idle — fixed in §0.1. Keep every hand-visibility change routed through `syncAvatarHandVisibility()`. |
| Hands are visible but frozen at the origin | `handSource.isHandUsable()` requires `WRIST` + `PALM` + `THUMB_TIP` valid; and `computeHandBasis()` additionally requires the four proximal knuckles and both index/little metacarpals. Check the `Hand frame #n …` log for per-joint validity. |
| Hands appear mirrored / nails on the palm side | Flip `handSign` in `computeHandBasis()` (or the GLB's X mirroring). |

---

## 8. Suggested next steps

0. **UNBLOCK HAND DATA FIRST (§0.3).** Nothing else in this list can be validated until
   `xrLocateHandJointsEXT` returns `isActive = true`. Order to try: Touch controllers out of the room
   and relaunch → double-tap the side of the headset → `oculus.software.handtracking` set to
   `required="true"` → re-check whether a newer Spatial SDK enables
   `XR_META_simultaneous_hands_and_controllers` on its instance (our `nativeEnableMultimodal()` then
   starts working automatically — this runtime currently reports `supported=0`).
1. **On-headset calibration pass** (§6) and then bake the tuned `CTRL_UP_OFFSET_M` /
   `CTRL_BACK_OFFSET_M` back into the source defaults.
2. **Real GTA V hand art.** The rig only needs the node names/hierarchy in §4.3, so replacing the
   procedural hands with `ig_jimmydisanto` / `mp_m_freemode_01` `hand_000_u` geometry (trimmed
   forearm→fingertips, baked `COLOR_0`, `KHR_materials_unlit`) is a pure asset swap. The Gen9 RPF7
   extractor already in the repo (`scripts/extract_gtav_enhanced_jimmys_room.py`) is the starting
   point; `tools/` is gitignored, so re-clone `wjy000/gtav-enhanced-rpf` and re-extract the keys.
3. **Thumb clamping.** Currently thumbs are pure CV; the plan asks for a soft clamp so the thumb pads
   never clip through the controller face. A cheap version is to clamp the thumb tip's position in
   `Pose(ctrlPose).inverse()` space to `y >= 0.028 m` before it is used for stick/button picking.
4. **Re-check multimodal on a newer runtime/SDK.** The code path exists; it is only unavailable here.
5. **Elite Series 2 profile.** Grip spacing differs slightly; expose a controller-model preset that
   scales `CTRL_SIDE_OFFSET_M` and the stick socket constants.
