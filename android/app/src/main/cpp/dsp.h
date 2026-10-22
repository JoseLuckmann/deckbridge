#pragma once

// Original DSP for DeckBridge. Not a copy of any commercial remix or effect firmware.
// Names describe the gesture. Algorithms are short original approximations.

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace deckbridge {

constexpr double kTwoPi = 6.283185307179586;
constexpr int kFadeSamples = 48;

inline float sanitize(float x) {
    if (!std::isfinite(x)) return 0.f;
    if (x > 1.f) return 1.f;
    if (x < -1.f) return -1.f;
    return x;
}

inline double samplesPerBeat(double bpm, double sampleRate) {
    if (bpm < 1.0) bpm = 120.0;
    if (sampleRate < 1.0) sampleRate = 48000.0;
    return sampleRate * 60.0 / bpm;
}

inline double directedStep(double pitch, double rateMul, int sampleRate, int outputRate, bool reversed) {
    if (sampleRate <= 0 || outputRate <= 0) return 0.0;
    const double step = (std::max(0.0, pitch) * std::max(0.0, rateMul) * static_cast<double>(sampleRate)) /
                        static_cast<double>(outputRate);
    return reversed ? -step : step;
}

inline double wrapReverseLoop(double position, double loopStart, double loopEnd, bool reversed) {
    if (!(loopEnd > loopStart)) return position;
    const double length = loopEnd - loopStart;
    if (position >= loopEnd) {
        double over = std::fmod(position - loopEnd, length);
        if (over < 0.0) over += length;
        return loopStart + over;
    }
    if (reversed && position < loopStart) {
        double back = std::fmod(loopStart - position, length);
        if (back < 0.0) back += length;
        double wrapped = loopEnd - back;
        if (wrapped >= loopEnd) wrapped = loopStart;
        if (wrapped < loopStart) wrapped = loopStart;
        return wrapped;
    }
    return position;
}

inline double wrapLoopSample(double position, double loopStart, double loopEnd) {
    if (!(loopEnd > loopStart)) return position;
    const double length = loopEnd - loopStart;
    double over = std::fmod(position - loopStart, length);
    if (over < 0.0) over += length;
    return loopStart + over;
}

inline double beatJumpSample(double position, double beats, double bpm, double sampleRate, double loopStart,
                             double loopEnd, double frames, bool quantize) {
    const double beat = samplesPerBeat(bpm, sampleRate);
    double origin = position;
    if (quantize && beat > 0.0) origin = std::round(position / beat) * beat;
    double target = origin + beats * beat;
    if (loopEnd > loopStart) target = wrapLoopSample(target, loopStart, loopEnd);
    if (target < 0.0) target = 0.0;
    if (frames > 1.0 && target > frames - 1.0) target = frames - 1.0;
    return target;
}

struct LoopState {
    double start = -1.0;
    double end = -1.0;
    double memoryStart = -1.0;
    double memoryEnd = -1.0;
    bool active() const { return end > start; }
};

inline void scaleLoop(LoopState &loop, double factor) {
    if (!loop.active() || factor <= 0.0) return;
    double length = (loop.end - loop.start) * factor;
    if (length < 1.0) length = 1.0;
    loop.end = loop.start + length;
    loop.memoryStart = loop.start;
    loop.memoryEnd = loop.end;
}

inline bool toggleBeatLoop(LoopState &loop, double position, double beats, double bpm, double sampleRate) {
    const double length = std::max(1.0, beats * samplesPerBeat(bpm, sampleRate));
    if (loop.active() && std::abs((loop.end - loop.start) - length) < 2.0) {
        loop.memoryStart = loop.start;
        loop.memoryEnd = loop.end;
        loop.start = -1.0;
        loop.end = -1.0;
        return false;
    }
    loop.start = std::max(0.0, position);
    loop.end = loop.start + length;
    loop.memoryStart = loop.start;
    loop.memoryEnd = loop.end;
    return true;
}

inline float readInterp(const float *data, int frames, double position) {
    if (data == nullptr || frames <= 0) return 0.f;
    if (position < 0.0) position = 0.0;
    int index = static_cast<int>(position);
    if (index >= frames) index = frames - 1;
    if (index < 0) index = 0;
    const int next = std::min(index + 1, frames - 1);
    const float frac = static_cast<float>(position - std::floor(position));
    return data[index] * (1.f - frac) + data[next] * frac;
}

inline float readLooped(const float *data, int frames, double position, double loopStart, double loopEnd, int fade) {
    const float dry = readInterp(data, frames, position);
    if (!(loopEnd > loopStart) || fade <= 0) return dry;
    if (position < loopStart || position >= loopEnd) return dry;
    const double into = loopEnd - position;
    if (into >= static_cast<double>(fade) || into < 0.0) return dry;
    const float keep = static_cast<float>(into / static_cast<double>(fade));
    const float incoming = readInterp(data, frames, position - (loopEnd - loopStart));
    return dry * keep + incoming * (1.f - keep);
}

inline void renderRoll(const float *src, int frames, int slice, float *dst) {
    if (src == nullptr || dst == nullptr || frames <= 0) return;
    if (slice < 1) slice = 1;
    for (int index = 0; index < frames; ++index) dst[index] = src[index % slice];
}

struct SlipClock {
    double audible = 0.0;
    double hidden = -1.0;
    double origin = 0.0;
    double length = 0.0;
    bool slipping = false;

    void engage(double position, double slice, bool slip) {
        origin = position;
        length = std::max(1.0, slice);
        audible = position;
        hidden = slip ? position : -1.0;
        slipping = slip;
    }

    void advance(double step) {
        audible += step;
        if (length > 0.0 && audible >= origin + length) {
            double over = std::fmod(audible - origin, length);
            if (over < 0.0) over += length;
            audible = origin + over;
        }
        if (hidden >= 0.0) hidden += step;
    }

    void release() {
        if (hidden >= 0.0) audible = hidden;
        hidden = -1.0;
        length = 0.0;
        slipping = false;
    }
};

struct Biquad {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f, z1 = 0.f, z2 = 0.f;

    void reset() { z1 = z2 = 0.f; }

    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return std::isfinite(y) ? y : 0.f;
    }

    void setLowpass(float freq, float sampleRate, float q = 0.7071f) { cook(freq, sampleRate, q, true); }
    void setHighpass(float freq, float sampleRate, float q = 0.7071f) { cook(freq, sampleRate, q, false); }

    void setAllpass(float freq, float sampleRate, float q = 0.7f) {
        const float nyquist = std::max(50.f, sampleRate * 0.45f);
        freq = std::clamp(freq, 30.f, nyquist);
        const float w0 = static_cast<float>(kTwoPi) * freq / sampleRate;
        const float cw = std::cos(w0);
        const float sw = std::sin(w0);
        const float alpha = sw / (2.f * std::max(0.05f, q));
        const float a0 = 1.f + alpha;
        b0 = (1.f - alpha) / a0;
        b1 = (-2.f * cw) / a0;
        b2 = (1.f + alpha) / a0;
        a1 = b1;
        a2 = (1.f - alpha) / a0;
    }

private:
    void cook(float freq, float sampleRate, float q, bool low) {
        const float nyquist = std::max(80.f, sampleRate * 0.45f);
        freq = std::clamp(freq, 20.f, nyquist);
        const float w0 = static_cast<float>(kTwoPi) * freq / sampleRate;
        const float cw = std::cos(w0);
        const float sw = std::sin(w0);
        const float alpha = sw / (2.f * std::max(0.05f, q));
        const float a0 = 1.f + alpha;
        if (low) {
            b0 = ((1.f - cw) * 0.5f) / a0;
            b1 = (1.f - cw) / a0;
            b2 = b0;
        } else {
            b0 = ((1.f + cw) * 0.5f) / a0;
            b1 = (-(1.f + cw)) / a0;
            b2 = b0;
        }
        a1 = (-2.f * cw) / a0;
        a2 = (1.f - alpha) / a0;
    }
};

