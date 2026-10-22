#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace deckbridge {

// Um produtor (a callback de áudio) e um consumidor (a thread de escrita).
// O push não aloca, não trava e não abre arquivo.
struct SpscRing {
    static constexpr uint32_t CAP = 1u << 16;
    static constexpr uint32_t MASK = CAP - 1;
    float left[CAP]{};
    float right[CAP]{};
    std::atomic<uint32_t> write{0};
    std::atomic<uint32_t> read{0};

    bool push(float sampleL, float sampleR) {
        const uint32_t at = write.load(std::memory_order_relaxed);
        const uint32_t next = (at + 1u) & MASK;
        if (next == read.load(std::memory_order_acquire)) return false;
        left[at] = sampleL;
        right[at] = sampleR;
        write.store(next, std::memory_order_release);
        return true;
    }

    int pop(float *outL, float *outR, int max) {
        uint32_t at = read.load(std::memory_order_relaxed);
        const uint32_t end = write.load(std::memory_order_acquire);
        int count = 0;
        while (count < max && at != end) {
            outL[count] = left[at];
            outR[count] = right[at];
            at = (at + 1u) & MASK;
            ++count;
        }
        read.store(at, std::memory_order_release);
        return count;
    }
};

class MasterRecorder {
public:
    bool start(const std::string &path, int rate) {
        if (armed.load(std::memory_order_acquire)) return false;
        FILE *opened = std::fopen(path.c_str(), "wb");
        if (opened == nullptr) return false;
        file = opened;
        sampleRate = rate > 0 ? rate : 48000;
        dataBytes = 0;
        frames.store(0);
        dropped.store(0);
        peak.store(0);
        stopFlag.store(false);
        if (!writePlaceholder()) {
            std::fclose(file);
            file = nullptr;
            return false;
        }
        armed.store(true, std::memory_order_release);
        worker = std::thread([this] { drain(); });
        return true;
    }

    void push(float sampleL, float sampleR) {
        if (!armed.load(std::memory_order_relaxed)) return;
        const float absL = sampleL < 0.f ? -sampleL : sampleL;
        const float absR = sampleR < 0.f ? -sampleR : sampleR;
        const int mag = static_cast<int>((absL > absR ? absL : absR) * 1000.f);
        int seen = peak.load(std::memory_order_relaxed);
        while (mag > seen && !peak.compare_exchange_weak(seen, mag, std::memory_order_relaxed)) {
        }
        if (!master.push(sampleL, sampleR)) dropped.fetch_add(1, std::memory_order_relaxed);
        else frames.fetch_add(1, std::memory_order_relaxed);
        if (sideEnabled.load(std::memory_order_relaxed)) side.push(sampleL, sampleR);
    }

    int pullSide(float *interleaved, int maxFrames) {
        float left[256];
        float right[256];
        const int ask = maxFrames < 256 ? maxFrames : 256;
        const int got = side.pop(left, right, ask);
        for (int index = 0; index < got; ++index) {
            interleaved[index * 2] = left[index];
            interleaved[index * 2 + 1] = right[index];
        }
        return got;
    }

    void setSide(bool enabled) { sideEnabled.store(enabled, std::memory_order_relaxed); }

    void stop() {
        if (!armed.load(std::memory_order_acquire) && file == nullptr) return;
        stopFlag.store(true, std::memory_order_release);
        armed.store(false, std::memory_order_release);
        if (worker.joinable()) worker.join();
        if (file != nullptr) {
            patchHeader();
            std::fclose(file);
            file = nullptr;
        }
    }

    std::string poll() {
        const int seen = peak.load(std::memory_order_relaxed);
        peak.store(seen / 2, std::memory_order_relaxed);
        char line[96];
        std::snprintf(line, sizeof(line), "%llu,%llu,%d,%d",
                      static_cast<unsigned long long>(frames.load()),
                      static_cast<unsigned long long>(dropped.load()),
                      seen, sampleRate);
        return line;
    }

    int rate() const { return sampleRate; }
    bool running() const { return armed.load(std::memory_order_acquire); }

private:
    bool writePlaceholder() {
        unsigned char header[44] = {};
        std::memcpy(header, "RIFF", 4);
        std::memcpy(header + 8, "WAVEfmt ", 8);
        header[16] = 16;
        header[20] = 1;
        header[22] = 2;
        put32(header + 24, static_cast<uint32_t>(sampleRate));
        put32(header + 28, static_cast<uint32_t>(sampleRate) * 4u);
        header[32] = 4;
        header[34] = 16;
        std::memcpy(header + 36, "data", 4);
        return std::fwrite(header, 1, 44, file) == 44;
    }

    void patchHeader() {
        if (file == nullptr) return;
        std::fflush(file);
        unsigned char size[4];
        put32(size, 36u + dataBytes);
        std::fseek(file, 4, SEEK_SET);
        std::fwrite(size, 1, 4, file);
        put32(size, dataBytes);
        std::fseek(file, 40, SEEK_SET);
        std::fwrite(size, 1, 4, file);
        std::fflush(file);
    }

    void drain() {
        std::vector<float> left(512);
        std::vector<float> right(512);
        std::vector<unsigned char> pcm(512 * 4);
        while (true) {
            const int got = master.pop(left.data(), right.data(), 512);
            if (got > 0) {
                for (int index = 0; index < got; ++index) {
                    writeSample(pcm.data() + index * 4, left[index]);
                    writeSample(pcm.data() + index * 4 + 2, right[index]);
                }
                const size_t bytes = static_cast<size_t>(got) * 4u;
                std::fwrite(pcm.data(), 1, bytes, file);
                dataBytes += static_cast<uint32_t>(bytes);
            } else if (stopFlag.load(std::memory_order_acquire)) {
                break;
            } else {
                std::this_thread::sleep_for(std::chrono::milliseconds(4));
            }
        }
    }

    static void writeSample(unsigned char *at, float sample) {
        float clamped = sample;
        if (clamped > 1.f) clamped = 1.f;
        if (clamped < -1.f) clamped = -1.f;
        int value = static_cast<int>(clamped * 32767.f);
        at[0] = static_cast<unsigned char>(value & 0xFF);
        at[1] = static_cast<unsigned char>((value >> 8) & 0xFF);
    }

    static void put32(unsigned char *at, uint32_t value) {
        at[0] = static_cast<unsigned char>(value & 0xFF);
        at[1] = static_cast<unsigned char>((value >> 8) & 0xFF);
        at[2] = static_cast<unsigned char>((value >> 16) & 0xFF);
        at[3] = static_cast<unsigned char>((value >> 24) & 0xFF);
    }

    SpscRing master;
    SpscRing side;
    std::atomic<bool> armed{false};
    std::atomic<bool> stopFlag{false};
    std::atomic<bool> sideEnabled{false};
    std::atomic<uint64_t> frames{0};
    std::atomic<uint64_t> dropped{0};
    std::atomic<int> peak{0};
    int sampleRate = 48000;
    uint32_t dataBytes = 0;
    FILE *file = nullptr;
    std::thread worker;
};

}  // namespace deckbridge
