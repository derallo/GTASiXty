#pragma once
// Which video codec the PC encodes and the headset decodes.
//
// This is a *wire* value: it travels in the VIDEO_CONFIG packet (see sink::QuestStreamServer) and in
// the LAN discovery offer, so both ends must agree on the numbering. Append new values, never
// renumber an existing one.
#include <cstdint>

namespace gta60::media {

enum class VideoCodec : uint8_t {
    H264 = 0, // AVC, High profile, 4:2:0 8-bit. The long-standing default path.
    HEVC = 1, // H.265, Main / Main10 profile. Roughly half the bitrate for equal quality.
    AV1  = 2, // AV1, Main profile (8-bit / 10-bit).
};

// Short name used in logs, the discovery offer and the CLI ("H264" / "HEVC" / "AV1").
inline const char* videoCodecName(VideoCodec c) {
    if (c == VideoCodec::AV1) return "AV1";
    return c == VideoCodec::HEVC ? "HEVC" : "H264";
}

// Android MediaCodec MIME type the headset must be told to decode with.
inline const char* videoCodecMime(VideoCodec c) {
    if (c == VideoCodec::AV1) return "video/av01";
    return c == VideoCodec::HEVC ? "video/hevc" : "video/avc";
}

} // namespace gta60::media