struct DelayLine {
    std::vector<float> data;
    int write = 0;

    void prepare(int size) {
        if (static_cast<int>(data.size()) != size) data.assign(size, 0.f);
    }

    void clear() {
        std::fill(data.begin(), data.end(), 0.f);
        write = 0;
    }

    float read(int delay) const {
        if (data.empty()) return 0.f;
        const int size = static_cast<int>(data.size());
        int index = write - delay;
        while (index < 0) index += size;
        index %= size;
        return data[static_cast<size_t>(index)];
    }

    void push(float sample) {
        if (data.empty()) return;
        data[static_cast<size_t>(write)] = sample;
        write = (write + 1) % static_cast<int>(data.size());
    }
};

struct PitchShift {
    static constexpr int kSize = 2048;
    float buffer[kSize]{};
    int write = 0;
    float readA = 0.f;
    float readB = static_cast<float>(kSize / 2);

    float process(float x, float ratio) {
        ratio = std::clamp(ratio, 0.25f, 4.f);
        buffer[write] = x;
        const float step = 1.f - ratio;
        auto tap = [&](float &cursor) {
            cursor += step;
            if (cursor >= kSize) cursor -= kSize;
            if (cursor < 0.f) cursor += kSize;
            const int index = static_cast<int>(cursor) % kSize;
            const int next = (index + 1) % kSize;
            const float frac = cursor - std::floor(cursor);
            return buffer[index] * (1.f - frac) + buffer[next] * frac;
        };
        const float a = tap(readA);
        const float b = tap(readB);
        const float window = std::abs(readA - readB) / static_cast<float>(kSize / 2);
        const float mix = std::clamp(window, 0.f, 1.f);
        write = (write + 1) % kSize;
        return sanitize(a * (1.f - mix) + b * mix);
    }
};

inline float noiseSample(uint32_t &state) {
    state ^= state << 13;
    state ^= state >> 17;
    state ^= state << 5;
    if (state == 0) state = 1;
    return (static_cast<int>(state & 0xFFFF) / 32768.f) - 1.f;
}

