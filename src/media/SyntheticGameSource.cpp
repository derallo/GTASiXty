#include "gta60/media/SyntheticGameSource.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace gta60::media {

SyntheticGameSource::SyntheticGameSource(uint32_t width, uint32_t height, bool addCompressionJitter)
    : width_(width), height_(height), jitter_(addCompressionJitter) {}

uint32_t SyntheticGameSource::movingBoxCenterX(uint64_t uniqueFrameIndex) const {
    const uint32_t startX = width_ / 6;
    const uint32_t span = (width_ * 2) / 3;
    return startX + static_cast<uint32_t>((uniqueFrameIndex * 16) % span);
}

uint32_t SyntheticGameSource::movingBoxCenterY(uint64_t uniqueFrameIndex) const {
    const int offset = static_cast<int>(std::sin(static_cast<double>(uniqueFrameIndex) * 0.08) * 60.0);
    return height_ / 2 + static_cast<uint32_t>(offset);
}

DecodedNv12Frame SyntheticGameSource::generateContainerFrame(uint64_t containerIndex) const {
    const uint64_t u = containerIndex / 2; // 30 FPS game inside 60 Hz container
    const bool isDuplicate = (containerIndex & 1ULL) != 0;

    DecodedNv12Frame f;
    f.width = width_;
    f.height = height_;
    f.stride = width_;
    f.pts100ns = static_cast<int64_t>(containerIndex) * 166667;
    f.yPlane.resize(static_cast<size_t>(width_) * height_, 32);
    f.uvPlane.resize(static_cast<size_t>(width_) * (height_ / 2), 128);

    const uint32_t scrollX = static_cast<uint32_t>(u * 8);
    const uint32_t cx = movingBoxCenterX(u);
    const uint32_t cy = movingBoxCenterY(u);

    // Draw background sky + scrolling grid road + moving textured vehicle block + static bottom-left minimap HUD
    for (uint32_t y = 0; y < height_; ++y) {
        uint8_t* row = f.yPlane.data() + static_cast<size_t>(y) * width_;
        const bool roadZone = (y > height_ / 2);
        const uint8_t baseY = roadZone ? static_cast<uint8_t>(45 + ((y - height_ / 2) / 16)) : static_cast<uint8_t>(85 - (y / 14));
        std::memset(row, baseY, width_);

        // Scrolling vertical perspective stripes so optical flow has rich global background texture
        if (roadZone) {
            for (uint32_t x = (scrollX % 64); x < width_; x += 64) {
                if (x + 6 < width_) std::memset(row + x, 130, 6);
            }
        } else {
            for (uint32_t x = ((scrollX / 2) % 128); x < width_; x += 128) {
                if (x + 12 < width_ && y > height_ / 4) std::memset(row + x, 110, 12);
            }
        }
    }

    // Primary moving textured vehicle block (160x100) with non-periodic shading & details
    const int boxW = 160, boxH = 100;
    const int x0 = std::max(0, static_cast<int>(cx) - boxW / 2);
    const int y0 = std::max(0, static_cast<int>(cy) - boxH / 2);
    const int x1 = std::min(static_cast<int>(width_), static_cast<int>(cx) + boxW / 2);
    const int y1 = std::min(static_cast<int>(height_), static_cast<int>(cy) + boxH / 2);
    for (int y = y0; y < y1; ++y) {
        for (int x = x0; x < x1; ++x) {
            const int lx = x - x0;
            const int ly = y - y0;
            // Non-periodic car silhouette: sloped gradient body + dark windshield + bright stripes
            uint8_t luma = static_cast<uint8_t>(145 + (lx * 90) / boxW);
            if (lx > 35 && lx < 115 && ly > 18 && ly < 52) {
                luma = static_cast<uint8_t>(55 + ((lx + ly) % 23)); // tinted windshield/cabin
            } else if (ly > 64 && ly < 74) {
                luma = 240; // racing stripe
            } else if (((lx + ly * 2) % 29) < 4) {
                luma = 215; // non-16-periodic diagonal highlight
            }
            f.yPlane[static_cast<size_t>(y) * width_ + static_cast<size_t>(x)] = luma;
            if ((y & 1) == 0 && (x & 1) == 0) {
                const size_t uvIdx = static_cast<size_t>(y / 2) * width_ + static_cast<size_t>(x);
                f.uvPlane[uvIdx] = 90;      // U
                f.uvPlane[uvIdx + 1] = 220; // V (bright red/orange sports car tint)
            }
        }
    }

    // Static GTA-style bottom-left minimap HUD (completely stationary across frames)
    const uint32_t hudX0 = width_ / 40;
    const uint32_t hudY0 = (height_ * 76) / 100;
    const uint32_t hudW = width_ / 7;
    const uint32_t hudH = height_ / 5;
    for (uint32_t y = hudY0; y < std::min(height_, hudY0 + hudH); ++y) {
        for (uint32_t x = hudX0; x < std::min(width_, hudX0 + hudW); ++x) {
            const bool border = (x < hudX0 + 4 || x >= hudX0 + hudW - 4 || y < hudY0 + 4 || y >= hudY0 + hudH - 4);
            const bool crosshair = (x > hudX0 + hudW / 2 - 4 && x < hudX0 + hudW / 2 + 4 &&
                                    y > hudY0 + hudH / 2 - 4 && y < hudY0 + hudH / 2 + 4);
            f.yPlane[static_cast<size_t>(y) * width_ + x] = border ? 245 : (crosshair ? 235 : 65);
            if ((y & 1) == 0 && (x & 1) == 0) {
                const size_t uvIdx = static_cast<size_t>(y / 2) * width_ + x;
                f.uvPlane[uvIdx] = crosshair ? 200 : 128;
                f.uvPlane[uvIdx + 1] = crosshair ? 80 : 128;
            }
        }
    }

    // Simulated +/-1 LSB video encoder quantization jitter on repeated container frames
    if (jitter_ && isDuplicate) {
        for (size_t i = 0; i < f.yPlane.size(); i += 17) {
            const uint8_t v = f.yPlane[i];
            f.yPlane[i] = (v < 254) ? static_cast<uint8_t>(v + 1) : static_cast<uint8_t>(v - 1);
        }
    }

    return f;
}

} // namespace gta60::media
