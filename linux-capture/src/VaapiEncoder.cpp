#include "VaapiEncoder.hpp"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext.h>
#include <libavutil/opt.h>
#include <libswscale/swscale.h>
}

#include <atomic>
#include <cstdio>
#include <cstring>

namespace gta60_linux {

struct VaapiEncoder::Impl {
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t fps = 60;
    VideoCodec codec = VideoCodec::HEVC;

    AVBufferRef* hwDeviceCtx = nullptr;
    AVBufferRef* hwFramesCtx = nullptr;
    AVCodecContext* codecCtx = nullptr;
    AVFrame* swFrame = nullptr;
    AVFrame* wrapFrame = nullptr;
    AVFrame* hwFrame = nullptr;
    AVPacket* pkt = nullptr;
    SwsContext* yuyvToNv12 = nullptr;

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
};

VaapiEncoder::VaapiEncoder() : p_(std::make_unique<Impl>()) {}
VaapiEncoder::~VaapiEncoder() { shutdown(); }

bool VaapiEncoder::init(uint32_t width, uint32_t height, uint32_t fps,
                        uint32_t bitrateMbps, VideoCodec codec,
                        const std::string& drmDevice) {
    shutdown();
    p_->width = width;
    p_->height = height;
    p_->fps = fps;
    p_->codec = codec;

    int ret = av_hwdevice_ctx_create(&p_->hwDeviceCtx, AV_HWDEVICE_TYPE_VAAPI,
                                     drmDevice.c_str(), nullptr, 0);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        std::fprintf(stderr, "[vaapi] failed to open %s: %s\n", drmDevice.c_str(), err);
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
    frames->sw_format = AV_PIX_FMT_NV12;
    frames->width = static_cast<int>(width);
    frames->height = static_cast<int>(height);
    frames->initial_pool_size = 8;

    ret = av_hwframe_ctx_init(p_->hwFramesCtx);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        std::fprintf(stderr, "[vaapi] av_hwframe_ctx_init failed: %s\n", err);
        shutdown();
        return false;
    }

    const char* encName = (codec == VideoCodec::HEVC) ? "hevc_vaapi" : "h264_vaapi";
    const AVCodec* avCodec = avcodec_find_encoder_by_name(encName);
    if (!avCodec) {
        std::fprintf(stderr, "[vaapi] encoder %s not found\n", encName);
        shutdown();
        return false;
    }

    p_->codecCtx = avcodec_alloc_context3(avCodec);
    if (!p_->codecCtx) {
        shutdown();
        return false;
    }

    p_->codecCtx->width = static_cast<int>(width);
    p_->codecCtx->height = static_cast<int>(height);
    p_->codecCtx->time_base = AVRational{1, static_cast<int>(fps)};
    p_->codecCtx->framerate = AVRational{static_cast<int>(fps), 1};
    p_->codecCtx->pix_fmt = AV_PIX_FMT_VAAPI;
    p_->codecCtx->hw_frames_ctx = av_buffer_ref(p_->hwFramesCtx);
    p_->codecCtx->max_b_frames = 0;
    p_->codecCtx->gop_size = static_cast<int>(fps * 2);
    p_->codecCtx->bit_rate = static_cast<int64_t>(bitrateMbps) * 1000000LL;
    p_->codecCtx->rc_max_rate = p_->codecCtx->bit_rate;
    p_->codecCtx->rc_buffer_size = static_cast<int>(p_->codecCtx->bit_rate / static_cast<int64_t>(fps) * 2);
    p_->codecCtx->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;

    av_opt_set(p_->codecCtx->priv_data, "rc_mode", "CBR", 0);
    av_opt_set(p_->codecCtx->priv_data, "qp", "0", 0);
    if (codec == VideoCodec::H264) {
        av_opt_set(p_->codecCtx->priv_data, "profile", "high", 0);
    } else {
        av_opt_set(p_->codecCtx->priv_data, "profile", "main", 0);
    }

    ret = avcodec_open2(p_->codecCtx, avCodec, nullptr);
    if (ret < 0) {
        char err[128];
        av_strerror(ret, err, sizeof(err));
        std::fprintf(stderr, "[vaapi] avcodec_open2(%s) failed: %s\n", encName, err);
        shutdown();
        return false;
    }

    if (p_->codecCtx->extradata && p_->codecCtx->extradata_size > 0) {
        p_->csdBytes.assign(p_->codecCtx->extradata,
                            p_->codecCtx->extradata + p_->codecCtx->extradata_size);
    }

    p_->swFrame = av_frame_alloc();
    p_->swFrame->format = AV_PIX_FMT_NV12;
    p_->swFrame->width = static_cast<int>(width);
    p_->swFrame->height = static_cast<int>(height);
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
    if (p_->yuyvToNv12) {
        sws_freeContext(p_->yuyvToNv12);
        p_->yuyvToNv12 = nullptr;
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

void VaapiEncoder::requestIdr() {
    p_->forceIdr.store(true);
}

bool VaapiEncoder::encodeNv12(const uint8_t* yPlane, int yStride,
                              const uint8_t* uvPlane, int uvStride,
                              int64_t pts, const EncodedCallback& cb) {
    if (!p_->codecCtx || !p_->wrapFrame) return false;

    AVFrame* src = p_->wrapFrame;
    src->format = AV_PIX_FMT_NV12;
    src->width = static_cast<int>(p_->width);
    src->height = static_cast<int>(p_->height);
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

    if (!p_->yuyvToNv12) {
        p_->yuyvToNv12 = sws_getContext(
            static_cast<int>(p_->width), static_cast<int>(p_->height), AV_PIX_FMT_YUYV422,
            static_cast<int>(p_->width), static_cast<int>(p_->height), AV_PIX_FMT_NV12,
            SWS_FAST_BILINEAR, nullptr, nullptr, nullptr);
        if (!p_->yuyvToNv12) return false;
    }

    const uint8_t* srcSlice[1] = {yuyv};
    int srcStride[1] = {yuyvStride};
    sws_scale(p_->yuyvToNv12, srcSlice, srcStride, 0, static_cast<int>(p_->height),
              p_->swFrame->data, p_->swFrame->linesize);
    return p_->encodeFrame(p_->swFrame, pts, cb);
}

} // namespace gta60_linux