struct BeatInsert {
    int effect = 0;
    bool on = false;
    float level = 0.5f;
    float bpm = 120.f;
    float beats = 1.f;
    int sampleRate = 48000;
    int clock = 0;
    float lfo = 0.f;
    float tail = 0.f;
    bool brake = false;
    bool roll = false;
    bool slip = false;
    float brakePos = 0.f;
    DelayLine left;
    DelayLine right;
    Biquad hpL, hpR, lpL, lpR;
    Biquad ap[8];
    std::vector<float> combL[4];
    std::vector<float> combR[4];
    int combPos[4]{};

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        const int lengths[4] = {1557, 1617, 1491, 1422};
        for (int index = 0; index < 4; ++index) {
            const int n = std::max(32, lengths[index] * sampleRate / 48000);
            combL[index].assign(n, 0.f);
            combR[index].assign(n, 0.f);
            combPos[index] = 0;
        }
        for (auto &item : ap) item.reset();
        hpL.reset();
        hpR.reset();
        lpL.reset();
        lpR.reset();
    }

    void clear() {
        left.clear();
        right.clear();
        for (int index = 0; index < 4; ++index) {
            std::fill(combL[index].begin(), combL[index].end(), 0.f);
            std::fill(combR[index].begin(), combR[index].end(), 0.f);
        }
        clock = 0;
        lfo = 0.f;
        tail = 0.f;
        brakePos = 0.f;
    }

    void configure(int nextEffect, bool enabled, float nextLevel, float nextBpm, float nextBeats, int rate) {
        const bool changed = nextEffect != effect || std::abs(nextBeats - beats) > 0.001f;
        effect = nextEffect;
        on = enabled;
        level = std::clamp(nextLevel, 0.f, 1.f);
        bpm = nextBpm > 1.f ? nextBpm : 120.f;
        beats = nextBeats > 0.f ? nextBeats : 1.f;
        sampleRate = rate > 0 ? rate : 48000;
        roll = on && (effect == 9 || effect == 10);
        slip = on && effect == 9;
        brake = on && effect == 14;
        if (!on && (effect == 1 || effect == 0 || effect == 2 || effect == 4 || effect == 11 || effect == 12)) tail = 1.f;
        if (changed) clear();
        if (left.data.empty()) prepare();
        const float wet = std::max(level, tail);
        hpL.setHighpass(180.f + wet * 2400.f, static_cast<float>(sampleRate));
        hpR.setHighpass(180.f + wet * 2400.f, static_cast<float>(sampleRate));
        lpL.setLowpass(400.f + (1.f - level) * 6000.f, static_cast<float>(sampleRate));
        lpR.setLowpass(400.f + (1.f - level) * 6000.f, static_cast<float>(sampleRate));
    }

    int delaySamples() const {
        const double beat = samplesPerBeat(bpm, sampleRate);
        return std::clamp(static_cast<int>(beat * beats), 1, 95999);
    }

    bool transportEffect() const { return effect == 9 || effect == 10 || effect == 14; }

    float brakeMul() {
        if (!brake) return 1.f;
        const float span = static_cast<float>(std::max(1.0, samplesPerBeat(bpm, sampleRate) * beats));
        brakePos = std::min(1.f, brakePos + 1.f / span);
        return 1.f - brakePos;
    }

    void process(float &inL, float &inR) {
        if (left.data.empty()) prepare();
        const float dryL = inL;
        const float dryR = inR;
        if (transportEffect()) {
            inL = sanitize(dryL);
            inR = sanitize(dryR);
            clock += 1;
            return;
        }
        const float amount = on ? level : tail;
        if (amount <= 0.0008f) {
            tail = 0.f;
            inL = sanitize(dryL);
            inR = sanitize(dryR);
            return;
        }
        float wetL = dryL;
        float wetR = dryR;
        const int delay = delaySamples();
        const float beat = static_cast<float>(samplesPerBeat(bpm, sampleRate));
        lfo += static_cast<float>(kTwoPi) / std::max(32.f, beat * 2.f);
        if (lfo > static_cast<float>(kTwoPi)) lfo -= static_cast<float>(kTwoPi);
        switch (effect) {
            case 0: {
                const float fb = 0.18f + 0.62f * amount;
                const float echoL = hpL.process(left.read(delay));
                const float echoR = hpR.process(right.read(delay));
                left.push(dryL + echoL * fb);
                right.push(dryR + echoR * fb);
                wetL = dryL * (1.f - amount) + echoL * amount;
                wetR = dryR * (1.f - amount) + echoR * amount;
                break;
            }
            case 1: {
                const float fb = 0.12f + 0.55f * amount;
                const float echoL = left.read(delay);
                const float echoR = right.read(delay);
                left.push(dryL + echoL * fb);
                right.push(dryR + echoR * fb);
                wetL = dryL * (1.f - amount * 0.65f) + echoL * amount;
                wetR = dryR * (1.f - amount * 0.65f) + echoR * amount;
                break;
            }
            case 2: {
                const int half = std::max(1, delay / 2);
                const float fb = 0.2f + 0.5f * amount;
                const float echoL = left.read(delay);
                const float echoR = right.read(half);
                left.push(dryR + echoR * fb);
                right.push(dryL + echoL * fb);
                wetL = dryL * (1.f - amount) + echoL * amount;
                wetR = dryR * (1.f - amount) + echoR * amount;
                break;
            }
            case 3: {
                const int spiral = std::max(8, static_cast<int>(delay * (1.f - 0.75f * (0.5f + 0.5f * std::sin(lfo)))));
                const float fb = 0.35f + 0.45f * amount;
                const float echoL = left.read(spiral);
                const float echoR = right.read(spiral);
                left.push(dryL + echoL * fb);
                right.push(dryR + echoR * fb);
                wetL = dryL * (1.f - amount) + echoL * amount;
                wetR = dryR * (1.f - amount) + echoR * amount;
                break;
            }
            case 4:
                wetL = reverb(dryL, combL, 0);
                wetR = reverb(dryR, combR, 1);
                wetL = dryL * (1.f - amount) + wetL * amount;
                wetR = dryR * (1.f - amount) + wetR * amount;
                break;
            case 5: {
                const int period = std::max(8, delay);
                const float duty = 0.18f + 0.62f * amount;
                const bool open = (clock % period) < static_cast<int>(period * duty);
                const float gate = open ? 1.f : 0.f;
                wetL = dryL * gate;
                wetR = dryR * gate;
                break;
            }
            case 6: {
                const int flanger = 180 + static_cast<int>(140.f * (0.5f + 0.5f * std::sin(lfo)));
                const float fb = 0.35f * amount;
                const float echoL = left.read(flanger);
                const float echoR = right.read(flanger + 7);
                left.push(dryL + echoL * fb);
                right.push(dryR + echoR * fb);
                wetL = dryL * (1.f - amount * 0.7f) + echoL * amount;
                wetR = dryR * (1.f - amount * 0.7f) + echoR * amount;
                break;
            }
            case 7: {
                float x = dryL;
                float y = dryR;
                const float freq = 400.f + 1800.f * (0.5f + 0.5f * std::sin(lfo));
                for (int index = 0; index < 4; ++index) {
                    ap[index].setAllpass(freq * (1.f + 0.15f * index), static_cast<float>(sampleRate), 0.6f);
                    x = ap[index].process(x);
                    ap[index + 4].setAllpass(freq * (1.f + 0.15f * index), static_cast<float>(sampleRate), 0.6f);
                    y = ap[index + 4].process(y);
                }
                wetL = dryL * (1.f - amount) + x * amount;
                wetR = dryR * (1.f - amount) + y * amount;
                break;
            }
            case 8: {
                const float semis = (amount * 2.f - 1.f) * 7.f;
                const float ratio = std::pow(2.f, semis / 12.f);
                wetL = dryL * 0.45f + pitchOne(left, dryL, ratio) * 0.85f;
                wetR = dryR * 0.45f + pitchOne(right, dryR, ratio) * 0.85f;
                break;
            }
            case 11: {
                const int helix = std::max(16, static_cast<int>(delay * (0.55f + 0.45f * (0.5f + 0.5f * std::sin(lfo * 0.5f)))));
                const float fb = 0.4f + 0.4f * amount;
                const float echoL = left.read(helix);
                const float echoR = right.read(std::max(8, helix / 2));
                left.push(dryR + echoR * fb);
                right.push(dryL + echoL * fb);
                wetL = dryL * (1.f - amount) + echoL * amount;
                wetR = dryR * (1.f - amount) + echoR * amount;
                break;
            }
            case 12: {
                const int a = delay;
                const int b = std::max(8, (delay * 3) / 2);
                const float fb = 0.28f + 0.5f * amount;
                const float echoL = left.read(a);
                const float echoR = right.read(b);
                left.push(dryL * 0.6f + echoR * fb);
                right.push(dryR * 0.6f + echoL * fb);
                wetL = dryL * (1.f - amount) + echoL * amount;
                wetR = dryR * (1.f - amount) + echoR * amount;
                break;
            }
            case 13: {
                const float cutoff = level < 0.5f ? (80.f + level * 2.f * 1800.f) : (200.f + (level - 0.5f) * 2.f * 8000.f);
                if (level < 0.48f) {
                    lpL.setLowpass(cutoff, static_cast<float>(sampleRate));
                    lpR.setLowpass(cutoff, static_cast<float>(sampleRate));
                    wetL = lpL.process(dryL);
                    wetR = lpR.process(dryR);
                } else if (level > 0.52f) {
                    hpL.setHighpass(cutoff, static_cast<float>(sampleRate));
                    hpR.setHighpass(cutoff, static_cast<float>(sampleRate));
                    wetL = hpL.process(dryL);
                    wetR = hpR.process(dryR);
                } else {
                    wetL = dryL;
                    wetR = dryR;
                }
                break;
            }
            default:
                break;
        }
        if (!on) tail *= 0.9995f;
        inL = sanitize(wetL);
        inR = sanitize(wetR);
        clock += 1;
    }

