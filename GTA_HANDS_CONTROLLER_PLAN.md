# GTA Hands + Auto-Spawning Xbox Controller (VR Hand Tracking) — Implementation Guide

> **Implementation status (2026-11-25): Phases 1–3 shipped, Phase 4/5 partial.**
> The runtime described below now exists in `quest-spatial-app` and has been verified on a Quest 3
> (`XR_EXT_hand_tracking` resolves, 900/900 `xrLocateHandJointsEXT` calls OK, 27/27 hand nodes and
> 18/18 controller nodes bound). See **[GTA_HANDS_HANDOFF.md](GTA_HANDS_HANDOFF.md)** for the file map,
> the actual rig contract, the reference-space inference, on-headset calibration steps, and the open
> items. Key deviations from this document worth knowing up front:
>
> * **Path 1 (native OpenXR) is the shipping path.** It needed the Android NDK (`r26d`) and a small
>   `.so`; build it with `scripts\build-hand-tracker.ps1`. Path 2
>   (`Scene.updateBodyTrackingBuffersAtTime`) is implemented as a fallback but **is dead on device** —
>   the SDK throws `Failed to initialize session for body`.
> * **No GTA V ped rip yet.** The hand/controller GLBs are generated procedurally by
>   `scripts/build_gta_hands_and_controller_glb.py` with `KHR_materials_unlit` + baked linear
>   `COLOR_0`. The rig is rigid-segment (one mesh per bone) rather than skinned, which keeps the
>   per-frame cost to three `setLocalNodeTransformsBatch` calls.
> * **Controller local frame is `+X` right, `+Y` face normal, `+Z` toward the player's chest** (a
>   right-handed basis cannot have `+Z` pointing at the TV while `+X` is right and `+Y` is up). Triggers
>   therefore sit at `-Z`.

## 1. Goal & User Vision
While sitting inside **Jimmy's Room** (`BACKDROP_JIMMYS_ROOM` in `quest-spatial-app`), enable Quest 3 real-world hand tracking so the app:
1. Sees the user's real hands and renders them as **GTA V hands** (authentic Jimmy De Santa / GTA V Enhanced style, matching the room's baked ambient lighting with minimal GPU hit).
2. Detects when both hands come together to hold the user's **real physical Xbox controller** — relying primarily on **wrists, palms, and thumbs** (since the index/middle/ring/pinky fingers wrap behind the physical controller and are occluded from Quest 3's cameras).
3. Smoothly **spawns a 3D Xbox / GTA controller** between the two hands, aligned 6-DoF to the real controller's position/orientation, with GTA hands gripping it naturally and thumbs/triggers/sticks reacting live to actual gameplay input.

---

## 2. Hard Performance & SDK Invariants (Do Not Break)
- **Swapchain invariant (`SpatialMainActivity.kt`)**: Keep `mips = 1`, `enableLayer = true`, `forceSceneTexture = false`, `fragmentShader = ""` on the OpenXR stream panel.
- **GPU budget (120 Hz + 1800p SR + FG60)**: Keep both GTA hand meshes and the Xbox controller on **`KHR_materials_unlit`** (`FORCE_UNLIT = 1`) with linear baked `COLOR_0` + baked AO matching Jimmy's room (~3,000–5,000 tris total for both hands + controller = **< 0.15 ms** on Quest 3 Adreno 740). Do **not** add dynamic PBR lights or shadow casters.
- **Idle auto-hide interplay**: `SpatialMainActivity.kt` currently calls `avatarSystem.setShowHands(visible)` after 5s of idle (`updateControllerVisibility()`). In Jimmy's Room with GTA Hands enabled, **keep Meta's default translucent hand visuals hidden** (`setShowHands(false)`) while our custom GTA hand entities stay visible whenever hand tracking reports valid poses.

---

## 3. Verified Meta Spatial SDK `0.15.0` APIs & Manifest Setup

