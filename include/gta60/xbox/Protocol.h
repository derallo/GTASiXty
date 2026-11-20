#pragma once
// Xbox home streaming WebRTC input/control/metadata binary wire protocol.
// Adapted from Veyra-NRVideo / Greenlight protocol definitions.

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <optional>
#include <vector>

namespace gta60::xbox {

enum ReportType : uint16_t {
    ReportMetadata = 1,
    ReportGamepad = 2,
    ReportPointer = 4,
    ReportClientMetadata = 8,
    ReportServerMetadata = 16,
    ReportMouse = 32,
    ReportKeyboard = 64,
    ReportVibration = 128,
};

enum GamepadButton : uint16_t {
    BtnNexus = 2,
    BtnMenu = 4,
    BtnView = 8,
    BtnA = 16,
    BtnB = 32,
    BtnX = 64,
    BtnY = 128,
    BtnUp = 256,
    BtnDown = 512,
    BtnLeft = 1024,
    BtnRight = 2048,
    BtnLeftShoulder = 4096,
    BtnRightShoulder = 8192,
    BtnLeftThumb = 16384,
    BtnRightThumb = 32768,
};

struct GamepadFrame {
    uint8_t index = 0;
    uint16_t buttons = 0;
    int16_t leftX = 0, leftY = 0, rightX = 0, rightY = 0; // up is positive
    uint16_t leftTrigger = 0, rightTrigger = 0;           // 0..65535
    bool operator==(const GamepadFrame&) const = default;
};

struct Vibration {
    uint8_t gamepad = 0;
    uint8_t leftMotor = 0, rightMotor = 0, leftTrigger = 0, rightTrigger = 0; // percent 0..100
    uint16_t durationMs = 0, delayMs = 0;
    uint8_t repeat = 0;
};

struct VideoFeedback {
    uint32_t rtpTimestamp = 0;
    uint32_t arrivalMs = 0;
    uint32_t submittedMs = 0;
    uint32_t decodedMs = 0;
    uint32_t renderedMs = 0;
};

namespace detail {
inline void put16(std::vector<uint8_t>& b, size_t at, uint16_t v) {
    b[at] = static_cast<uint8_t>(v);
    b[at + 1] = static_cast<uint8_t>(v >> 8);
}
inline void put32(std::vector<uint8_t>& b, size_t at, uint32_t v) {
    for (int i = 0; i < 4; ++i) b[at + static_cast<size_t>(i)] = static_cast<uint8_t>(v >> (8 * i));
}
inline void putDouble(std::vector<uint8_t>& b, size_t at, double v) {
    uint64_t u = 0;
    std::memcpy(&u, &v, 8);
    for (int i = 0; i < 8; ++i) b[at + static_cast<size_t>(i)] = static_cast<uint8_t>(u >> (8 * i));
}
inline uint16_t get16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
inline uint32_t get32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
} // namespace detail

inline std::vector<uint8_t> clientMetadataReport(uint32_t sequence, double timeMs, uint8_t maxTouchPoints = 1) {
    std::vector<uint8_t> b(15, 0);
    detail::put16(b, 0, ReportClientMetadata);
    detail::put32(b, 2, sequence);
    detail::putDouble(b, 6, timeMs);
    b[14] = maxTouchPoints;
    return b;
}

inline std::vector<uint8_t> videoFeedbackReport(uint32_t sequence, double timeMs, const VideoFeedback& f) {
    std::vector<uint8_t> b(43, 0);
    detail::put16(b, 0, ReportMetadata);
    detail::put32(b, 2, sequence);
    detail::putDouble(b, 6, timeMs);
    b[14] = 1;
    const auto sent = static_cast<uint32_t>(static_cast<uint64_t>(std::max(0.0, timeMs)));
    const uint32_t values[] = {f.rtpTimestamp, f.arrivalMs, f.submittedMs, f.decodedMs, f.renderedMs, sent, sent};
    for (size_t i = 0; i < 7; ++i) detail::put32(b, 15 + 4 * i, values[i]);
    return b;
}

inline std::vector<uint8_t> gamepadReport(uint32_t sequence, double timeMs, const std::vector<GamepadFrame>& pads) {
    std::vector<uint8_t> b(14 + 1 + 23 * pads.size(), 0);
    detail::put16(b, 0, ReportGamepad);
    detail::put32(b, 2, sequence);
    detail::putDouble(b, 6, timeMs);
    size_t at = 14;
    b[at++] = static_cast<uint8_t>(pads.size());
    for (const auto& p : pads) {
        b[at++] = p.index;
        detail::put16(b, at, p.buttons);
        detail::put16(b, at + 2, static_cast<uint16_t>(p.leftX));
        detail::put16(b, at + 4, static_cast<uint16_t>(p.leftY));
        detail::put16(b, at + 6, static_cast<uint16_t>(p.rightX));
        detail::put16(b, at + 8, static_cast<uint16_t>(p.rightY));
        detail::put16(b, at + 10, p.leftTrigger);
        detail::put16(b, at + 12, p.rightTrigger);
        detail::put32(b, at + 14, 1);
        b[at + 18] = 0;
        b[at + 19] = 0;
        b[at + 20] = 0;
        b[at + 21] = 1; // virtual physicality big-endian 1
        at += 22;
    }
    return b;
}

inline GamepadFrame gamepadFromXInput(uint16_t xinputButtons,
                                      uint8_t leftTrigger,
                                      uint8_t rightTrigger,
                                      int16_t thumbLX,
                                      int16_t thumbLY,
                                      int16_t thumbRX,
                                      int16_t thumbRY,
                                      uint8_t index = 0) {
    GamepadFrame f;
    f.index = index;
    struct Map {
        uint16_t from;
        uint16_t to;
    };
    static constexpr Map kMap[] = {
        {0x0001, BtnUp},
        {0x0002, BtnDown},
        {0x0004, BtnLeft},
        {0x0008, BtnRight},
        {0x0010, BtnMenu},          // XINPUT_GAMEPAD_START
        {0x0020, BtnView},          // XINPUT_GAMEPAD_BACK
        {0x0040, BtnLeftThumb},     // XINPUT_GAMEPAD_LEFT_THUMB
        {0x0080, BtnRightThumb},    // XINPUT_GAMEPAD_RIGHT_THUMB
        {0x0100, BtnLeftShoulder},  // XINPUT_GAMEPAD_LEFT_SHOULDER
        {0x0200, BtnRightShoulder}, // XINPUT_GAMEPAD_RIGHT_SHOULDER
        {0x0400, BtnNexus},         // Guide button (XInputGetStateEx)
        {0x1000, BtnA},
        {0x2000, BtnB},
        {0x4000, BtnX},
        {0x8000, BtnY},
    };
    for (const auto& m : kMap) {
        if (xinputButtons & m.from) f.buttons = static_cast<uint16_t>(f.buttons | m.to);
    }
    const auto clampAxis = [](int16_t v) { return v < -32767 ? static_cast<int16_t>(-32767) : v; };
    f.leftX = clampAxis(thumbLX);
    f.leftY = clampAxis(thumbLY);
    f.rightX = clampAxis(thumbRX);
    f.rightY = clampAxis(thumbRY);
    f.leftTrigger = static_cast<uint16_t>(leftTrigger * 257u);
    f.rightTrigger = static_cast<uint16_t>(rightTrigger * 257u);
    return f;
}

inline std::optional<Vibration> parseVibration(const uint8_t* p, size_t n) {
    if (n < 13 || p[0] != ReportVibration) return std::nullopt;
    Vibration v;
    v.gamepad = p[3];
    v.leftMotor = p[4];
    v.rightMotor = p[5];
    v.leftTrigger = p[6];
    v.rightTrigger = p[7];
    v.durationMs = detail::get16(p + 8);
    v.delayMs = detail::get16(p + 10);
    v.repeat = p[12];
    return v;
}

inline bool parseServerMetadata(const uint8_t* p, size_t n, uint32_t* width, uint32_t* height) {
    if (n < 10 || p[0] != ReportServerMetadata) return false;
    *height = detail::get32(p + 2);
    *width = detail::get32(p + 6);
    return true;
}

} // namespace gta60::xbox
