#include <string>

// A tiny native library that links the shared C++ runtime needed by FFmpeg.
extern "C" int ytdlp_cpp_runtime_probe(const char *value) {
    const std::string text(value != nullptr ? value : "");
    return static_cast<int>(text.size());
}
