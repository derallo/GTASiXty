#pragma once
#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>
#include <string>

#include "gta60/xbox/Https.h"

namespace gta60::xbox {

inline constexpr const char* kMsalClientId = "1f907974-e22b-4810-a9de-d9647380c97e";

struct DeviceCode {
    std::string userCode;
    std::string deviceCode;
    std::string verificationUri;
    int expiresInSeconds = 900;
    int intervalSeconds = 5;
};

enum class SignInPoll { Pending, SlowDown, Done, Expired, Declined, Failed };

struct StreamingAccess {
    std::string host;
    std::string gsToken;
    std::string regionName;
    int64_t expiresAt = 0;
};

struct SavedTokens {
    std::string refreshToken;
    std::string accessToken;
    int64_t accessExpiresAt = 0;
    std::string userHash;
};

std::filesystem::path dataDirectory();
std::optional<SavedTokens> loadTokens(const std::filesystem::path& file);
bool saveTokens(const std::filesystem::path& file, const SavedTokens& tokens);
std::wstring describeXboxError(const std::string& responseBody);

class Account {
public:
    Account(std::shared_ptr<HttpsTransport> transport, std::filesystem::path tokenFile);

    bool signedIn() const;
    void signOut();

    DeviceCode beginSignIn();
    SignInPoll pollSignIn(const DeviceCode& code, std::string* detail = nullptr);

    StreamingAccess streamingAccess();
    std::string transferToken();

    void setClock(std::function<int64_t()> clock) { clock_ = std::move(clock); }

private:
    std::string userAccessToken();
    std::string xstsUserToken();
    void store();

    std::shared_ptr<HttpsTransport> http_;
    std::filesystem::path file_;
    mutable std::mutex mutex_;
    std::optional<SavedTokens> tokens_;
    std::string userToken_;
    int64_t userTokenExpiresAt_ = 0;
    std::optional<StreamingAccess> streaming_;
    std::function<int64_t()> clock_;
};

} // namespace gta60::xbox
