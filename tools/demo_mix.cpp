// Mistura offline pelo mesmo caminho do APK: dsp.h e a ordem de
// ToneEngine::onAudioReady / Deck::read em engine.cpp. Não é o tablet.
#include "dsp.h"
#include "drum_load.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr int kRate = 48000;
constexpr int kBeat = 24000;  // 120 BPM
constexpr int kBar = kBeat * 4;
constexpr float kBpm = 120.f;

struct Deck {
    std::vector<float> left;
    std::vector<float> right;
    int sampleRate = kRate;
    double position = 0.0;
    double pitch = 1.0;
    float volume = 1.f;
    bool playing = false;
    float bpm = 120.f;
    double gridOriginSec = 0.0;
    double gridBpm = 0.0;
    double lastEmitted = 0.0;
    double rateMul = 1.0;
    double rollOrigin = -1.0;
    double rollLength = -1.0;
    double slip = -1.0;
    bool keyLock = false;
    int keyShift = 0;
    bool reversed = false;
    bool userSlip = false;
    double slipAnchor = -1.0;
    double loopStart = -1.0;
    double loopEnd = -1.0;
    deckbridge::PitchShift shiftL;
    deckbridge::PitchShift shiftR;

    void wrapLoop() { position = deckbridge::wrapReverseLoop(position, loopStart, loopEnd, reversed); }

    void read(int outputRate, float &leftOut, float &rightOut) {
        leftOut = 0.f;
        rightOut = 0.f;
        if (!playing || left.empty() || sampleRate <= 0 || outputRate <= 0) return;
        if (position < 0.0) position = 0.0;
        wrapLoop();
        const int frames = static_cast<int>(left.size());
        const int index = static_cast<int>(position);
        if (index >= frames) {
            playing = false;
            position = 0.0;
            return;
        }
        lastEmitted = position;
        leftOut = deckbridge::readPerformance(left.data(), frames, position, loopStart, loopEnd, rollOrigin, rollLength,
                                             deckbridge::kFadeSamples);
        rightOut = deckbridge::readPerformance(right.data(), frames, position, loopStart, loopEnd, rollOrigin, rollLength,
                                              deckbridge::kFadeSamples);
        float ratio = 1.f;
        bool shift = false;
        if (keyLock && pitch > 0.05 && std::abs(pitch - 1.0) > 0.01) {
            ratio = static_cast<float>(1.0 / pitch);
            shift = true;
        }
        if (keyShift != 0) {
            ratio *= std::pow(2.f, static_cast<float>(keyShift) / 12.f);
            shift = true;
        }
        if (shift && std::abs(ratio - 1.f) > 0.0008f) {
            leftOut = shiftL.process(leftOut, ratio);
            rightOut = shiftR.process(rightOut, ratio);
        }
        const double step = deckbridge::directedStep(pitch, rateMul, sampleRate, outputRate, reversed);
        if (slip >= 0.0) slip += (pitch * static_cast<double>(sampleRate)) / static_cast<double>(outputRate);
        position += step;
        if (rollLength > 1.0 && rollOrigin >= 0.0 && position >= rollOrigin + rollLength) {
            double over = std::fmod(position - rollOrigin, rollLength);
            if (over < 0.0) over += rollLength;
            position = rollOrigin + over;
        }
        if (position < 0.0) {
            position = 0.0;
            if (reversed && !(loopEnd > loopStart)) playing = false;
        }
        wrapLoop();
    }
};

bool loadWav(const char *path, std::vector<float> &left, std::vector<float> &right) {
    FILE *file = std::fopen(path, "rb");
    if (file == nullptr) return false;
    char riff[12];
    if (std::fread(riff, 1, 12, file) != 12) {
        std::fclose(file);
        return false;
    }
    int rate = 0;
    int channels = 0;
    int bits = 0;
    int audioFormat = 1;
    std::vector<unsigned char> data;
    while (true) {
        char id[4];
        uint32_t size = 0;
        if (std::fread(id, 1, 4, file) != 4) break;
        if (std::fread(&size, 4, 1, file) != 1) break;
        if (std::memcmp(id, "fmt ", 4) == 0) {
            unsigned char fmt[64]{};
            const uint32_t take = std::min<uint32_t>(size, sizeof(fmt));
            if (std::fread(fmt, 1, take, file) != take) break;
            if (size > take) std::fseek(file, static_cast<long>(size - take), SEEK_CUR);
            audioFormat = fmt[0] | (fmt[1] << 8);
            channels = fmt[2] | (fmt[3] << 8);
            rate = fmt[4] | (fmt[5] << 8) | (fmt[6] << 16) | (fmt[7] << 24);
            bits = fmt[14] | (fmt[15] << 8);
            if (size & 1) std::fgetc(file);
        } else if (std::memcmp(id, "data", 4) == 0) {
            data.resize(size);
            if (size > 0 && std::fread(data.data(), 1, size, file) != size) {
                std::fclose(file);
                return false;
            }
            if (size & 1) std::fgetc(file);
            break;
        } else {
            std::fseek(file, static_cast<long>(size + (size & 1)), SEEK_CUR);
        }
    }
    std::fclose(file);
    if (rate != kRate || bits != 16 || audioFormat != 1 || (channels != 1 && channels != 2) || data.size() < 2) return false;
    const int frames = static_cast<int>(data.size() / (channels * 2));
    left.resize(frames);
    right.resize(frames);
    for (int index = 0; index < frames; ++index) {
        auto sample = [&](int channel) {
            const int byte = (index * channels + channel) * 2;
            const int16_t value = static_cast<int16_t>(data[byte] | (data[byte + 1] << 8));
            return value / 32768.f;
        };
        left[index] = sample(0);
        right[index] = channels == 2 ? sample(1) : left[index];
    }
    return true;
}

