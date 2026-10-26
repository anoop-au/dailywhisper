# Vendored llama.cpp (pruned)

- Source: https://github.com/ggml-org/llama.cpp, PR #22836 "ggml-cpu : add STQ1_0 ternary quantization with ARM NEON
  vec_dot kernel" (Tencent AngelSlim), head commit `1e411d8f5a1e23525fa3265dfb4bd76265465397`. Needed for
  Hy-MT1.5-1.8B-1.25bit (Sherry 1.25-bit, STQ1_0 tensors). Not merged upstream as of Oct 2026.
- Files are unmodified. Kept: top-level CMakeLists.txt, LICENSE (MIT), cmake/, include/, src/, ggml/ (CMake, include,
  core sources and the CPU backend only). Dropped: GPU backends, common/, tools/, tests, examples, docs, scripts.
- The published GGUF stores STQ tensors as type id 42 (the id before upstream added Q2_0). This PR uses 43, so the app
  rewrites those type ids once after download (GgufPatch in TranslateConfig.kt). Weights are untouched.
- To update: copy the same subset from a newer checkout and rebuild.
