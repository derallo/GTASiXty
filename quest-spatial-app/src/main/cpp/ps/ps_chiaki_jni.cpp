#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <pthread.h>
#include <sys/socket.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cinttypes>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <chiaki/common.h>
#include <chiaki/controller.h>
#include <chiaki/discovery.h>
#include <chiaki/discoveryservice.h>
#include <chiaki/log.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/regist.h>
#include <chiaki/session.h>
}

namespace {

constexpr const char *kLogTag = "GTA60-PS";

JavaVM *g_vm = nullptr;
pthread_key_t g_jni_detach_key;
pthread_once_t g_jni_key_once = PTHREAD_ONCE_INIT;
std::once_flag g_lib_init_once;
bool g_lib_init_ok = false;

void detach_current_thread(void *value) {
    if (value && g_vm) {
        g_vm->DetachCurrentThread();
    }
}

void create_detach_key() {
    pthread_key_create(&g_jni_detach_key, detach_current_thread);
}

JNIEnv *get_jni_env() {
    if (!g_vm) return nullptr;
    JNIEnv *env = nullptr;
    int status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_OK) return env;
    if (status == JNI_EDETACHED) {
        pthread_once(&g_jni_key_once, create_detach_key);
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            pthread_setspecific(g_jni_detach_key, reinterpret_cast<void *>(1));
            return env;
        }
    }
    return nullptr;
}

void android_log_cb(ChiakiLogLevel level, const char *msg, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case CHIAKI_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case CHIAKI_LOG_VERBOSE: prio = ANDROID_LOG_VERBOSE; break;
        case CHIAKI_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case CHIAKI_LOG_WARNING: prio = ANDROID_LOG_WARN; break;
        case CHIAKI_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_print(prio, kLogTag, "%s", msg ? msg : "");
}

void init_chiaki_log(ChiakiLog *log) {
    chiaki_log_init(log, CHIAKI_LOG_ALL & ~CHIAKI_LOG_VERBOSE & ~CHIAKI_LOG_DEBUG, android_log_cb, nullptr);
}

std::string sanitize_pipe_field(const char *s) {
    if (!s) return "";
    std::string out(s);
    for (char &c : out) {
        if (c == '|' || c == '\n' || c == '\r') c = ' ';
        else if (static_cast<unsigned char>(c) >= 0x80) c = '?';
    }
    return out;
}

std::string bytes_to_hex(const uint8_t *data, size_t len) {
    static const char kHex[] = "0123456789abcdef";
    std::string out;
    out.reserve(len * 2);
    for (size_t i = 0; i < len; ++i) {
        out.push_back(kHex[(data[i] >> 4) & 0x0F]);
        out.push_back(kHex[data[i] & 0x0F]);
    }
    return out;
}

std::string jstring_to_std(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) return "";
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring new_safe_jstring(JNIEnv *env, const char *str) {
    if (!str) return env->NewStringUTF("");
    std::string clean(str);
    for (char &c : clean) {
        if (static_cast<unsigned char>(c) >= 0x80) c = '?';
    }
    return env->NewStringUTF(clean.c_str());
}

struct DiscoveryCollectState {
    std::mutex mu;
    std::vector<std::string> hosts;
};

void on_discovery_hosts(ChiakiDiscoveryHost *hosts, size_t hosts_count, void *user) {
    auto *state = static_cast<DiscoveryCollectState *>(user);
    if (!state) return;
    std::lock_guard<std::mutex> lock(state->mu);
    state->hosts.clear();
    for (size_t i = 0; i < hosts_count; ++i) {
        ChiakiDiscoveryHost *h = &hosts[i];
        bool is_ps5 = chiaki_discovery_host_is_ps5(h);
        char line[1024];
        std::snprintf(
            line, sizeof(line),
            "%s|%s|%s|%d|%s|%s|%d|%s",
            sanitize_pipe_field(h->host_addr).c_str(),
            sanitize_pipe_field(h->host_name).c_str(),
            sanitize_pipe_field(h->host_type).c_str(),
            static_cast<int>(h->state),
            sanitize_pipe_field(h->host_id).c_str(),
            sanitize_pipe_field(h->system_version).c_str(),
            is_ps5 ? 1 : 0,
            sanitize_pipe_field(h->running_app_name).c_str()
        );
        state->hosts.emplace_back(line);
    }
}

