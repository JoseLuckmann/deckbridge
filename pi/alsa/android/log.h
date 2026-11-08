// __android_log_print no Linux: vai para o stderr (o journald guarda quando o motor roda como serviço).
#pragma once

#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6

inline int __android_log_print(int, const char *tag, const char *fmt, ...) {
    std::fprintf(stderr, "[%s] ", tag != nullptr ? tag : "deckbridge");
    va_list args;
    va_start(args, fmt);
    const int n = std::vfprintf(stderr, fmt, args);
    va_end(args);
    std::fputc('\n', stderr);
    return n;
}
