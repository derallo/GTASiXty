#include "VaapiEncoder.hpp"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <vector>

namespace gta60_linux {

std::vector<uint8_t> buildAv1CodecConfigRecord(const uint8_t* seqHdrObu, size_t len, bool tenBit) {
    if (!seqHdrObu || len == 0) return {};
    if (len >= 4 && seqHdrObu[0] == 0x81) {
        return std::vector<uint8_t>(seqHdrObu, seqHdrObu + len);
    }
    std::vector<uint8_t> rec(4 + len);
    rec[0] = 0x81; // marker=1 (bit7), version=1 (bits 6..0)
    rec[1] = 0x0D; // seq_profile=0 (Main), seq_level_idx_0=13 (Level 5.1)
    rec[2] = tenBit ? 0x4C : 0x0C; // high_bitdepth, chroma_subsampling_x=1, chroma_subsampling_y=1
    rec[3] = 0x00;
    std::memcpy(rec.data() + 4, seqHdrObu, len);
    return rec;
}

namespace {

const char* encoderNameFor(VideoCodec codec) {
    switch (codec) {
        case VideoCodec::AV1: return "av1_vaapi";
        case VideoCodec::HEVC: return "hevc_vaapi";
        default: return "h264_vaapi";
    }
}

void promoteNv12ToP010(const uint8_t* yPlane, int yStride,
                       const uint8_t* uvPlane, int uvStride,
                       uint8_t* dstY, int dstYStride,
                       uint8_t* dstUv, int dstUvStride,
                       uint32_t width, uint32_t height) {
    for (uint32_t y = 0; y < height; ++y) {
        const uint8_t* srcRow = yPlane + static_cast<size_t>(y) * yStride;
        auto* dstRow = reinterpret_cast<uint16_t*>(dstY + static_cast<size_t>(y) * dstYStride);
        for (uint32_t x = 0; x < width; ++x) {
            dstRow[x] = static_cast<uint16_t>(srcRow[x]) << 8;
        }
    }
    const uint32_t uvHeight = height / 2;
    for (uint32_t y = 0; y < uvHeight; ++y) {
        const uint8_t* srcRow = uvPlane + static_cast<size_t>(y) * uvStride;
        auto* dstRow = reinterpret_cast<uint16_t*>(dstUv + static_cast<size_t>(y) * dstUvStride);
        for (uint32_t x = 0; x < width; ++x) {
            dstRow[x] = static_cast<uint16_t>(srcRow[x]) << 8;
        }
    }
}

} // namespace

struct VaapiEncoder::Impl {
    EncoderConfig cfg{};
    RateControlMode activeRc = RateControlMode::CBR;

    AVBufferRef* hwDeviceCtx = nullptr;
    AVBufferRef* hwFramesCtx = nullptr;
    AVCodecContext* codecCtx = nullptr;
    AVFrame* swFrame = nullptr;
    AVFrame* wrapFrame = nullptr;
    AVFrame* hwFrame = nullptr;
    AVPacket* pkt = nullptr;
    SwsContext* yuyvToSw = nullptr;

    std::vector<uint8_t> csdBytes;
    std::atomic<bool> forceIdr{true};

    bool encodeFrame(AVFrame* srcFrame, int64_t pts, const EncodedCallback& cb) {
        if (!hwFrame) {
            hwFrame = av_frame_alloc();
        } else {
            av_frame_unref(hwFrame);
        }
        if (av_hwframe_get_buffer(hwFramesCtx, hwFrame, 0) < 0) {
            return false;
        }
        if (av_hwframe_transfer_data(hwFrame, srcFrame, 0) < 0) {
            return false;
        }
        hwFrame->pts = pts;
        if (forceIdr.exchange(false)) {
            hwFrame->pict_type = AV_PICTURE_TYPE_I;
            hwFrame->flags |= AV_FRAME_FLAG_KEY;
        } else {
            hwFrame->pict_type = AV_PICTURE_TYPE_NONE;
            hwFrame->flags &= ~AV_FRAME_FLAG_KEY;
        }

        int ret = avcodec_send_frame(codecCtx, hwFrame);
        if (ret < 0) {
            char err[128];
            av_strerror(ret, err, sizeof(err));
            std::fprintf(stderr, "[vaapi] avcodec_send_frame failed: %s\n", err);
            return false;
        }

        while (true) {
            ret = avcodec_receive_packet(codecCtx, pkt);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
                break;
            }
            if (ret < 0) {
                char err[128];
                av_strerror(ret, err, sizeof(err));
                std::fprintf(stderr, "[vaapi] avcodec_receive_packet failed: %s\n", err);
                return false;
            }
            const bool isIdr = (pkt->flags & AV_PKT_FLAG_KEY) != 0;
            if (cb) {
                cb(pkt->data, static_cast<size_t>(pkt->size), isIdr);
            }
            av_packet_unref(pkt);
        }
        return true;
    }

