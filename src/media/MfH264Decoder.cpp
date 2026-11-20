#include "gta60/media/MfH264Decoder.h"
#include "gta60/Log.h"

#include <windows.h>
#include <codecapi.h>
#include <d3d10_1.h>
#include <d3d11.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mftransform.h>
#include <wmcodecdsp.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstring>
#include <format>

namespace gta60::media {
using Microsoft::WRL::ComPtr;

struct MfH264Decoder::Impl {
    bool mfStarted = false;
    bool streamStarted = false;
    ComPtr<IMFTransform> mft;
    uint32_t allocWidth = 1920;
    uint32_t allocHeight = 1080;
    uint32_t width = 1920;
    uint32_t height = 1080;
    uint32_t stride = 1920;
    MFT_OUTPUT_STREAM_INFO outStreamInfo{};
    ComPtr<IMFSample> outSample;
    ComPtr<IMFMediaBuffer> outBuffer;

    // Optional DXVA hardware decode. The MFT decodes straight into a D3D11 NV12 texture; we copy it
    // into a CPU-readable staging texture so the existing CPU-side dedup/pipeline path is untouched.
    ComPtr<ID3D11Device> d11Device;
    ComPtr<ID3D11DeviceContext> d11Context;
    ComPtr<IMFDXGIDeviceManager> dxgiManager;
    UINT dxgiResetToken = 0;
    ComPtr<ID3D11Texture2D> stagingTex;
    uint32_t stagingW = 0;
    uint32_t stagingH = 0;
    bool hwDecode = false;

    bool setupHardware() {
        const UINT flags = D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT;
        const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
        HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, flags, levels, 2,
                                       D3D11_SDK_VERSION, &d11Device, nullptr, &d11Context);
        if (FAILED(hr)) {
            log::warn("mf-h264", std::format("D3D11CreateDevice for DXVA failed hr=0x{:X}", unsigned(hr)));
            return false;
        }
        ComPtr<ID3D10Multithread> mt;
        if (SUCCEEDED(d11Device.As(&mt)) && mt) mt->SetMultithreadProtected(TRUE);

        if (FAILED(MFCreateDXGIDeviceManager(&dxgiResetToken, &dxgiManager)) || !dxgiManager) {
            log::warn("mf-h264", "MFCreateDXGIDeviceManager failed");
            return false;
        }
        if (FAILED(dxgiManager->ResetDevice(d11Device.Get(), dxgiResetToken))) {
            log::warn("mf-h264", "IMFDXGIDeviceManager::ResetDevice failed");
            return false;
        }
        const HRESULT mhr = mft->ProcessMessage(MFT_MESSAGE_SET_D3D_MANAGER,
                                                reinterpret_cast<ULONG_PTR>(dxgiManager.Get()));
        if (FAILED(mhr)) {
            log::warn("mf-h264", std::format("MFT_MESSAGE_SET_D3D_MANAGER failed hr=0x{:X}", unsigned(mhr)));
            return false;
        }
        hwDecode = true;
        return true;
    }