private:
    float reverb(float in, std::vector<float> *combs, int channel) {
        float sum = 0.f;
        const float fb = 0.45f + 0.4f * level;
        for (int index = 0; index < 4; ++index) {
            auto &buf = combs[index];
            if (buf.empty()) continue;
            int &pos = combPos[index];
            const float y = buf[static_cast<size_t>(pos)];
            buf[static_cast<size_t>(pos)] = in + y * fb;
            pos = (pos + 1) % static_cast<int>(buf.size());
            sum += y;
        }
        sum *= 0.25f;
        Biquad &first = ap[channel];
        Biquad &second = ap[channel + 2];
        first.setAllpass(900.f, static_cast<float>(sampleRate), 0.5f);
        second.setAllpass(1400.f, static_cast<float>(sampleRate), 0.5f);
        return second.process(first.process(sum));
    }

    float pitchOne(DelayLine &line, float in, float ratio) {
        const int base = 1024;
        const int wobble = static_cast<int>(180.f * std::sin(lfo * ratio));
        const int delay = std::clamp(base + wobble, 64, 4000);
        const float y = line.read(delay);
        line.push(in);
        return y;
    }
};

struct SceneFx {
    int scene = 0;
    float bars = 4.f;
    float bpm = 120.f;
    float intensity = 0.f;
    float ceiling = 1.f;
    bool holding = false;
    bool releasing = false;
    int sampleRate = 48000;
    DelayLine left;
    DelayLine right;
    Biquad hpL, hpR, lpL, lpR;
    uint32_t noise = 0x1234567u;
    std::vector<float> comb[2];
    int combPos[2]{};

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        comb[0].assign(std::max(32, 1600 * sampleRate / 48000), 0.f);
        comb[1].assign(std::max(32, 1900 * sampleRate / 48000), 0.f);
        combPos[0] = combPos[1] = 0;
    }

    void setHold(bool down) {
        if (down) {
            holding = true;
            releasing = false;
        } else if (holding || intensity > 0.f) {
            holding = false;
            releasing = intensity > 0.f;
        }
    }

    void setCeiling(float value) { ceiling = std::clamp(value, 0.f, 1.f); }

    float pitchMul() const {
        if (scene == 6) return std::clamp(1.f - intensity, 0.f, 1.f);
        return 1.f;
    }

    void process(float &inL, float &inR) {
        if (left.data.empty()) prepare();
        const float dryL = inL;
        const float dryR = inR;
        const float beat = static_cast<float>(samplesPerBeat(bpm, sampleRate));
        const float ramp = std::max(1.f, bars * 4.f * beat);
        const float tail = std::max(1.f, 4.f * beat);
        if (holding) intensity = std::min(ceiling, intensity + 1.f / ramp);
        else if (releasing) {
            intensity = std::max(0.f, intensity - 1.f / tail);
            if (intensity <= 0.f) releasing = false;
        }
        if (intensity <= 0.0008f && !holding) {
            inL = sanitize(dryL);
            inR = sanitize(dryR);
            return;
        }
        const int delay = std::clamp(static_cast<int>(beat), 1, 95999);
        const float t = intensity;
        float wetL = dryL;
        float wetR = dryR;
        switch (scene) {
            case 0: {
                hpL.setHighpass(100.f + t * 7000.f, static_cast<float>(sampleRate));
                hpR.setHighpass(100.f + t * 7000.f, static_cast<float>(sampleRate));
                const float fb = 0.2f + 0.7f * t;
                const float eL = hpL.process(left.read(delay));
                const float eR = hpR.process(right.read(delay));
                left.push(dryL + eL * fb);
                right.push(dryR + eR * fb);
                wetL = dryL * (1.f - t * 0.5f) + eL * t;
                wetR = dryR * (1.f - t * 0.5f) + eR * t;
                break;
            }
            case 1: {
                const float n = noiseSample(noise);
                hpL.setHighpass(200.f + t * 9000.f, static_cast<float>(sampleRate));
                const float colored = hpL.process(n) * t * 0.35f;
                wetL = dryL * (1.f - t * 0.4f) + colored;
                wetR = dryR * (1.f - t * 0.4f) + colored;
                break;
            }
            case 2: {
                const int spiral = std::max(8, static_cast<int>(delay * (1.f - 0.85f * t)));
                const float fb = 0.3f + 0.6f * t;
                const float eL = left.read(spiral);
                const float eR = right.read(spiral);
                left.push(dryL + eL * fb);
                right.push(dryR + eR * fb);
                wetL = dryL * (1.f - t) + eL * t;
                wetR = dryR * (1.f - t) + eR * t;
                break;
            }
            case 3: {
                hpL.setHighpass(80.f + t * 5000.f, static_cast<float>(sampleRate));
                hpR.setHighpass(80.f + t * 5000.f, static_cast<float>(sampleRate));
                wetL = hpL.process(dryL) * (1.f - t * 0.3f) + verb(dryL, 0) * t;
                wetR = hpR.process(dryR) * (1.f - t * 0.3f) + verb(dryR, 1) * t;
                break;
            }
            case 4: {
                lpL.setLowpass(8000.f - t * 7400.f, static_cast<float>(sampleRate));
                lpR.setLowpass(8000.f - t * 7400.f, static_cast<float>(sampleRate));
                wetL = lpL.process(dryL) * (1.f - t * 0.45f) + verb(dryL, 0) * (0.2f + t);
                wetR = lpR.process(dryR) * (1.f - t * 0.45f) + verb(dryR, 1) * (0.2f + t);
                break;
            }
            case 5: {
                lpL.setLowpass(10000.f - t * 9600.f, static_cast<float>(sampleRate));
                lpR.setLowpass(10000.f - t * 9600.f, static_cast<float>(sampleRate));
                const float fb = 0.15f + 0.7f * t;
                const float eL = lpL.process(left.read(delay));
                const float eR = lpR.process(right.read(delay));
                left.push(dryL + eL * fb);
                right.push(dryR + eR * fb);
                wetL = dryL * (1.f - t * 0.7f) + eL * t;
                wetR = dryR * (1.f - t * 0.7f) + eR * t;
                break;
            }
            case 6: {
                lpL.setLowpass(6000.f - t * 5200.f, static_cast<float>(sampleRate));
                lpR.setLowpass(6000.f - t * 5200.f, static_cast<float>(sampleRate));
                wetL = lpL.process(dryL);
                wetR = lpR.process(dryR);
                break;
            }
            default: {
                const int dub = std::max(8, (delay * 3) / 4);
                const float fb = 0.35f + 0.55f * t;
                const float eL = left.read(dub);
                const float eR = right.read(dub);
                left.push(lpL.process(dryL + eL * fb));
                right.push(lpR.process(dryR + eR * fb));
                lpL.setLowpass(5000.f - t * 3000.f, static_cast<float>(sampleRate));
                lpR.setLowpass(5000.f - t * 3000.f, static_cast<float>(sampleRate));
                wetL = dryL * (1.f - t * 0.4f) + eL * t;
                wetR = dryR * (1.f - t * 0.4f) + eR * t;
                break;
            }
        }
        inL = sanitize(wetL);
        inR = sanitize(wetR);
    }