    bool openCodecWithRc(const AVCodec* avCodec, RateControlMode rc) {
        if (codecCtx) {
            avcodec_free_context(&codecCtx);
        }
        codecCtx = avcodec_alloc_context3(avCodec);
        if (!codecCtx) return false;

        const uint32_t fps = std::max<uint32_t>(1, cfg.fps);
        const int quality = std::clamp(cfg.quality, 1, 51);
        const int64_t targetBitrate = static_cast<int64_t>(std::max<uint32_t>(5, cfg.bitrateMbps)) * 1000000LL;

        codecCtx->width = static_cast<int>(cfg.width);
        codecCtx->height = static_cast<int>(cfg.height);
        codecCtx->time_base = AVRational{1, static_cast<int>(fps)};
        codecCtx->framerate = AVRational{static_cast<int>(fps), 1};
        codecCtx->pix_fmt = AV_PIX_FMT_VAAPI;
        codecCtx->hw_frames_ctx = av_buffer_ref(hwFramesCtx);
        codecCtx->max_b_frames = 0;
        codecCtx->gop_size = static_cast<int>(fps * 5);
        codecCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

        av_opt_set_int(codecCtx->priv_data, "async_depth", 1, 0);
        if (cfg.codec == VideoCodec::H264) {
            av_opt_set(codecCtx->priv_data, "profile", "high", 0);
        } else if (cfg.codec == VideoCodec::HEVC) {
            av_opt_set(codecCtx->priv_data, "profile", cfg.tenBit ? "main10" : "main", 0);
        } else {
            av_opt_set(codecCtx->priv_data, "profile", "main", 0);
        }

        switch (rc) {
            case RateControlMode::QVBR: {
                const int64_t maxRate = (targetBitrate * 16) / 10;
                codecCtx->bit_rate = targetBitrate;
                codecCtx->rc_max_rate = maxRate;
                codecCtx->rc_buffer_size = static_cast<int>((maxRate / static_cast<int64_t>(fps)) * 6);
                codecCtx->global_quality = quality;
                av_opt_set(codecCtx->priv_data, "rc_mode", "QVBR", 0);
                break;
            }
            case RateControlMode::VBR: {
                const int64_t maxRate = (targetBitrate * 16) / 10;
                codecCtx->bit_rate = targetBitrate;
                codecCtx->rc_max_rate = maxRate;
                codecCtx->rc_buffer_size = static_cast<int>((maxRate / static_cast<int64_t>(fps)) * 6);
                av_opt_set(codecCtx->priv_data, "rc_mode", "VBR", 0);
                break;
            }
            case RateControlMode::CQP: {
                av_opt_set(codecCtx->priv_data, "rc_mode", "CQP", 0);
                av_opt_set_int(codecCtx->priv_data, "qp", quality, 0);
                break;
            }
            case RateControlMode::CBR:
            default: {
                codecCtx->bit_rate = targetBitrate;
                codecCtx->rc_max_rate = targetBitrate;
                codecCtx->rc_buffer_size = static_cast<int>((targetBitrate / static_cast<int64_t>(fps)) * 4);
                av_opt_set(codecCtx->priv_data, "rc_mode", "CBR", 0);
                av_opt_set(codecCtx->priv_data, "qp", "0", 0);
                break;
            }
        }

        const int ret = avcodec_open2(codecCtx, avCodec, nullptr);
        if (ret < 0) {
            char err[128];
            av_strerror(ret, err, sizeof(err));
            std::fprintf(stderr, "[vaapi] %s rc=%s open failed: %s\n",
                         encoderNameFor(cfg.codec), rateControlModeName(rc), err);
            avcodec_free_context(&codecCtx);
            return false;
        }
        activeRc = rc;
        return true;
    }
};

