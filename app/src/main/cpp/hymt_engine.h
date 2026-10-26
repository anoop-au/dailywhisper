// Hy-MT offline translation on llama.cpp (CPU only). Plain C++ so the same code runs in the app (via
// hymt_jni.cpp) and in the box test (tools/hymt-native-test). No logging of note text, ever.
#pragma once
#include <atomic>
#include <functional>
#include <string>

struct llama_model;
struct llama_context;

namespace hymt {

struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

// Loads the GGUF (memory-mapped). Returns nullptr on failure.
Engine * load(const char * path, int threads, int n_ctx);

// Generates the model's reply to [prompt] (already in the chat format, special tokens as text).
// [on_partial] gets the valid-UTF-8 text so far (may be null). Returns false if cancelled or failed;
// [error] then says why ("cancelled", "too long", ...).
bool generate(Engine * e, const std::string & prompt, int max_new, uint32_t seed,
              const std::function<void(const std::string &)> & on_partial, std::string & out, std::string & error);

void free_engine(Engine * e);

// Length of the longest prefix of [s] that is complete UTF-8 (drops a cut-off multi-byte tail).
size_t utf8_complete_prefix(const std::string & s);

}  // namespace hymt
