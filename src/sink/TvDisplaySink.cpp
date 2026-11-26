#include "gta60/sink/TvDisplaySink.h"
#include "gta60/Log.h"

#include <algorithm>
#include <chrono>
#include <climits>
#include <cstdlib>
#include <format>
#include <mutex>
#include <vector>

using Microsoft::WRL::ComPtr;

namespace gta60::sink {
namespace {

constexpr wchar_t kClassName[] = L"GTA60TvDisplayWindow";

struct MonitorEntry {
    HMONITOR handle = nullptr;
    RECT rect{};
    std::wstring device;
    std::wstring friendly;
};

BOOL CALLBACK enumMonitorProc(HMONITOR handle, HDC, LPRECT, LPARAM param) {
    auto* out = reinterpret_cast<std::vector<MonitorEntry>*>(param);
    MONITORINFOEXW mi{};
    mi.cbSize = sizeof(mi);
    if (!GetMonitorInfoW(handle, &mi)) return TRUE;
    MonitorEntry entry;
    entry.handle = handle;
    entry.rect = mi.rcMonitor;
    entry.device = mi.szDevice;
    DISPLAY_DEVICEW dd{};
    dd.cb = sizeof(dd);
    if (EnumDisplayDevicesW(mi.szDevice, 0, &dd, 0) && dd.DeviceString[0]) entry.friendly = dd.DeviceString;
    out->push_back(std::move(entry));
    return TRUE;
}

std::vector<MonitorEntry> listMonitors() {
    std::vector<MonitorEntry> monitors;
    EnumDisplayMonitors(nullptr, nullptr, enumMonitorProc, reinterpret_cast<LPARAM>(&monitors));
    return monitors;
}

struct ModeEntry {
    uint32_t w = 0;
    uint32_t h = 0;
    uint32_t hz = 0;
};

std::vector<ModeEntry> listModes(const std::wstring& device) {
    std::vector<ModeEntry> modes;
    if (device.empty()) return modes;
    for (DWORD i = 0;; ++i) {
        DEVMODEW dm{};
        dm.dmSize = sizeof(dm);
        if (!EnumDisplaySettingsExW(device.c_str(), i, &dm, 0)) break;
        if (dm.dmPelsWidth < 640 || dm.dmPelsHeight < 360) continue;
        if (dm.dmBitsPerPel < 32) continue;
        if (dm.dmDisplayFlags & DM_INTERLACED) continue;
        const ModeEntry m{dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency};
        const bool duplicate = std::any_of(modes.begin(), modes.end(), [&](const ModeEntry& x) {
            return x.w == m.w && x.h == m.h && x.hz == m.hz;
        });
        if (!duplicate) modes.push_back(m);
    }
    return modes;
}

uint64_t area(const ModeEntry& m) { return static_cast<uint64_t>(m.w) * m.h; }

// Exact size if the panel has it, else the largest size that does not exceed the request (a TV that
// cannot do 4K gets 1440p, not a smaller 16:10 mode), else the largest it has.
ModeEntry chooseMode(const std::vector<ModeEntry>& modes, uint32_t wantW, uint32_t wantH, uint32_t wantHz) {
    if (modes.empty()) return {};
    const uint64_t wanted = static_cast<uint64_t>(wantW) * wantH;
    uint64_t targetArea = 0;
    for (const auto& m : modes) {
        if (m.w == wantW && m.h == wantH) {
            targetArea = wanted;
            break;
        }
    }
    if (targetArea == 0) {
        for (const auto& m : modes) {
            const uint64_t a = area(m);
            if (a <= wanted && a > targetArea) targetArea = a;
        }
    }
    if (targetArea == 0) {
        for (const auto& m : modes) targetArea = std::max(targetArea, area(m));
    }

    std::vector<ModeEntry> group;
    for (const auto& m : modes) {
        if (area(m) == targetArea) group.push_back(m);
    }
    if (group.empty()) return {};

    if (wantHz > 0) {
        const ModeEntry* best = nullptr;
        int bestDelta = INT_MAX;
        for (const auto& m : group) {
            const int delta = std::abs(static_cast<int>(m.hz) - static_cast<int>(wantHz));
            if (delta < bestDelta) {
                bestDelta = delta;
                best = &m;
            }
        }
        // 59 vs 60 and 119 vs 120 are the same thing on a television.
        if (best && bestDelta <= 2) return *best;
    }
    const ModeEntry* highest = &group.front();
    for (const auto& m : group) {
        if (m.hz > highest->hz) highest = &m;
    }
    return *highest;
}

bool currentMode(const std::wstring& device, ModeEntry& out) {
    DEVMODEW dm{};
    dm.dmSize = sizeof(dm);
    if (!EnumDisplaySettingsExW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm, 0)) return false;
    out = ModeEntry{dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency};
    return true;
}

bool setMode(const std::wstring& device, const ModeEntry& want, DEVMODEW* restore, bool* changed) {
    DEVMODEW dm{};
    dm.dmSize = sizeof(dm);
    if (!EnumDisplaySettingsExW(device.c_str(), ENUM_CURRENT_SETTINGS, &dm, 0)) return false;
    if (restore) *restore = dm;
    if (dm.dmPelsWidth == want.w && dm.dmPelsHeight == want.h &&
        std::abs(static_cast<int>(dm.dmDisplayFrequency) - static_cast<int>(want.hz)) <= 1) {
        *changed = false;
        return true;
    }
    const DWORD beforeW = dm.dmPelsWidth, beforeH = dm.dmPelsHeight, beforeHz = dm.dmDisplayFrequency;
    dm.dmPelsWidth = want.w;
    dm.dmPelsHeight = want.h;
    dm.dmDisplayFrequency = want.hz;
    dm.dmFields |= DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY;
    const LONG rc = ChangeDisplaySettingsExW(device.c_str(), &dm, nullptr, CDS_FULLSCREEN, nullptr);
    if (rc != DISP_CHANGE_SUCCESSFUL) {
        log::warn("tv", std::format("display mode {}x{}@{} refused (DISP_CHANGE {})", want.w, want.h,
                                    want.hz, int(rc)));
        return false;
    }
    ModeEntry now{};
    currentMode(device, now);
    *changed = (now.w != beforeW || now.h != beforeH || now.hz != beforeHz);
    if (now.w != want.w || now.h != want.h) {
        log::warn("tv", std::format("display settled on {}x{}@{} instead of {}x{}@{}", now.w, now.h, now.hz,
                                    want.w, want.h, want.hz));
    }
    return true;
}

std::wstring widened(const std::string& s) { return log::widen(s); }

} // namespace