struct Voice {
    std::vector<float> samples;
    int cursor = -1;

    void trigger() { cursor = samples.empty() ? -1 : 0; }

    float next() {
        if (cursor < 0 || cursor >= static_cast<int>(samples.size())) {
            cursor = -1;
            return 0.f;
        }
        return samples[cursor++];
    }

    bool active() const { return cursor >= 0 && cursor < static_cast<int>(samples.size()); }
};

struct Seg {
    int samples = 0;
    std::string cue;
    std::string voicePath;
    bool each = false;
    std::function<void(double)> apply;
    std::function<void(float &, float &)> tap;
};

struct Host {
    Deck decks[2];
    deckbridge::MixerFx mix;
    deckbridge::PeakLimiter limit;
    float cross = 0.f;
    float trim[2]{1.f, 1.f};
    std::vector<float> outL;
    std::vector<float> outR;
    std::vector<Seg> segs;
    std::vector<std::pair<double, std::string>> cues;
    deckbridge::DelayLine legacyL;
    deckbridge::DelayLine legacyR;
    deckbridge::Biquad legacyLpL;
    deckbridge::Biquad legacyLpR;
    bool legacyOn = false;
    bool lockDrums = true;
    bool solo = false;
    int shotVoice = -1;
    int shotPos = 0;

    void legacy(float &left, float &right) {
        if (!legacyOn) return;
        if (legacyL.data.empty()) {
            legacyL.prepare(96000);
            legacyR.prepare(96000);
        }
        const float amount = 0.95f;
        const float fb = 0.12f + 0.55f * amount;
        const float echoL = legacyL.read(12000);
        const float echoR = legacyR.read(12000);
        legacyL.push(left + echoL * fb);
        legacyR.push(right + echoR * fb);
        float wetL = left * (1.f - amount * 0.65f) + echoL * amount;
        float wetR = right * (1.f - amount * 0.65f) + echoR * amount;
        const float cutoff = 120.f + (0.04f / 0.5f) * 2000.f;
        legacyLpL.setLowpass(cutoff, static_cast<float>(kRate));
        legacyLpR.setLowpass(cutoff, static_cast<float>(kRate));
        left = legacyLpL.process(wetL);
        right = legacyLpR.process(wetR);
    }

    void armRoll(int deck) {
        Deck &target = decks[deck];
        const bool want = mix.rollDeck[deck];
        if (want && target.rollLength < 0.0 && target.sampleRate > 0) {
            const double bpm = target.pitch > 0.0 ? kBpm / target.pitch : kBpm;
            const double slice = deckbridge::samplesPerBeat(bpm, target.sampleRate) * std::max(0.0625, mix.rollBeats);
            const double origin = std::floor(target.position / slice) * slice;
            target.rollOrigin = origin;
            target.rollLength = slice;
            if (mix.slipDeck[deck]) target.slip = target.position;
        }
        if (!want && target.rollLength > 0.0) {
            if (target.slip >= 0.0) target.position = target.slip;
            target.slip = -1.0;
            target.rollOrigin = -1.0;
            target.rollLength = -1.0;
        }
    }

    void tickSlip(int deck) {
        Deck &target = decks[deck];
        if (!target.userSlip || target.slipAnchor < 0.0) return;
        const double forward = (std::max(0.0, target.pitch) * std::max(0.0, target.rateMul) * target.sampleRate) /
                               static_cast<double>(kRate);
        target.slipAnchor += forward;
    }

    void regrid(int deck, double downbeatSec, int64_t sample) {
        Deck &target = decks[deck];
        const double frames = static_cast<double>(target.left.size());
        double pos = downbeatSec * target.sampleRate + static_cast<double>(sample) * target.pitch;
        if (pos < 0.0) pos = 0.0;
        if (frames > 2.0 && pos > frames - 2.0) pos = frames - 2.0;
        target.position = pos;
    }

    void setLoop(int deck, float startSec, float endSec) {
        Deck &target = decks[deck];
        if (endSec > startSec && startSec >= 0.f) {
            target.loopStart = static_cast<double>(startSec) * target.sampleRate;
            target.loopEnd = static_cast<double>(endSec) * target.sampleRate;
        } else {
            target.loopStart = -1.0;
            target.loopEnd = -1.0;
        }
    }

    void jump(int deck, double beats) {
        Deck &target = decks[deck];
        const double frames = static_cast<double>(target.left.size());
        target.position = deckbridge::beatJumpSample(target.position, beats, kBpm / target.pitch, target.sampleRate,
                                                      target.loopStart, target.loopEnd, frames, true);
    }

