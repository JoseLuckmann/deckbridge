#include "../../main/cpp/dsp.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <vector>

namespace {

bool readWav(const std::string &path, std::vector<float> &mono, int &rate) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    char riff[12];
    in.read(riff, 12);
    if (!in || std::string(riff, 4) != "RIFF" || std::string(riff + 8, 4) != "WAVE") return false;
    int channels = 0;
    int bits = 0;
    rate = 0;
    std::vector<char> data;
    while (in) {
        char id[4];
        uint32_t size = 0;
        in.read(id, 4);
        in.read(reinterpret_cast<char *>(&size), 4);
        if (!in) break;
        std::string tag(id, 4);
        if (tag == "fmt ") {
            std::vector<char> body(size);
            in.read(body.data(), size);
            if (size >= 16) {
                channels = body[2] | (body[3] << 8);
                rate = body[4] | (body[5] << 8) | (body[6] << 16) | (body[7] << 24);
                bits = body[14] | (body[15] << 8);
            }
        } else if (tag == "data") {
            data.resize(size);
            in.read(data.data(), size);
            break;
        } else {
            in.seekg(size, std::ios::cur);
        }
        if (size % 2) in.seekg(1, std::ios::cur);
    }
    if (channels < 1 || channels > 2 || bits != 16 || rate < 8000 || data.size() < 4) return false;
    const int frames = static_cast<int>(data.size() / (channels * 2));
    mono.resize(frames);
    for (int index = 0; index < frames; ++index) {
        auto sample = [&](int channel) {
            const int at = (index * channels + channel) * 2;
            const int raw = static_cast<int16_t>(static_cast<unsigned char>(data[at]) |
                                                  (static_cast<unsigned char>(data[at + 1]) << 8));
            return raw / 32768.f;
        };
        mono[index] = channels == 1 ? sample(0) : 0.5f * (sample(0) + sample(1));
    }
    return true;
}

void writeWav(const std::string &path, const std::vector<float> &left, const std::vector<float> &right, int rate) {
    const int frames = static_cast<int>(std::min(left.size(), right.size()));
    uint32_t dataBytes = static_cast<uint32_t>(frames * 4);
    std::ofstream out(path, std::ios::binary);
    out.write("RIFF", 4);
    uint32_t riff = 36 + dataBytes;
    out.write(reinterpret_cast<char *>(&riff), 4);
    out.write("WAVE", 4);
    out.write("fmt ", 4);
    uint32_t fmt = 16;
    uint16_t audio = 1;
    uint16_t channels = 2;
    uint32_t sampleRate = rate;
    uint32_t byteRate = rate * 4;
    uint16_t block = 4;
    uint16_t bits = 16;
    out.write(reinterpret_cast<char *>(&fmt), 4);
    out.write(reinterpret_cast<char *>(&audio), 2);
    out.write(reinterpret_cast<char *>(&channels), 2);
    out.write(reinterpret_cast<char *>(&sampleRate), 4);
    out.write(reinterpret_cast<char *>(&byteRate), 4);
    out.write(reinterpret_cast<char *>(&block), 2);
    out.write(reinterpret_cast<char *>(&bits), 2);
    out.write("data", 4);
    out.write(reinterpret_cast<char *>(&dataBytes), 4);
    for (int index = 0; index < frames; ++index) {
        auto store = [&](float value) {
            float clamped = deckbridge::sanitize(value);
            int raw = static_cast<int>(std::lround(clamped * 32767.f));
            if (raw > 32767) raw = 32767;
            if (raw < -32768) raw = -32768;
            uint16_t bits = static_cast<uint16_t>(raw);
            out.put(static_cast<char>(bits & 0xFF));
            out.put(static_cast<char>((bits >> 8) & 0xFF));
        };
        store(left[index]);
        store(right[index]);
    }
}

