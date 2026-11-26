# Agent & Contributor Instructions — Merge Into This Project

**Please merge your work into this project (`GTASiXty`) instead of creating another separate project or standalone app.**

Whether you are a human contributor or an AI coding agent picking up a feature, bug fix, shader improvement, or VR scene:

1. **Extend this repository in place** — do not scaffold a parallel repo, duplicate Android app, or fork off a separate APK package ID.
2. **One Quest 3 APK (`com.gtasixty.spatial`)** — all Quest 3 features (Capture, Direct, PC relay, Spatial MR display, Jimmy's Room 3D VR scene, GTA Hands, Adreno 740 upscaling & frame generation) live together inside `quest-spatial-app/` and build to `build/GTA-Sixty-Quest3.apk`.
3. **One Linux capture server (`linux-capture/`) and one Windows streamer (`src/`, `include/`)** — keep protocol changes (`GT60` TCP 7660 / UDP 7661 / UDP 7662) synchronized across all components in this repo.
4. **Open a Pull Request / merge back into `main`** when your changes are tested and verified.

## Key Reference Docs in This Repo
- [AGENTS.md](../AGENTS.md) — Architecture, GT60 protocol, Linux capture box setup, and build/test commands
- [README.md](../README.md) — Project overview, Jimmy's Room, upscaling/frame-gen pipeline, and setup guide
- [SPATIAL_APP_PLAN.md](../SPATIAL_APP_PLAN.md) — Quest 3 Meta Spatial SDK invariants (`mips = 1`, zero-copy swapchain, cylinder math)
- [JIMMYS_ROOM_HANDOFF.md](../JIMMYS_ROOM_HANDOFF.md) — Jimmy's Room 3D VR scene asset pipeline and runtime TV-lock invariants
- [GTA_HANDS_HANDOFF.md](../GTA_HANDS_HANDOFF.md) — Experimental OpenXR GTA V hands + Xbox controller tracking handoff
- [LATENCY_OPTIMIZATION_NOTES.md](../LATENCY_OPTIMIZATION_NOTES.md) — Latency measurements and pipeline pitfalls