    void slip(int deck, bool on) {
        Deck &target = decks[deck];
        if (on) {
            target.userSlip = true;
            target.slipAnchor = target.position;
            return;
        }
        if (target.userSlip && target.slipAnchor >= 0.0) {
            const double frames = static_cast<double>(target.left.size());
            const double limit = frames > 1.0 ? frames - 1.0 : 0.0;
            target.position = std::min(std::max(0.0, target.slipAnchor), limit);
        }
        target.userSlip = false;
        target.slipAnchor = -1.0;
        target.loopStart = -1.0;
        target.loopEnd = -1.0;
    }

    void beat(int effect, bool on, int level, int channel, float beats) {
        mix.setBeat(effect, on, std::clamp(level / 127.f, 0.f, 1.f), channel, kBpm, beats);
    }

    void bed(double pitch, bool drumsSolo) {
        lockDrums = true;
        solo = drumsSolo;
        shotVoice = -1;
        legacyOn = false;
        mix.setFilter(0, 0.5f);
        mix.setFilter(1, 0.5f);
        decks[0].pitch = pitch;
        decks[1].pitch = pitch;
        cross = 1.f;
        decks[0].volume = 0.f;
        decks[1].volume = 1.f;
    }

    void armDrums(bool on, int voice, int bars, bool gap, bool pitchRise, float build) {
        mix.setRmx(true, 0, false, 0.f, static_cast<float>(bars), 2, 0.25f, on, -1, 1.f, 1.f, 1.f, 1.f, 1.f, 1.f, false, 1,
                   kBpm, build, voice, bars, gap, pitchRise, 1);
    }

    void iso(float aL, float aM, float aH, float bL, float bM, float bH) {
        mix.setRmx(false, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, aL, aM, aH, bL, bM, bH, false, 1, kBpm);
    }

    void render(const std::vector<Voice> &library) {
        mix.setSampleRate(kRate);
        std::vector<Voice> voices = library;
        int64_t cursor = 0;
        int voice = -1;
        double sumL = 0.0;
        double sumR = 0.0;
        int64_t counted = 0;
        int nan = 0;
        for (const Seg &seg : segs) {
            const double sec = static_cast<double>(cursor) / kRate;
            if (!seg.cue.empty()) {
                cues.push_back({sec, seg.cue});
                std::printf("CUE %.3f %s\n", sec, seg.cue.c_str());
            }
            if (!seg.voicePath.empty()) {
                const std::string &path = seg.voicePath;
                const auto slash = path.find_last_of('/');
                const std::string name = slash == std::string::npos ? path : path.substr(slash + 1);
                const int id = std::atoi(name.c_str());
                if (id >= 0 && id < static_cast<int>(voices.size())) {
                    voices[id].trigger();
                    voice = id;
                }
            }
            if (!seg.each && seg.apply) seg.apply(0.0);
            sumL = 0.0;
            sumR = 0.0;
            counted = 0;
            for (int index = 0; index < seg.samples; ++index) {
                if (seg.each && seg.apply) seg.apply(seg.samples > 1 ? static_cast<double>(index) / (seg.samples - 1) : 0.0);
                mix.beginSample();
                if (mix.release.active() && mix.release.kind != 1) {
                    float ignoredL = 0.f;
                    float ignoredR = 0.f;
                    mix.release.process(ignoredL, ignoredR);
                }
                armRoll(0);
                armRoll(1);
                decks[0].rateMul = mix.rateFor(0);
                decks[1].rateMul = mix.rateFor(1);
                tickSlip(0);
                tickSlip(1);
                float al = 0.f, ar = 0.f, bl = 0.f, br = 0.f;
                decks[0].read(kRate, al, ar);
                decks[1].read(kRate, bl, br);
                if (solo) {
                    al = ar = bl = br = 0.f;
                }
                const int follow = std::clamp(mix.drumDeck, 0, 1);
                const Deck &grid = decks[follow];
                const double gridBpm = grid.gridBpm > 1.0 ? grid.gridBpm : static_cast<double>(grid.bpm);
                const bool gridLive = grid.playing && gridBpm > 1.0;
                mix.setDrumPhase(deckbridge::musicalBeat(grid.lastEmitted, grid.sampleRate, gridBpm, grid.gridOriginSec),
                                 gridLive, lockDrums);
                mix.processDeck(0, al, ar);
                mix.processDeck(1, bl, br);
                al *= trim[0];
                ar *= trim[0];
                bl *= trim[1];
                br *= trim[1];
                float masterL = al * (1.f - cross) * decks[0].volume + bl * cross * decks[1].volume;
                float masterR = ar * (1.f - cross) * decks[0].volume + br * cross * decks[1].volume;
                mix.processMaster(masterL, masterR);
                if (shotVoice >= 0) {
                    const auto &pcm = deckbridge::drumBank().voice[shotVoice];
                    if (shotPos >= 0 && shotPos < static_cast<int>(pcm.size())) {
                        masterL += pcm[static_cast<size_t>(shotPos)] * 0.95f;
                        masterR += pcm[static_cast<size_t>(shotPos)] * 0.95f;
                    }
                    shotPos += 1;
                }
                if (seg.tap) seg.tap(masterL, masterR);
                if (voice >= 0 && voices[voice].active()) {
                    const float spoken = voices[voice].next();
                    masterL = masterL * 0.70f + spoken;
                    masterR = masterR * 0.70f + spoken;
                }
                limit.process(masterL, masterR);
                if (!std::isfinite(masterL) || !std::isfinite(masterR)) {
                    nan += 1;
                    masterL = 0.f;
                    masterR = 0.f;
                }
                outL.push_back(masterL);
                outR.push_back(masterR);
                sumL += static_cast<double>(masterL) * masterL;
                sumR += static_cast<double>(masterR) * masterR;
                counted += 1;
                cursor += 1;
            }
            if (!seg.cue.empty() && counted > 0) {
                const double rms = std::sqrt((sumL + sumR) / (2.0 * static_cast<double>(counted)));
                std::printf("RMS %.3f %.4f\n", sec, rms);
            }
        }
        const int fadeIn = kRate / 100;
        const int fadeOut = kRate / 20;
        const int total = static_cast<int>(outL.size());
        for (int index = 0; index < fadeIn && index < total; ++index) {
            const float gain = index / static_cast<float>(fadeIn);
            outL[index] *= gain;
            outR[index] *= gain;
        }
        for (int index = 0; index < fadeOut && index < total; ++index) {
            const float gain = index / static_cast<float>(fadeOut);
            outL[total - 1 - index] *= gain;
            outR[total - 1 - index] *= gain;
        }
        float peak = 0.f;
        for (int index = 0; index < total; ++index) peak = std::max(peak, std::max(std::fabs(outL[index]), std::fabs(outR[index])));
        std::printf("PEAK_LIMITED %.6f\n", peak);
        std::printf("NAN %d\n", nan);
        if (peak > 0.01f && peak < 0.93f) {
            const float gain = 0.97f / peak;
            deckbridge::PeakLimiter again;
            float after = 0.f;
            for (int index = 0; index < total; ++index) {
                outL[index] *= gain;
                outR[index] *= gain;
                again.process(outL[index], outR[index]);
                after = std::max(after, std::max(std::fabs(outL[index]), std::fabs(outR[index])));
            }
            std::printf("PEAK_NORMALISED %.6f\n", after);
        } else {
            std::printf("PEAK_NORMALISED %.6f\n", peak);
        }
        std::printf("SECONDS %.3f\n", total / static_cast<double>(kRate));
    }
};

