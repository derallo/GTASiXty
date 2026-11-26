#pragma once
#include <windows.h>

#include "gta60/pipeline/D3D12Context.h"

#include <d3d12.h>
#include <dxgi1_6.h>
#include <wrl/client.h>

#include <cstdint>
#include <string>

// TV mode's output stage: a borderless fullscreen window on a chosen display, driven by a
// flip-model DXGI swap chain on the D3D12 queue the enhancement pipeline already uses. Frames arrive
// as BGRA8 textures at exactly the display size and are copied into the back buffer, so the display
// path costs one 4K copy (about 0.1 ms on this GPU) and nothing else - there is no encoder, decoder,
// network or headset in this mode at all.
namespace gta60::sink {

template <typename T>
using ComPtr = Microsoft::WRL::ComPtr<T>;

struct TvDisplayConfig {
    uint32_t width = 3840;
    uint32_t height = 2160;
    // 0 = the highest refresh the display offers at that size. TVs report 59/60/119/120; the sink
    // accepts ±2 Hz as a match.
    uint32_t refreshHz = 0;
    // Friendly-name substring (e.g. "LG TV", "SAMSUNG") or a 0-based index as text. Empty = primary.
    std::string displayMatch;
    // Development/smoke-test path: a normal window instead of fullscreen. The swap chain still
    // matches the client area exactly, so the caller must size its output to the window.
    bool windowed = false;
    uint32_t windowWidth = 1280;
    uint32_t windowHeight = 720;
    // Switch the display to the requested mode, and put the previous one back on shutdown.
    bool setDisplayMode = true;
    bool topmost = true;
    bool hideCursor = true;
    uint32_t backBuffers = 2;
};

class TvDisplaySink {
public:
    struct Info {
        std::wstring displayName;
        uint32_t width = 0;
        uint32_t height = 0;
        uint32_t refreshHz = 0;
        DXGI_FORMAT format = DXGI_FORMAT_B8G8R8A8_UNORM;
        bool fullscreen = false;
        bool tearing = false;
        bool modeChanged = false;
    };

    struct Stats {
        uint64_t presented = 0;
        double presentMsSum = 0.0;   // wall time inside Present(), i.e. vsync wait + queue drain
        double presentMsMax = 0.0;
        uint64_t occluded = 0;
    };

    TvDisplaySink();
    ~TvDisplaySink();
    TvDisplaySink(const TvDisplaySink&) = delete;
    TvDisplaySink& operator=(const TvDisplaySink&) = delete;

    bool create(pipeline::D3D12Context& ctx, const TvDisplayConfig& cfg);
    void shutdown();

    // Copies `bgra` (must be width x height at info().format) into the back buffer and presents.
    // `vsync` = true waits for the next vblank, which is what paced frame generation needs;
    // false presents immediately and only works when the chain was created with tearing.
    bool present(pipeline::D3D12Context& ctx, ID3D12Resource* bgra, bool vsync);

    // Pumps window messages. Returns false once the user closed the window or pressed Escape.
    bool pumpMessages();

    const Info& info() const { return info_; }
    Stats consumeStats();

    // Logs every display with its advertised modes. Used by --tv-list.
    static void listDisplays();

private:
    friend LRESULT CALLBACK tvWindowProc(HWND, UINT, WPARAM, LPARAM);
    bool selectDisplay(const TvDisplayConfig& cfg);
    bool createWindow(const TvDisplayConfig& cfg);
    bool createSwapChain(pipeline::D3D12Context& ctx, const TvDisplayConfig& cfg);
    void onClose();

    ComPtr<IDXGISwapChain3> swapChain_;
    std::vector<ComPtr<ID3D12Resource>> backBuffers_;
    HWND hwnd_ = nullptr;
    std::wstring displayDevice_;
    DEVMODEW restoreMode_{};
    Info info_{};
    bool closed_ = false;
    bool cursorHidden_ = false;
    Stats stats_;
};

} // namespace gta60::sink
