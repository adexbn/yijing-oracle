//
//  yijing_llm_jni.cpp
//
//  JNI bridge between Kotlin `com.yijing.app.core.MnnLlm` and MNN's
//  Transformer::Llm C++ API (libllm.so, shipped prebuilt in jniLibs/).
//
//  Design notes
//  ------------
//  * The Kotlin side is a *blocking, non-streaming* API (it historically wrapped
//    `Llama.complete()` and returned the full text plus timings). Therefore this
//    bridge does NOT need the official app's streaming plumbing
//    (LlmStreamBuffer / Utf8StreamProcessor / generate(1) loop). A single
//    `Llm::response(chatMessages, os, "<eop>", maxTokens)` call is enough:
//    ArGeneration appends every decoded token to `mContext->generate_str` and
//    writes it to the ostream, stopping on the EOS token.
//  * Backend selection (cpu / opencl) is entirely driven by the JSON config the
//    Kotlin side passes in: `llm.cpp:initRuntime()` reads `backend_type` and adds
//    the OpenCL-specific thread bits (|64, |512) itself. Nothing extra is needed
//    here beyond writing `"backend_type": "opencl"` and a writable `tmp_path`
//    (non-CPU backends always call setCache(tmp_path + "/mnn_cachefile.bin")).
//  * Strings are converted UTF-16 <-> UTF-8 by hand. `GetStringUTFChars` /
//    `NewStringUTF` use Modified UTF-8, which mangles astral-plane characters
//    (emoji, rare CJK ext-B) that the tokenizer / model output may contain.
//

#include <jni.h>

#include <android/log.h>

#include <chrono>
#include <cstdint>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <MNN/Interpreter.hpp>   // MNN::getVersion()
#include <MNN/MNNDefine.h>
#include <llm/llm.hpp>

#define YJ_LOG_TAG "YijingLlm"
#define YJ_LOGI(...) __android_log_print(ANDROID_LOG_INFO, YJ_LOG_TAG, __VA_ARGS__)
#define YJ_LOGW(...) __android_log_print(ANDROID_LOG_WARN, YJ_LOG_TAG, __VA_ARGS__)
#define YJ_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, YJ_LOG_TAG, __VA_ARGS__)

// Sentinel written to the ostream by the engine when generation ends.
// Kept identical to the official MNN Android app so behaviour matches upstream.
static const char* kEndWith = "<eop>";