bool writeWav(const char *path, const std::vector<float> &left, const std::vector<float> &right) {
    FILE *file = std::fopen(path, "wb");
    if (file == nullptr) return false;
    const uint32_t frames = static_cast<uint32_t>(std::min(left.size(), right.size()));
    const uint32_t dataBytes = frames * 2 * 2;
    const uint32_t riffSize = 36 + dataBytes;
    std::fwrite("RIFF", 1, 4, file);
    std::fwrite(&riffSize, 4, 1, file);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    const uint32_t fmtSize = 16;
    const uint16_t format = 1;
    const uint16_t channels = 2;
    const uint32_t rate = kRate;
    const uint16_t bits = 16;
    const uint16_t block = channels * bits / 8;
    const uint32_t byteRate = rate * block;
    std::fwrite(&fmtSize, 4, 1, file);
    std::fwrite(&format, 2, 1, file);
    std::fwrite(&channels, 2, 1, file);
    std::fwrite(&rate, 4, 1, file);
    std::fwrite(&byteRate, 4, 1, file);
    std::fwrite(&block, 2, 1, file);
    std::fwrite(&bits, 2, 1, file);
    std::fwrite("data", 1, 4, file);
    std::fwrite(&dataBytes, 4, 1, file);
    for (uint32_t index = 0; index < frames; ++index) {
        auto store = [&](float sample) {
            const float clamped = std::clamp(sample, -1.f, 1.f);
            const int16_t value = static_cast<int16_t>(std::lrint(clamped * 32767.f));
            std::fwrite(&value, 2, 1, file);
        };
        store(left[index]);
        store(right[index]);
    }
    std::fclose(file);
    return true;
}

