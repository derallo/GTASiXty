#include "gta60/media/WasapiPcmRender.h"
#include "gta60/Log.h"

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <propsys.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstring>
#include <format>
#include <mutex>
#include <vector>

namespace gta60::media {
using Microsoft::WRL::ComPtr;

namespace {

// PKEY_Device_FriendlyName ({A45C254E-DF1C-4EFD-8020-67D146A850E0}, 14). Spelled out instead of
// pulling in functiondiscoverykeys_devpkey.h, which needs the devpkey headers in a specific order.
const PROPERTYKEY kFriendlyName = {
    {0xa45c254e, 0xdf1c, 0x4efd, {0x80, 0x20, 0x67, 0xd1, 0x46, 0xa8, 0x50, 0xe0}}, 14};

std::wstring fold(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(s.data(), static_cast<DWORD>(s.size()));
    return s;
}

// Friendly name out of the endpoint's property store. Devices that have none still open; they are
// just harder to select by name.
std::wstring endpointName(IMMDevice* device) {
    if (!device) return {};
    ComPtr<IPropertyStore> store;
    if (FAILED(device->OpenPropertyStore(STGM_READ, &store)) || !store) return {};
    PROPVARIANT var;
    PropVariantInit(&var);
    std::wstring out;
    if (SUCCEEDED(store->GetValue(kFriendlyName, &var)) && var.vt == VT_LPWSTR && var.pwszVal) {
        out = var.pwszVal;
    }
    PropVariantClear(&var);
    return out;
}

} // namespace

struct WasapiPcmRender::Impl {
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioClient> client;
    ComPtr<IAudioRenderClient> render;
    UINT32 bufferFrames = 0;
    WORD channels = 2;
    DWORD sampleRate = 48000;
    bool isFloat = true;
    bool started = false;
    uint32_t startupDelayFrames = 0;
    std::string name;
    std::mutex mutex;
    Stats stats;

    bool openDevice(const std::wstring& needle) {
        HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL,
                                      IID_PPV_ARGS(&enumerator));
        if (FAILED(hr) || !enumerator) {
            log::warn("wasapi-pcm", "MMDeviceEnumerator activation failed");
            return false;
        }
        if (needle.empty()) {
            if (FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) || !device) {
                log::warn("wasapi-pcm", "no default audio render endpoint");
                return false;
            }
            name = log::narrow(endpointName(device.Get()));
            return true;
        }