private:
    float verb(float in, int channel) {
        auto &buf = comb[channel];
        if (buf.empty()) return in;
        int &pos = combPos[channel];
        const float y = buf[static_cast<size_t>(pos)];
        buf[static_cast<size_t>(pos)] = in + y * (0.4f + 0.4f * intensity);
        pos = (pos + 1) % static_cast<int>(buf.size());
        return y;
    }
};

struct XPad {
    int mode = 0;
    float beats = 0.25f;
    bool requested = false;
    bool engaged = false;
    float bpm = 120.f;
    int sampleRate = 48000;
    int clock = 0;
    int voice = -1;
    int env = 0;
    float phase = 0.f;
    Biquad hp;
    uint32_t noise = 0x51u;

    void set(int nextMode, float nextBeats, bool on, float nextBpm, int rate) {
        mode = nextMode;
        beats = nextBeats > 0.f ? nextBeats : 0.25f;
        requested = on;
        bpm = nextBpm > 1.f ? nextBpm : 120.f;
        sampleRate = rate > 0 ? rate : 48000;
        if (!on) {
            engaged = false;
            env = 0;
        }
    }

    int sliceSamples() const {
        return std::max(1, static_cast<int>(samplesPerBeat(bpm, sampleRate) * beats));
    }

    bool rollEngaged() const { return engaged && mode == 0; }

