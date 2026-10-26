// Box test for app/src/main/cpp/hymt_engine.cpp: same engine code, built as a static arm64 executable with the NDK
// and run under qemu-aarch64 (exercises the ARM NEON STQ1_0 kernel), or natively on the host.
// Usage: hymt-test <model.gguf> <threads> <prompt-file> [max_new]
#include <cstdio>
#include <fstream>
#include <sstream>
#include <chrono>
#include "../../app/src/main/cpp/hymt_engine.h"

int main(int argc, char ** argv) {
    if (argc < 4) { fprintf(stderr, "usage: %s model threads prompt-file [max_new]\n", argv[0]); return 2; }
    // utf8 prefix checks
    int fails = 0;
    auto chk = [&](const std::string & s, size_t want) {
        size_t got = hymt::utf8_complete_prefix(s);
        if (got != want) { fprintf(stderr, "utf8 FAIL len=%zu got=%zu want=%zu\n", s.size(), got, want); fails++; }
    };
    std::string hi = "\xe0\xa4\xae";  // one Devanagari char (3 bytes)
    chk("abc", 3); chk("ab" + hi, 5); chk("ab" + hi.substr(0, 2), 2); chk("ab" + hi.substr(0, 1), 2);
    chk("\xf0\x9f\x98\x80", 4); chk("x\xf0\x9f\x98", 1); chk("", 0);
    printf("utf8 checks: %s\n", fails ? "FAIL" : "ok");

    std::ifstream f(argv[3]); std::stringstream ss; ss << f.rdbuf();
    auto t0 = std::chrono::steady_clock::now();
    hymt::Engine * e = hymt::load(argv[1], atoi(argv[2]), 2048);
    if (!e) { printf("load FAILED\n"); return 1; }
    auto t1 = std::chrono::steady_clock::now();
    std::string out, err; int partials = 0;
    bool ok = hymt::generate(e, ss.str(), argc > 4 ? atoi(argv[4]) : 256, 42,
        [&](const std::string &) { partials++; }, out, err);
    auto t2 = std::chrono::steady_clock::now();
    printf("load %.1fs, generate %.1fs, ok=%d err=%s partials=%d\n",
        std::chrono::duration<double>(t1 - t0).count(), std::chrono::duration<double>(t2 - t1).count(), ok, err.c_str(), partials);
    printf("OUT<<%s>>\n", out.c_str());
    hymt::free_engine(e);
    return ok && !fails ? 0 : 1;
}