        ComPtr<IMMDeviceCollection> collection;
        if (FAILED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, &collection)) || !collection) {
            log::warn("wasapi-pcm", "EnumAudioEndpoints failed");
            return false;
        }
        UINT32 count = 0;
        collection->GetCount(&count);
        const std::wstring want = fold(needle);
        std::wstring firstSeen;
        for (UINT32 i = 0; i < count; ++i) {
            ComPtr<IMMDevice> candidate;
            if (FAILED(collection->Item(i, &candidate)) || !candidate) continue;
            const std::wstring friendly = endpointName(candidate.Get());
            if (firstSeen.empty()) firstSeen = friendly;
            if (fold(friendly).find(want) != std::wstring::npos) {
                device = candidate;
                name = log::narrow(friendly);
                return true;
            }
        }
        log::warn("wasapi-pcm", std::format("no render endpoint matches '{}'; using the default", log::narrow(needle)));
        if (FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &device)) || !device) return false;
        name = log::narrow(endpointName(device.Get()));
        return true;
    }

    bool start(const std::wstring& needle, uint32_t bufferMs) {
        std::lock_guard lock(mutex);
        if (started) return true;
        if (!openDevice(needle)) return false;
        if (FAILED(device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                    reinterpret_cast<void**>(client.GetAddressOf()))) || !client) {
            log::warn("wasapi-pcm", "IAudioClient activation failed");
            return false;
        }

        WAVEFORMATEX* mixFmt = nullptr;
        if (FAILED(client->GetMixFormat(&mixFmt)) || !mixFmt) {
            log::warn("wasapi-pcm", "GetMixFormat failed");
            return false;
        }

        // Ask for exactly what the card gives us: 48 kHz stereo S16. Shared mode with conversion
        // allowed means the endpoint may still take it even if the mixer runs 32-bit float.
        WAVEFORMATEXTENSIBLE target{};
        target.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
        target.Format.nChannels = 2;
        target.Format.nSamplesPerSec = 48000;
        target.Format.wBitsPerSample = 16;
        target.Format.nBlockAlign = 4;
        target.Format.nAvgBytesPerSec = 48000 * 4;
        target.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
        target.Samples.wValidBitsPerSample = 16;
        target.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
        target.SubFormat = KSDATAFORMAT_SUBTYPE_PCM;

        WAVEFORMATEX* closest = nullptr;
        const HRESULT probe = client->IsFormatSupported(AUDCLNT_SHAREMODE_SHARED, &target.Format, &closest);
        const WAVEFORMATEX* useFmt = (probe == S_OK) ? &target.Format : mixFmt;
        channels = useFmt->nChannels;
        sampleRate = useFmt->nSamplesPerSec;
        isFloat = (useFmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) ||
                  (useFmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                   reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(useFmt)->SubFormat ==
                       KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);

        const REFERENCE_TIME duration = static_cast<REFERENCE_TIME>(std::max<uint32_t>(bufferMs, 10)) * 10000;
        HRESULT hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                        AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                        duration, 0, useFmt, nullptr);
        if (FAILED(hr) && useFmt != mixFmt) {
            // Some endpoints refuse the S16 request; the mixer format always works.
            hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                    duration, 0, mixFmt, nullptr);
        }
        if (closest) CoTaskMemFree(closest);
        CoTaskMemFree(mixFmt);

        if (FAILED(hr) || FAILED(client->GetBufferSize(&bufferFrames)) ||
            FAILED(client->GetService(IID_PPV_ARGS(&render))) || FAILED(client->Start())) {
            log::warn("wasapi-pcm", std::format("audio endpoint Initialize/Start failed hr=0x{:X}", unsigned(hr)));
            client.Reset();
            render.Reset();
            return false;
        }

        started = true;
        log::info("wasapi-pcm", std::format("WASAPI PCM sink '{}' {} Hz {} ch {} {} frames ({:.1f} ms)",
                                            name, sampleRate, channels, isFloat ? "float" : "s16",
                                            bufferFrames, 1000.0 * bufferFrames / double(sampleRate)));
        return true;
    }

    void stop() {
        std::lock_guard lock(mutex);
        if (client) client->Stop();
        started = false;
        client.Reset();
        render.Reset();
        device.Reset();
        enumerator.Reset();
    }

    void push(const int16_t* data, size_t frames) {
        std::lock_guard lock(mutex);
        if (!started || !data || frames == 0) return;

        UINT32 offset = 0;
        if (startupDelayFrames > 0) {
            // Fill the queue with silence first: that shifts the whole stream later in time without
            // needing a real delay line, which is exactly what a lip-sync offset needs.
            const size_t silenceFrames = std::min<size_t>(startupDelayFrames, frames);
            UINT32 padding = 0;
            if (SUCCEEDED(client->GetCurrentPadding(&padding))) {
                const UINT32 avail = bufferFrames > padding ? bufferFrames - padding : 0;
                const UINT32 n = std::min<UINT32>(avail, static_cast<UINT32>(silenceFrames));
                BYTE* dst = nullptr;
                if (n > 0 && SUCCEEDED(render->GetBuffer(n, &dst)) && dst) {
                    std::memset(dst, 0, static_cast<size_t>(n) * channels * (isFloat ? sizeof(float) : sizeof(int16_t)));
                    render->ReleaseBuffer(n, 0);
                }
            }
            startupDelayFrames -= static_cast<uint32_t>(silenceFrames);
            offset = static_cast<UINT32>(silenceFrames);
            if (offset >= frames) return;
        }

        UINT32 padding = 0;
        if (FAILED(client->GetCurrentPadding(&padding))) return;
        stats.paddingFrames = padding;
        const UINT32 avail = bufferFrames > padding ? bufferFrames - padding : 0;
        const size_t want = frames - offset;
        const UINT32 toWrite = std::min<UINT32>(avail, static_cast<UINT32>(want));
        if (toWrite == 0) {
            stats.framesDropped += want;
            return;
        }

        BYTE* dst = nullptr;
        if (FAILED(render->GetBuffer(toWrite, &dst)) || !dst) {
            stats.framesDropped += want;
            return;
        }
        const int16_t* src = data + static_cast<size_t>(offset) * 2;
        if (isFloat && channels == 2) {
            float* out = reinterpret_cast<float*>(dst);
            for (UINT32 i = 0; i < toWrite; ++i) {
                out[i * 2 + 0] = static_cast<float>(src[i * 2 + 0]) / 32768.0f;
                out[i * 2 + 1] = static_cast<float>(src[i * 2 + 1]) / 32768.0f;
            }
        } else if (isFloat) {
            float* out = reinterpret_cast<float*>(dst);
            for (UINT32 i = 0; i < toWrite; ++i) {
                for (WORD c = 0; c < channels; ++c) {
                    const int16_t v = (c < 2) ? src[i * 2 + c] : int16_t{0};
                    out[i * channels + c] = static_cast<float>(v) / 32768.0f;
                }
            }
        } else if (channels == 2) {
            std::memcpy(dst, src, static_cast<size_t>(toWrite) * 2 * sizeof(int16_t));
        } else {
            int16_t* out = reinterpret_cast<int16_t*>(dst);
            for (UINT32 i = 0; i < toWrite; ++i) {
                for (WORD c = 0; c < channels; ++c) {
                    out[i * channels + c] = (c < 2) ? src[i * 2 + c] : int16_t{0};
                }
            }
        }
        render->ReleaseBuffer(toWrite, 0);
        stats.framesPushed += toWrite;
        stats.framesDropped += want - toWrite;
    }
};

WasapiPcmRender::WasapiPcmRender() : p_(std::make_unique<Impl>()) {}
WasapiPcmRender::~WasapiPcmRender() { stop(); }

bool WasapiPcmRender::start(const std::wstring& deviceNameSubstring, uint32_t bufferMs) {
    return p_->start(deviceNameSubstring, bufferMs);
}

void WasapiPcmRender::stop() { p_->stop(); }

bool WasapiPcmRender::active() const { return p_->started; }

void WasapiPcmRender::setStartupDelayMs(uint32_t ms) {
    p_->startupDelayFrames = static_cast<uint32_t>((uint64_t(ms) * p_->sampleRate) / 1000);
}

void WasapiPcmRender::pushStereo16(const int16_t* interleaved, size_t frames) {
    p_->push(interleaved, frames);
}

std::string WasapiPcmRender::deviceName() const { return p_->name; }

WasapiPcmRender::Stats WasapiPcmRender::consumeStats() {
    std::lock_guard lock(p_->mutex);
    Stats s = p_->stats;
    p_->stats = {};
    return s;
}

} // namespace gta60::media
