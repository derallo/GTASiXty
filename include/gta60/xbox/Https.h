#pragma once
#include <atomic>
#include <map>
#include <memory>
#include <stdexcept>
#include <string>

namespace gta60::xbox {

struct HttpsRequest {
    std::string method = "GET";
    std::string host;
    std::string path;
    std::map<std::string, std::string> headers;
    std::string body;
    int timeoutMs = 15000;
};

struct HttpsResponse {
    int status = 0;
    std::string body;
};

struct NetworkError : std::runtime_error {
    explicit NetworkError(const std::string& message) : std::runtime_error(message) {}
};

struct ServiceError : std::runtime_error {
    int status;
    std::string body;
    ServiceError(int code, std::string text, const std::string& what)
        : std::runtime_error(what), status(code), body(std::move(text)) {}
};

class HttpsTransport {
public:
    virtual ~HttpsTransport() = default;
    virtual HttpsResponse send(const HttpsRequest& request) = 0;
};

std::shared_ptr<HttpsTransport> makeWinHttpTransport(const std::atomic<bool>* cancel = nullptr);
std::string urlEncode(const std::string& text);

} // namespace gta60::xbox
