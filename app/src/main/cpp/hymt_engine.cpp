#include "hymt_engine.h"

#include <vector>
#include "llama.h"

namespace hymt {

namespace {
// Tencent's recommended sampling for HY-MT1.5 (model card): top_k 20, top_p 0.6, temperature 0.7, repetition 1.05.
constexpr int   kTopK = 20;
constexpr float kTopP = 0.6f;
constexpr float kTemp = 0.7f;
constexpr float kRepeat = 1.05f;
constexpr int   kRepeatLastN = 64;

void quiet_log(enum ggml_log_level, const char *, void *) {}

bool abort_cb(void * data) { return static_cast<Engine *>(data)->cancel.load(); }
}  // namespace

size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    // Walk back over at most 3 continuation bytes to the lead byte of the last character.
    size_t i = n;
    int back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {  // lead byte or ASCII
            size_t need = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 1;
            return (n - (i - 1)) >= need ? n : i - 1;
        }
        --i; ++back;
    }
    return n;
}

Engine * load(const char * path, int threads, int n_ctx) {
    static bool inited = false;
    if (!inited) { llama_log_set(quiet_log, nullptr); llama_backend_init(); inited = true; }
    auto * e = new Engine();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    e->model = llama_model_load_from_file(path, mp);
    if (!e->model) { delete e; return nullptr; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(n_ctx);
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.no_perf = true;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = e;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { llama_model_free(e->model); delete e; return nullptr; }
    return e;
}

bool generate(Engine * e, const std::string & prompt, int max_new, uint32_t seed,
              const std::function<void(const std::string &)> & on_partial, std::string & out, std::string & error) {
    out.clear();
    e->cancel.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_memory_clear(llama_get_memory(e->ctx), true);

    // The prompt already carries the begin-of-sentence and role tokens as text, so: no extra BOS, parse specials.
    int n = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
    std::vector<llama_token> toks(n);
    if (llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), toks.data(), n, false, true) < 0) {
        error = "tokenize"; return false;
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(e->ctx));
    if (n + 8 >= n_ctx) { error = "too long"; return false; }
    if (max_new > n_ctx - n) max_new = n_ctx - n;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), kRepeatLastN, kRepeat, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(kTopK));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(kTopP, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(kTemp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));

    bool ok = true;
    llama_batch batch = llama_batch_get_one(toks.data(), n);
    llama_token tok;
    char piece[256];
    for (int i = 0; i < max_new; ++i) {
        if (e->cancel.load()) { error = "cancelled"; ok = false; break; }
        if (llama_decode(e->ctx, batch) != 0) { error = e->cancel.load() ? "cancelled" : "decode"; ok = false; break; }
        tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;
        int len = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, false);
        if (len > 0) {
            out.append(piece, static_cast<size_t>(len));
            if (on_partial) on_partial(out.substr(0, utf8_complete_prefix(out)));
        }
        batch = llama_batch_get_one(&tok, 1);
    }
    llama_sampler_free(smpl);
    out.resize(utf8_complete_prefix(out));
    return ok;
}

void free_engine(Engine * e) {
    if (!e) return;
    if (e->ctx) llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

}  // namespace hymt