struct RegistWaitState {
    std::mutex mu;
    std::condition_variable cv;
    bool done = false;
    ChiakiRegistEventType type = CHIAKI_REGIST_EVENT_TYPE_FINISHED_FAILED;
    ChiakiRegisteredHost host{};
    bool has_host = false;
};

void on_regist_event(ChiakiRegistEvent *event, void *user) {
    auto *state = static_cast<RegistWaitState *>(user);
    if (!state || !event) return;
    std::lock_guard<std::mutex> lock(state->mu);
    state->type = event->type;
    if (event->type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS && event->registered_host) {
        state->host = *event->registered_host;
        state->has_host = true;
    }
    state->done = true;
    state->cv.notify_all();
}

struct PsSessionContext {
    ChiakiLog log{};
    ChiakiSession session{};
    ChiakiOpusDecoder opus_decoder{};
    bool session_initialized = false;
    bool opus_initialized = false;

    jobject callback_ref = nullptr;
    jmethodID on_video_sample_mid = nullptr;
    jmethodID on_audio_settings_mid = nullptr;
    jmethodID on_audio_pcm_mid = nullptr;
    jmethodID on_event_mid = nullptr;

    std::mutex jni_video_mu;
    jbyteArray video_buf = nullptr;
    jsize video_buf_cap = 0;

    std::mutex jni_audio_mu;
    jbyteArray audio_buf = nullptr;
    jsize audio_buf_cap = 0;

    std::mutex ctrl_mu;
    ChiakiControllerState ctrl_state{};
    int8_t active_touch_id = -1;

    std::atomic<bool> stopping{false};
};

bool ensure_byte_array(JNIEnv *env, jbyteArray &arr, jsize &cap, jsize needed) {
    if (needed <= 0) return false;
    if (arr && cap >= needed) return true;
    if (arr) {
        env->DeleteGlobalRef(arr);
        arr = nullptr;
        cap = 0;
    }
    jsize alloc_len = std::max<jsize>(needed, 65536);
    jbyteArray local = env->NewByteArray(alloc_len);
    if (!local) return false;
    arr = static_cast<jbyteArray>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!arr) return false;
    cap = alloc_len;
    return true;
}

bool on_session_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user) {
    auto *ctx = static_cast<PsSessionContext *>(user);
    if (!ctx || ctx->stopping.load(std::memory_order_acquire)) return false;
    if (!buf || buf_size == 0) return true;

    JNIEnv *env = get_jni_env();
    if (!env || !ctx->callback_ref || !ctx->on_video_sample_mid) return false;

    std::lock_guard<std::mutex> lock(ctx->jni_video_mu);
    if (ctx->stopping.load(std::memory_order_acquire)) return false;
    jsize size_j = static_cast<jsize>(buf_size);
    if (!ensure_byte_array(env, ctx->video_buf, ctx->video_buf_cap, size_j)) {
        return false;
    }
    env->SetByteArrayRegion(ctx->video_buf, 0, size_j, reinterpret_cast<const jbyte *>(buf));
    jboolean ok = env->CallBooleanMethod(
        ctx->callback_ref,
        ctx->on_video_sample_mid,
        ctx->video_buf,
        static_cast<jint>(size_j),
        static_cast<jint>(frames_lost),
        static_cast<jboolean>(frame_recovered)
    );
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return ok == JNI_TRUE;
}

