#include "gta60/media/OpusWasapiAudio.h"
#include "gta60/Log.h"

#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <opus.h>
#include <wrl/client.h>

#include <algorithm>
#include <cstring>
#include <format>
#include <mutex>
#include <vector>

namespace gta60::media {
using Microsoft::WRL::ComPtr;

struct OpusWasapiAudio::Impl {
    OpusDecoder* decoder = nullptr;
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioClient> client;
    ComPtr<IAudioRenderClient> render;
    UINT32 bufferFrames = 0;
    WORD channels = 2;
    DWORD sampleRate = 48000;
    bool isFloat = true;
    bool started = false;
    std::mutex mutex;
    std::vector<float> decodeBuf;
};

OpusWasapiAudio::OpusWasapiAudio() : p_(std::make_unique<Impl>()) {}
OpusWasapiAudio::~OpusWasapiAudio() { stop(); }

bool OpusWasapiAudio::start() {
    std::lock_guard lock(p_->mutex);
    if (p_->started) return true;

    int err = OPUS_OK;
    p_->decoder = opus_decoder_create(48000, 2, &err);
    if (!p_->decoder || err != OPUS_OK) {
        log::warn("wasapi-opus", std::format("opus_decoder_create failed err={}", err));
        return false;
    }
    p_->decodeBuf.resize(5760 * 2);

    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), nullptr, CLSCTX_ALL, IID_PPV_ARGS(&p_->enumerator));
    if (FAILED(hr) || FAILED(p_->enumerator->GetDefaultAudioEndpoint(eRender, eConsole, &p_->device))) {
        log::warn("wasapi-opus", "no default WASAPI audio render endpoint");
        stop();
        return false;
    }
    if (FAILED(p_->device->Activate(__uuidof(IAudioClient), CLSCTX_ALL, nullptr,
                                    reinterpret_cast<void**>(p_->client.GetAddressOf())))) {
        log::warn("wasapi-opus", "IAudioClient activation failed");
        stop();
        return false;
    }

    WAVEFORMATEX* mixFmt = nullptr;
    if (FAILED(p_->client->GetMixFormat(&mixFmt)) || !mixFmt) {
        stop();
        return false;
    }

    WAVEFORMATEXTENSIBLE target{};
    target.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    target.Format.nChannels = 2;
    target.Format.nSamplesPerSec = 48000;
    target.Format.wBitsPerSample = 32;
    target.Format.nBlockAlign = 8;
    target.Format.nAvgBytesPerSec = 48000 * 8;
    target.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    target.Samples.wValidBitsPerSample = 32;
    target.dwChannelMask = SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT;
    target.SubFormat = KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;

    WAVEFORMATEX* closest = nullptr;
    hr = p_->client->IsFormatSupported(AUDCLNT_SHAREMODE_SHARED, &target.Format, &closest);
    const WAVEFORMATEX* useFmt = (hr == S_OK) ? &target.Format : mixFmt;
    p_->channels = useFmt->nChannels;
    p_->sampleRate = useFmt->nSamplesPerSec;
    p_->isFloat = (useFmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT) ||
                  (useFmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE &&
                   reinterpret_cast<const WAVEFORMATEXTENSIBLE*>(useFmt)->SubFormat == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);

    hr = p_->client->Initialize(AUDCLNT_SHAREMODE_SHARED,
                                AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
                                400000, // 40ms shared buffer for jitter-free VR forwarding
                                0, useFmt, nullptr);
    if (closest) CoTaskMemFree(closest);
    CoTaskMemFree(mixFmt);

    if (FAILED(hr) || FAILED(p_->client->GetBufferSize(&p_->bufferFrames)) ||
        FAILED(p_->client->GetService(IID_PPV_ARGS(&p_->render))) || FAILED(p_->client->Start())) {
        log::warn("wasapi-opus", std::format("IAudioClient Initialize/Start failed hr=0x{:X}", unsigned(hr)));
        stop();
        return false;
    }

    p_->started = true;
    log::info("wasapi-opus", std::format("WASAPI Opus sink started ({} Hz, {} ch, {} frames buffer)",
                                         p_->sampleRate, p_->channels, p_->bufferFrames));
    return true;
}

void OpusWasapiAudio::pushOpusPacket(const uint8_t* data, size_t size) {
    std::lock_guard lock(p_->mutex);
    if (!p_->started || !p_->decoder || !data || size == 0) return;

    const int frames = opus_decode_float(p_->decoder, data, static_cast<opus_int32>(size),
                                         p_->decodeBuf.data(), 5760, 0);
    if (frames <= 0) return;

    UINT32 padding = 0;
    if (FAILED(p_->client->GetCurrentPadding(&padding))) return;
    const UINT32 avail = p_->bufferFrames > padding ? p_->bufferFrames - padding : 0;
    const UINT32 toWrite = std::min<UINT32>(avail, static_cast<UINT32>(frames));
    if (toWrite == 0) return;

    BYTE* dst = nullptr;
    if (FAILED(p_->render->GetBuffer(toWrite, &dst)) || !dst) return;

    if (p_->isFloat && p_->channels == 2) {
        std::memcpy(dst, p_->decodeBuf.data(), static_cast<size_t>(toWrite) * 2 * sizeof(float));
    } else if (p_->isFloat) {
        float* f = reinterpret_cast<float*>(dst);
        for (UINT32 i = 0; i < toWrite; ++i) {
            const float l = p_->decodeBuf[i * 2];
            const float r = p_->decodeBuf[i * 2 + 1];
            for (WORD c = 0; c < p_->channels; ++c) {
                f[i * p_->channels + c] = (c == 0) ? l : (c == 1 ? r : 0.0f);
            }
        }
    } else {
        int16_t* s = reinterpret_cast<int16_t*>(dst);
        for (UINT32 i = 0; i < toWrite; ++i) {
            for (WORD c = 0; c < p_->channels; ++c) {
                const float v = std::clamp(p_->decodeBuf[i * 2 + (c & 1)], -1.0f, 1.0f);
                s[i * p_->channels + c] = static_cast<int16_t>(v * 32767.0f);
            }
        }
    }
    p_->render->ReleaseBuffer(toWrite, 0);
}

void OpusWasapiAudio::stop() {
    std::lock_guard lock(p_->mutex);
    p_->started = false;
    if (p_->client) {
        p_->client->Stop();
        p_->client.Reset();
    }
    p_->render.Reset();
    p_->device.Reset();
    p_->enumerator.Reset();
    if (p_->decoder) {
        opus_decoder_destroy(p_->decoder);
        p_->decoder = nullptr;
    }
}

} // namespace gta60::media