VaapiEncoder::VaapiEncoder() : p_(std::make_unique<Impl>()) {}
VaapiEncoder::~VaapiEncoder() { shutdown(); }

bool VaapiEncoder::init(uint32_t width, uint32_t height, uint32_t fps,
                        uint32_t bitrateMbps, VideoCodec codec,
                        const std::string& drmDevice) {
    EncoderConfig cfg{};
    cfg.width = width;
    cfg.height = height;
    cfg.fps = fps;
    cfg.bitrateMbps = bitrateMbps;
    cfg.codec = codec;
    cfg.tenBit = false;
    cfg.rcMode = RateControlMode::QVBR;
    cfg.quality = 15;
    cfg.drmDevice = drmDevice;
    return init(cfg);
}

bool VaapiEncoder::init(const EncoderConfig& inCfg) {
    shutdown();
    p_->cfg = inCfg;
    if (p_->cfg.codec == VideoCodec::H264) {
        p_->cfg.tenBit = false;
    }

    int ret = av_hwdevice_ctx_create(&p_->hwDeviceCtx, AV_HWDEVICE_TYPE_VAAPI,
                                     p_->cfg.drmDevice.c_str(), nullptr, 0);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        std::fprintf(stderr, "[vaapi] failed to open %s: %s\n", p_->cfg.drmDevice.c_str(), err);
        shutdown();
        return false;
    }

    p_->hwFramesCtx = av_hwframe_ctx_alloc(p_->hwDeviceCtx);
    if (!p_->hwFramesCtx) {
        shutdown();
        return false;
    }
    auto* frames = reinterpret_cast<AVHWFramesContext*>(p_->hwFramesCtx->data);
    frames->format = AV_PIX_FMT_VAAPI;
    frames->sw_format = p_->cfg.tenBit ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
    frames->width = static_cast<int>(p_->cfg.width);
    frames->height = static_cast<int>(p_->cfg.height);
    frames->initial_pool_size = 8;

    ret = av_hwframe_ctx_init(p_->hwFramesCtx);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        std::fprintf(stderr, "[vaapi] av_hwframe_ctx_init failed: %s\n", err);
        shutdown();
        return false;
    }

    const char* encName = encoderNameFor(p_->cfg.codec);
    const AVCodec* avCodec = avcodec_find_encoder_by_name(encName);
    if (!avCodec) {
        std::fprintf(stderr, "[vaapi] encoder %s not found\n", encName);
        shutdown();
        return false;
    }

    std::vector<RateControlMode> tryOrder;
    if (p_->cfg.rcMode == RateControlMode::Auto || p_->cfg.rcMode == RateControlMode::QVBR) {
        tryOrder = {RateControlMode::QVBR, RateControlMode::VBR, RateControlMode::CBR};
    } else if (p_->cfg.rcMode == RateControlMode::VBR) {
        tryOrder = {RateControlMode::VBR, RateControlMode::CBR};
    } else if (p_->cfg.rcMode == RateControlMode::CQP) {
        tryOrder = {RateControlMode::CQP, RateControlMode::VBR, RateControlMode::CBR};
    } else {
        tryOrder = {RateControlMode::CBR};
    }

    bool opened = false;
    for (RateControlMode rc : tryOrder) {
        if (p_->openCodecWithRc(avCodec, rc)) {
            opened = true;
            break;
        }
    }
    if (!opened) {
        shutdown();
        return false;
    }

    if (p_->codecCtx->extradata && p_->codecCtx->extradata_size > 0) {
        if (p_->cfg.codec == VideoCodec::AV1) {
            p_->csdBytes = buildAv1CodecConfigRecord(
                p_->codecCtx->extradata,
                static_cast<size_t>(p_->codecCtx->extradata_size),
                p_->cfg.tenBit);
        } else {
            p_->csdBytes.assign(p_->codecCtx->extradata,
                                p_->codecCtx->extradata + p_->codecCtx->extradata_size);
        }
    }

    p_->swFrame = av_frame_alloc();
    p_->swFrame->format = p_->cfg.tenBit ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
    p_->swFrame->width = static_cast<int>(p_->cfg.width);
    p_->swFrame->height = static_cast<int>(p_->cfg.height);
    if (av_frame_get_buffer(p_->swFrame, 32) < 0) {
        shutdown();
        return false;
    }

    p_->wrapFrame = av_frame_alloc();
    if (!p_->wrapFrame) {
        shutdown();
        return false;
    }

    p_->pkt = av_packet_alloc();
    p_->forceIdr.store(true);
    return true;
}

