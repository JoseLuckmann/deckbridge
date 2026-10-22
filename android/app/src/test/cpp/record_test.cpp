#include "../../main/cpp/record.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

static thread_local int gNewCount = 0;

void *operator new(std::size_t size) {
    ++gNewCount;
    return std::malloc(size == 0 ? 1 : size);
}

void *operator new[](std::size_t size) {
    ++gNewCount;
    return std::malloc(size == 0 ? 1 : size);
}

void operator delete(void *pointer) noexcept { std::free(pointer); }
void operator delete(void *pointer, std::size_t) noexcept { std::free(pointer); }
void operator delete[](void *pointer) noexcept { std::free(pointer); }
void operator delete[](void *pointer, std::size_t) noexcept { std::free(pointer); }

static int fails = 0;

static void check(bool ok, const char *name) {
    if (ok) {
        std::printf("PASS %s\n", name);
    } else {
        std::printf("FAIL %s\n", name);
        ++fails;
    }
}

int main() {
    deckbridge::SpscRing ring;
    gNewCount = 0;
    for (int index = 0; index < 10000; ++index) {
        const float sample = std::sin(index * 0.01f) * 0.5f;
        if (!ring.push(sample, -sample)) {
            check(false, "ring accepts 10000 frames");
            return 1;
        }
    }
    check(gNewCount == 0, "ring push allocates nothing");
    float left[64];
    float right[64];
    int popped = 0;
    while (true) {
        const int got = ring.pop(left, right, 64);
        if (got == 0) break;
        popped += got;
    }
    check(popped == 10000, "ring returns every frame");

    const std::string path = "/tmp/deckbridge-record.wav";
    deckbridge::MasterRecorder recorder;
    check(recorder.start(path, 48000), "wav file opens");
    gNewCount = 0;
    for (int index = 0; index < 4800; ++index) {
        const float sample = (index % 200 == 0) ? 0.8f : 0.05f;
        recorder.push(sample, sample * 0.5f);
    }
    check(gNewCount == 0, "audio-thread push allocates nothing");
    recorder.stop();
    FILE *file = std::fopen(path.c_str(), "rb");
    check(file != nullptr, "wav remains on disk");
    if (file != nullptr) {
        unsigned char header[44];
        const size_t read = std::fread(header, 1, 44, file);
        check(read == 44, "wav header is 44 bytes");
        check(std::memcmp(header, "RIFF", 4) == 0, "wav is RIFF");
        check(std::memcmp(header + 8, "WAVE", 4) == 0, "wav is WAVE");
        uint32_t data = header[40] | (header[41] << 8) | (header[42] << 16) | (header[43] << 24);
        check(data == 4800u * 4u, "wav data length matches the frames");
        std::fclose(file);
    }
    const std::string poll = recorder.poll();
    check(poll.find("4800,") == 0, "poll reports the frame count");
    if (fails != 0) {
        std::printf("%d FAILED\n", fails);
        return 1;
    }
    std::printf("ALL PASS\n");
    return 0;
}
