#pragma once
#include "gta60/media/MfH264Decoder.h"
#include "gta60/media/VideoCodec.h"
#include "gta60/pipeline/D3D12Context.h"

#include <cstdint>
#include <functional>
#include <memory>
#include <vector>

namespace gta60::sink {

struct NvencConfig {
    uint32_t width = 2560;
    uint32_t height = 1440;
    uint32_t fps = 60;
    uint32_t bitrateMbps = 50;
    // NVENC preset 1 (fastest) .. 7 (slowest/best quality). P4 has always been the default; P1-P3
    // are worth measuring because encode latency is a large share of the PC-side budget.
    uint32_t presetIndex = 4;
    // H.264 keeps the measured baseline; HEVC buys roughly half the bitrate for equal quality at the
    // same headset cap. Rate control, GOP and latency settings are identical for both.
    media::VideoCodec codec = media::VideoCodec::H264;
};

using EncodedPacketCallback = std::function<void(const uint8_t* data, size_t size,
                                                   uint32_t timestampMs, bool isIdr, bool isGenerated)>;

// Per-frame breakdown of where encodeFrame() spends its time (milliseconds, averaged since the
// previous call to consumeTimings). Used to tell fixed submission/round-trip cost apart from the
// actual NVENC encode, which is what decides whether an optimisation is worth making.
struct EncodeTimings {
    double submitMs = 0.0; // record + ExecuteCommandLists (includes allocator fence wait)
    double picMs = 0.0;    // nvEncEncodePicture submission
    double waitMs = 0.0;   // waiting for the bitstream-ready fence (the GPU round trip)
    double lockMs = 0.0;   // nvEncLockBitstream
    double cbMs = 0.0;     // consumer copy (e.g. into the TCP queue) + unlock
    uint64_t frames = 0;
};

class NvencD3D12Encoder {
public:
    NvencD3D12Encoder();
    ~NvencD3D12Encoder();
    NvencD3D12Encoder(const NvencD3D12Encoder&) = delete;
    NvencD3D12Encoder& operator=(const NvencD3D12Encoder&) = delete;

    bool initialize(pipeline::D3D12Context& ctx, const NvencConfig& cfg);
    void shutdown();

    // Converts rgbTex (R8G8B8A8_UNORM or B8G8R8A8_UNORM) -> NV12 on GPU, encodes via D3D12 NVENC,
    // waits for the ultra-low-latency bitstream, and invokes `cb`.
    bool encodeFrame(ID3D12Resource* rgbTex, uint32_t timestampMs, bool isGenerated, const EncodedPacketCallback& cb);

    // Zero-shader direct NV12 fast path (used when input resolution/FPS matches output with no FG/upscale).
    // Uploads Y+UV planes straight into the registered NVENC D3D12 NV12 surface and encodes immediately.
    bool encodeNv12Frame(const media::DecodedNv12Frame& frame, uint32_t timestampMs, bool isGenerated,
                         const EncodedPacketCallback& cb);

    void requestIdr();
    // Annex-B parameter sets for the current session: SPS+PPS for H.264, VPS+SPS+PPS for HEVC.
    // Concatenated with 4-byte start codes, which is exactly what the headset splits into csd-N.
    const std::vector<uint8_t>& spsPps() const;
    media::VideoCodec codec() const;
    bool initialized() const;

    // Returns and resets the accumulated per-frame timing breakdown.
    EncodeTimings consumeTimings();

private:
    struct Impl;
    std::unique_ptr<Impl> p_;
};

} // namespace gta60::sink