LRESULT CALLBACK tvWindowProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    auto* self = reinterpret_cast<TvDisplaySink*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
    case WM_CLOSE:
    case WM_DESTROY:
        if (self) self->onClose();
        return 0;
    case WM_KEYDOWN:
        if (wParam == VK_ESCAPE && self) self->onClose();
        return 0;
    case WM_SYSKEYDOWN:
        if (wParam == VK_F4 && self) self->onClose(); // Alt+F4 arrives as WM_SYSKEYDOWN
        return 0;
    case WM_SETCURSOR:
        if (self && self->info_.fullscreen && self->cursorHidden_) {
            SetCursor(nullptr);
            return TRUE;
        }
        break;
    default:
        break;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

TvDisplaySink::TvDisplaySink() = default;
TvDisplaySink::~TvDisplaySink() { shutdown(); }

bool TvDisplaySink::selectDisplay(const TvDisplayConfig& cfg) {
    auto monitors = listMonitors();
    if (monitors.empty()) {
        log::error("tv", "no displays are attached");
        return false;
    }
    std::wstring match = widened(cfg.displayMatch);
    int index = -1;
    if (!cfg.displayMatch.empty()) {
        char* end = nullptr;
        const long parsed = std::strtol(cfg.displayMatch.c_str(), &end, 10);
        if (end && *end == '\0') index = static_cast<int>(parsed);
    }

    const MonitorEntry* chosen = nullptr;
    if (index >= 0) {
        if (index < static_cast<int>(monitors.size())) chosen = &monitors[index];
    } else if (!match.empty()) {
        std::wstring folded = match;
        CharLowerBuffW(folded.data(), static_cast<DWORD>(folded.size()));
        for (const auto& m : monitors) {
            std::wstring haystack = m.friendly + L" " + m.device;
            CharLowerBuffW(haystack.data(), static_cast<DWORD>(haystack.size()));
            if (haystack.find(folded) != std::wstring::npos) {
                chosen = &m;
                break;
            }
        }
    }
    if (!chosen && index < 0 && match.empty()) {
        // Primary display.
        HMONITOR primary = MonitorFromPoint(POINT{0, 0}, MONITOR_DEFAULTTOPRIMARY);
        for (const auto& m : monitors) {
            if (m.handle == primary) chosen = &m;
        }
        if (!chosen) chosen = &monitors.front();
    }
    if (!chosen) {
        log::error("tv", std::format("no display matches '{}'", cfg.displayMatch));
        return false;
    }

    // `chosen` points into a local vector, so copy the values we still need.
    displayDevice_ = chosen->device;
    info_.displayName = chosen->friendly.empty() ? chosen->device : chosen->friendly;

    DEVMODEW restore{};
    bool changed = false;
    ModeEntry want{};
    if (cfg.windowed) {
        // A windowed run only exercises the plumbing; leave the desktop alone and let the client
        // area decide the output size.
        if (!currentMode(displayDevice_, want)) return false;
    } else {
        const auto modes = listModes(displayDevice_);
        want = chooseMode(modes, cfg.width, cfg.height, cfg.refreshHz);
        if (want.w == 0) {
            log::error("tv", "the display reports no usable modes");
            return false;
        }
        if (want.w != cfg.width || want.h != cfg.height) {
            log::warn("tv", std::format("{} cannot do {}x{}; using {}x{}@{}", log::narrow(info_.displayName),
                                        cfg.width, cfg.height, want.w, want.h, want.hz));
        }
        if (cfg.refreshHz > 0 && std::abs(static_cast<int>(want.hz) - static_cast<int>(cfg.refreshHz)) > 2) {
            log::warn("tv", std::format("{} Hz was not available; using {} Hz", cfg.refreshHz, want.hz));
        }
        if (cfg.setDisplayMode) {
            if (!setMode(displayDevice_, want, &restore, &changed)) {
                ModeEntry now{};
                if (!currentMode(displayDevice_, now)) return false;
                want = now;
                changed = false;
            }
        } else {
            ModeEntry now{};
            if (!currentMode(displayDevice_, now)) return false;
            want = now;
            changed = false;
        }
    }
    restoreMode_ = restore;
    info_.modeChanged = changed;
    info_.width = want.w;
    info_.height = want.h;
    info_.refreshHz = want.hz;

    log::info("tv", std::format("display '{}' ({}) {}x{} @ {} Hz{}", log::narrow(info_.displayName),
                                log::narrow(displayDevice_), info_.width, info_.height, info_.refreshHz,
                                changed ? " [mode changed, restored on exit]" : ""));
    return true;
}