    // Pull the decoded NV12 surface out of a DXGI-backed output sample and read it back to CPU.
    bool copyFromDxgi(ID3D11Texture2D* src, UINT subresource, DecodedNv12Frame& outFrame) {
        D3D11_TEXTURE2D_DESC desc{};
        src->GetDesc(&desc);
        if (desc.Format != DXGI_FORMAT_NV12 || desc.Width == 0 || desc.Height == 0) return false;

        if (!stagingTex || stagingW != desc.Width || stagingH != desc.Height) {
            D3D11_TEXTURE2D_DESC sd = desc;
            sd.Usage = D3D11_USAGE_STAGING;
            sd.BindFlags = 0;
            sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            sd.MiscFlags = 0;
            sd.ArraySize = 1;
            sd.MipLevels = 1;
            sd.SampleDesc.Count = 1;
            sd.SampleDesc.Quality = 0;
            stagingTex.Reset();
            if (FAILED(d11Device->CreateTexture2D(&sd, nullptr, &stagingTex))) {
                log::warn("mf-h264", "failed to create NV12 staging texture");
                return false;
            }
            stagingW = desc.Width;
            stagingH = desc.Height;
        }

        d11Context->CopySubresourceRegion(stagingTex.Get(), 0, 0, 0, 0, src, subresource, nullptr);
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(d11Context->Map(stagingTex.Get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;

        const uint32_t w = desc.Width;
        const uint32_t h = desc.Height;
        outFrame.width = w;
        outFrame.height = h;
        outFrame.stride = w;
        const size_t yBytes = static_cast<size_t>(w) * h;
        const size_t uvBytes = static_cast<size_t>(w) * (h / 2);
        if (outFrame.yPlane.size() != yBytes) outFrame.yPlane.resize(yBytes);
        if (outFrame.uvPlane.size() != uvBytes) outFrame.uvPlane.resize(uvBytes);

        const uint8_t* base = static_cast<const uint8_t*>(mapped.pData);
        const uint8_t* ySrc = base;
        const uint8_t* uvSrc = base + static_cast<size_t>(mapped.RowPitch) * h;
        if (mapped.RowPitch == w) {
            // Tightly packed (the common case for NV12 staging): copy each plane in one go instead
            // of issuing one memcpy per scanline.
            std::memcpy(outFrame.yPlane.data(), ySrc, yBytes);
            std::memcpy(outFrame.uvPlane.data(), uvSrc, uvBytes);
        } else {
            for (uint32_t row = 0; row < h; ++row) {
                std::memcpy(outFrame.yPlane.data() + static_cast<size_t>(row) * w,
                            ySrc + static_cast<size_t>(row) * mapped.RowPitch, w);
            }
            for (uint32_t row = 0; row < h / 2; ++row) {
                std::memcpy(outFrame.uvPlane.data() + static_cast<size_t>(row) * w,
                            uvSrc + static_cast<size_t>(row) * mapped.RowPitch, w);
            }
        }
        d11Context->Unmap(stagingTex.Get(), 0);
        return true;
    }

    bool configureOutputType() {
        for (DWORD idx = 0;; ++idx) {
            ComPtr<IMFMediaType> type;
            const HRESULT hr = mft->GetOutputAvailableType(0, idx, &type);
            if (hr == MF_E_NO_MORE_TYPES || FAILED(hr)) break;
            GUID subtype{};
            if (SUCCEEDED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) && subtype == MFVideoFormat_NV12) {
                if (FAILED(mft->SetOutputType(0, type.Get(), 0))) continue;
                UINT32 w = 0, h = 0;
                if (SUCCEEDED(MFGetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, &w, &h)) && w > 0 && h > 0) {
                    allocWidth = w;
                    allocHeight = h;
                    width = w;
                    height = h;
                }
                MFVideoArea area{};
                UINT32 areaBytes = sizeof(area);
                if (SUCCEEDED(type->GetBlob(MF_MT_MINIMUM_DISPLAY_APERTURE, reinterpret_cast<UINT8*>(&area), sizeof(area), &areaBytes)) ||
                    SUCCEEDED(type->GetBlob(MF_MT_GEOMETRIC_APERTURE, reinterpret_cast<UINT8*>(&area), sizeof(area), &areaBytes))) {
                    if (area.Area.cx > 0 && area.Area.cy > 0 &&
                        static_cast<uint32_t>(area.Area.cx) <= allocWidth &&
                        static_cast<uint32_t>(area.Area.cy) <= allocHeight) {
                        width = static_cast<uint32_t>(area.Area.cx) & ~1u;
                        height = static_cast<uint32_t>(area.Area.cy) & ~1u;
                    }
                }
                UINT32 defStride = allocWidth;
                if (SUCCEEDED(type->GetUINT32(MF_MT_DEFAULT_STRIDE, &defStride)) && defStride >= allocWidth) {
                    stride = defStride;
                } else {
                    stride = allocWidth;
                }
                mft->GetOutputStreamInfo(0, &outStreamInfo);
                ensureOutputSample();
                log::info("mf-h264", std::format("output negotiated NV12 {}x{} (alloc={}x{}, stride={})",
                                                 width, height, allocWidth, allocHeight, stride));
                return true;
            }
        }
        return false;
    }