### A. Manifest Permissions & Features (`quest-spatial-app/src/main/AndroidManifest.xml`)
Add Quest hand tracking + optional body/joint permissions:
```xml
<uses-permission android:name="com.oculus.permission.HAND_TRACKING" />
<uses-feature
    android:name="oculus.software.handtracking"
    android:required="false" />
<meta-data
    android:name="com.oculus.handtracking.frequency"
    android:value="HIGH" />
<meta-data
    android:name="com.oculus.handtracking.version"
    android:value="V2.0" />
```
*(Optional if using `Scene.updateBodyTrackingBuffersAtTime`: also add `com.oculus.permission.BODY_TRACKING` + `oculus.software.body_tracking`, though direct OpenXR `XR_EXT_hand_tracking` is lighter and doesn't require body-tracking consent prompts.)*

### B. Reading Real-Time Hand Joints (Two Verified Paths in SDK `0.15.0`)
Inspecting `meta-spatial-sdk-0.15.0.aar` & `meta-spatial-sdk-toolkit-0.15.0.aar` reveals two concrete ways to get per-joint world poses every `onSceneTick()`:

1. **Path 1 (Recommended — Pure OpenXR `XR_EXT_hand_tracking` via ~120-line JNI helper)**:
   - `com.meta.spatial.runtime.Scene` directly exposes raw OpenXR handles to Kotlin:
     - `scene.getOpenXrInstanceHandle(): Long`
     - `scene.getOpenXrSessionHandle(): Long`
     - `scene.getOpenXrGetInstanceProcAddrHandle(): Long`
   - Why this is best for holding a real Xbox controller:
     - Calls `xrCreateHandTrackerEXT` (left + right) and `xrLocateHandJointsEXT` every frame.
     - Returns all **26 OpenXR joints per hand** (`XR_HAND_JOINT_PALM_EXT`, `WRIST`, `THUMB_METACARPAL/PROXIMAL/DISTAL/TIP`, plus the 4 fingers) **along with exact per-joint validity flags (`POSITION_VALID | ORIENTATION_VALID | POSITION_TRACKED`) and joint radii**.
     - Does **not** require `BODY_TRACKING` permission and works even when `avatarSystem.setShowHands(false)` hides Meta's default translucent hands!
     - Note: Check if Meta Spatial SDK already enables `XR_EXT_hand_tracking` on the `XrInstance` (it does when `oculus.software.handtracking` is declared in the manifest; verify with `xrGetInstanceProcAddr(instance, "xrCreateHandTrackerEXT")` and fail gracefully if unavailable).

2. **Path 2 (Zero-JNI Kotlin Fallback — `Scene` Body/Hand Buffers or `AvatarBody`)**:
   - `Scene` provides:
     - `scene.setBodyTrackingJointSet(JointSet.DEFAULT)`
     - `scene.updateBodyTrackingBuffersAtTime(poses, skeleton, time)` which populates `JointType.LEFT_HAND_WRIST`, `LEFT_HAND_PALM`, `LEFT_HAND_THUMB_*`, `RIGHT_HAND_WRIST`, `RIGHT_HAND_PALM`, `RIGHT_HAND_THUMB_*`, etc.
   - Alternatively, query `AvatarBody` (`leftHand: Entity`, `rightHand: Entity`) and read node matrices via `SceneObject.getNodeGlobalMatrix(nodeName)` on the SDK's hand scene objects — though note `setShowHands(false)` may pause updating hidden avatar meshes, so test first!

### C. Driving Custom Skinned GTA Hand Meshes
`metaSpatialSdkDefaultVertex.glsl` explicitly supports GPU-skinned glTF 2.0 meshes (`VERTEX_FORMAT_SKINNED == 1`, `JOINTS_0`, `WEIGHTS_0`), and `SceneObject` exposes fast batched bone updates:
- `sceneObject.setLocalNodeTransformsBatch(nodeIndices: IntArray, transforms: FloatArray, stride: Int)`
- `sceneObject.setLocalNodePoses(poses: Map<Int, Pose>)`
- `sceneObject.getNodeIndex(name: String): Int`

This lets us drive a single skinned left-hand `.glb` and skinned right-hand `.glb` at 120 Hz with **zero allocations per frame**.

---

## 4. Sourcing Authentic GTA V Assets (Already on This PC!)
The repo already includes a working Gen9 RPF7 extractor ([extract_gtav_enhanced_jimmys_room.py](scripts/extract_gtav_enhanced_jimmys_room.py)) pointing to `D:\Program Files (x86)\Steam\steamapps\common\Grand Theft Auto V Enhanced`:
1. **GTA Hands**:
   - Extract Jimmy's actual ped drawable (`ig_jimmydisanto` / `cs_jimmydisanto` in `x64e.rpf` / `x64v.rpf`, or `mp_m_freemode_01` first-person hands/forearms `hand_000_u` + `uppr_000_u` skin textures).
   - Trim to forearm-to-fingertips (wrist + forearm cuff so hands don't look weirdly severed at the wrist), bake matching warm/cool Jimmy's Room linear `COLOR_0` lighting (unlit!), and rig to a clean 21-joint OpenXR hand skeleton (`WRIST` + 5 fingers × 4 joints) in GLB format (`quest-spatial-app/src/main/assets/scenes/gta_hand_left.glb` & `gta_hand_right.glb`).
   - *Fast MVP milestone*: Before skinning a full GTA ped mesh, validate the entire tracking + thumb-based controller spawn pipeline with a low-poly stylized GTA-textured segmented hand (palm + forearm + 15 articulated phalanx capsules with GTA skin texture), then swap in the skinned GTA V `.glb`.
2. **Xbox / GTA Controller Model (`xbox_controller.glb`)**:
   - Either extract GTA V's real gamepad prop **`prop_controller_01.ydr`** (the exact controller Jimmy holds in his room!) OR craft/adapt a clean ~1,800-tri **Xbox Series X|S controller GLB** (exact real-world width `15.3 cm`, grip center spacing `11.4 cm`, thumbstick positions matching physical ergonomics!).
   - Make the **Left Thumbstick**, **Right Thumbstick**, **D-Pad**, **LT/RT triggers**, **LB/RB bumpers**, and **A/B/X/Y** separate named glTF nodes inside `xbox_controller.glb` so `SceneObject.setLocalNodePose()` animates them in real time from gamepad input!
   - Bake subtle linear `COLOR_0` shading matching Jimmy's Room (cool TV top sheen + dark grip AO + glowing white Xbox guide button).

---

## 5. Thumb-First Controller Detection & 6-DoF Alignment (The Core Algorithm)

When holding a real Xbox controller in Quest 3, **index/middle/ring/pinky fingers are mostly hidden behind the plastic grips**, whereas **wrists, palms, and thumbs remain reliably visible** to Quest's front/bottom cameras. Design the entire state machine around **wrists + palms + thumbs**, fused with live Xbox gamepad events!

### A. Detecting "Holding Controller" State (Hysteresis State Machine)
Every `onSceneTick()` (~120 Hz), extract for Left (`L`) and Right (`R`) hands:
- `wristL`, `wristR` (`WRIST`)
- `palmL`, `palmR` (`PALM`)
- `thumbBaseL/R` (`THUMB_METACARPAL` / `THUMB_PROXIMAL`)
- `thumbTipL/R` (`THUMB_TIP`)
- Tracking confidence / validity flags

Compute **thumb- & wrist-centric grip metrics** (completely independent of occluded lower fingers!):
1. **Wrist/Palm Span**: `dPalm = length(palmR - palmL)` — holding a real Xbox controller puts `dPalm ∈ [0.11 m, 0.25 m]` (typical `~0.16–0.19 m`).
2. **Thumb Span**: `dThumb = length(thumbTipR - thumbTipL)` — on an Xbox controller, thumbs sit ~`5–15 cm` apart (`dThumb ∈ [0.035 m, 0.17 m]`).
3. **Thumbs Above Palms (Operating Posture)**: Thumb bases/tips sit above the wrist-to-palm axis (facing the viewer / upward toward the controller face), and left/right palms face roughly inward toward each other (`dot(palmNormalL, normalize(palmR - palmL)) > 0.20` and vice versa).
4. **IMPORTANT — Primary Mode is Capture Card (No Gamepad Telemetry on Quest!)**:
   - The user usually pairs their Xbox controller **directly to the Xbox** (using the Elgato 4K S capture card for video), meaning **Quest 3 receives ZERO Bluetooth/USB gamepad events** in normal play.
   - Therefore, detection, 6-DoF alignment, and thumb/stick animation MUST work **100% from Quest 3 optical hand tracking alone** (pure CV)!
   - **Optional Enhancement (Direct / PC Mode)**: If `SpatialMainActivity.dispatchKeyEvent` / `dispatchGenericMotionEvent` *does* receive live gamepad events (`lastInputUptimeMs < 15s`), use them as an optional bonus to sharpen trigger curl, button presses, and dropout latching — never require them!
5. **Anti-Flicker Hysteresis (Tuned for Pure CV)**:
   - **Enter `HOLDING_CONTROLLER`**: wrist/palm/thumb grip criteria satisfied continuously for **`250 ms`**.
   - **Exit `HOLDING_CONTROLLER`**: hands clearly separate (`dPalm > 0.30 m`), thumbs drop far below grip posture, OR tracking is lost for **both** hands continuously for **`650 ms`**.
   - **Single-Hand Coasting**: While holding a physical controller, one hand occasionally flickers out of Quest camera view (e.g., resting low in lap). If `HOLDING_CONTROLLER` is active and only 1 hand is tracked, hold the controller locked to the surviving hand's grip socket offset for up to `1.0 s` before fading out.
   - Smoothly animate `controllerBlend ∈ [0.0, 1.0]` over `180 ms` (scale/fade controller in and blend fingers from CV tracking → ergonomic grip pose).

### B. Estimating 6-DoF Xbox Controller Pose from Wrists + Thumbs
Because thumbs sit directly on top of the controller face (Left Stick / D-Pad on the left, Right Stick / ABXY on the right), **wrists + thumb bases** define a rock-solid 3D coordinate frame even when every other finger is invisible:
1. **Anchor Points**:
   - `anchorL = lerp(palmL, thumbBaseL, 0.45f)`
   - `anchorR = lerp(palmR, thumbBaseR, 0.45f)`
2. **Controller Center Position**:
   - `ctrlCenter = (anchorL + anchorR) * 0.5f + ctrlForward * forwardOffsetM + ctrlUp * upOffsetM`
   - Calibrate `forwardOffsetM` (~`+0.025 m`) and `upOffsetM` (~`-0.015 m`) once against a real Xbox controller in passthrough so the virtual sticks land right underneath your physical thumbs!
3. **Controller Orientation (`Quaternion`)**:
   - **Right axis (`+X`)**: `xAxis = normalize(anchorR - anchorL)`
   - **Up/Face-normal hint (`upHint`)**:
     - Combine the thumb plane vector (`((thumbTipL + thumbTipR) * 0.5f) - ((wristL + wristR) * 0.5f)`) with the averaged dorsal/radial palm up-vectors, gently biased toward viewer-up (`(0, 1, 0)`) by `0.25` so roll/pitch never flips when hands tilt steeply.
   - **Forward axis (`+Z`, pointing toward the TV)**: `zAxis = normalize(cross(xAxis, upHint))`
   - **Orthogonal Up axis (`+Y`, normal to ABXY face)**: `yAxis = normalize(cross(zAxis, xAxis))`
   - Build `Quaternion` from orthonormal basis `(xAxis, yAxis, zAxis)`.
4. **Jitter Filtering (Essential for Comfort)**:
   - Run a **One-Euro Filter** (minCutoff ≈ `1.8 Hz`, beta ≈ `0.04`) on `ctrlCenter` and SLERP-filtered `ctrlRot`. Zero lag when moving hands quickly, zero shimmer when resting in your lap.
   - **Tracking Dropout Coasting**: If one hand briefly loses CV tracking while gripping the controller, hold the controller rigid relative to the still-tracked hand (or freeze gently in lap space if both drop briefly while gamepad input is still active).

---

## 6. Occlusion-Proof Hand & Controller Animation (Pure CV Primary + Optional Gamepad Fusion)

Because the physical Xbox controller is usually paired directly to the Xbox, design **pure Quest 3 CV animation** first, with live gamepad input layered on only when available:

| Body Part | Free-Hand Mode (`controllerBlend == 0`) | Controller Mode — Pure CV (Primary / Capture Mode) | Optional Bonus (When Gamepad Paired to Quest) |
|---|---|---|---|
| **Forearm & Wrist** | 100% Quest 3 CV tracking | Anchored smoothly to left/right controller grip sockets (following smoothed `ctrlPose`) | Same |
| **Middle / Ring / Pinky** | 100% Quest 3 CV tracking | **Locked ergonomic grip wrap pose** around the left/right grips (ignores noisy occluded CV fingertips!) | Same |
| **Index Fingers** | 100% Quest 3 CV tracking | Rest naturally on `LT`/`RT` triggers; apply gentle clamped curl driven ONLY by visible **`INDEX_PROXIMAL` knuckle flex** (ignore occluded distal/tip joints so they never jitter!) | Exact trigger pull (`AXIS_LTRIGGER`/`RTRIGGER`) & bumper tap (`L1`/`R1`) |
| **Left & Right Thumbs** | 100% Quest 3 CV tracking | **100% Live Quest 3 CV Thumb Tracking** (clamped softly so thumb pads don't clip through the controller face!) — thumbs stay clearly visible on top of the controller | Blend with exact stick/ABXY/D-pad targets |
| **3D Controller Sticks & Buttons** | Hidden (`Visible(false)`) | **CV-Driven Inverse Stick Tilt!** Transform `thumbTipL`/`thumbTipR` into controller-local space: when a thumb tip sits within `~2.5 cm` of a stick cap, tilt that 3D stick toward the thumb's `(dx, dz)` offset (clamped ±22°), and subtly depress A/B/X/Y when `thumbTipR` dips over a button! | Driven directly by live analog axes & button presses |

---

## 7. Codebase Integration Map (`quest-spatial-app/`)

| File | Changes Needed |
|---|---|
| [AndroidManifest.xml](quest-spatial-app/src/main/AndroidManifest.xml) | Add `com.oculus.permission.HAND_TRACKING`, `oculus.software.handtracking` (`required="false"`), `HIGH` frequency / `V2.0` metadata. |
| `src/main/cpp/hand_tracker_jni.cpp` + `CMakeLists.txt` *(new, ~150 lines)* | Thin JNI wrapper using `scene.getOpenXrInstanceHandle()` / `getOpenXrSessionHandle()` / `getOpenXrGetInstanceProcAddrHandle()` to call `xrCreateHandTrackerEXT` & `xrLocateHandJointsEXT`, returning a flat `FloatArray` (`26 joints × 8 floats [px,py,pz,qx,qy,qz,qw,valid]` per hand) directly to Kotlin with zero per-frame allocations. |
| `src/main/java/com/gtasixty/spatial/GamepadVisualState.java` *(new)* | Thread-safe volatile snapshot updated from `SpatialMainActivity.dispatchKeyEvent` & `dispatchGenericMotionEvent` (sticks, triggers, bumpers, D-pad, ABXY, `lastInputUptimeMs`). |
| `src/main/java/com/gtasixty/spatial/GtaHandsControllerSystem.kt` *(new)* | Manages `leftHandEntity`, `rightHandEntity`, `xboxControllerEntity`, One-Euro pose smoothing, thumb/wrist hold detector, hybrid CV+gamepad bone solver, and `SceneObject.setLocalNodeTransformsBatch()` updates. |
| [SpatialMainActivity.kt](quest-spatial-app/src/main/java/com/gtasixty/spatial/SpatialMainActivity.kt) | Tick `GtaHandsControllerSystem` inside `onSceneTick()` when `isJimmysRoomActive()` (and optionally in Passthrough/Dim/Dark if toggled); keep `avatarSystem.setShowHands(false)` while GTA hands are active; feed `GamepadVisualState` from `dispatchKeyEvent` / `dispatchGenericMotionEvent`. |
| [SpatialDisplayBridge.java](quest-spatial-app/src/main/java/com/gtasixty/spatial/SpatialDisplayBridge.java) & [DisplayControlsView.java](quest-spatial-app/src/main/java/com/gtasixty/spatial/DisplayControlsView.java) | Add a simple toggle (e.g., `GTA Hands: On / Off`, persisted in `DisplaySettings`) plus optional fine-tune offset if needed. |
| `scripts/build_gta_hands_and_controller_glb.py` *(new)* | Extracts GTA V Enhanced hand geometry/textures + `prop_controller_01` (or clean Xbox Series X|S rigged GLB), bakes matching Jimmy's Room linear `COLOR_0` lighting (`KHR_materials_unlit`), exports `gta_hand_left.glb`, `gta_hand_right.glb`, `xbox_controller.glb` into `quest-spatial-app/src/main/assets/scenes/`. |

---

## 8. Recommended Phased Rollout for the Next Agent
1. **Phase 1 — Hand Tracking Plumb & Debug Dots**: Wire `HAND_TRACKING` manifest + `hand_tracker_jni.cpp` (or `updateBodyTrackingBuffersAtTime` fallback). Verify live 26-joint poses on Quest 3 by rendering tiny unlit spheres on wrists & thumb tips (`adb logcat -s GTA60Hands:I`).
2. **Phase 2 — Thumb-Based Controller Spawner**: Implement the wrist+thumb hold detector, 6-DoF basis math, One-Euro filter, and spawn `xbox_controller.glb` between the hands. Test in Passthrough mode first so you can visually verify 1:1 overlap with the real physical Xbox controller!
3. **Phase 3 — Live Gamepad Button/Stick Animation**: Hook `GamepadVisualState` to animate the 3D thumbsticks, triggers, bumpers, and ABXY buttons on `xbox_controller.glb`.
4. **Phase 4 — Skinned GTA Hands + Grip Blending**: Export `gta_hand_left.glb` & `gta_hand_right.glb` from GTA V Enhanced assets (`KHR_materials_unlit`, linear baked lighting matching Jimmy's room), drive bones via `setLocalNodeTransformsBatch()`, and smoothly blend between free CV hands and live-input-driven controller grip poses.
5. **Phase 5 — Polish & Comfort**: Add optional subtle wrist-only pass-through calibration tweak (if needed for different controller models like Elite Series 2 vs standard Xbox) and verify locked 120 Hz + zero regressions on `mips = 1` OpenXR streaming.