bool TvDisplaySink::createWindow(const TvDisplayConfig& cfg) {
    HINSTANCE instance = GetModuleHandleW(nullptr);
    static std::once_flag classOnce;
    std::call_once(classOnce, [instance] {
        WNDCLASSEXW wc{};
        wc.cbSize = sizeof(wc);
        wc.style = CS_HREDRAW | CS_VREDRAW;
        wc.lpfnWndProc = tvWindowProc;
        wc.hInstance = instance;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = kClassName;
        if (!RegisterClassExW(&wc)) {
            const DWORD err = GetLastError();
            if (err != ERROR_CLASS_ALREADY_EXISTS) {
                log::warn("tv", std::format("RegisterClassExW failed {}", err));
            }
        }
    });

    const bool fullscreen = !cfg.windowed;
    DWORD style = WS_POPUP;
    RECT rect{0, 0, static_cast<LONG>(info_.width), static_cast<LONG>(info_.height)};
    if (fullscreen) {
        // Cover the chosen monitor exactly.
        HMONITOR target = nullptr;
        for (const auto& m : listMonitors()) {
            if (m.device == displayDevice_) target = m.handle;
        }
        MONITORINFO mi{};
        mi.cbSize = sizeof(mi);
        if (target && GetMonitorInfoW(target, &mi)) rect = mi.rcMonitor;
    } else {
        style = WS_OVERLAPPEDWINDOW;
        rect.right = static_cast<LONG>(cfg.windowWidth);
        rect.bottom = static_cast<LONG>(cfg.windowHeight);
        AdjustWindowRectEx(&rect, style, FALSE, 0);
        const int w = rect.right - rect.left;
        const int h = rect.bottom - rect.top;
        rect.left = 60;
        rect.top = 60;
        rect.right = rect.left + w;
        rect.bottom = rect.top + h;
    }

    hwnd_ = CreateWindowExW(fullscreen && cfg.topmost ? WS_EX_TOPMOST : 0, kClassName, L"GTA-Sixty TV",
                            style, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top,
                            nullptr, nullptr, instance, nullptr);
    if (!hwnd_) {
        log::error("tv", std::format("CreateWindowExW failed {}", GetLastError()));
        return false;
    }
    SetWindowLongPtrW(hwnd_, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));
    ShowWindow(hwnd_, SW_SHOW);
    if (fullscreen) {
        SetForegroundWindow(hwnd_);
        SetWindowPos(hwnd_, HWND_TOPMOST, rect.left, rect.top, rect.right - rect.left,
                     rect.bottom - rect.top, SWP_SHOWWINDOW);
    }
    UpdateWindow(hwnd_);

    // The swap chain has to match the client area exactly (flip model, identity scaling).
    RECT client{};
    GetClientRect(hwnd_, &client);
    info_.width = static_cast<uint32_t>(client.right - client.left);
    info_.height = static_cast<uint32_t>(client.bottom - client.top);
    if (info_.width == 0 || info_.height == 0) {
        log::error("tv", "window has an empty client area");
        return false;
    }
    info_.fullscreen = fullscreen;
    if (cfg.hideCursor && fullscreen) {
        ShowCursor(FALSE);
        cursorHidden_ = true;
    }
    return true;
}