    void ensureOutputSample() {
        outSample.Reset();
        outBuffer.Reset();
        if (outStreamInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES) return;
        const DWORD needed = std::max<DWORD>(outStreamInfo.cbSize, stride * allocHeight * 3 / 2);
        if (SUCCEEDED(MFCreateSample(&outSample)) && SUCCEEDED(MFCreateMemoryBuffer(needed, &outBuffer))) {
            outSample->AddBuffer(outBuffer.Get());
        }
    }
};

MfH264Decoder::MfH264Decoder() : p_(std::make_unique<Impl>()) {}
MfH264Decoder::~MfH264Decoder() { shutdown(); }

bool MfH264Decoder::initialize(uint32_t initialWidth, uint32_t initialHeight) {
    shutdown();
    p_->allocWidth = initialWidth;
    p_->allocHeight = initialHeight;
    p_->width = initialWidth;
    p_->height = initialHeight;
    p_->stride = initialWidth;

    if (FAILED(MFStartup(MF_VERSION, MFSTARTUP_NOSOCKET))) {
        log::error("mf-h264", "MFStartup failed");
        return false;
    }
    p_->mfStarted = true;

    HRESULT hr = CoCreateInstance(CLSID_CMSH264DecoderMFT, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&p_->mft));
    if (FAILED(hr) || !p_->mft) {
        log::error("mf-h264", std::format("CoCreateInstance CLSID_CMSH264DecoderMFT failed hr=0x{:X}", unsigned(hr)));
        shutdown();
        return false;
    }

    ComPtr<ICodecAPI> codecApi;
    if (SUCCEEDED(p_->mft.As(&codecApi))) {
        VARIANT v{};
        v.vt = VT_BOOL;
        v.boolVal = VARIANT_TRUE;
        codecApi->SetValue(&CODECAPI_AVLowLatencyMode, &v);
    }

    // CMSH264DecoderMFT queues output aggressively by default: measured on this box it held ~37
    // frames (~618 ms at 60 fps) before returning the first frame, while ProcessInput itself still
    // returned in ~1 ms so the buffering was invisible in per-stage timing. MF_LOW_LATENCY is what
    // actually tells the MFT the stream is interactive and must be emitted as it is decoded.
    {
        ComPtr<IMFAttributes> attrs;
        if (SUCCEEDED(p_->mft->GetAttributes(&attrs)) && attrs) {
            const HRESULT setHr = attrs->SetUINT32(MF_LOW_LATENCY, TRUE);
            UINT32 applied = 0;
            attrs->GetUINT32(MF_LOW_LATENCY, &applied);
            log::info("mf-h264", std::format("MF_LOW_LATENCY set hr=0x{:X} value={}", unsigned(setHr), applied));
        } else {
            log::warn("mf-h264", "could not access decoder attributes for MF_LOW_LATENCY");
        }
        // NB: do not also pin CODECAPI_AVDecNumWorkerThreads to 1 here. MF_LOW_LATENCY already
        // shortens the pipeline; force-threading it to one worker only raises decode time (~8 ms at
        // 4K), which lands directly on the critical path.
    }

    // Try DXVA first; fall back to software decode if the device manager cannot be attached.
    if (p_->setupHardware()) {
        log::info("mf-h264", "DXVA hardware decode enabled (D3D11 + IMFDXGIDeviceManager)");
    } else {
        log::info("mf-h264", "hardware decode unavailable; using software decode");
    }

    ComPtr<IMFMediaType> inType;
    if (FAILED(MFCreateMediaType(&inType))) {
        shutdown();
        return false;
    }
    inType->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
    inType->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
    MFSetAttributeSize(inType.Get(), MF_MT_FRAME_SIZE, initialWidth, initialHeight);
    MFSetAttributeRatio(inType.Get(), MF_MT_FRAME_RATE, 60, 1);

    hr = p_->mft->SetInputType(0, inType.Get(), 0);
    if (FAILED(hr)) {
        log::error("mf-h264", std::format("SetInputType H264 failed hr=0x{:X}", unsigned(hr)));
        shutdown();
        return false;
    }

    if (!p_->configureOutputType()) {
        log::error("mf-h264", "configureOutputType NV12 failed");
        shutdown();
        return false;
    }

    p_->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    p_->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    p_->streamStarted = true;
    return true;
}