    void process(float &inL, float &inR) {
        const int slice = sliceSamples();
        if (requested && !engaged && (clock % slice) == 0) engaged = true;
        if (!requested) engaged = false;
        if (!engaged) {
            clock += 1;
            inL = sanitize(inL);
            inR = sanitize(inR);
            return;
        }
        if ((clock % slice) == 0) {
            env = sampleRate / 12;
            voice = (voice + 1) & 3;
            phase = 0.f;
        }
        if (mode == 1) {
            const bool open = (clock % slice) < slice / 2;
            const float g = open ? 1.f : 0.f;
            inL = sanitize(inL * g);
            inR = sanitize(inR * g);
        } else if (mode == 2) {
            const float hit = drum();
            inL = sanitize(inL * 0.85f + hit);
            inR = sanitize(inR * 0.85f + hit);
        } else if (mode == 3) {
            const bool open = (clock % slice) < (slice * 2) / 3;
            hp.setHighpass(open ? 1800.f : 8000.f, static_cast<float>(sampleRate));
            inL = sanitize(hp.process(inL));
            inR = sanitize(inL);
        }
        clock += 1;
        if (env > 0) env -= 1;
    }

    float drum() {
        if (env <= 0) return 0.f;
        const float t = 1.f - (static_cast<float>(env) / static_cast<float>(sampleRate / 12));
        const float decay = std::exp(-6.f * t);
        const int which = voice;
        float y = 0.f;
        if (which == 0) {
            phase += static_cast<float>(kTwoPi) * (140.f - 80.f * t) / static_cast<float>(sampleRate);
            y = std::sin(phase) * decay + noiseSample(noise) * decay * decay * 0.25f;
        } else if (which == 1) {
            y = noiseSample(noise) * decay * 0.7f + std::sin(phase) * decay * 0.2f;
            phase += static_cast<float>(kTwoPi) * 180.f / static_cast<float>(sampleRate);
        } else if (which == 2) {
            const float burst = (t < 0.15f || (t > 0.22f && t < 0.32f) || (t > 0.4f && t < 0.48f)) ? 1.f : 0.2f;
            y = noiseSample(noise) * decay * burst * 0.55f;
        } else {
            hp.setHighpass(6000.f, static_cast<float>(sampleRate));
            y = hp.process(noiseSample(noise)) * std::exp(-18.f * t) * 0.45f;
        }
        return y;
    }
};

struct Isolator {
    float low = 1.f;
    float mid = 1.f;
    float high = 1.f;
    float smL = 1.f, smM = 1.f, smH = 1.f, smGate = 1.f;
    bool gate = false;
    int sampleRate = 48000;
    int clock = 0;
    bool ready = false;
    Biquad lp1, lp2, hp1, hp2, bpHp1, bpHp2, bpLp1, bpLp2;
    Biquad rlp1, rlp2, rhp1, rhp2, rbpHp1, rbpHp2, rbpLp1, rbpLp2;