bool TvDisplaySink::createSwapChain(pipeline::D3D12Context& ctx, const TvDisplayConfig& cfg) {
    // A D3D12 device does not expose IDXGIDevice, so the factory and adapter are found by the
    // device's LUID instead - that also guarantees the swap chain is created on the same GPU the
    // pipeline renders on.
    ComPtr<IDXGIFactory2> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(&factory))) || !factory) {
        log::error("tv", "CreateDXGIFactory1 failed");
        return false;
    }
    ComPtr<IDXGIFactory4> factory4;
    ComPtr<IDXGIAdapter1> adapter;
    if (FAILED(factory.As(&factory4)) || !factory4 ||
        FAILED(factory4->EnumAdapterByLuid(ctx.adapterLuid(), IID_PPV_ARGS(&adapter))) || !adapter) {
        log::error("tv", "cannot find the DXGI adapter for the D3D12 device");
        return false;
    }
    BOOL tearSupported = FALSE;
    ComPtr<IDXGIFactory5> factory5;
    if (SUCCEEDED(factory.As(&factory5)) && factory5) {
        if (FAILED(factory5->CheckFeatureSupport(DXGI_FEATURE_PRESENT_ALLOW_TEARING, &tearSupported,
                                                 sizeof(tearSupported)))) {
            tearSupported = FALSE;
        }
    }

    info_.format = DXGI_FORMAT_B8G8R8A8_UNORM;
    DXGI_SWAP_CHAIN_DESC1 desc{};
    desc.Width = info_.width;
    desc.Height = info_.height;
    desc.Format = info_.format;
    desc.Stereo = FALSE;
    desc.SampleDesc = DXGI_SAMPLE_DESC{1, 0};
    desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    desc.BufferCount = std::max<uint32_t>(2, cfg.backBuffers);
    desc.Scaling = DXGI_SCALING_STRETCH;
    desc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    desc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
    desc.Flags = tearSupported ? DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING : 0;

    ComPtr<IDXGISwapChain1> chain1;
    const HRESULT hr = factory->CreateSwapChainForHwnd(ctx.queue(), hwnd_, &desc, nullptr, nullptr, &chain1);
    if (FAILED(hr) || !chain1) {
        log::error("tv", std::format("CreateSwapChainForHwnd failed hr=0x{:X}", unsigned(hr)));
        return false;
    }
    // Alt+Enter must not switch modes behind our back; the mode is this class's business.
    factory->MakeWindowAssociation(hwnd_, DXGI_MWA_NO_ALT_ENTER | DXGI_MWA_NO_WINDOW_CHANGES);
    if (FAILED(chain1.As(&swapChain_)) || !swapChain_) {
        log::error("tv", "swap chain is not IDXGISwapChain3");
        return false;
    }
    ComPtr<IDXGISwapChain2> chain2;
    if (SUCCEEDED(swapChain_.As(&chain2)) && chain2) chain2->SetMaximumFrameLatency(1);

    backBuffers_.resize(desc.BufferCount);
    for (uint32_t i = 0; i < desc.BufferCount; ++i) {
        if (FAILED(swapChain_->GetBuffer(i, IID_PPV_ARGS(&backBuffers_[i]))) || !backBuffers_[i]) {
            log::error("tv", std::format("swap chain buffer {} is unavailable", i));
            return false;
        }
    }
    info_.tearing = tearSupported != FALSE;
    log::info("tv", std::format("swap chain {}x{} {} buffers flip-discard tearing={} fullscreen={}",
                                info_.width, info_.height, desc.BufferCount, info_.tearing, info_.fullscreen));
    return true;
}