void buildV2(Host &host) {
    auto add = [&](int bars, const std::string &cue, bool each, std::function<void(double)> apply, bool tap = false) {
        Seg seg;
        seg.samples = bars * kBar;
        seg.cue = cue;
        seg.each = each;
        seg.apply = std::move(apply);
        if (tap) seg.tap = [&](float &left, float &right) { host.legacy(left, right); };
        host.segs.push_back(std::move(seg));
    };
    auto fxOff = [&]() {
        host.legacyOn = false;
        host.mix.setFilter(0, 0.5f);
        host.mix.setFilter(1, 0.5f);
        host.iso(1.f, 1.f, 1.f, 1.f, 1.f, 1.f);
        host.beat(1, false, 0, 1, 0.5f);
    };

    add(8, "Deck A, filtro abre devagar", true, [&](double t) {
        host.cross = 0.f;
        fxOff();
        host.mix.setFilter(0, 0.28f + 0.22f * static_cast<float>(t));
    });
    add(8, "Deck B entra, grave troca, filtro sobe", true, [&](double t) {
        host.legacyOn = false;
        host.cross = static_cast<float>(t);
        host.mix.setFilter(1, 0.20f + 0.30f * static_cast<float>(t));
        host.mix.setFilter(0, 0.50f - 0.12f * static_cast<float>(t));
        const float lowA = 1.f - 0.55f * static_cast<float>(t);
        const float lowB = 0.45f + 0.55f * static_cast<float>(t);
        host.iso(lowA, 1.f, 1.f, lowB, 1.f, 1.f);
        host.beat(1, false, 0, 1, 0.5f);
    });
    add(8, "Eco no deck B, de 1/2 para 1 tempo, 55%", true, [&](double t) {
        host.cross = 1.f;
        host.mix.setFilter(0, 0.5f);
        host.mix.setFilter(1, 0.5f);
        host.iso(1, 1, 1, 1, 1, 1);
        host.beat(1, true, 70, 1, 0.5f + 0.5f * static_cast<float>(t));
    });
    add(4, "Eco desliga, a cauda continua", false, [&](double) {
        host.cross = 1.f;
        host.beat(1, false, 70, 1, 1.f);
    });
    add(4, "Flanger, profundidade sobe ate 60%", true, [&](double t) {
        host.beat(6, true, 51 + static_cast<int>(25.0 * t), 1, 1.f);
    });
    add(4, "Phaser em 1/2, por volta de 55%", true, [&](double t) {
        host.beat(7, true, 58 + static_cast<int>(12.0 * t), 1, 0.5f);
    });
    add(4, "Reverb por volta de 45%", true, [&](double t) {
        host.beat(4, true, 45 + static_cast<int>(18.0 * t), 1, 1.f);
    });
    add(2, "Slip roll de 1 tempo", false, [&](double) { host.beat(9, true, 70, 1, 1.f); });
    add(2, "Slip solta", false, [&](double) { host.beat(9, false, 0, 1, 1.f); });
    add(2, "Roll de 1/2 tempo", false, [&](double) { host.beat(10, true, 64, 1, 0.5f); });
    add(2, "Roll solta", false, [&](double) { host.beat(10, false, 0, 1, 0.5f); });
    add(8, "Filtro do deck B, grave e agudo", true, [&](double t) {
        host.cross = 1.f;
        host.beat(13, false, 0, 1, 0.5f);
        const float norm = t < 0.5 ? 0.50f - static_cast<float>(t / 0.5) * 0.28f
                                    : 0.22f + static_cast<float>((t - 0.5) / 0.5) * 0.56f;
        host.mix.setFilter(1, norm);
        host.mix.setFilter(0, 0.5f);
    });
    add(8, "Comparacao: curva antiga, depois a nova", true, [&](double t) {
        host.mix.setFilter(1, 0.5f);
        if (t < 0.5) {
            host.legacyOn = true;
            host.beat(1, false, 0, 1, 0.5f);
        } else {
            host.legacyOn = false;
            const float local = static_cast<float>((t - 0.5) / 0.5);
            host.mix.setFilter(1, 0.46f - 0.16f * local);
            host.beat(1, true, 64, 1, 0.5f);
        }
    }, true);
    add(8, "Scene FX Echo UP, oito compassos", false, [&](double) {
        host.legacyOn = false;
        host.cross = 1.f;
        host.mix.setFilter(0, 0.5f);
        host.mix.setFilter(1, 0.5f);
        host.beat(1, false, 0, 1, 0.5f);
        host.mix.setRmx(true, 0, true, 1.f, 8.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 1, kBpm, 0.f);
    });
    add(2, "Solta a cena no downbeat do drop", false, [&](double) {
        host.mix.setRmx(true, 0, false, 0.f, 8.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 1, kBpm, 0.f);
    });
    add(8, "Drum build, 1/4 ate 1/32", true, [&](double t) {
        host.mix.setRmx(true, 0, false, 0.f, 8.f, 2, 0.25f, true, -1, 1, 1, 1, 1, 1, 1, false, 1, kBpm,
                        static_cast<float>(t));
    });
    add(2, "Drop, o roll para no downbeat", false, [&](double) {
        host.mix.setRmx(true, 0, false, 0.f, 8.f, 2, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 1, kBpm, 0.f);
    });
    add(4, "Eco na volta para o deck A", true, [&](double t) {
        host.mix.setRmx(false, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm, 0.f);
        host.cross = 1.f - 0.6f * static_cast<float>(t);
        host.mix.setFilter(0, 0.5f);
        host.mix.setFilter(1, 0.5f);
        host.beat(1, true, 76, 0, 0.5f);
    });
    add(4, "Eco desliga na transicao, cauda", true, [&](double t) {
        host.cross = 0.4f - 0.4f * static_cast<float>(t);
        host.beat(1, false, 76, 0, 0.5f);
    });
    add(4, "Isolador, grave sobe e volta", true, [&](double t) {
        host.cross = 0.f;
        host.beat(1, false, 0, 0, 0.5f);
        const float low = 1.f + 0.35f * std::sin(static_cast<float>(t) * 3.14159265f);
        host.iso(low, 1.f, 1.f, 1.f, 1.f, 1.f);
    });
    add(4, "Final seco", false, [&](double) {
        host.cross = 0.f;
        fxOff();
    });
}