void on_opus_settings(uint32_t channels, uint32_t rate, void *user) {
    auto *ctx = static_cast<PsSessionContext *>(user);
    if (!ctx || ctx->stopping.load(std::memory_order_acquire)) return;
    JNIEnv *env = get_jni_env();
    if (!env || !ctx->callback_ref || !ctx->on_audio_settings_mid) return;
    env->CallVoidMethod(
        ctx->callback_ref,
        ctx->on_audio_settings_mid,
        static_cast<jint>(channels),
        static_cast<jint>(rate)
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void on_opus_frame(int16_t *pcm, size_t samples_count, void *user) {
    auto *ctx = static_cast<PsSessionContext *>(user);
    if (!ctx || ctx->stopping.load(std::memory_order_acquire)) return;
    if (!pcm || samples_count == 0) return;

    JNIEnv *env = get_jni_env();
    if (!env || !ctx->callback_ref || !ctx->on_audio_pcm_mid) return;

    const uint32_t channels = ctx->opus_decoder.audio_header.channels > 0
        ? ctx->opus_decoder.audio_header.channels
        : 2;
    const jsize byte_len = static_cast<jsize>(samples_count * channels * sizeof(int16_t));
    std::lock_guard<std::mutex> lock(ctx->jni_audio_mu);
    if (ctx->stopping.load(std::memory_order_acquire)) return;
    if (!ensure_byte_array(env, ctx->audio_buf, ctx->audio_buf_cap, byte_len)) return;
    env->SetByteArrayRegion(ctx->audio_buf, 0, byte_len, reinterpret_cast<const jbyte *>(pcm));
    env->CallVoidMethod(
        ctx->callback_ref,
        ctx->on_audio_pcm_mid,
        ctx->audio_buf,
        static_cast<jint>(byte_len)
    );
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void on_session_event(ChiakiEvent *event, void *user) {
    auto *ctx = static_cast<PsSessionContext *>(user);
    if (!ctx || !event || ctx->stopping.load(std::memory_order_acquire)) return;

    JNIEnv *env = get_jni_env();
    if (!env || !ctx->callback_ref || !ctx->on_event_mid) return;

    jint arg0 = 0;
    jint arg1 = 0;
    jstring str_arg = nullptr;

    switch (event->type) {
        case CHIAKI_EVENT_CONNECTED:
            break;
        case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
            arg0 = event->login_pin_request.pin_incorrect ? 1 : 0;
            break;
        case CHIAKI_EVENT_RUMBLE:
            arg0 = static_cast<jint>(event->rumble.left);
            arg1 = static_cast<jint>(event->rumble.right);
            break;
        case CHIAKI_EVENT_QUIT: {
            arg0 = static_cast<jint>(event->quit.reason);
            arg1 = chiaki_quit_reason_is_error(event->quit.reason) ? 1 : 0;
            std::string reason = chiaki_quit_reason_string(event->quit.reason);
            if (event->quit.reason_str && event->quit.reason_str[0] != '\0') {
                reason += " (";
                reason += event->quit.reason_str;
                reason += ")";
            }
            str_arg = new_safe_jstring(env, reason.c_str());
            break;
        }
        case CHIAKI_EVENT_NICKNAME_RECEIVED:
            str_arg = new_safe_jstring(env, event->server_nickname);
            break;
        default:
            return;
    }

    env->CallVoidMethod(
        ctx->callback_ref,
        ctx->on_event_mid,
        static_cast<jint>(event->type),
        arg0,
        arg1,
        str_arg
    );
    if (str_arg) env->DeleteLocalRef(str_arg);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeInit(JNIEnv * /*env*/, jclass /*cls*/) {
    std::call_once(g_lib_init_once, []() {
        ChiakiErrorCode err = chiaki_lib_init();
        g_lib_init_ok = (err == CHIAKI_ERR_SUCCESS);
        __android_log_print(
            g_lib_init_ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
            kLogTag,
            "chiaki_lib_init -> %s",
            chiaki_error_string(err)
        );
    });
    return g_lib_init_ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jobjectArray JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeDiscoverConsoles(
    JNIEnv *env,
    jclass /*cls*/,
    jobjectArray broadcast_addrs,
    jint timeout_ms
) {
    ChiakiLog log{};
    init_chiaki_log(&log);

    std::vector<sockaddr_storage> bcast_storage;
    if (broadcast_addrs) {
        jsize count = env->GetArrayLength(broadcast_addrs);
        for (jsize i = 0; i < count; ++i) {
            auto jstr = static_cast<jstring>(env->GetObjectArrayElement(broadcast_addrs, i));
            std::string ip = jstring_to_std(env, jstr);
            if (jstr) env->DeleteLocalRef(jstr);
            if (ip.empty()) continue;
            sockaddr_storage ss{};
            auto *sin = reinterpret_cast<sockaddr_in *>(&ss);
            sin->sin_family = AF_INET;
            if (inet_pton(AF_INET, ip.c_str(), &sin->sin_addr) == 1) {
                bcast_storage.push_back(ss);
            }
        }
    }

    sockaddr_storage send_addr{};
    auto *send_in = reinterpret_cast<sockaddr_in *>(&send_addr);
    send_in->sin_family = AF_INET;
    send_in->sin_addr.s_addr = htonl(INADDR_BROADCAST);
    send_in->sin_port = 0;

    DiscoveryCollectState state;
    ChiakiDiscoveryServiceOptions opts{};
    opts.hosts_max = 16;
    opts.host_drop_pings = 8;
    opts.ping_ms = 400;
    opts.ping_initial_ms = 40;
    opts.send_addr = &send_addr;
    opts.send_addr_size = sizeof(sockaddr_in);
    opts.broadcast_addrs = bcast_storage.empty() ? nullptr : bcast_storage.data();
    opts.broadcast_num = bcast_storage.size();
    opts.cb = on_discovery_hosts;
    opts.cb_user = &state;

    ChiakiDiscoveryService svc{};
    ChiakiErrorCode err = chiaki_discovery_service_init(&svc, &opts, &log);
    if (err != CHIAKI_ERR_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "discovery_service_init failed: %s", chiaki_error_string(err));
        jclass str_cls = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, str_cls, nullptr);
    }

    int wait_ms = std::max(300, std::min(5000, static_cast<int>(timeout_ms)));
    std::this_thread::sleep_for(std::chrono::milliseconds(wait_ms));
    chiaki_discovery_service_fini(&svc);

    std::vector<std::string> snapshot;
    {
        std::lock_guard<std::mutex> lock(state.mu);
        snapshot = state.hosts;
    }

    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(snapshot.size()), str_cls, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(snapshot.size()); ++i) {
        jstring s = new_safe_jstring(env, snapshot[i].c_str());
        env->SetObjectArrayElement(out, i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeWakeConsole(
    JNIEnv *env,
    jclass /*cls*/,
    jstring host,
    jlong regist_key_u64,
    jboolean is_ps5
) {
    ChiakiLog log{};
    init_chiaki_log(&log);
    std::string host_str = jstring_to_std(env, host);
    if (host_str.empty()) return static_cast<jint>(CHIAKI_ERR_INVALID_DATA);
    ChiakiErrorCode err = chiaki_discovery_wakeup(
        &log,
        nullptr,
        host_str.c_str(),
        static_cast<uint64_t>(regist_key_u64),
        is_ps5 == JNI_TRUE
    );
    return static_cast<jint>(err);
}

JNIEXPORT jstring JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeRegisterConsole(
    JNIEnv *env,
    jclass /*cls*/,
    jstring host,
    jboolean is_ps5,
    jbyteArray psn_account_id,
    jstring psn_online_id,
    jint pin,
    jint console_pin
) {
    ChiakiLog log{};
    init_chiaki_log(&log);

    std::string host_str = jstring_to_std(env, host);
    std::string online_id = jstring_to_std(env, psn_online_id);
    if (host_str.empty()) {
        return env->NewStringUTF("ERR|Missing console IP address");
    }

    ChiakiRegistInfo info{};
    info.target = (is_ps5 == JNI_TRUE) ? CHIAKI_TARGET_PS5_1 : CHIAKI_TARGET_PS4_10;
    info.host = host_str.c_str();
    info.broadcast = false;
    info.pin = static_cast<uint32_t>(pin);
    info.console_pin = static_cast<uint32_t>(console_pin);

    if (psn_account_id && env->GetArrayLength(psn_account_id) == CHIAKI_PSN_ACCOUNT_ID_SIZE) {
        jbyte *id_bytes = env->GetByteArrayElements(psn_account_id, nullptr);
        std::memcpy(info.psn_account_id, id_bytes, CHIAKI_PSN_ACCOUNT_ID_SIZE);
        env->ReleaseByteArrayElements(psn_account_id, id_bytes, JNI_ABORT);
    } else if (!online_id.empty()) {
        info.psn_online_id = online_id.c_str();
    } else {
        return env->NewStringUTF("ERR|Missing PSN Account ID");
    }

    RegistWaitState wait_state;
    ChiakiRegist regist{};
    ChiakiErrorCode err = chiaki_regist_start(&regist, &log, &info, on_regist_event, &wait_state);
    if (err != CHIAKI_ERR_SUCCESS) {
        std::string msg = std::string("ERR|regist_start failed: ") + chiaki_error_string(err);
        return new_safe_jstring(env, msg.c_str());
    }

    {
        std::unique_lock<std::mutex> lock(wait_state.mu);
        if (!wait_state.cv.wait_for(lock, std::chrono::seconds(20), [&]() { return wait_state.done; })) {
            chiaki_regist_stop(&regist);
        }
    }
    chiaki_regist_fini(&regist);

    if (!wait_state.done) {
        return env->NewStringUTF("ERR|Registration timed out. Confirm PS Remote Play Link Device screen is open.");
    }
    if (wait_state.type != CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS || !wait_state.has_host) {
        if (wait_state.type == CHIAKI_REGIST_EVENT_TYPE_FINISHED_CANCELED) {
            return env->NewStringUTF("ERR|Registration canceled");
        }
        return env->NewStringUTF("ERR|Registration failed. Check PIN, PSN Account ID, and console IP.");
    }

    const ChiakiRegisteredHost &h = wait_state.host;
    std::string mac_hex = bytes_to_hex(h.server_mac, sizeof(h.server_mac));
    std::string key_hex = bytes_to_hex(reinterpret_cast<const uint8_t *>(h.rp_regist_key), sizeof(h.rp_regist_key));
    std::string rp_key_hex = bytes_to_hex(h.rp_key, sizeof(h.rp_key));

    char out_buf[512];
    std::snprintf(
        out_buf,
        sizeof(out_buf),
        "OK|%d|%s|%s|%s|%s",
        static_cast<int>(h.target),
        sanitize_pipe_field(h.server_nickname).c_str(),
        mac_hex.c_str(),
        key_hex.c_str(),
        rp_key_hex.c_str()
    );
    return new_safe_jstring(env, out_buf);
}

JNIEXPORT jlong JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionStart(
    JNIEnv *env,
    jclass /*cls*/,
    jstring host,
    jboolean is_ps5,
    jbyteArray regist_key_16,
    jbyteArray morning_16,
    jint width,
    jint height,
    jint fps,
    jint bitrate_kbps,
    jint codec_id,
    jobject callback
) {
    if (!host || !regist_key_16 || !morning_16 || !callback) return 0;
    if (env->GetArrayLength(regist_key_16) != CHIAKI_SESSION_AUTH_SIZE) return 0;
    if (env->GetArrayLength(morning_16) != 0x10) return 0;

    std::string host_str = jstring_to_std(env, host);
    if (host_str.empty()) return 0;

    auto *ctx = new PsSessionContext();
    init_chiaki_log(&ctx->log);
    chiaki_controller_state_set_idle(&ctx->ctrl_state);

    ctx->callback_ref = env->NewGlobalRef(callback);
    jclass cb_cls = env->GetObjectClass(callback);
    ctx->on_video_sample_mid = env->GetMethodID(cb_cls, "onVideoSample", "([BIIZ)Z");
    ctx->on_audio_settings_mid = env->GetMethodID(cb_cls, "onAudioSettings", "(II)V");
    ctx->on_audio_pcm_mid = env->GetMethodID(cb_cls, "onAudioPcm", "([BI)V");
    ctx->on_event_mid = env->GetMethodID(cb_cls, "onEvent", "(IIILjava/lang/String;)V");
    env->DeleteLocalRef(cb_cls);

    if (!ctx->callback_ref || !ctx->on_video_sample_mid || !ctx->on_audio_settings_mid
            || !ctx->on_audio_pcm_mid || !ctx->on_event_mid) {
        if (ctx->callback_ref) env->DeleteGlobalRef(ctx->callback_ref);
        delete ctx;
        return 0;
    }

    ChiakiConnectInfo ci{};
    ci.ps5 = (is_ps5 == JNI_TRUE);
    ci.host = host_str.c_str();
    jbyte *rk = env->GetByteArrayElements(regist_key_16, nullptr);
    std::memcpy(ci.regist_key, rk, CHIAKI_SESSION_AUTH_SIZE);
    env->ReleaseByteArrayElements(regist_key_16, rk, JNI_ABORT);

    jbyte *mn = env->GetByteArrayElements(morning_16, nullptr);
    std::memcpy(ci.morning, mn, 0x10);
    env->ReleaseByteArrayElements(morning_16, mn, JNI_ABORT);

    ci.video_profile.width = static_cast<unsigned int>(std::max(640, width));
    ci.video_profile.height = static_cast<unsigned int>(std::max(360, height));
    ci.video_profile.max_fps = static_cast<unsigned int>(fps >= 60 ? 60 : 30);
    ci.video_profile.bitrate = static_cast<unsigned int>(std::max(2000, bitrate_kbps));
    ci.video_profile.codec = (codec_id == 1) ? CHIAKI_CODEC_H265 : CHIAKI_CODEC_H264;
    ci.video_profile_auto_downgrade = true;
    ci.enable_keyboard = false;
    ci.enable_dualsense = (is_ps5 == JNI_TRUE);
    ci.auto_regist = false;
    ci.packet_loss_max = 0.05;
    ci.enable_idr_on_fec_failure = true;

    ChiakiErrorCode err = chiaki_session_init(&ctx->session, &ci, &ctx->log);
    if (err != CHIAKI_ERR_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "chiaki_session_init failed: %s", chiaki_error_string(err));
        env->DeleteGlobalRef(ctx->callback_ref);
        delete ctx;
        return 0;
    }
    ctx->session_initialized = true;

    chiaki_opus_decoder_init(&ctx->opus_decoder, &ctx->log);
    ctx->opus_initialized = true;
    chiaki_opus_decoder_set_cb(&ctx->opus_decoder, on_opus_settings, on_opus_frame, ctx);
    ChiakiAudioSink audio_sink{};
    chiaki_opus_decoder_get_sink(&ctx->opus_decoder, &audio_sink);
    chiaki_session_set_audio_sink(&ctx->session, &audio_sink);

    chiaki_session_set_event_cb(&ctx->session, on_session_event, ctx);
    chiaki_session_set_video_sample_cb(&ctx->session, on_session_video_sample, ctx);

    err = chiaki_session_start(&ctx->session);
    if (err != CHIAKI_ERR_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "chiaki_session_start failed: %s", chiaki_error_string(err));
        chiaki_session_fini(&ctx->session);
        chiaki_opus_decoder_fini(&ctx->opus_decoder);
        env->DeleteGlobalRef(ctx->callback_ref);
        delete ctx;
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionSetControllerState(
    JNIEnv * /*env*/,
    jclass /*cls*/,
    jlong handle,
    jint buttons,
    jint l2,
    jint r2,
    jshort lx,
    jshort ly,
    jshort rx,
    jshort ry,
    jboolean touch_down,
    jint touch_x,
    jint touch_y
) {
    auto *ctx = reinterpret_cast<PsSessionContext *>(handle);
    if (!ctx || !ctx->session_initialized || ctx->stopping.load(std::memory_order_acquire)) return;

    std::lock_guard<std::mutex> lock(ctx->ctrl_mu);
    ctx->ctrl_state.buttons = static_cast<uint32_t>(buttons);
    ctx->ctrl_state.l2_state = static_cast<uint8_t>(std::max(0, std::min(255, static_cast<int>(l2))));
    ctx->ctrl_state.r2_state = static_cast<uint8_t>(std::max(0, std::min(255, static_cast<int>(r2))));
    ctx->ctrl_state.left_x = static_cast<int16_t>(lx);
    ctx->ctrl_state.left_y = static_cast<int16_t>(ly);
    ctx->ctrl_state.right_x = static_cast<int16_t>(rx);
    ctx->ctrl_state.right_y = static_cast<int16_t>(ry);

    uint16_t tx = static_cast<uint16_t>(std::max(0, std::min(1919, static_cast<int>(touch_x))));
    uint16_t ty = static_cast<uint16_t>(std::max(0, std::min(941, static_cast<int>(touch_y))));
    if (touch_down == JNI_TRUE) {
        if (ctx->active_touch_id < 0) {
            ctx->active_touch_id = chiaki_controller_state_start_touch(&ctx->ctrl_state, tx, ty);
        } else {
            chiaki_controller_state_set_touch_pos(&ctx->ctrl_state, static_cast<uint8_t>(ctx->active_touch_id), tx, ty);
        }
    } else if (ctx->active_touch_id >= 0) {
        chiaki_controller_state_stop_touch(&ctx->ctrl_state, static_cast<uint8_t>(ctx->active_touch_id));
        ctx->active_touch_id = -1;
    }

    chiaki_session_set_controller_state(&ctx->session, &ctx->ctrl_state);
}

JNIEXPORT void JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionSetLoginPin(
    JNIEnv *env,
    jclass /*cls*/,
    jlong handle,
    jstring pin
) {
    auto *ctx = reinterpret_cast<PsSessionContext *>(handle);
    if (!ctx || !ctx->session_initialized || ctx->stopping.load(std::memory_order_acquire)) return;
    std::string pin_str = jstring_to_std(env, pin);
    chiaki_session_set_login_pin(
        &ctx->session,
        reinterpret_cast<const uint8_t *>(pin_str.data()),
        pin_str.size()
    );
}

JNIEXPORT void JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionRequestIdr(
    JNIEnv * /*env*/,
    jclass /*cls*/,
    jlong handle
) {
    auto *ctx = reinterpret_cast<PsSessionContext *>(handle);
    if (!ctx || !ctx->session_initialized || ctx->stopping.load(std::memory_order_acquire)) return;
    chiaki_session_request_idr(&ctx->session);
}

JNIEXPORT void JNICALL
Java_com_gtasixty_spatial_PsChiakiNative_nativeSessionStop(
    JNIEnv *env,
    jclass /*cls*/,
    jlong handle
) {
    auto *ctx = reinterpret_cast<PsSessionContext *>(handle);
    if (!ctx) return;

    ctx->stopping.store(true, std::memory_order_release);
    if (ctx->session_initialized) {
        chiaki_session_stop(&ctx->session);
        chiaki_session_join(&ctx->session);
        chiaki_session_fini(&ctx->session);
        ctx->session_initialized = false;
    }
    if (ctx->opus_initialized) {
        chiaki_opus_decoder_fini(&ctx->opus_decoder);
        ctx->opus_initialized = false;
    }
    {
        std::lock_guard<std::mutex> lock(ctx->jni_video_mu);
        if (ctx->video_buf) {
            env->DeleteGlobalRef(ctx->video_buf);
            ctx->video_buf = nullptr;
            ctx->video_buf_cap = 0;
        }
    }
    {
        std::lock_guard<std::mutex> lock(ctx->jni_audio_mu);
        if (ctx->audio_buf) {
            env->DeleteGlobalRef(ctx->audio_buf);
            ctx->audio_buf = nullptr;
            ctx->audio_buf_cap = 0;
        }
    }
    if (ctx->callback_ref) {
        env->DeleteGlobalRef(ctx->callback_ref);
        ctx->callback_ref = nullptr;
    }
    delete ctx;
}

} // extern "C"