bool MfH264Decoder::decode(const uint8_t* annexB, size_t size, int64_t pts100ns, DecodedNv12Frame& outFrame) {
    if (!p_->mft || !annexB || size == 0) return false;

    // Must allocate a fresh IMFSample/IMFMediaBuffer per input AU because CMSH264DecoderMFT
    // AddRefs and reads input buffers asynchronously on internal worker threads.
    ComPtr<IMFSample> inSample;
    ComPtr<IMFMediaBuffer> inBuf;
    if (FAILED(MFCreateSample(&inSample)) || FAILED(MFCreateMemoryBuffer(static_cast<DWORD>(size), &inBuf))) {
        return false;
    }
    BYTE* dst = nullptr;
    if (FAILED(inBuf->Lock(&dst, nullptr, nullptr))) return false;
    std::memcpy(dst, annexB, size);
    inBuf->Unlock();
    inBuf->SetCurrentLength(static_cast<DWORD>(size));
    inSample->AddBuffer(inBuf.Get());
    inSample->SetSampleTime(pts100ns);
    inSample->SetSampleDuration(166667);

    auto drainOutput = [&]() -> bool {
        bool drained = false;
        for (;;) {
            MFT_OUTPUT_DATA_BUFFER outBuf{};
            outBuf.dwStreamID = 0;
            if (!(p_->outStreamInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
                if (p_->outBuffer) p_->outBuffer->SetCurrentLength(0);
                outBuf.pSample = p_->outSample.Get();
            }
            DWORD status = 0;
            const HRESULT outHr = p_->mft->ProcessOutput(0, 1, &outBuf, &status);
            ComPtr<IMFSample> produced;
            if (outBuf.pSample && (p_->outStreamInfo.dwFlags & MFT_OUTPUT_STREAM_PROVIDES_SAMPLES)) {
                produced.Attach(outBuf.pSample);
            } else {
                produced = outBuf.pSample;
            }
            if (outBuf.pEvents) outBuf.pEvents->Release();

            if (outHr == MF_E_TRANSFORM_STREAM_CHANGE) {
                if (!p_->configureOutputType()) break;
                continue;
            }
            if (outHr == MF_E_TRANSFORM_NEED_MORE_INPUT) {
                break;
            }
            if (FAILED(outHr) || !produced) {
                break;
            }

            LONGLONG producedTime = pts100ns;
            produced->GetSampleTime(&producedTime);
            outFrame.pts100ns = producedTime;

            // DXVA: the MFT hands back a D3D11 NV12 surface. Read it back through a staging texture.
            if (p_->hwDecode) {
                ComPtr<IMFMediaBuffer> hwBuf;
                ComPtr<IMFDXGIBuffer> dxgiBuf;
                if (SUCCEEDED(produced->GetBufferByIndex(0, &hwBuf)) && SUCCEEDED(hwBuf.As(&dxgiBuf))) {
                    ComPtr<ID3D11Texture2D> tex;
                    UINT sub = 0;
                    if (SUCCEEDED(dxgiBuf->GetResource(IID_PPV_ARGS(&tex))) &&
                        SUCCEEDED(dxgiBuf->GetSubresourceIndex(&sub)) &&
                        p_->copyFromDxgi(tex.Get(), sub, outFrame)) {
                        drained = true;
                        continue;
                    }
                }
            }

            ComPtr<IMFMediaBuffer> mediaBuf;
            if (FAILED(produced->ConvertToContiguousBuffer(&mediaBuf))) continue;

            BYTE* raw = nullptr;
            DWORD rawLen = 0;
            LONG pitch = static_cast<LONG>(p_->stride);
            ComPtr<IMF2DBuffer> buf2d;
            bool locked2d = false;
            if (SUCCEEDED(mediaBuf.As(&buf2d)) && SUCCEEDED(buf2d->Lock2D(&raw, &pitch))) {
                locked2d = true;
            } else if (FAILED(mediaBuf->Lock(&raw, nullptr, &rawLen))) {
                continue;
            }

            const uint32_t w = p_->width;
            const uint32_t h = p_->height;
            const size_t srcPitch = static_cast<size_t>(pitch > 0 ? pitch : static_cast<LONG>(p_->stride));
            outFrame.width = w;
            outFrame.height = h;
            outFrame.stride = w;
            LONGLONG sampleTime = pts100ns;
            produced->GetSampleTime(&sampleTime);
            outFrame.pts100ns = sampleTime;
            const size_t yBytes = static_cast<size_t>(w) * h;
            const size_t uvBytes = static_cast<size_t>(w) * (h / 2);
            if (outFrame.yPlane.size() != yBytes) outFrame.yPlane.resize(yBytes);
            if (outFrame.uvPlane.size() != uvBytes) outFrame.uvPlane.resize(uvBytes);

            if (srcPitch == w) {
                std::memcpy(outFrame.yPlane.data(), raw, yBytes);
                std::memcpy(outFrame.uvPlane.data(), raw + static_cast<size_t>(p_->allocHeight) * srcPitch, uvBytes);
            } else {
                for (uint32_t y = 0; y < h; ++y) {
                    std::memcpy(outFrame.yPlane.data() + static_cast<size_t>(y) * w, raw + static_cast<size_t>(y) * srcPitch, w);
                }
                const uint8_t* uvSrc = raw + static_cast<size_t>(p_->allocHeight) * srcPitch;
                for (uint32_t y = 0; y < h / 2; ++y) {
                    std::memcpy(outFrame.uvPlane.data() + static_cast<size_t>(y) * w, uvSrc + static_cast<size_t>(y) * srcPitch, w);
                }
            }

            if (locked2d) buf2d->Unlock2D();
            else mediaBuf->Unlock();
            drained = true;
        }
        return drained;
    };

    bool gotFrame = false;
    HRESULT hr = p_->mft->ProcessInput(0, inSample.Get(), 0);
    if (hr == MF_E_NOTACCEPTING) {
        gotFrame |= drainOutput();
        hr = p_->mft->ProcessInput(0, inSample.Get(), 0);
    }
    if (FAILED(hr)) {
        return gotFrame;
    }

    gotFrame |= drainOutput();
    return gotFrame;
}

void MfH264Decoder::flush() {
    if (p_->mft && p_->streamStarted) {
        p_->mft->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
        p_->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
        p_->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    }
}

void MfH264Decoder::shutdown() {
    if (p_->mft) {
        if (p_->streamStarted) {
            p_->mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
            p_->mft->ProcessMessage(MFT_MESSAGE_COMMAND_FLUSH, 0);
            p_->streamStarted = false;
        }
        p_->mft.Reset();
    }
    p_->outSample.Reset();
    p_->outBuffer.Reset();
    if (p_->mfStarted) {
        MFShutdown();
        p_->mfStarted = false;
    }
}

} // namespace gta60::media
