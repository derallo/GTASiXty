#pragma once
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "gta60/xbox/Https.h"

namespace gta60::xbox {

struct Console {
    std::string serverId;
    std::string name;
    std::string powerState;
    std::string consoleType;
    bool outOfHomeWarning = false;
};

struct SessionState {
    std::string state;
    std::string errorCode;
    std::string errorMessage;
};

struct IceCandidate {
    std::string candidate;
    std::string sdpMid;
    int sdpMLineIndex = 0;
};

class StreamApi {
public:
    StreamApi(std::shared_ptr<HttpsTransport> transport, std::string host, std::string gsToken);

    std::vector<Console> consoles();
    std::string play(const std::string& serverId, const std::string& locale = "en-US");
    SessionState state(const std::string& sessionId);
    void connect(const std::string& sessionId, const std::string& transferToken);
    std::string exchangeSdp(const std::string& sessionId, const std::string& offerSdp);
    std::vector<IceCandidate> exchangeIce(const std::string& sessionId, const std::vector<IceCandidate>& local);
    void keepalive(const std::string& sessionId);
    void stop(const std::string& sessionId);

    static std::vector<Console> parseConsoles(const std::string& body);
    static std::vector<IceCandidate> parseIce(const std::string& exchangeResponse);
    static bool teredoIpv4(const std::string& ipv6, std::string* ipv4, int* port);

    void setRetryDelayMs(int ms) { retryDelayMs_ = ms; }

private:
    HttpsResponse call(const std::string& method, const std::string& path, const std::string& body,
                       const std::map<std::string, std::string>& extraHeaders = {});
    std::string getExchange(const std::string& path);

    std::shared_ptr<HttpsTransport> http_;
    std::string host_, token_;
    int retryDelayMs_ = 750;
};

} // namespace gta60::xbox