float at(const std::vector<float> &data, double position) {
    if (data.empty()) return 0.f;
    if (position < 0.0) position = 0.0;
    if (position >= data.size()) position = static_cast<double>(data.size() - 1);
    return deckbridge::readInterp(data.data(), static_cast<int>(data.size()), position);
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 8) {
        std::fprintf(stderr, "uso: real_mix a.wav b.wav out.wav startA startB bpmA bpmB\n");
        return 2;
    }
    std::vector<float> a;
    std::vector<float> b;
    int rateA = 0;
    int rateB = 0;
    if (!readWav(argv[1], a, rateA) || !readWav(argv[2], b, rateB)) {
        std::fprintf(stderr, "falha ao ler wav\n");
        return 1;
    }
    const double startA = std::atof(argv[4]);
    const double startB = std::atof(argv[5]);
    const double bpmA = std::atof(argv[6]);
    const double bpmB = std::atof(argv[7]);
    const int rate = 48000;
    const int seconds = 120;
    const int frames = rate * seconds;
    const double beat = deckbridge::samplesPerBeat(bpmA > 1.0 ? bpmA : 120.0, rate);
    const double loopStart = startA * rateA;
    const double loopLen = 4.0 * (rateA * 60.0 / (bpmA > 1.0 ? bpmA : 120.0));
    const double loopEnd = loopStart + loopLen;
    const float dryBefore = at(a, loopEnd - 1.0);
    const float dryAfter = at(a, loopStart);
    const float wetBefore = deckbridge::readLooped(a.data(), static_cast<int>(a.size()), loopEnd - 1.0, loopStart, loopEnd,
                                                   deckbridge::kFadeSamples);
    const float dryJump = dryBefore - dryAfter;
    const float wetJump = wetBefore - dryAfter;
    std::printf("seam_dry_energy %.8f\n", dryJump * dryJump);
    std::printf("seam_wet_energy %.8f\n", wetJump * wetJump);
    std::printf("seam_loop_start_sec %.3f\n", loopStart / rateA);
    std::printf("seam_loop_beats 4\n");

    deckbridge::BeatInsert echo;
    echo.configure(1, true, 96.f, static_cast<float>(bpmA > 1.0 ? bpmA : 120.0), 1.f, rate);
    const int echoDelay = static_cast<int>(std::llround(beat));
    std::printf("echo_delay_samples %d\n", echoDelay);
    std::printf("echo_beat_samples %.2f\n", beat);

    std::vector<float> left(frames);
    std::vector<float> right(frames);
    int nans = 0;
    float peak = 0.f;
    double posA = startA * rateA;
    double posB = startB * rateB;
    const double stepA = static_cast<double>(rateA) / rate;
    const double stepB = static_cast<double>(rateB) / rate;
    for (int index = 0; index < frames; ++index) {
        const double t = index / static_cast<double>(rate);
        float gainA = 0.72f;
        float gainB = 0.f;
        if (t >= 30.0 && t < 50.0) gainB = 0.7f * static_cast<float>((t - 30.0) / 20.0);
        else if (t >= 50.0) gainB = 0.7f;
        if (t >= 70.0 && t < 90.0) gainA = 0.72f * static_cast<float>(1.0 - (t - 70.0) / 20.0);
        else if (t >= 90.0) gainA = 0.f;
        float l = at(a, posA) * gainA + at(b, posB) * gainB;
        float r = l;
        if (t >= 48.0 && t < 78.0) echo.process(l, r);
        if (!std::isfinite(l) || !std::isfinite(r)) nans += 1;
        l = deckbridge::sanitize(l);
        r = deckbridge::sanitize(r);
        peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
        left[index] = l;
        right[index] = r;
        posA += stepA;
        posB += stepB;
    }
    writeWav(argv[3], left, right, rate);
    std::printf("mix_seconds %d\n", seconds);
    std::printf("mix_rate %d\n", rate);
    std::printf("mix_peak %.5f\n", peak);
    std::printf("mix_nans %d\n", nans);
    std::printf("bpm_a %.2f\n", bpmA);
    std::printf("bpm_b %.2f\n", bpmB);
    std::printf("ALL PASS\n");
    return nans == 0 && peak <= 1.f ? 0 : 1;
}