void buildDrums(Host &host) {
    const double fast = 128.0 / 120.0;
    auto samplesFor = [&](int bars, double pitch) {
        return std::max(1, static_cast<int>(std::llround(static_cast<double>(bars) * static_cast<double>(kBar) / pitch)));
    };
    auto add = [&](int samples, const std::string &cue, std::function<void(double)> apply) {
        Seg seg;
        seg.samples = samples;
        seg.cue = cue;
        seg.apply = std::move(apply);
        host.segs.push_back(std::move(seg));
    };
    add(2 * kBar, "Sintese antiga, dois compassos, fora da grade", [&](double) {
        host.bed(1.0, true);
        host.lockDrums = false;
        host.armDrums(false, 0, 4, false, false, 0.f);
        host.armDrums(true, 0, 4, false, false, 0.9f);
    });
    const char *hits[] = {"Bumbo gravado", "Palma com cauda curta", "Caixa", "Chimbal fechado", "Chimbal aberto"};
    for (int voice = 0; voice < 5; ++voice) {
        add(32000, hits[voice], [&, voice](double) {
            host.bed(1.0, true);
            host.armDrums(false, 0, 4, true, false, 0.f);
            host.shotVoice = voice;
            host.shotPos = 0;
        });
    }
    add(4 * kBar, "Roll sozinho, quatro compassos, 120, bumbo e palma", [&](double) {
        host.bed(1.0, true);
        host.armDrums(false, 3, 4, true, false, 1.f);
        host.armDrums(true, 3, 4, true, false, 1.f);
    });
    add(2 * kBar, "So a palma, dois compassos, 120", [&](double) {
        host.bed(1.0, true);
        host.armDrums(false, 1, 4, true, false, 1.f);
        host.armDrums(true, 1, 4, true, false, 1.f);
    });
    add(2 * kBar, "So a caixa, dois compassos, 120", [&](double) {
        host.bed(1.0, true);
        host.armDrums(false, 2, 4, true, false, 1.f);
        host.armDrums(true, 2, 4, true, false, 1.f);
    });
    add(8 * kBar, "Na faixa, oito compassos, 120, sobe, palma por cima, meio tempo de silencio", [&](double) {
        host.bed(1.0, false);
        host.armDrums(false, 3, 8, true, true, 1.f);
        host.armDrums(true, 3, 8, true, true, 1.f);
    });
    add(4 * kBar, "Drop a 120", [&](double) {
        host.bed(1.0, false);
        host.armDrums(false, 3, 8, true, false, 1.f);
    });
    add(samplesFor(4, fast), "Roll sozinho, quatro compassos, 128", [&](double) {
        host.bed(fast, true);
        host.armDrums(false, 3, 4, true, false, 1.f);
        host.armDrums(true, 3, 4, true, false, 1.f);
    });
    add(samplesFor(8, fast), "Na faixa, oito compassos, 128, bumbo, sobe e cala meio tempo", [&](double) {
        host.bed(fast, false);
        host.armDrums(false, 0, 8, true, true, 1.f);
        host.armDrums(true, 0, 8, true, true, 1.f);
    });
    add(samplesFor(10, fast), "Drop a 128", [&](double) {
        host.bed(fast, false);
        host.armDrums(false, 0, 8, true, false, 1.f);
    });
}

}  // namespace

