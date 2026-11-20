#pragma once
#include "gta60/media/MfH264Decoder.h"
#include <cstdint>

namespace gta60::media {

// Emits a 60 Hz NV12 stream containing duplicated 30 FPS game frames (A, A, B, B, ...),
// with optional +/-1 LSB simulated H.264 compression noise on duplicate frames,
// moving high-contrast scene geometry, and a static GTA-style corner HUD/minimap.
class SyntheticGameSource {
public:
    SyntheticGameSource(uint32_t width = 1920, uint32_t height = 1080, bool addCompressionJitter = true);

    // containerIndex: 0, 1, 2, ... at 60 Hz (pairs share uniqueFrameIndex = containerIndex / 2).
    DecodedNv12Frame generateContainerFrame(uint64_t containerIndex) const;

    // Expected horizontal pixel X center of the primary moving car/block for unique game frame `u`.
    uint32_t movingBoxCenterX(uint64_t uniqueFrameIndex) const;
    uint32_t movingBoxCenterY(uint64_t uniqueFrameIndex) const;

private:
    uint32_t width_;
    uint32_t height_;
    bool jitter_;
};

} // namespace gta60::media
