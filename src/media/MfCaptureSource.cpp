#include "gta60/media/MfCaptureSource.h"
#include "gta60/Log.h"

#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfobjects.h>
#include <mfreadwrite.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstring>
#include <format>
#include <string>
#include <vector>

namespace gta60::media {
namespace {
using Microsoft::WRL::ComPtr;

std::string hrText(HRESULT hr) { return std::format("0x{:08X}", static_cast<unsigned>(hr)); }

std::wstring fold(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(s.data(), static_cast<DWORD>(s.size()));
    return s;
}

bool nameMatches(const std::wstring& friendly, const std::wstring& needle) {
    if (needle.empty()) return true;
    return fold(friendly).find(fold(needle)) != std::wstring::npos;
}

std::string subtypeName(const GUID& subtype) {
    if (subtype == MFVideoFormat_NV12) return "NV12";
    if (subtype == MFVideoFormat_YUY2) return "YUY2";
    if (subtype == MFVideoFormat_MJPG) return "MJPG";
    if (subtype == MFVideoFormat_RGB32) return "RGB32";
    if (subtype == MFVideoFormat_I420) return "I420";
    if (subtype == MFVideoFormat_YV12) return "YV12";
    char fcc[5] = {};
    const uint32_t v = subtype.Data1;
    fcc[0] = static_cast<char>(v & 0xFF);
    fcc[1] = static_cast<char>((v >> 8) & 0xFF);
    fcc[2] = static_cast<char>((v >> 16) & 0xFF);
    fcc[3] = static_cast<char>((v >> 24) & 0xFF);
    for (int i = 0; i < 4; ++i) {
        if (fcc[i] != 0 && (fcc[i] < 32 || fcc[i] > 126)) fcc[i] = '?';
    }
    if (fcc[0] == 0) return "unknown";
    return std::string(fcc);
}

uint32_t roundedFps(UINT32 num, UINT32 den) {
    if (den == 0) return 0;
    return static_cast<uint32_t>((static_cast<uint64_t>(num) + den / 2) / den);
}

// Uncompressed always beats MJPEG: a card that compresses on the wire is the ceiling this mode
// exists to get past. Within uncompressed, 1440p beats 1080p, then NV12 beats YUY2, then ~60 fps.
int scoreFormat(uint32_t w, uint32_t h, uint32_t fps, const GUID& subtype) {
    if (w < 1280 || h < 720 || w > 2560 || h > 1440) return -1;
    if ((w & 1u) != 0 || (h & 1u) != 0) return -1;
    if (fps < 24 || fps > 120) return -1;
    int family = 0;
    int fmt = 0;
    if (subtype == MFVideoFormat_NV12) {
        family = 100000;
        fmt = 1000;
    } else if (subtype == MFVideoFormat_YUY2) {
        family = 100000;
        fmt = 500;
    } else if (subtype == MFVideoFormat_MJPG) {
        family = 0;
        fmt = 100;
    } else {
        return -1;
    }
    int res = 1000 + static_cast<int>(h);
    if (w == 2560 && h == 1440) res = 3000;
    else if (w == 1920 && h == 1080) res = 2000;
    int rate = 50;
    if (fps >= 59 && fps <= 61) rate = 500;
    else if (fps >= 50 && fps <= 90) rate = 200;
    return family + res * 10 + fmt + rate;
}

struct Candidate {
    ComPtr<IMFMediaType> type;
    uint32_t width = 0;
    uint32_t height = 0;
    UINT32 fpsNum = 0;
    UINT32 fpsDen = 1;
    uint32_t fps = 0;
    GUID subtype{};
    int score = -1;
};

bool readTypeSize(IMFMediaType* type, uint32_t& w, uint32_t& h, UINT32& fpsNum, UINT32& fpsDen, GUID& subtype) {
    if (!type) return false;
    UINT32 uw = 0, uh = 0;
    if (FAILED(MFGetAttributeSize(type, MF_MT_FRAME_SIZE, &uw, &uh))) return false;
    w = uw;
    h = uh;
    fpsNum = 0;
    fpsDen = 1;
    MFGetAttributeRatio(type, MF_MT_FRAME_RATE, &fpsNum, &fpsDen);
    if (fpsDen == 0) fpsDen = 1;
    subtype = {};
    if (FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype))) return false;
    return w > 0 && h > 0;
}