bool TvDisplaySink::create(pipeline::D3D12Context& ctx, const TvDisplayConfig& cfg) {
    shutdown();
    if (!ctx.device() || !ctx.queue()) {
        log::error("tv", "D3D12 context is not initialized");
        return false;
    }
    closed_ = false;
    if (!selectDisplay(cfg)) return false;
    if (!createWindow(cfg)) {
        shutdown();
        return false;
    }
    if (!createSwapChain(ctx, cfg)) {
        shutdown();
        return false;
    }
    return true;
}

bool TvDisplaySink::present(pipeline::D3D12Context& ctx, ID3D12Resource* bgra, bool vsync) {
    if (!swapChain_ || !bgra || closed_) return false;
    const D3D12_RESOURCE_DESC desc = bgra->GetDesc();
    if (desc.Width != info_.width || desc.Height != info_.height || desc.Format != info_.format) {
        log::error("tv", std::format("present size/format mismatch: {}x{} fmt={} vs {}x{} fmt={}",
                                     desc.Width, desc.Height, int(desc.Format), info_.width, info_.height,
                                     int(info_.format)));
        return false;
    }

    ID3D12GraphicsCommandList* list = nullptr;
    if (!ctx.beginCommandList(&list)) return false;
    const UINT index = swapChain_->GetCurrentBackBufferIndex();
    if (index >= backBuffers_.size()) return false;
    ID3D12Resource* back = backBuffers_[index].Get();
    ctx.transition(list, back, D3D12_RESOURCE_STATE_PRESENT, D3D12_RESOURCE_STATE_COPY_DEST);
    list->CopyResource(back, bgra);
    ctx.transition(list, back, D3D12_RESOURCE_STATE_COPY_DEST, D3D12_RESOURCE_STATE_PRESENT);
    if (!ctx.submitCommandList()) return false;

    const UINT syncInterval = vsync ? 1u : 0u;
    const UINT flags = (!vsync && info_.tearing) ? DXGI_PRESENT_ALLOW_TEARING : 0u;
    const auto t0 = std::chrono::steady_clock::now();
    const HRESULT hr = swapChain_->Present(syncInterval, flags);
    const auto t1 = std::chrono::steady_clock::now();
    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    stats_.presented += 1;
    stats_.presentMsSum += ms;
    stats_.presentMsMax = std::max(stats_.presentMsMax, ms);

    if (hr == DXGI_STATUS_OCCLUDED) {
        // Another window covers the display (or the session locked). Nothing to do but wait.
        ++stats_.occluded;
        Sleep(16);
        return true;
    }
    if (FAILED(hr)) {
        log::error("tv", std::format("Present failed hr=0x{:X}", unsigned(hr)));
        return false;
    }
    return true;
}

