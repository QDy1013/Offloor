// 楼外端侧小模型：llama.cpp 的最小 JNI 封装。
// 只做一件事：给定 system / user 两段文字（可选 GBNF 语法约束），贪心生成一段回复。
// 输入输出都用 UTF-8 字节数组，避开 JNI “modified UTF-8” 对表情等四字节字符的问题。
// 一个句柄同一时刻只允许一个线程调用，由 Java 侧 LocalLlm 加锁保证。

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "offloor-llm", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

std::once_flag g_backend_once;

void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set([](ggml_log_level level, const char * text, void *) {
            if (level >= GGML_LOG_LEVEL_ERROR && text != nullptr) LOGE("%s", text);
        }, nullptr);
        llama_backend_init();
    });
}

bool abort_requested(void * data) {
    return static_cast<Session *>(data)->cancel.load(std::memory_order_relaxed);
}

std::string bytes_to_string(JNIEnv * env, jbyteArray bytes) {
    if (bytes == nullptr) return std::string();
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out != nullptr && !s.empty()) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return out;
}

void throw_state(JNIEnv * env, const char * message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

// 模型自带模板 llama.cpp 不认识时，按 ChatML 拼（Qwen 系列就是 ChatML）。
std::string format_prompt(const llama_model * model, const std::string & system, const std::string & user) {
    std::vector<llama_chat_message> chat;
    if (!system.empty()) chat.push_back({"system", system.c_str()});
    chat.push_back({"user", user.c_str()});
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl != nullptr) {
        std::vector<char> buf(2 * (system.size() + user.size()) + 512);
        int32_t n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        if (n > static_cast<int32_t>(buf.size())) {
            buf.resize(static_cast<size_t>(n));
            n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
        }
        if (n >= 0) return std::string(buf.data(), static_cast<size_t>(n));
    }
    std::string p;
    if (!system.empty()) p += "<|im_start|>system\n" + system + "<|im_end|>\n";
    p += "<|im_start|>user\n" + user + "<|im_end|>\n<|im_start|>assistant\n";
    return p;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_nopalou_LocalLlm_nativeLoad(JNIEnv * env, jclass, jbyteArray path_bytes, jint n_ctx, jint n_threads) {
    init_backend();
    const std::string path = bytes_to_string(env, path_bytes);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // 手机上只用 CPU，行为一致、不依赖厂商 GPU 驱动
    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (model == nullptr) return 0;

    auto * s = new Session();
    s->model = model;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx > 0 ? n_ctx : 4096);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads > 0 ? n_threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true;
    cp.abort_callback = abort_requested;
    cp.abort_callback_data = s;
    s->ctx = llama_init_from_model(model, cp);
    if (s->ctx == nullptr) {
        llama_model_free(model);
        delete s;
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_com_nopalou_LocalLlm_nativeContext(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    return s == nullptr ? 0 : static_cast<jint>(llama_n_ctx(s->ctx));
}

JNIEXPORT jbyteArray JNICALL
Java_com_nopalou_LocalLlm_nativeGenerate(JNIEnv * env, jclass, jlong handle, jbyteArray system_bytes,
                                          jbyteArray user_bytes, jbyteArray grammar_bytes, jint max_tokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) { throw_state(env, "本机模型未加载"); return nullptr; }
    s->cancel.store(false);

    const std::string system = bytes_to_string(env, system_bytes);
    const std::string user = bytes_to_string(env, user_bytes);
    const std::string grammar = bytes_to_string(env, grammar_bytes);
    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string prompt = format_prompt(s->model, system, user);

    const int32_t n_prompt = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (n_prompt <= 0) { throw_state(env, "提示词无法切分"); return nullptr; }
    std::vector<llama_token> tokens(static_cast<size_t>(n_prompt));
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), n_prompt, true, true) < 0) {
        throw_state(env, "提示词无法切分");
        return nullptr;
    }
    const int32_t limit = max_tokens > 0 ? max_tokens : 512;
    const int32_t n_ctx = static_cast<int32_t>(llama_n_ctx(s->ctx));
    if (n_prompt + limit > n_ctx) { throw_state(env, "这一批消息太长，超出本机模型上下文"); return nullptr; }

    llama_memory_clear(llama_get_memory(s->ctx), true);

    // 贪心解码 + 可选语法约束。先不带语法取最大值，只有它不合语法时才对整张词表做一次语法过滤：
    // 大多数步里最可能的 token 本来就合法，这样能省掉逐个检查 15 万词表的开销（手机上差别明显）。
    llama_sampler * gram = nullptr;
    if (!grammar.empty()) {
        gram = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (gram == nullptr) { throw_state(env, "输出格式约束无效"); return nullptr; }
    }

    const int32_t n_batch = static_cast<int32_t>(llama_n_batch(s->ctx));
    for (int32_t i = 0; i < n_prompt; i += n_batch) {
        const int32_t n = std::min(n_batch, n_prompt - i);
        if (llama_decode(s->ctx, llama_batch_get_one(tokens.data() + i, n)) != 0) {
            if (gram != nullptr) llama_sampler_free(gram);
            throw_state(env, s->cancel.load() ? "已取消" : "本机模型处理提示词失败");
            return nullptr;
        }
    }

    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> cand(static_cast<size_t>(n_vocab));
    std::string out;
    bool failed = false;
    for (int32_t produced = 0; produced < limit; ++produced) {
        if (s->cancel.load()) { failed = true; break; }
        const float * logits = llama_get_logits_ith(s->ctx, -1);
        if (logits == nullptr) { failed = true; break; }
        llama_token tok = static_cast<llama_token>(std::max_element(logits, logits + n_vocab) - logits);
        if (gram != nullptr) {
            llama_token_data one = {tok, logits[tok], 0.0f};
            llama_token_data_array single = {&one, 1, -1, false};
            llama_sampler_apply(gram, &single);
            if (!std::isfinite(one.logit)) {
                for (int32_t t = 0; t < n_vocab; ++t) cand[static_cast<size_t>(t)] = {t, logits[t], 0.0f};
                llama_token_data_array all = {cand.data(), cand.size(), -1, false};
                llama_sampler_apply(gram, &all);
                tok = -1;
                float best = -INFINITY;
                for (size_t k = 0; k < all.size; ++k) {
                    if (std::isfinite(all.data[k].logit) && all.data[k].logit > best) { best = all.data[k].logit; tok = all.data[k].id; }
                }
                if (tok < 0) break;   // 语法已无可选 token：输出已完整
            }
            llama_sampler_accept(gram, tok);
        }
        // 语法走到终点后只剩结束符合法，下一轮自然退出
        if (llama_vocab_is_eog(vocab, tok)) break;
        char piece[256];
        const int32_t n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, static_cast<size_t>(n));
        if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) { failed = true; break; }
    }
    if (gram != nullptr) llama_sampler_free(gram);
    if (failed) { throw_state(env, s->cancel.load() ? "已取消" : "本机模型生成失败"); return nullptr; }
    return string_to_bytes(env, out);
}

JNIEXPORT void JNICALL
Java_com_nopalou_LocalLlm_nativeCancel(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_nopalou_LocalLlm_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

}  // extern "C"