void VaapiEncoder::shutdown() {
    if (p_->yuyvToSw) {
        sws_freeContext(p_->yuyvToSw);
        p_->yuyvToSw = nullptr;
    }
    if (p_->pkt) av_packet_free(&p_->pkt);
    if (p_->wrapFrame) {
        p_->wrapFrame->data[0] = nullptr;
        p_->wrapFrame->data[1] = nullptr;
        av_frame_free(&p_->wrapFrame);
    }
    if (p_->swFrame) av_frame_free(&p_->swFrame);
    if (p_->hwFrame) av_frame_free(&p_->hwFrame);
    if (p_->codecCtx) avcodec_free_context(&p_->codecCtx);
    if (p_->hwFramesCtx) av_buffer_unref(&p_->hwFramesCtx);
    if (p_->hwDeviceCtx) av_buffer_unref(&p_->hwDeviceCtx);
    p_->csdBytes.clear();
}

const std::vector<uint8_t>& VaapiEncoder::csd() const {
    return p_->csdBytes;
}

const EncoderConfig& VaapiEncoder::config() const {
    return p_->cfg;
}

const char* VaapiEncoder::activeRcModeName() const {
    return rateControlModeName(p_->activeRc);
}

void VaapiEncoder::requestIdr() {
    p_->forceIdr.store(true);
}

bool VaapiEncoder::encodeNv12(const uint8_t* yPlane, int yStride,
                              const uint8_t* uvPlane, int uvStride,
                              int64_t pts, const EncodedCallback& cb) {
    if (!p_->codecCtx || !p_->wrapFrame) return false;

    if (p_->cfg.tenBit) {
        if (!p_->swFrame || av_frame_make_writable(p_->swFrame) < 0) return false;
        promoteNv12ToP010(yPlane, yStride, uvPlane, uvStride,
                          p_->swFrame->data[0], p_->swFrame->linesize[0],
                          p_->swFrame->data[1], p_->swFrame->linesize[1],
                          p_->cfg.width, p_->cfg.height);
        return p_->encodeFrame(p_->swFrame, pts, cb);
    }

    AVFrame* src = p_->wrapFrame;
    src->format = AV_PIX_FMT_NV12;
    src->width = static_cast<int>(p_->cfg.width);
    src->height = static_cast<int>(p_->cfg.height);
    src->data[0] = const_cast<uint8_t*>(yPlane);
    src->linesize[0] = yStride;
    src->data[1] = const_cast<uint8_t*>(uvPlane);
    src->linesize[1] = uvStride;
    return p_->encodeFrame(src, pts, cb);
}

bool VaapiEncoder::encodeYuyv(const uint8_t* yuyv, int yuyvStride,
                              int64_t pts, const EncodedCallback& cb) {
    if (!p_->codecCtx || !p_->swFrame) return false;
    if (av_frame_make_writable(p_->swFrame) < 0) return false;

    const AVPixelFormat dstFmt = p_->cfg.tenBit ? AV_PIX_FMT_P010LE : AV_PIX_FMT_NV12;
    if (!p_->yuyvToSw) {
        p_->yuyvToSw = sws_getContext(
            static_cast<int>(p_->cfg.width), static_cast<int>(p_->cfg.height), AV_PIX_FMT_YUYV422,
            static_cast<int>(p_->cfg.width), static_cast<int>(p_->cfg.height), dstFmt,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (!p_->yuyvToSw) return false;
    }

    const uint8_t* srcSlice[1] = {yuyv};
    int srcStride[1] = {yuyvStride};
    sws_scale(p_->yuyvToSw, srcSlice, srcStride, 0, static_cast<int>(p_->cfg.height),
              p_->swFrame->data, p_->swFrame->linesize);
    return p_->encodeFrame(p_->swFrame, pts, cb);
}

} // namespace gta60_linux