bool TvDisplaySink::pumpMessages() {
    MSG msg;
    while (PeekMessageW(&msg, hwnd_, 0, 0, PM_REMOVE)) {
        if (msg.message == WM_QUIT) return false;
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
    return !closed_;
}

void TvDisplaySink::onClose() { closed_ = true; }

void TvDisplaySink::shutdown() {
    backBuffers_.clear();
    swapChain_.Reset();
    if (hwnd_) {
        SetWindowLongPtrW(hwnd_, GWLP_USERDATA, 0);
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
    }
    if (cursorHidden_) {
        ShowCursor(TRUE);
        cursorHidden_ = false;
    }
    if (info_.modeChanged && !displayDevice_.empty()) {
        // Put the desktop back the way we found it.
        DEVMODEW dm = restoreMode_;
        dm.dmSize = sizeof(dm);
        const LONG rc = ChangeDisplaySettingsExW(displayDevice_.c_str(), &dm, nullptr, 0, nullptr);
        log::info("tv", std::format("restored the display mode ({})", rc == DISP_CHANGE_SUCCESSFUL ? "ok" : "failed"));
        info_.modeChanged = false;
    }
    displayDevice_.clear();
    info_ = {};
    closed_ = false;
}

TvDisplaySink::Stats TvDisplaySink::consumeStats() {
    Stats s = stats_;
    stats_ = {};
    return s;
}

void TvDisplaySink::listDisplays() {
    const auto monitors = listMonitors();
    std::printf("%zu display(s):\n", monitors.size());
    for (size_t i = 0; i < monitors.size(); ++i) {
        const auto& m = monitors[i];
        ModeEntry now{};
        currentMode(m.device, now);
        const std::string friendly = log::narrow(m.friendly);
        const std::string device = log::narrow(m.device);
        std::printf("  [%zu] %s  (%s)  current %ux%u@%u\n", i, friendly.c_str(), device.c_str(), now.w,
                    now.h, now.hz);
        std::vector<std::string> printed;
        for (const auto& mode : listModes(m.device)) {
            const std::string text = std::format("{}x{}@{}", mode.w, mode.h, mode.hz);
            if (std::find(printed.begin(), printed.end(), text) == printed.end()) printed.push_back(text);
        }
        std::printf("      %zu mode(s):", printed.size());
        for (const auto& text : printed) std::printf(" %s", text.c_str());
        std::printf("\n");
    }
}

} // namespace gta60::sink
