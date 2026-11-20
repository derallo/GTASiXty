#include "gta60/Log.h"

#include <windows.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>

namespace gta60::log {
namespace {

std::atomic<bool> g_verbose{false};
std::mutex g_logMutex;

void emit(const char* level, std::string_view tag, std::string_view message) {
    using namespace std::chrono;
    static const auto start = steady_clock::now();
    const auto ms = duration_cast<milliseconds>(steady_clock::now() - start).count();

    std::lock_guard lock(g_logMutex);
    std::fprintf(stderr, "[%8lld ms] [%-5s] [%-12.*s] %.*s\n",
                 static_cast<long long>(ms),
                 level,
                 static_cast<int>(tag.size()), tag.data(),
                 static_cast<int>(message.size()), message.data());
    std::fflush(stderr);
}

} // namespace

void setVerbose(bool enabled) { g_verbose.store(enabled, std::memory_order_relaxed); }
bool isVerbose() { return g_verbose.load(std::memory_order_relaxed); }

void info(std::string_view tag, std::string_view message) { emit("INFO", tag, message); }
void warn(std::string_view tag, std::string_view message) { emit("WARN", tag, message); }
void error(std::string_view tag, std::string_view message) { emit("ERROR", tag, message); }

std::wstring widen(std::string_view text) {
    if (text.empty()) return {};
    const int len = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    if (len <= 0) return {};
    std::wstring out(static_cast<size_t>(len), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), out.data(), len);
    return out;
}

std::string narrow(std::wstring_view text) {
    if (text.empty()) return {};
    const int len = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0, nullptr, nullptr);
    if (len <= 0) return {};
    std::string out(static_cast<size_t>(len), '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), out.data(), len, nullptr, nullptr);
    return out;
}

} // namespace gta60::log
