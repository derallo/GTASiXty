#include "gta60/xbox/Https.h"

#include <windows.h>
#include <winhttp.h>

#include <vector>

namespace gta60::xbox {
namespace {

std::wstring widen(const std::string& text) {
    if (text.empty()) return {};
    const int length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    std::wstring out(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), out.data(), length);
    return out;
}

struct Handle {
    HINTERNET h = nullptr;
    Handle() = default;
    explicit Handle(HINTERNET value) : h(value) {}
    Handle(const Handle&) = delete;
    Handle& operator=(const Handle&) = delete;
    ~Handle() {
        if (h) WinHttpCloseHandle(h);
    }
    explicit operator bool() const { return h != nullptr; }
};

std::string lastError(const char* step) {
    return std::string(step) + " failed (WinHTTP error " + std::to_string(GetLastError()) + ")";
}

class WinHttpTransport final : public HttpsTransport {
public:
    explicit WinHttpTransport(const std::atomic<bool>* cancel) : cancel_(cancel) {
        // Talk directly to the Xbox/Microsoft endpoints. AUTO_PROXY first tries WPAD/DHCP proxy
        // discovery, which stalls and then fails with ERROR_WINHTTP_AUTO_PROXY_SERVICE_ERROR
        // (12185) on networks without a proxy, so only use it as a last-resort fallback.
        session_.h = WinHttpOpen(L"GTA-Sixty/1.0", WINHTTP_ACCESS_TYPE_NO_PROXY,
                                 WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session_) {
            session_.h = WinHttpOpen(L"GTA-Sixty/1.0", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
                                     WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        }
        if (!session_) {
            session_.h = WinHttpOpen(L"GTA-Sixty/1.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
                                     WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        }
        if (session_) {
            DWORD protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2 | WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_3;
            if (!WinHttpSetOption(session_.h, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof(protocols))) {
                protocols = WINHTTP_FLAG_SECURE_PROTOCOL_TLS1_2;
                WinHttpSetOption(session_.h, WINHTTP_OPTION_SECURE_PROTOCOLS, &protocols, sizeof(protocols));
            }
        }
    }

    HttpsResponse send(const HttpsRequest& request) override {
        if (!session_) throw NetworkError(lastError("WinHttpOpen"));
        checkCancel();
        Handle connection(WinHttpConnect(session_.h, widen(request.host).c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0));
        if (!connection) throw NetworkError(lastError("WinHttpConnect"));
        Handle handle(WinHttpOpenRequest(connection.h, widen(request.method).c_str(), widen(request.path).c_str(),
                                         nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE));
        if (!handle) throw NetworkError(lastError("WinHttpOpenRequest"));
        const int t = request.timeoutMs;
        WinHttpSetTimeouts(handle.h, t, t, t, t);
        std::wstring headers;
        for (const auto& [name, value] : request.headers) {
            headers += widen(name) + L": " + widen(value) + L"\r\n";
        }
        const BOOL sent = WinHttpSendRequest(
            handle.h,
            headers.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : headers.c_str(),
            headers.empty() ? 0 : DWORD(-1),
            request.body.empty() ? WINHTTP_NO_REQUEST_DATA : const_cast<char*>(request.body.data()),
            static_cast<DWORD>(request.body.size()),
            static_cast<DWORD>(request.body.size()),
            0);
        if (!sent) throw NetworkError(lastError("WinHttpSendRequest"));
        checkCancel();
        if (!WinHttpReceiveResponse(handle.h, nullptr)) throw NetworkError(lastError("WinHttpReceiveResponse"));
        HttpsResponse response;
        DWORD status = 0, size = sizeof(status);
        WinHttpQueryHeaders(handle.h, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &size, WINHTTP_NO_HEADER_INDEX);
        response.status = static_cast<int>(status);
        for (;;) {
            checkCancel();
            DWORD available = 0;
            if (!WinHttpQueryDataAvailable(handle.h, &available)) throw NetworkError(lastError("WinHttpQueryDataAvailable"));
            if (available == 0) break;
            if (response.body.size() + available > (16u << 20)) throw NetworkError("response larger than 16 MB");
            std::vector<char> chunk(available);
            DWORD read = 0;
            if (!WinHttpReadData(handle.h, chunk.data(), available, &read)) throw NetworkError(lastError("WinHttpReadData"));
            response.body.append(chunk.data(), read);
        }
        return response;
    }

private:
    void checkCancel() const {
        if (cancel_ && cancel_->load()) throw NetworkError("cancelled");
    }
    Handle session_;
    const std::atomic<bool>* cancel_;
};

} // namespace

std::shared_ptr<HttpsTransport> makeWinHttpTransport(const std::atomic<bool>* cancel) {
    return std::make_shared<WinHttpTransport>(cancel);
}

std::string urlEncode(const std::string& text) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    for (const unsigned char c : text) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            out += static_cast<char>(c);
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

} // namespace gta60::xbox