    void prepare(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        const float sr = static_cast<float>(sampleRate);
        lp1.setLowpass(200.f, sr);
        lp2.setLowpass(200.f, sr);
        hp1.setHighpass(2000.f, sr);
        hp2.setHighpass(2000.f, sr);
        bpHp1.setHighpass(200.f, sr);
        bpHp2.setHighpass(200.f, sr);
        bpLp1.setLowpass(2000.f, sr);
        bpLp2.setLowpass(2000.f, sr);
        rlp1.setLowpass(200.f, sr);
        rlp2.setLowpass(200.f, sr);
        rhp1.setHighpass(2000.f, sr);
        rhp2.setHighpass(2000.f, sr);
        rbpHp1.setHighpass(200.f, sr);
        rbpHp2.setHighpass(200.f, sr);
        rbpLp1.setLowpass(2000.f, sr);
        rbpLp2.setLowpass(2000.f, sr);
        ready = true;
    }

    void set(float nextLow, float nextMid, float nextHigh, bool nextGate) {
        low = std::clamp(nextLow, 0.f, 2.f);
        mid = std::clamp(nextMid, 0.f, 2.f);
        high = std::clamp(nextHigh, 0.f, 2.f);
        gate = nextGate;
    }

    bool unity() const {
        return !gate && std::abs(low - 1.f) < 0.01f && std::abs(mid - 1.f) < 0.01f && std::abs(high - 1.f) < 0.01f;
    }

    void process(float &inL, float &inR) {
        if (!ready) prepare(sampleRate);
        const float coef = 1.f - std::exp(-1.f / (0.02f * static_cast<float>(sampleRate)));
        smL += (low - smL) * coef;
        smM += (mid - smM) * coef;
        smH += (high - smH) * coef;
        if (unity() && std::abs(smL - 1.f) < 0.02f && std::abs(smM - 1.f) < 0.02f && std::abs(smH - 1.f) < 0.02f) {
            clock += 1;
            return;
        }
        const float lLow = lp2.process(lp1.process(inL));
        const float lHigh = hp2.process(hp1.process(inL));
        const float lMid = bpLp2.process(bpLp1.process(bpHp2.process(bpHp1.process(inL))));
        const float rLow = rlp2.process(rlp1.process(inR));
        const float rHigh = rhp2.process(rhp1.process(inR));
        const float rMid = rbpLp2.process(rbpLp1.process(rbpHp2.process(rbpHp1.process(inR))));
        float gateGain = 1.f;
        if (gate) {
            const int slice = std::max(1, static_cast<int>(samplesPerBeat(120.0, sampleRate) / 2.0));
            const bool open = (clock % slice) < slice / 2;
            gateGain = open ? 1.f : 0.f;
        }
        smGate += (gateGain - smGate) * coef;
        const float gL = smL * smGate;
        const float gM = smM * smGate;
        const float gH = smH * smGate;
        inL = sanitize(lLow * gL + lMid * gM + lHigh * gH);
        inR = sanitize(rLow * gL + rMid * gM + rHigh * gH);
        clock += 1;
    }
};

struct ReleaseFx {
    int kind = -1;
    float pos = 1.f;
    float bpm = 120.f;
    int sampleRate = 48000;
    DelayLine left;
    DelayLine right;

    void trigger(int next, float nextBpm, int rate) {
        kind = next;
        pos = 0.f;
        bpm = nextBpm > 1.f ? nextBpm : 120.f;
        sampleRate = rate > 0 ? rate : 48000;
        left.prepare(96000);
        right.prepare(96000);
        left.clear();
        right.clear();
    }

    bool active() const {
        if (kind < 0) return false;
        if (kind == 0) return true;
        return pos < 1.f;
    }

    float pitchMul() const {
        if (kind == 0) return std::max(0.f, 1.f - pos);
        if (kind == 2) return std::max(-1.15f, 1.f - pos * 2.3f);
        return 1.f;
    }

    void process(float &inL, float &inR) {
        if (kind < 0) return;
        const float span = static_cast<float>(std::max(1.0, samplesPerBeat(bpm, sampleRate) * (kind == 2 ? 2.0 : 4.0)));
        pos = std::min(1.f, pos + 1.f / span);
        if (kind == 1) {
            const int delay = std::clamp(static_cast<int>(samplesPerBeat(bpm, sampleRate)), 1, 95999);
            const float dry = 1.f - pos;
            const float fb = 0.55f * (1.f - pos);
            const float eL = left.read(delay);
            const float eR = right.read(delay);
            left.push(inL + eL * fb);
            right.push(inR + eR * fb);
            inL = sanitize(inL * dry + eL * (1.f - dry));
            inR = sanitize(inR * dry + eR * (1.f - dry));
        }
        if (pos >= 1.f && kind != 0) kind = -1;
    }
};

struct ColorFilter {
    float norm = 0.5f;
    int sampleRate = 48000;
    Biquad left;
    Biquad right;

    void set(float value, int rate) {
        norm = std::clamp(value, 0.f, 1.f);
        sampleRate = rate > 0 ? rate : 48000;
    }

    void process(float &inL, float &inR) {
        if (std::abs(norm - 0.5f) < 0.02f) return;
        const float sr = static_cast<float>(sampleRate);
        if (norm < 0.5f) {
            const float cutoff = 120.f + (norm / 0.5f) * 2000.f;
            left.setLowpass(cutoff, sr);
            right.setLowpass(cutoff, sr);
        } else {
            const float cutoff = 180.f + ((norm - 0.5f) / 0.5f) * 6000.f;
            left.setHighpass(cutoff, sr);
            right.setHighpass(cutoff, sr);
        }
        inL = sanitize(left.process(inL));
        inR = sanitize(right.process(inR));
    }
};