namespace {

using MNN::Transformer::ChatMessage;
using MNN::Transformer::ChatMessages;
using MNN::Transformer::Llm;
using MNN::Transformer::LlmContext;
using MNN::Transformer::LlmStatus;

int64_t nowUs() {
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

// ---------------------------------------------------------------- string codec

std::string utf16ToUtf8(JNIEnv* env, jstring js) {
    if (js == nullptr) {
        return std::string();
    }
    const jchar* chars = env->GetStringChars(js, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    const jsize len = env->GetStringLength(js);
    std::string out;
    out.reserve(static_cast<size_t>(len) * 3 + 1);
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                c = 0x10000u + ((c - 0xD800u) << 10) + (lo - 0xDC00u);
                ++i;
            }
        }
        if (c < 0x80u) {
            out.push_back(static_cast<char>(c));
        } else if (c < 0x800u) {
            out.push_back(static_cast<char>(0xC0u | (c >> 6)));
            out.push_back(static_cast<char>(0x80u | (c & 0x3Fu)));
        } else if (c < 0x10000u) {
            out.push_back(static_cast<char>(0xE0u | (c >> 12)));
            out.push_back(static_cast<char>(0x80u | ((c >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (c & 0x3Fu)));
        } else {
            out.push_back(static_cast<char>(0xF0u | (c >> 18)));
            out.push_back(static_cast<char>(0x80u | ((c >> 12) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | ((c >> 6) & 0x3Fu)));
            out.push_back(static_cast<char>(0x80u | (c & 0x3Fu)));
        }
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

jstring utf8ToJString(JNIEnv* env, const std::string& s) {
    std::vector<jchar> out;
    out.reserve(s.size() + 1);
    const size_t n = s.size();
    size_t i = 0;
    while (i < n) {
        const unsigned char lead = static_cast<unsigned char>(s[i]);
        uint32_t c = lead;
        size_t extra = 0;
        if (lead < 0x80u) {
            extra = 0;
        } else if ((lead & 0xE0u) == 0xC0u) {
            c = lead & 0x1Fu;
            extra = 1;
        } else if ((lead & 0xF0u) == 0xE0u) {
            c = lead & 0x0Fu;
            extra = 2;
        } else if ((lead & 0xF8u) == 0xF0u) {
            c = lead & 0x07u;
            extra = 3;
        } else {
            ++i; // stray continuation / invalid lead byte: drop it
            continue;
        }
        if (i + extra >= n) {
            break; // truncated multi-byte sequence
        }
        bool ok = true;
        for (size_t k = 1; k <= extra; ++k) {
            const unsigned char cc = static_cast<unsigned char>(s[i + k]);
            if ((cc & 0xC0u) != 0x80u) {
                ok = false;
                break;
            }
            c = (c << 6) | (cc & 0x3Fu);
        }
        i += extra + 1;
        if (!ok) {
            continue;
        }
        if (c < 0x10000u) {
            out.push_back(static_cast<jchar>(c));
        } else {
            const uint32_t v = c - 0x10000u;
            out.push_back(static_cast<jchar>(0xD800u + (v >> 10)));
            out.push_back(static_cast<jchar>(0xDC00u + (v & 0x3FFu)));
        }
    }
    if (out.empty()) {
        return env->NewStringUTF("");
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

// ------------------------------------------------------------------- session

struct Session {
    Llm* llm = nullptr;
    ChatMessages history;
    std::string systemPrompt;
    std::string lastError;
    std::string backend;
    std::string configPath;

    // stats of the most recent nativeComplete() call
    int64_t prefillUs = 0;
    int64_t decodeUs = 0;
    int64_t sampleUs = 0;
    int64_t ttfaUs = 0;
    int64_t loadUs = 0;
    int64_t wallUs = 0;
    int promptLen = 0;
    int genSeqLen = 0;
    int allSeqLen = 0;
    int status = -1;

    std::mutex mu;
};

inline Session* sessionOf(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

void destroySession(Session* s) {
    if (s == nullptr) {
        return;
    }
    if (s->llm != nullptr) {
        Llm::destroy(s->llm);
        s->llm = nullptr;
    }
    delete s;
}

void snapshotStats(Session* s) {
    if (s->llm == nullptr) {
        return;
    }
    const LlmContext* ctx = s->llm->getContext();
    if (ctx == nullptr) {
        return;
    }
    s->prefillUs = ctx->prefill_us;
    s->decodeUs = ctx->decode_us;
    s->sampleUs = ctx->sample_us;
    s->ttfaUs = ctx->ttfa_us;
    s->promptLen = ctx->prompt_len;
    s->genSeqLen = ctx->gen_seq_len;
    s->allSeqLen = ctx->all_seq_len;
    s->status = static_cast<int>(ctx->status);
}

} // namespace

// ===========================================================================
//  Kotlin: com.yijing.app.core.MnnLlm
// ===========================================================================

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_yijing_app_core_MnnLlm_nativeVersion(JNIEnv* env, jclass /*clazz*/) {
    return utf8ToJString(env, std::string(MNN::getVersion()));
}

/**
 * Create a session: createLLM(configPath) -> set_config(configJson) -> load().
 *
 * @param configPath absolute path to the model directory's `config.json`
 *                   (base_dir is derived from it, so `llm.mnn`, `llm.mnn.weight`
 *                   and `tokenizer.txt` resolve inside the same directory).
 * @param configJson runtime overrides, merged on top of the model config.
 * @return session handle (never 0 unless allocation failed). Inspect
 *         nativeLastError() to decide whether the session is usable.
 */
JNIEXPORT jlong JNICALL
Java_com_yijing_app_core_MnnLlm_nativeCreate(JNIEnv* env, jclass /*clazz*/,
                                            jstring jConfigPath, jstring jConfigJson) {
    const std::string configPath = utf16ToUtf8(env, jConfigPath);
    const std::string configJson = utf16ToUtf8(env, jConfigJson);

    auto* s = new Session();
    s->configPath = configPath;

    if (configPath.empty()) {
        s->lastError = "configPath is empty";
        YJ_LOGE("nativeCreate: configPath is empty");
        return reinterpret_cast<jlong>(s);
    }

    const int64_t t0 = nowUs();
    YJ_LOGI("createLLM(%s)", configPath.c_str());
    s->llm = Llm::createLLM(configPath);
    if (s->llm == nullptr) {
        s->lastError = "createLLM failed for config path: " + configPath +
                       " (config file missing or invalid)";
        YJ_LOGE("%s", s->lastError.c_str());
        return reinterpret_cast<jlong>(s);
    }

    if (!configJson.empty()) {
        if (!s->llm->set_config(configJson)) {
            s->lastError = "set_config() rejected the runtime config JSON";
            YJ_LOGE("%s", s->lastError.c_str());
            return reinterpret_cast<jlong>(s);
        }
    }

    // `backend_type` is read by initRuntime() during load(); remember it for
    // diagnostics before the engine starts logging.
    s->backend = "unknown";

    if (!s->llm->load()) {
        s->lastError = s->llm->getContext() != nullptr &&
                               s->llm->getContext()->status == LlmStatus::NOT_LOADED
                           ? "load() failed (model files missing, unreadable or corrupt)"
                           : "load() failed";
        YJ_LOGE("%s", s->lastError.c_str());
        return reinterpret_cast<jlong>(s);
    }

    const LlmContext* ctx = s->llm->getContext();
    if (ctx == nullptr || ctx->status != LlmStatus::RUNNING) {
        s->lastError = "load() returned but LLM status is not RUNNING";
        YJ_LOGE("%s", s->lastError.c_str());
        return reinterpret_cast<jlong>(s);
    }

    s->loadUs = nowUs() - t0;
    s->lastError.clear();

    const std::string dump = s->llm->dump_config();
    YJ_LOGI("session ready in %.1f ms", s->loadUs / 1000.0);
    YJ_LOGI("effective config: %s", dump.c_str());

    return reinterpret_cast<jlong>(s);
}

/** Empty string means "no error". */
JNIEXPORT jstring JNICALL
Java_com_yijing_app_core_MnnLlm_nativeLastError(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    if (s == nullptr) {
        return utf8ToJString(env, "invalid session handle");
    }
    std::lock_guard<std::mutex> lock(s->mu);
    return utf8ToJString(env, s->lastError);
}

/** `dump_config()` of the loaded session: the real backend / thread / token budget. */
JNIEXPORT jstring JNICALL
Java_com_yijing_app_core_MnnLlm_nativeDumpConfig(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    if (s == nullptr || s->llm == nullptr) {
        return utf8ToJString(env, "");
    }
    std::lock_guard<std::mutex> lock(s->mu);
    return utf8ToJString(env, s->llm->dump_config());
}

/** Wall-clock cost of the last load, in milliseconds. */
JNIEXPORT jlong JNICALL
Java_com_yijing_app_core_MnnLlm_nativeLoadMillis(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    if (s == nullptr) {
        return 0;
    }
    std::lock_guard<std::mutex> lock(s->mu);
    return static_cast<jlong>(s->loadUs / 1000);
}

/**
 * Run one blocking completion and return the generated text.
 *
 * @param resetHistory when true the conversation KV/token history is dropped
 *                     before this call (each reading is independent).
 */
JNIEXPORT jstring JNICALL
Java_com_yijing_app_core_MnnLlm_nativeComplete(JNIEnv* env, jclass /*clazz*/, jlong handle,
                                              jstring jSystem, jstring jUser, jint maxTokens,
                                              jboolean resetHistory) {
    Session* s = sessionOf(handle);
    if (s == nullptr || s->llm == nullptr) {
        if (s != nullptr) {
            s->lastError = "nativeComplete called on a session without a loaded model";
        }
        return utf8ToJString(env, "");
    }

    const std::string system = utf16ToUtf8(env, jSystem);
    const std::string user = utf16ToUtf8(env, jUser);

    std::lock_guard<std::mutex> lock(s->mu);

    // --- rebuild the conversation -------------------------------------------
    const bool systemChanged = (system != s->systemPrompt);
    if (resetHistory || systemChanged) {
        s->llm->reset();
        s->history.clear();
        s->systemPrompt = system;
    }
    if (s->history.empty()) {
        s->history.emplace_back("system", system);
    } else {
        s->history.front().second = system;
        while (s->history.size() > 1) {
            s->history.pop_back();
        }
    }
    s->history.emplace_back("user", user);

    // --- run ----------------------------------------------------------------
    std::ostringstream oss;
    const int64_t t0 = nowUs();
    s->llm->response(s->history, &oss, kEndWith, static_cast<int>(maxTokens));
    const int64_t t1 = nowUs();
    s->wallUs = t1 - t0;
    snapshotStats(s);

    std::string text;
    const LlmContext* ctx = s->llm->getContext();
    if (ctx != nullptr) {
        text = ctx->generate_str;
    }
    if (text.empty()) {
        // Fall back to the ostream capture and strip the sentinel.
        text = oss.str();
        if (text.size() >= 5 && text.compare(text.size() - 5, 5, kEndWith) == 0) {
            text.erase(text.size() - 5);
        }
    }

    if (ctx != nullptr && (ctx->status == LlmStatus::INTERNAL_ERROR ||
                           ctx->status == LlmStatus::TIMEOUT ||
                           ctx->status == LlmStatus::USER_CANCEL)) {
        std::ostringstream err;
        err << "generation ended in error state, status=" << static_cast<int>(ctx->status);
        s->lastError = err.str();
        YJ_LOGE("%s", s->lastError.c_str());
    } else {
        s->lastError.clear();
    }

    YJ_LOGI("complete: prompt=%d gen=%d all=%d prefill=%.1fms decode=%.1fms wall=%.1fms status=%d",
            s->promptLen, s->genSeqLen, s->allSeqLen, s->prefillUs / 1000.0, s->decodeUs / 1000.0,
            s->wallUs / 1000.0, s->status);

    // Keep the assistant turn in the history (harmless today; needed if prompt
    // caching is ever enabled for multi-turn readings).
    if (!text.empty()) {
        s->history.emplace_back("assistant", text);
    }

    return utf8ToJString(env, text);
}

/**
 * Stats of the last nativeComplete() call:
 *   [0] prefillUs [1] decodeUs [2] sampleUs [3] ttfaUs [4] promptLen
 *   [5] genSeqLen [6] allSeqLen [7] status    [8] loadUs   [9] wallUs
 */
JNIEXPORT jlongArray JNICALL
Java_com_yijing_app_core_MnnLlm_nativeLastStats(JNIEnv* env, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    jlong values[10] = {0, 0, 0, 0, 0, 0, 0, -1, 0, 0};
    if (s != nullptr) {
        std::lock_guard<std::mutex> lock(s->mu);
        values[0] = s->prefillUs;
        values[1] = s->decodeUs;
        values[2] = s->sampleUs;
        values[3] = s->ttfaUs;
        values[4] = s->promptLen;
        values[5] = s->genSeqLen;
        values[6] = s->allSeqLen;
        values[7] = s->status;
        values[8] = s->loadUs;
        values[9] = s->wallUs;
    }
    jlongArray out = env->NewLongArray(10);
    if (out == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(out, 0, 10, values);
    return out;
}

/** Drop KV cache + token history (keeps the model loaded). */
JNIEXPORT void JNICALL
Java_com_yijing_app_core_MnnLlm_nativeReset(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    if (s == nullptr || s->llm == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(s->mu);
    s->llm->reset();
    s->history.clear();
    s->systemPrompt.clear();
}

/** Destroy the session and free the weights. Safe to call with 0. */
JNIEXPORT void JNICALL
Java_com_yijing_app_core_MnnLlm_nativeRelease(JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    Session* s = sessionOf(handle);
    if (s == nullptr) {
        return;
    }
    YJ_LOGI("releasing session");
    destroySession(s);
}

} // extern "C"