int main(int argc, char **argv) {
    if (argc < 6) {
        std::fprintf(stderr, "uso: demo_mix wrist.wav toodaze.wav vozes/ saida.wav cues.tsv\n");
        return 2;
    }
    Host host;
    if (!loadWav(argv[1], host.decks[0].left, host.decks[0].right) ||
        !loadWav(argv[2], host.decks[1].left, host.decks[1].right)) {
        std::fprintf(stderr, "wav inválido (precisa de PCM 16 bits, 48000 Hz, estéreo)\n");
        return 1;
    }
    // Grelha de kick a 120,00. wrist watch entra no drop; toodaze no primeiro kick.
    const double wristDownbeat = 32.0325;
    const double toodazeDownbeat = 0.0300;
    host.decks[0].position = wristDownbeat * kRate;
    host.decks[1].position = toodazeDownbeat * kRate;
    host.decks[0].bpm = kBpm;
    host.decks[1].bpm = kBpm;
    host.decks[0].gridOriginSec = wristDownbeat;
    host.decks[1].gridOriginSec = toodazeDownbeat;
    host.decks[0].pitch = 1.0;
    host.decks[1].pitch = 1.0;
    host.decks[0].playing = true;
    host.decks[1].playing = true;
    host.decks[0].volume = 1.f;
    host.decks[1].volume = 1.f;
    host.cross = 0.f;

    std::vector<Voice> voices(48);
    for (int index = 0; index < 48; ++index) {
        char path[512];
        std::snprintf(path, sizeof(path), "%s/%02d.wav", argv[3], index);
        std::vector<float> left;
        std::vector<float> right;
        if (!loadWav(path, left, right)) continue;
        float peak = 0.f;
        for (float sample : left) peak = std::max(peak, std::fabs(sample));
        const float gain = peak > 1e-4f ? 0.20f / peak : 0.f;
        voices[index].samples.resize(left.size());
        for (size_t sample = 0; sample < left.size(); ++sample) voices[index].samples[sample] = left[sample] * gain;
    }

    const bool v2 = argc >= 7 && std::strcmp(argv[6], "v2") == 0;
    const bool drums = argc >= 7 && std::strcmp(argv[6], "drums") == 0;
    if (drums) {
        const char *names[] = {"kick.flac", "clap.flac", "snare.flac", "hat-closed.flac", "hat-open.flac"};
        for (int voice = 0; voice < 5; ++voice) {
            const std::string path = std::string("android/app/src/main/assets/drums/") + names[voice];
            FILE *file = std::fopen(path.c_str(), "rb");
            if (file == nullptr) {
                std::fprintf(stderr, "sample ausente %s\n", path.c_str());
                continue;
            }
            std::fseek(file, 0, SEEK_END);
            const long bytes = std::ftell(file);
            std::fseek(file, 0, SEEK_SET);
            std::vector<uint8_t> data(static_cast<size_t>(std::max(0L, bytes)));
            if (!data.empty() && std::fread(data.data(), 1, data.size(), file) != data.size()) {
                std::fprintf(stderr, "leitura curta %s\n", names[voice]);
            }
            std::fclose(file);
            const bool ok = deckbridge::loadDrumFlac(data.data(), data.size(), voice);
            std::fprintf(stderr, "drum %s %s\n", names[voice], ok ? "ok" : "falhou");
        }
        buildDrums(host);
    } else if (v2) {
        buildV2(host);
    } else {
    auto add = [&](int bars, const std::string &cue, int voice, bool each, std::function<void(double)> apply) {
        Seg seg;
        seg.samples = bars * kBar;
        seg.cue = cue;
        if (voice >= 0) {
            char path[32];
            std::snprintf(path, sizeof(path), "%02d.wav", voice);
            seg.voicePath = path;
        }
        seg.each = each;
        seg.apply = std::move(apply);
        host.segs.push_back(std::move(seg));
    };

    auto neutral = [&]() {
        host.mix.setFilter(0, 0.5f);
        host.mix.setFilter(1, 0.5f);
        host.iso(1, 1, 1, 1, 1, 1);
        host.beat(0, false, 0, 1, 0.5f);
    };

    add(3, "Sincronia, dois decks a 120", 0, false, [&](double) {
        host.cross = 0.f;
        neutral();
    });

    add(4, "Troca: sync, grave e filtro", 1, true, [&](double t) {
        host.cross = static_cast<float>(t);
        const float bring = t < 0.30 ? static_cast<float>(t / 0.30) : 1.f;
        const float killLow = t < 0.30 ? 1.f : std::max(0.f, 1.f - static_cast<float>((t - 0.30) / 0.45));
        const float killMid = t < 0.65 ? 1.f : std::max(0.f, 1.f - static_cast<float>((t - 0.65) / 0.35));
        host.mix.setFilter(1, 0.07f + 0.43f * static_cast<float>(t));
        host.mix.setFilter(0, 0.5f);
        host.iso(killLow, killMid, 1.f, bring, 1.f, 1.f);
    });

    struct Fx {
        int id;
        int voice;
        const char *cue;
        float beats;
        int level;
        int bars;
    };
    const Fx effects[] = {
        {0, 2, "Beat FX, eco com corte", 0.5f, 100, 2},
        {1, 3, "Beat FX, eco em meio tempo", 0.5f, 100, 2},
        {1, 4, "Beat FX, eco em uma batida", 1.f, 100, 2},
        {2, 5, "Beat FX, pingue-pongue", 0.5f, 100, 2},
        {3, 6, "Beat FX, espiral", 0.5f, 100, 2},
        {4, 7, "Beat FX, reverb", 0.5f, 96, 2},
        {5, 8, "Beat FX, trans", 0.5f, 100, 2},
        {6, 9, "Beat FX, flanger", 0.5f, 100, 2},
        {7, 10, "Beat FX, phaser", 0.5f, 100, 2},
        {8, 11, "Beat FX, pitch", 0.5f, 110, 2},
        {9, 12, "Beat FX, slip roll", 0.5f, 100, 2},
        {10, 13, "Beat FX, roll", 0.25f, 100, 2},
        {11, 14, "Beat FX, hélice", 0.5f, 100, 2},
        {12, 15, "Beat FX, móbius", 0.5f, 100, 2},
        {13, 16, "Beat FX, filtro", 0.5f, 36, 2},
        {14, 17, "Beat FX, freio de vinil", 2.f, 100, 1},
    };
    for (const Fx &fx : effects) {
        add(fx.bars, fx.cue, fx.voice, fx.id == 13, [&, fx](double t) {
            host.cross = 1.f;
            host.mix.setFilter(0, 0.5f);
            host.mix.setFilter(1, 0.5f);
            host.iso(1, 1, 1, 1, 1, 1);
            const int level = fx.id == 13 && t >= 0.5 ? 108 : fx.level;
            host.beat(fx.id, true, level, 1, fx.beats);
        });
        add(1, "", -1, false, [&, fx](double) {
            host.beat(fx.id, false, fx.level, 1, fx.beats);
            if (fx.id == 14) host.regrid(1, toodazeDownbeat, static_cast<int64_t>(host.outL.size()));
        });
    }

    add(4, "Color FX, filtro", 18, true, [&](double t) {
        host.cross = 1.f;
        host.beat(13, false, 0, 1, 0.5f);
        float norm = 0.5f;
        if (t < 0.35) norm = 0.5f - static_cast<float>(t / 0.35) * 0.44f;
        else if (t < 0.55) norm = 0.06f + static_cast<float>((t - 0.35) / 0.20) * 0.44f;
        else norm = 0.5f + static_cast<float>((t - 0.55) / 0.45) * 0.44f;
        host.mix.setFilter(1, norm);
        host.mix.setFilter(0, 0.5f);
    });

    add(4, "Volta: sync, grave e filtro", 19, true, [&](double t) {
        host.cross = 1.f - static_cast<float>(t);
        host.mix.setFilter(1, 0.5f);
        host.mix.setFilter(0, 0.07f + 0.43f * static_cast<float>(t));
        const float bring = t < 0.30 ? static_cast<float>(t / 0.30) : 1.f;
        const float killLow = t < 0.30 ? 1.f : std::max(0.f, 1.f - static_cast<float>((t - 0.30) / 0.45));
        const float killMid = t < 0.65 ? 1.f : std::max(0.f, 1.f - static_cast<float>((t - 0.65) / 0.35));
        host.iso(bring, 1.f, 1.f, killLow, killMid, 1.f);
        host.beat(0, false, 0, 0, 0.5f);
    });

    const char *scenes[] = {
        "RMX, Scene FX, eco acima", "RMX, Scene FX, ruído", "RMX, Scene FX, espiral",
        "RMX, Scene FX, reverb acima", "RMX, Scene FX, reverb abaixo", "RMX, Scene FX, eco abaixo",
        "RMX, Scene FX, fita", "RMX, Scene FX, dub eco",
    };
    for (int scene = 0; scene < 8; ++scene) {
        add(1, scenes[scene], 20 + scene, false, [&, scene](double) {
            if (scene == 7) host.regrid(0, wristDownbeat, static_cast<int64_t>(host.outL.size()));
            host.cross = 0.f;
            host.mix.setFilter(0, 0.5f);
            host.mix.setFilter(1, 0.5f);
            host.mix.setRmx(true, scene, true, 1.f, 1.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm);
        });
        add(1, "", -1, false, [&, scene](double) {
            host.mix.setRmx(true, scene, false, 1.f, 1.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm);
        });
    }

    const char *pads[] = {"RMX, X-Pad, roll", "RMX, X-Pad, trans", "RMX, X-Pad, bateria", "RMX, X-Pad, agudo"};
    const float padBeats[] = {0.25f, 0.5f, 0.5f, 0.5f};
    for (int pad = 0; pad < 4; ++pad) {
        add(2, pads[pad], 28 + pad, false, [&, pad](double) {
            host.cross = 0.f;
            host.mix.setRmx(true, 0, false, 0.f, 1.f, pad, padBeats[pad], true, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm);
        });
        add(1, "", -1, false, [&](double) {
            host.mix.setRmx(true, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm);
        });
    }

    struct Band {
        int voice;
        const char *cue;
        float low, mid, high;
    };
    const Band bands[] = {
        {32, "RMX, isolador, corta o grave", 0.f, 1.f, 1.f},
        {33, "RMX, isolador, sobe o grave", 2.f, 1.f, 1.f},
        {34, "RMX, isolador, corta o médio", 1.f, 0.f, 1.f},
        {35, "RMX, isolador, sobe o médio", 1.f, 2.f, 1.f},
        {36, "RMX, isolador, corta o agudo", 1.f, 1.f, 0.f},
        {37, "RMX, isolador, sobe o agudo", 1.f, 1.f, 2.f},
    };
    for (const Band &band : bands) {
        add(1, band.cue, band.voice, false, [&, band](double) {
            host.cross = 0.f;
            host.mix.setRmx(true, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, band.low, band.mid, band.high, 1, 1, 1,
                            false, 0, kBpm);
        });
        add(1, "", -1, false, [&](double) {
            host.mix.setRmx(false, 0, false, 0.f, 1.f, 0, 0.25f, false, -1, 1, 1, 1, 1, 1, 1, false, 0, kBpm);
        });
    }

    add(2, "Loop de quatro batidas", 38, false, [&](double) {
        host.cross = 0.f;
        host.beat(0, false, 0, 0, 0.5f);
        const float start = static_cast<float>(host.decks[0].position / host.decks[0].sampleRate);
        host.setLoop(0, start, start + 2.f);
    });
    add(1, "", -1, false, [&](double) { host.setLoop(0, -1.f, -1.f); });

    add(1, "Salto de oito batidas", 39, false, [&](double) { host.jump(0, 8.0); });
    add(1, "Salto de volta", -1, false, [&](double) { host.jump(0, -8.0); });

    add(1, "Slip", 40, false, [&](double) {
        host.slip(0, true);
        host.jump(0, -4.0);
    });
    add(1, "Slip, solta", -1, false, [&](double) { host.slip(0, false); });
    add(2, "Fim, seco", -1, false, [&](double) {
        host.cross = 0.f;
        neutral();
    });
    }

    host.render(voices);

    if (!writeWav(argv[4], host.outL, host.outR)) return 1;
    FILE *cues = std::fopen(argv[5], "w");
    if (cues == nullptr) return 1;
    for (const auto &cue : host.cues) {
        const int total = static_cast<int>(std::lround(cue.first));
        const int minutes = total / 60;
        const int seconds = total % 60;
        std::fprintf(cues, "%02d:%02d\t%.3f\t%s\n", minutes, seconds, cue.first, cue.second.c_str());
    }
    std::fclose(cues);
    return 0;
}