struct MixerFx {
    BeatInsert beat;
    SceneFx scene;
    XPad xpad;
    Isolator iso[2];
    ReleaseFx release;
    ColorFilter color[2];
    bool rmx = false;
    int channel = 0;
    int sampleRate = 48000;
    bool rollDeck[2]{};
    bool slipDeck[2]{};
    double rollOrigin[2]{-1.0, -1.0};
    double rollBeats = 0.25;

    void setSampleRate(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        iso[0].prepare(sampleRate);
        iso[1].prepare(sampleRate);
    }

    void setBeat(int effect, bool on, float level, int nextChannel, float bpm, float beats) {
        channel = std::clamp(nextChannel, 0, 2);
        beat.configure(effect, on && !rmx, level, bpm, beats, sampleRate);
        const bool transport = on && !rmx && (effect == 9 || effect == 10 || effect == 14);
        rollBeats = beats;
        for (int deck = 0; deck < 2; ++deck) {
            const bool hit = transport && (channel == 2 || channel == deck) && (effect == 9 || effect == 10);
            rollDeck[deck] = hit;
            slipDeck[deck] = hit && effect == 9;
            if (!hit) rollOrigin[deck] = -1.0;
        }
    }

    void setRmx(bool armed, int sceneId, bool holding, float ceiling, float bars, int xMode, float xBeats, bool xOn,
                int releaseKind, float low0, float mid0, float high0, float low1, float mid1, float high1, bool gate,
                int nextChannel, float bpm) {
        const bool was = rmx;
        rmx = armed;
        if (armed) beat.on = false;
        channel = std::clamp(nextChannel, 0, 2);
        scene.scene = std::clamp(sceneId, 0, 7);
        scene.bars = std::clamp(bars, 1.f, 16.f);
        scene.bpm = bpm;
        scene.sampleRate = sampleRate;
        if (holding) {
            scene.setCeiling(1.f);
            if (!scene.holding) scene.setHold(true);
        } else if (scene.holding) {
            scene.setHold(false);
        } else if (!scene.releasing) {
            scene.setCeiling(ceiling);
            scene.intensity = ceiling;
        }
        xpad.set(xMode, xBeats, armed && xOn, bpm, sampleRate);
        iso[0].set(low0, mid0, high0, gate);
        iso[1].set(low1, mid1, high1, gate);
        if (releaseKind < 0) release.kind = -1;
        else if (release.kind != releaseKind) release.trigger(releaseKind, bpm, sampleRate);
        if (!armed && was) scene.setHold(false);
        for (int deck = 0; deck < 2; ++deck) {
            const bool hit = armed && xOn && xMode == 0 && (channel == 2 || channel == deck);
            rollDeck[deck] = hit;
            slipDeck[deck] = false;
            if (hit) rollBeats = xBeats;
        }
    }

    void beginSample() {
        if (!beat.brake) return;
        const float span = static_cast<float>(std::max(1.0, samplesPerBeat(beat.bpm, sampleRate) * beat.beats));
        beat.brakePos = std::min(1.f, beat.brakePos + 1.f / span);
    }

    float brakeValue() const { return beat.brake ? (1.f - beat.brakePos) : 1.f; }

    void setFilter(int deck, float norm) {
        if (deck < 0 || deck > 1) return;
        color[deck].set(norm, sampleRate);
    }

    double rateFor(int deck) {
        double mul = 1.0;
        if (beat.brake && (channel == 2 || channel == deck)) mul *= brakeValue();
        if (rmx && scene.scene == 6 && (channel == 2 || channel == deck)) mul *= scene.pitchMul();
        if (release.active() && (release.kind == 0 || release.kind == 2) && (channel == 2 || channel == deck)) {
            mul *= release.pitchMul();
        }
        return mul;
    }

    void processDeck(int deck, float &left, float &right) {
        if (deck < 0 || deck > 1) return;
        color[deck].process(left, right);
        iso[deck].process(left, right);
        if (channel == deck) applyInsert(left, right);
    }

    void processMaster(float &left, float &right) {
        if (channel == 2) applyInsert(left, right);
        if (release.active() && release.kind == 1 && channel == 2) release.process(left, right);
    }

private:
    void applyInsert(float &left, float &right) {
        if (rmx) {
            scene.process(left, right);
            xpad.process(left, right);
            if (release.active() && release.kind == 1 && channel != 2) release.process(left, right);
        } else {
            beat.process(left, right);
        }
    }
};

inline bool finiteBuffer(const std::vector<float> &data) {
    return std::all_of(data.begin(), data.end(), [](float sample) { return std::isfinite(sample) && sample <= 1.f && sample >= -1.f; });
}

inline float diffRms(const std::vector<float> &a, const std::vector<float> &b) {
    const int n = std::min(a.size(), b.size());
    if (n <= 0) return 0.f;
    double sum = 0.0;
    for (int index = 0; index < n; ++index) {
        const double d = static_cast<double>(a[index]) - b[index];
        sum += d * d;
    }
    return static_cast<float>(std::sqrt(sum / n));
}

}  // namespace deckbridge