void freeActivates(IMFActivate** devices, UINT32 count) {
    if (!devices) return;
    for (UINT32 i = 0; i < count; ++i) {
        if (devices[i]) devices[i]->Release();
    }
    CoTaskMemFree(devices);
}

} // namespace

struct MfCaptureSource::Impl {
    bool mfStarted = false;
    ComPtr<IMFMediaSource> source;
    ComPtr<IMFSourceReader> reader;
    ComPtr<IMFMediaType> videoType;
    CaptureFormat format;
    enum class Kind { Nv12, Yuy2 } kind = Kind::Nv12;
    DWORD videoStream = MF_SOURCE_READER_INVALID_STREAM_INDEX;
    DWORD audioStream = MF_SOURCE_READER_INVALID_STREAM_INDEX;
    bool audio = false;
    bool loggedGpuBuffer = false;

    ~Impl() { close(); }

    void close() {
        reader.Reset();
        if (source) {
            source->Shutdown();
            source.Reset();
        }
        videoType.Reset();
        if (mfStarted) {
            MFShutdown();
            mfStarted = false;
        }
        format = {};
        audio = false;
    }

    bool createReader(bool advanced) {
        ComPtr<IMFAttributes> attr;
        if (FAILED(MFCreateAttributes(&attr, 4)) || !attr) return false;
        attr->SetUINT32(MF_LOW_LATENCY, TRUE);
        // Hardware transforms can hand back DXGI surfaces. The encoder wants CPU NV12.
        attr->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, FALSE);
        if (advanced) attr->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE);
        reader.Reset();
        const HRESULT hr = MFCreateSourceReaderFromMediaSource(source.Get(), attr.Get(), &reader);
        if (FAILED(hr) || !reader) {
            log::warn("capture", std::format("MFCreateSourceReaderFromMediaSource failed {}", hrText(hr)));
            return false;
        }
        return true;
    }

    bool currentVideo(uint32_t& w, uint32_t& h, GUID& subtype, uint32_t& fps) {
        ComPtr<IMFMediaType> cur;
        if (FAILED(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, &cur)) || !cur) return false;
        UINT32 num = 0, den = 1;
        if (!readTypeSize(cur.Get(), w, h, num, den, subtype)) return false;
        fps = roundedFps(num, den);
        videoType = cur;
        return true;
    }

    bool setNv12Output(const Candidate& c) {
        ComPtr<IMFMediaType> out;
        if (FAILED(MFCreateMediaType(&out)) || !out) return false;
        out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video);
        out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12);
        MFSetAttributeSize(out.Get(), MF_MT_FRAME_SIZE, c.width, c.height);
        MFSetAttributeRatio(out.Get(), MF_MT_FRAME_RATE, c.fpsNum, c.fpsDen);
        MFSetAttributeRatio(out.Get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        out->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        const HRESULT hr = reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, out.Get());
        if (FAILED(hr)) {
            log::info("capture", std::format("NV12 output refused for {}x{} {} {}", c.width, c.height,
                                             subtypeName(c.subtype), hrText(hr)));
            return false;
        }
        return true;
    }

    bool acceptCurrent(Kind want) {
        uint32_t w = 0, h = 0, fps = 0;
        GUID subtype{};
        if (!currentVideo(w, h, subtype, fps)) return false;
        if ((w & 1u) || (h & 1u) || w > 2560 || h > 1440 || w < 1280 || h < 720) {
            log::info("capture", std::format("driver settled on {}x{}, which is outside 720p-1440p", w, h));
            return false;
        }
        const bool nv12 = subtype == MFVideoFormat_NV12;
        const bool yuy2 = subtype == MFVideoFormat_YUY2;
        if (want == Kind::Nv12 && !nv12) return false;
        if (want == Kind::Yuy2 && !yuy2) return false;
        kind = want;
        format.width = w;
        format.height = h;
        format.fps = fps == 0 ? 60 : fps;
        format.subtype = subtypeName(subtype);
        return true;
    }

    bool configureCandidate(Candidate& c, bool advanced) {
        HRESULT hr = reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, c.type.Get());
        if (FAILED(hr)) {
            log::info("capture", std::format("device refused {}x{} @ {} {} ({})", c.width, c.height, c.fps,
                                             subtypeName(c.subtype), hrText(hr)));
            return false;
        }
        if (c.subtype == MFVideoFormat_NV12 && acceptCurrent(Kind::Nv12)) return true;

        if (setNv12Output(c) && acceptCurrent(Kind::Nv12)) {
            if (c.subtype != MFVideoFormat_NV12) {
                log::info("capture", std::format("converting {} to NV12 at {}x{}", subtypeName(c.subtype),
                                                 format.width, format.height));
            }
            return true;
        }

        if (c.subtype == MFVideoFormat_YUY2) {
            reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, c.type.Get());
            if (acceptCurrent(Kind::Yuy2)) {
                log::info("capture", "YUY2 will be converted to NV12 on the CPU");
                return true;
            }
        }

        if (c.subtype == MFVideoFormat_MJPG && !advanced) {
            if (!createReader(true)) return false;
            return configureCandidate(c, true);
        }
        return false;
    }

    bool enableAudio() {
        ComPtr<IMFMediaType> native;
        HRESULT hr = reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0, &native);
        if (FAILED(hr)) {
            log::info("capture", "no audio stream on this device");
            return false;
        }
        ComPtr<IMFMediaType> pcm;
        if (FAILED(MFCreateMediaType(&pcm)) || !pcm) return false;
        pcm->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio);
        pcm->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM);
        pcm->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, 2);
        pcm->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, 48000);
        pcm->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16);
        pcm->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, 4);
        pcm->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 48000u * 4u);
        pcm->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE);
        hr = reader->SetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, nullptr, pcm.Get());
        if (FAILED(hr)) {
            log::warn("capture", std::format("PCM 48 kHz stereo was refused ({}); video only", hrText(hr)));
            reader->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, FALSE);
            return false;
        }
        ComPtr<IMFMediaType> cur;
        if (FAILED(reader->GetCurrentMediaType(MF_SOURCE_READER_FIRST_AUDIO_STREAM, &cur)) || !cur) return false;
        UINT32 channels = 0, rate = 0, bits = 0;
        cur->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &channels);
        cur->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &rate);
        cur->GetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, &bits);
        if (channels != 2 || rate != 48000 || bits != 16) {
            log::warn("capture", std::format("audio negotiated {} Hz {} ch {}-bit; video only", rate, channels, bits));
            reader->SetStreamSelection(MF_SOURCE_READER_FIRST_AUDIO_STREAM, FALSE);
            return false;
        }
        return true;
    }

    bool selectStreams() {
        videoStream = MF_SOURCE_READER_INVALID_STREAM_INDEX;
        audioStream = MF_SOURCE_READER_INVALID_STREAM_INDEX;
        for (DWORD i = 0; i < 16; ++i) {
            ComPtr<IMFMediaType> type;
            const HRESULT hr = reader->GetCurrentMediaType(i, &type);
            if (hr == MF_E_INVALIDSTREAMNUMBER) break;
            if (FAILED(hr) || !type) continue;
            GUID major{};
            if (FAILED(type->GetGUID(MF_MT_MAJOR_TYPE, &major))) continue;
            if (major == MFMediaType_Video && videoStream == MF_SOURCE_READER_INVALID_STREAM_INDEX) videoStream = i;
            if (major == MFMediaType_Audio && audioStream == MF_SOURCE_READER_INVALID_STREAM_INDEX) audioStream = i;
        }
        for (DWORD i = 0; i < 16; ++i) {
            const HRESULT hr = reader->SetStreamSelection(i, FALSE);
            if (hr == MF_E_INVALIDSTREAMNUMBER) break;
        }
        if (videoStream == MF_SOURCE_READER_INVALID_STREAM_INDEX) {
            log::error("capture", "source reader has no video stream after negotiation");
            return false;
        }
        reader->SetStreamSelection(videoStream, TRUE);
        if (audio && audioStream != MF_SOURCE_READER_INVALID_STREAM_INDEX) {
            reader->SetStreamSelection(audioStream, TRUE);
        }
        return true;
    }

    bool copyPlanes(const uint8_t* src, int pitch, uint32_t width, uint32_t height, bool yuy2, DecodedNv12Frame& frame) {
        if (!src || pitch == 0 || width == 0 || height == 0) return false;
        const int absPitch = pitch < 0 ? -pitch : pitch;
        if (yuy2) {
            if (absPitch < static_cast<int>(width * 2)) return false;
            frame.width = width;
            frame.height = height;
            frame.stride = width;
            frame.yPlane.assign(static_cast<size_t>(width) * height, 0);
            frame.uvPlane.assign(static_cast<size_t>(width) * (height / 2), 0);
            for (uint32_t row = 0; row < height; ++row) {
                const uint8_t* s = src + static_cast<ptrdiff_t>(row) * pitch;
                uint8_t* y = frame.yPlane.data() + static_cast<size_t>(row) * width;
                for (uint32_t x = 0; x < width; x += 2) {
                    y[x] = s[0];
                    y[x + 1] = s[2];
                    s += 4;
                }
            }
            for (uint32_t row = 0; row + 1 < height; row += 2) {
                const uint8_t* s0 = src + static_cast<ptrdiff_t>(row) * pitch;
                const uint8_t* s1 = src + static_cast<ptrdiff_t>(row + 1) * pitch;
                uint8_t* uv = frame.uvPlane.data() + static_cast<size_t>(row / 2) * width;
                for (uint32_t x = 0; x < width; x += 2) {
                    uv[x] = static_cast<uint8_t>((int(s0[1]) + int(s1[1]) + 1) >> 1);
                    uv[x + 1] = static_cast<uint8_t>((int(s0[3]) + int(s1[3]) + 1) >> 1);
                    s0 += 4;
                    s1 += 4;
                }
            }
            return true;
        }

        const uint32_t rowPitch = static_cast<uint32_t>(absPitch);
        if (rowPitch < width) return false;
        frame.width = width;
        frame.height = height;
        frame.stride = rowPitch;
        frame.yPlane.resize(static_cast<size_t>(rowPitch) * height);
        frame.uvPlane.resize(static_cast<size_t>(rowPitch) * (height / 2));
        for (uint32_t row = 0; row < height; ++row) {
            const uint8_t* s = src + static_cast<ptrdiff_t>(row) * pitch;
            std::memcpy(frame.yPlane.data() + static_cast<size_t>(row) * rowPitch, s, width);
        }
        const uint8_t* uvBase = src + static_cast<ptrdiff_t>(pitch) * static_cast<ptrdiff_t>(height);
        for (uint32_t row = 0; row < height / 2; ++row) {
            const uint8_t* s = uvBase + static_cast<ptrdiff_t>(row) * pitch;
            std::memcpy(frame.uvPlane.data() + static_cast<size_t>(row) * rowPitch, s, width);
        }
        return true;
    }

    bool copyFromBuffer(IMFMediaBuffer* buffer, bool yuy2, DecodedNv12Frame& frame) {
        if (!buffer) return false;
        BYTE* scan = nullptr;
        LONG pitch = 0;
        ComPtr<IMF2DBuffer> buf2d;
        if (SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&buf2d))) && buf2d &&
            SUCCEEDED(buf2d->Lock2D(&scan, &pitch)) && scan) {
            const bool ok = copyPlanes(scan, static_cast<int>(pitch), format.width, format.height, yuy2, frame);
            buf2d->Unlock2D();
            return ok;
        }

        BYTE* data = nullptr;
        DWORD maxLen = 0, curLen = 0;
        if (FAILED(buffer->Lock(&data, &maxLen, &curLen)) || !data) {
            if (!loggedGpuBuffer) {
                loggedGpuBuffer = true;
                log::error("capture", "capture sample is not a CPU buffer");
            }
            return false;
        }
        LONG stride = 0;
        UINT32 strideAttr = 0;
        if (videoType && SUCCEEDED(videoType->GetUINT32(MF_MT_DEFAULT_STRIDE, &strideAttr))) {
            stride = static_cast<LONG>(static_cast<int32_t>(strideAttr));
        }
        if (stride == 0) {
            const uint32_t fourcc = yuy2 ? MFVideoFormat_YUY2.Data1 : MFVideoFormat_NV12.Data1;
            if (FAILED(MFGetStrideForBitmapInfoHeader(fourcc, format.width, &stride)) || stride == 0) {
                stride = static_cast<LONG>(yuy2 ? format.width * 2 : format.width);
            }
        }
        const size_t rows = yuy2 ? format.height : format.height + format.height / 2;
        const size_t need = static_cast<size_t>(stride < 0 ? -stride : stride) * rows;
        bool ok = false;
        if (curLen >= need) {
            ok = copyPlanes(data, static_cast<int>(stride), format.width, format.height, yuy2, frame);
        } else {
            log::warn("capture", std::format("capture buffer is {} bytes, need {}", curLen, need));
        }
        buffer->Unlock();
        return ok;
    }

    bool copyVideo(IMFSample* sample, DecodedNv12Frame& frame) {
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(sample->GetBufferByIndex(0, &buffer)) || !buffer) {
            if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) || !buffer) return false;
        }
        const bool yuy2 = kind == Kind::Yuy2;
        if (copyFromBuffer(buffer.Get(), yuy2, frame)) return true;
        ComPtr<IMFMediaBuffer> contig;
        if (FAILED(sample->ConvertToContiguousBuffer(&contig)) || !contig || contig.Get() == buffer.Get()) return false;
        return copyFromBuffer(contig.Get(), yuy2, frame);
    }

    bool copyAudio(IMFSample* sample, std::vector<int16_t>& pcm) {
        ComPtr<IMFMediaBuffer> buffer;
        if (FAILED(sample->ConvertToContiguousBuffer(&buffer)) || !buffer) return false;
        BYTE* data = nullptr;
        DWORD maxLen = 0, curLen = 0;
        if (FAILED(buffer->Lock(&data, &maxLen, &curLen)) || !data || curLen < 4) {
            if (data) buffer->Unlock();
            return false;
        }
        const size_t samples = (curLen / sizeof(int16_t)) & ~size_t{1};
        pcm.assign(reinterpret_cast<const int16_t*>(data), reinterpret_cast<const int16_t*>(data) + samples);
        buffer->Unlock();
        return !pcm.empty();
    }

    bool open(const std::wstring& deviceNameSubstring) {
        close();
        HRESULT hr = MFStartup(MF_VERSION, MFSTARTUP_LITE);
        if (FAILED(hr)) {
            log::error("capture", std::format("MFStartup failed {}", hrText(hr)));
            return false;
        }
        mfStarted = true;

        ComPtr<IMFAttributes> attrs;
        if (FAILED(MFCreateAttributes(&attrs, 1)) || !attrs) return false;
        attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID);
        IMFActivate** devices = nullptr;
        UINT32 count = 0;
        hr = MFEnumDeviceSources(attrs.Get(), &devices, &count);
        if (FAILED(hr) || count == 0 || !devices) {
            freeActivates(devices, count);
            log::error("capture", "no video capture device is connected");
            return false;
        }

        struct ListedDevice {
            UINT32 index = 0;
            std::wstring name;
        };
        std::vector<ListedDevice> matches;
        log::info("capture", std::format("{} video capture device(s):", count));
        for (UINT32 i = 0; i < count; ++i) {
            WCHAR* name = nullptr;
            UINT32 nameLen = 0;
            std::wstring friendly;
            if (SUCCEEDED(devices[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &nameLen)) && name) {
                friendly.assign(name, nameLen);
                CoTaskMemFree(name);
            } else {
                friendly = L"(unnamed)";
            }
            log::info("capture", std::format("  [{}] {}", i, log::narrow(friendly)));
            if (nameMatches(friendly, deviceNameSubstring)) matches.push_back(ListedDevice{i, std::move(friendly)});
        }
        if (matches.empty()) {
            freeActivates(devices, count);
            log::error("capture", deviceNameSubstring.empty()
                                     ? "no video capture device is connected"
                                     : std::format("no capture device matches '{}'", log::narrow(deviceNameSubstring)));
            return false;
        }

        bool opened = false;
        bool sawAccessDenied = false;
        for (const auto& item : matches) {
            reader.Reset();
            if (source) {
                source->Shutdown();
                source.Reset();
            }
            format = {};
            audio = false;
            videoType.Reset();

            hr = devices[item.index]->ActivateObject(IID_PPV_ARGS(&source));
            if (FAILED(hr) || !source) {
                if (hr == E_ACCESSDENIED) sawAccessDenied = true;
                log::warn("capture", std::format("skipping '{}': ActivateObject {}", log::narrow(item.name), hrText(hr)));
                source.Reset();
                continue;
            }
            format.deviceName = item.name;
            if (!createReader(false)) {
                source->Shutdown();
                source.Reset();
                continue;
            }

            std::vector<Candidate> candidates;
            for (DWORD i = 0;; ++i) {
                Candidate c;
                hr = reader->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, i, &c.type);
                if (hr == MF_E_NO_MORE_TYPES) break;
                if (FAILED(hr) || !c.type) break;
                if (!readTypeSize(c.type.Get(), c.width, c.height, c.fpsNum, c.fpsDen, c.subtype)) continue;
                c.fps = roundedFps(c.fpsNum, c.fpsDen);
                c.score = scoreFormat(c.width, c.height, c.fps, c.subtype);
                log::info("capture", std::format("  native {}x{} @ {} {} score {}", c.width, c.height, c.fps,
                                                 subtypeName(c.subtype), c.score));
                if (c.score >= 0) candidates.push_back(std::move(c));
            }
            std::sort(candidates.begin(), candidates.end(),
                      [](const Candidate& a, const Candidate& b) { return a.score > b.score; });
            if (candidates.empty()) {
                log::warn("capture", std::format("skipping '{}': no 720p-1440p NV12, YUY2, or MJPEG mode",
                                                 log::narrow(item.name)));
                reader.Reset();
                source->Shutdown();
                source.Reset();
                continue;
            }

            bool configured = false;
            for (auto& c : candidates) {
                if (!reader && !createReader(false)) break;
                if (configureCandidate(c, false)) {
                    configured = true;
                    break;
                }
                createReader(false);
            }
            if (!configured || !reader) {
                log::warn("capture", std::format("skipping '{}': could not negotiate NV12 or YUY2", log::narrow(item.name)));
                reader.Reset();
                if (source) {
                    source->Shutdown();
                    source.Reset();
                }
                continue;
            }

            audio = enableAudio();
            format.audio = audio;
            format.deviceName = item.name;
            if (!selectStreams()) {
                reader.Reset();
                source->Shutdown();
                source.Reset();
                continue;
            }
            opened = true;
            break;
        }
        freeActivates(devices, count);
        if (!opened) {
            if (sawAccessDenied) {
                log::error("capture", "capture devices refused access (0x80070005). Allow desktop apps to use the camera in Windows privacy settings.");
            } else {
                log::error("capture", "no capture device could be opened");
            }
            return false;
        }

        log::info("capture", std::format("using '{}' {}x{} @ {} fps {} audio={}", log::narrow(format.deviceName),
                                         format.width, format.height, format.fps, format.subtype, audio));
        return true;
    }

    bool read(CaptureRead& out) {
        out = {};
        if (!reader) return false;
        DWORD streamIndex = 0;
        DWORD flags = 0;
        LONGLONG timestamp = 0;
        ComPtr<IMFSample> sample;
        const DWORD which = audio ? static_cast<DWORD>(MF_SOURCE_READER_ANY_STREAM) : videoStream;
        const HRESULT hr = reader->ReadSample(which, 0, &streamIndex, &flags, &timestamp, &sample);
        if (FAILED(hr)) {
            log::error("capture", std::format("ReadSample failed {}", hrText(hr)));
            return false;
        }
        if (flags & MF_SOURCE_READERF_ERROR) {
            log::error("capture", "capture device reported a stream error");
            return false;
        }
        if (flags & MF_SOURCE_READERF_ENDOFSTREAM) {
            log::error("capture", "capture device ended the stream");
            return false;
        }
        if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
            uint32_t w = 0, h = 0, fps = 0;
            GUID subtype{};
            if (!currentVideo(w, h, subtype, fps) || w != format.width || h != format.height) {
                log::error("capture", "capture format changed mid-stream");
                return false;
            }
        }
        if (!sample || (flags & MF_SOURCE_READERF_STREAMTICK)) return true;

        if (streamIndex == videoStream || (!audio && streamIndex == which)) {
            if (!copyVideo(sample.Get(), out.frame)) return false;
            out.video = true;
            return true;
        }
        if (audio && streamIndex == audioStream) {
            out.audio = copyAudio(sample.Get(), out.pcm);
            return true;
        }
        return true;
    }
};

MfCaptureSource::MfCaptureSource() = default;
MfCaptureSource::~MfCaptureSource() { close(); }

bool MfCaptureSource::open(const std::wstring& deviceNameSubstring) {
    p_ = std::make_unique<Impl>();
    if (!p_->open(deviceNameSubstring)) {
        p_->close();
        return false;
    }
    return true;
}

void MfCaptureSource::close() {
    if (p_) p_->close();
}

const CaptureFormat& MfCaptureSource::format() const {
    static const CaptureFormat kEmpty{};
    return p_ ? p_->format : kEmpty;
}

bool MfCaptureSource::read(CaptureRead& out) {
    if (!p_) return false;
    return p_->read(out);
}

} // namespace gta60::media
