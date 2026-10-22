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

inline void flushDenorm(float &value) {
    if (std::fabs(value) < 1e-15f || !std::isfinite(value)) value = 0.f;
}

struct ParamSmooth {
    float value = 0.f;
    float coef = 1.f;
    bool primed = false;

    void setMs(float ms, int sampleRate) {
        const float samples = std::max(1.f, ms * 0.001f * static_cast<float>(std::max(1, sampleRate)));
        coef = 1.f - std::exp(-1.f / samples);
    }

    float next(float target, bool snap) {
        if (!primed || snap) {
            value = target;
            primed = true;
            return value;
        }
        value += (target - value) * coef;
        if (std::fabs(target - value) < 1e-6f) value = target;
        flushDenorm(value);
        return value;
    }
};

// Filtro TPT/SVF (Zavalishin). Implementação própria, Q musical, sem brickwall.
struct Svf {
    float ic1 = 0.f;
    float ic2 = 0.f;
    float g = 0.f;
    float k = 1.f;
    float a1 = 1.f;
    float a2 = 0.f;
    float a3 = 0.f;
    int mode = 0;

    void reset() { ic1 = ic2 = 0.f; }

    void set(float freq, float sampleRate, float q, int nextMode) {
        const float sr = std::max(8000.f, sampleRate);
        freq = std::clamp(freq, 25.f, sr * 0.45f);
        q = std::clamp(q, 0.55f, 2.2f);
        g = std::tan(3.14159265f * freq / sr);
        k = 1.f / q;
        a1 = 1.f / (1.f + g * (g + k));
        a2 = g * a1;
        a3 = g * a2;
        mode = nextMode;
    }

    float process(float input) {
        if (!std::isfinite(input)) input = 0.f;
        const float v3 = input - ic2;
        const float v1 = a1 * ic1 + a2 * v3;
        const float v2 = ic2 + a2 * ic1 + a3 * v3;
        ic1 = 2.f * v1 - ic1;
        ic2 = 2.f * v2 - ic2;
        flushDenorm(ic1);
        flushDenorm(ic2);
        const float low = v2;
        const float band = v1;
        const float high = input - k * v1 - v2;
        const float output = mode == 1 ? high : (mode == 2 ? band : low);
        if (!std::isfinite(output)) {
            reset();
            return 0.f;
        }
        return output;
    }
};

// Zona morta no centro. Lados em curva exponencial. Perto do centro o mix é de prateleira.
inline void mapFilterKnob(float norm, float sampleRate, int &mode, float &freq, float &mix) {
    if (norm > 0.47f && norm < 0.53f) {
        mode = 0;
        freq = 1000.f;
        mix = 0.f;
        return;
    }
    float travel = 0.f;
    if (norm <= 0.47f) {
        travel = (0.47f - norm) / 0.47f;
        freq = 95.f * std::pow(11000.f / 95.f, 1.f - travel);
        mode = 0;
    } else {
        travel = (norm - 0.53f) / 0.47f;
        freq = 45.f * std::pow(8500.f / 45.f, travel);
        mode = 1;
    }
    travel = std::clamp(travel, 0.f, 1.f);
    mix = std::pow(travel, 0.7f);
    if (travel < 0.2f) mix *= 0.35f + 0.65f * (travel / 0.2f);
    freq = std::clamp(freq, 30.f, std::max(200.f, sampleRate) * 0.45f);
}

inline float readLooped(const float *data, int frames, double position, double loopStart, double loopEnd, int fade) {
    const float dry = readInterp(data, frames, position);
    if (!(loopEnd > loopStart) || fade <= 1) return dry;
    if (position < loopStart || position >= loopEnd) return dry;
    const double into = loopEnd - position;
    if (into >= static_cast<double>(fade) || into < 0.0) return dry;
    // t = 0 no início da janela e 1 na última amostra, que cai em cima de loopStart.
    // O áudio de entrada é o trecho imediatamente anterior ao início, deslocado uma
    // amostra para o crossfade terminar na primeira amostra do loop, não na anterior.
    const double span = static_cast<double>(fade - 1);
    const double t = std::min(1.0, std::max(0.0, (static_cast<double>(fade) - into) / span));
    // A última amostra (into <= 1) é exatamente o ponto de aterrissagem do wrap.
    // cos(pi/2) em float não é zero e vazava o seco, abrindo uma emenda já fechada.
    const float incoming = readInterp(data, frames, loopStart - (into - 1.0));
    if (t >= 1.0) return incoming;
    const float angle = static_cast<float>(t * 1.5707963267948966);
    return dry * std::cos(angle) + incoming * std::sin(angle);
}

inline float readRolled(const float *data, int frames, double position, double origin, double length, int fade) {
    const float dry = readInterp(data, frames, position);
    if (!(length > 1.0) || origin < 0.0 || fade <= 1) return dry;
    if (position < origin || position >= origin + length) return dry;
    const double into = (origin + length) - position;
    if (into >= static_cast<double>(fade) || into < 0.0) return dry;
    const double span = static_cast<double>(fade - 1);
    const double t = std::min(1.0, std::max(0.0, (static_cast<double>(fade) - into) / span));
    const float incoming = readInterp(data, frames, origin - (into - 1.0));
    if (t >= 1.0) return incoming;
    const float angle = static_cast<float>(t * 1.5707963267948966);
    return dry * std::cos(angle) + incoming * std::sin(angle);
}

inline float readPerformance(const float *data, int frames, double position, double loopStart, double loopEnd,
                             double rollOrigin, double rollLength, int fade) {
    if (rollLength > 1.0 && rollOrigin >= 0.0) return readRolled(data, frames, position, rollOrigin, rollLength, fade);
    return readLooped(data, frames, position, loopStart, loopEnd, fade);
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

// O fundo anda para a frente no tempo do pitch, mesmo em pausa, scratch, hot cue ou reverse.
// Ao soltar, o audível volta a esse fundo.
struct TransportSlip {
    double audible = 0.0;
    double hidden = 0.0;
    bool armed = false;
    bool paused = false;
    bool reversed = false;
    bool holding = false;

    void armAt(double position) {
        audible = position;
        hidden = position;
        armed = true;
        paused = false;
        reversed = false;
        holding = false;
    }

    void disarm() {
        if (armed) audible = hidden;
        armed = false;
        paused = false;
        reversed = false;
        holding = false;
    }

    void tick(double forward) {
        const double step = forward > 0.0 ? forward : 0.0;
        if (!armed) {
            if (!paused && !holding) audible += reversed ? -step : step;
            hidden = audible;
            return;
        }
        hidden += step;
        if (paused || holding) return;
        audible += reversed ? -step : step;
    }

    void jog(double delta) { audible += delta; }

    void pause() { paused = true; }

    void resume() {
        if (armed) audible = hidden;
        paused = false;
        holding = false;
    }

    void setReversed(bool on) {
        if (!on && reversed && armed) audible = hidden;
        reversed = on;
    }

    void holdAt(double cue) {
        holding = true;
        paused = false;
        audible = cue;
    }

    void releaseHold() {
        if (armed) audible = hidden;
        holding = false;
    }
};

struct PeakLimiter {
    float envelope = 1.f;
    float ceiling = 0.98f;

    void process(float &left, float &right) {
        const float peak = std::max(std::fabs(left), std::fabs(right));
        const float need = peak > ceiling ? ceiling / peak : 1.f;
        if (need < envelope) envelope = need;
        else envelope += (1.f - envelope) * 0.002f;
        if (envelope > 1.f) envelope = 1.f;
        left *= envelope;
        right *= envelope;
    }
};

struct Biquad {
    float b0 = 1.f, b1 = 0.f, b2 = 0.f, a1 = 0.f, a2 = 0.f, z1 = 0.f, z2 = 0.f;

    void reset() { z1 = z2 = 0.f; }

    float process(float x) {
        const float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        flushDenorm(z1);
        flushDenorm(z2);
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

    float readAt(float delay) const {
        if (data.size() < 4) return 0.f;
        const float limit = static_cast<float>(data.size() - 2);
        delay = std::clamp(delay, 1.f, limit);
        const int base = static_cast<int>(delay);
        const float frac = delay - static_cast<float>(base);
        return read(base) * (1.f - frac) + read(base + 1) * frac;
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
    float tailMix = 0.f;
    float delaySm = 0.f;
    float gateEnv = 1.f;
    float darkL = 0.f;
    float darkR = 0.f;
    bool brake = false;
    bool roll = false;
    bool slip = false;
    bool ready = false;
    bool snap = true;
    float brakePos = 0.f;
    ParamSmooth levelSm;
    ParamSmooth sendSm;
    ParamSmooth wetSm;
    ParamSmooth freqSm;
    DelayLine left;
    DelayLine right;
    DelayLine preL;
    DelayLine preR;
    Svf toneL;
    Svf toneR;
    Svf fbL;
    Svf fbR;
    Biquad ap[8];
    std::vector<float> combL[4];
    std::vector<float> combR[4];
    float combDampL[4]{};
    float combDampR[4]{};
    int combPosL[4]{};
    int combPosR[4]{};
    int lengths[4] = {1301, 1571, 1861, 2113};

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        const int pre = std::max(32, sampleRate / 40);
        preL.prepare(pre + 8);
        preR.prepare(pre + 8);
        for (int index = 0; index < 4; ++index) {
            const int n = std::max(32, lengths[index] * sampleRate / 48000);
            combL[index].assign(n, 0.f);
            combR[index].assign(n, 0.f);
            combDampL[index] = 0.f;
            combDampR[index] = 0.f;
            combPosL[index] = 0;
            combPosR[index] = 0;
        }
        for (auto &item : ap) item.reset();
        toneL.reset();
        toneR.reset();
        fbL.reset();
        fbR.reset();
        const float sr = static_cast<float>(sampleRate);
        levelSm.setMs(32.f, sampleRate);
        sendSm.setMs(18.f, sampleRate);
        wetSm.setMs(22.f, sampleRate);
        freqSm.setMs(36.f, sampleRate);
        fbL.set(6200.f, sr, 0.707f, 0);
        fbR.set(6200.f, sr, 0.707f, 0);
    }

    void clear() {
        left.clear();
        right.clear();
        preL.clear();
        preR.clear();
        for (int index = 0; index < 4; ++index) {
            std::fill(combL[index].begin(), combL[index].end(), 0.f);
            std::fill(combR[index].begin(), combR[index].end(), 0.f);
            combDampL[index] = 0.f;
            combDampR[index] = 0.f;
            combPosL[index] = 0;
            combPosR[index] = 0;
        }
        clock = 0;
        lfo = 0.f;
        tailMix = 0.f;
        darkL = darkR = 0.f;
        brakePos = 0.f;
        gateEnv = 1.f;
        toneL.reset();
        toneR.reset();
    }

    void configure(int nextEffect, bool enabled, float nextLevel, float nextBpm, float nextBeats, int rate) {
        const bool effectChanged = !ready || nextEffect != effect;
        effect = nextEffect;
        on = enabled;
        level = std::clamp(nextLevel, 0.f, 1.f);
        bpm = nextBpm > 1.f ? nextBpm : 120.f;
        beats = nextBeats > 0.f ? nextBeats : 1.f;
        sampleRate = rate > 0 ? rate : 48000;
        roll = on && (effect == 9 || effect == 10);
        slip = on && effect == 9;
        brake = on && effect == 14;
        if (left.data.empty() || !ready) prepare();
        snap = effectChanged;
        if (effectChanged && ready) clear();
        if (effectChanged) delaySm = static_cast<float>(delaySamples());
        ready = true;
    }

    int delaySamples() const {
        const double beat = samplesPerBeat(bpm, sampleRate);
        return std::clamp(static_cast<int>(beat * beats), 1, 95999);
    }

    bool transportEffect() const { return effect == 9 || effect == 10 || effect == 14; }

    bool tailEffect() const {
        return effect == 0 || effect == 1 || effect == 2 || effect == 3 || effect == 4 || effect == 11 || effect == 12;
    }

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
        const bool snapNow = snap;
        snap = false;
        const float amount = levelSm.next(level, snapNow);
        const float targetDelay = static_cast<float>(delaySamples());
        if (snapNow) delaySm = targetDelay;
        else {
            const float glide = 1.f - std::exp(-1.f / std::max(1.f, 0.05f * static_cast<float>(sampleRate)));
            delaySm += (targetDelay - delaySm) * glide;
        }
        if (transportEffect()) {
            inL = sanitize(dryL);
            inR = sanitize(dryR);
            clock += 1;
            return;
        }
        const float beat = static_cast<float>(samplesPerBeat(bpm, sampleRate));
        const float lfoHz = std::max(0.05f, bpm / 60.f) / std::max(0.25f, beats);
        lfo += static_cast<float>(kTwoPi) * lfoHz / static_cast<float>(sampleRate);
        if (lfo > static_cast<float>(kTwoPi)) lfo -= static_cast<float>(kTwoPi);
        float wetL = dryL;
        float wetR = dryR;
        const float sr = static_cast<float>(sampleRate);
        switch (effect) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 11:
            case 12:
                echoFamily(dryL, dryR, amount, snapNow, wetL, wetR);
                break;
            case 4:
                reverbPair(dryL, dryR, amount, snapNow, wetL, wetR);
                break;
            case 5: {
                const int period = std::max(8, static_cast<int>(delaySm));
                const float duty = 0.18f + 0.62f * amount;
                const bool open = (clock % period) < static_cast<int>(period * duty);
                const float coef = 1.f - std::exp(-1.f / std::max(1.f, 0.004f * sr));
                gateEnv += ((open ? 1.f : 0.f) - gateEnv) * coef;
                wetL = dryL * gateEnv;
                wetR = dryR * gateEnv;
                break;
            }
            case 6: {
                const float lfoPos = 0.5f + 0.5f * std::sin(lfo);
                const float flanger = 90.f + 220.f * lfoPos;
                const float fb = 0.08f + 0.42f * amount;
                const float echoL = left.readAt(flanger);
                const float echoR = right.readAt(flanger + 11.f);
                left.push(dryL + echoL * fb);
                right.push(dryR + echoR * fb);
                const float wet = 0.2f + 0.55f * amount;
                wetL = dryL * (1.f - wet * 0.5f) + echoL * wet;
                wetR = dryR * (1.f - wet * 0.5f) + echoR * wet;
                break;
            }
            case 7: {
                const float target = 280.f + 2200.f * (0.5f + 0.5f * std::sin(lfo));
                const float freq = freqSm.next(target, snapNow);
                float x = dryL;
                float y = dryR;
                for (int index = 0; index < 4; ++index) {
                    ap[index].setAllpass(freq * (1.f + 0.17f * index), sr, 0.65f);
                    x = ap[index].process(x);
                    ap[index + 4].setAllpass(freq * (1.f + 0.17f * index), sr, 0.65f);
                    y = ap[index + 4].process(y);
                }
                const float wet = 0.25f + 0.6f * amount;
                const float fb = 0.15f * amount;
                wetL = dryL * (1.f - wet) + (x + fb * (x - dryL)) * wet;
                wetR = dryR * (1.f - wet) + (y + fb * (y - dryR)) * wet;
                break;
            }
            case 8: {
                const float semis = (amount * 2.f - 1.f) * 5.f;
                const float ratio = std::pow(2.f, semis / 12.f);
                const float wet = 0.35f + 0.4f * std::fabs(amount - 0.5f) * 2.f;
                wetL = dryL * (1.f - wet) + pitchOne(left, dryL, ratio) * wet * 1.2f;
                wetR = dryR * (1.f - wet) + pitchOne(right, dryR, ratio) * wet * 1.2f;
                break;
            }
            case 13: {
                int mode = 0;
                float freq = 1000.f;
                float mix = 0.f;
                mapFilterKnob(amount, sr, mode, freq, mix);
                const float smoothFreq = freqSm.next(freq, snapNow);
                const float smoothMix = wetSm.next(mix, snapNow);
                toneL.set(smoothFreq, sr, 0.95f, mode);
                toneR.set(smoothFreq, sr, 0.95f, mode);
                wetL = dryL * (1.f - smoothMix) + toneL.process(dryL) * smoothMix;
                wetR = dryR * (1.f - smoothMix) + toneR.process(dryR) * smoothMix;
                break;
            }
            default:
                break;
        }
        if (!tailEffect()) {
            const float bypass = sendSm.next(on ? 0.f : 1.f, snapNow);
            wetL = dryL * bypass + wetL * (1.f - bypass);
            wetR = dryR * bypass + wetR * (1.f - bypass);
        }
        inL = sanitize(wetL);
        inR = sanitize(wetR);
        clock += 1;
        (void)beat;
    }

private:
    float darken(Svf &filter, float sample) { return filter.process(sample); }

    void echoFamily(float dryL, float dryR, float amount, bool snapNow, float &wetL, float &wetR) {
        const float send = sendSm.next(on ? 1.f : 0.f, snapNow && on);
        const float wetTarget = 0.1f + 0.58f * amount;
        const float fbTarget = 0.16f + 0.5f * amount;
        if (on) tailMix = wetSm.next(wetTarget, snapNow);
        else {
            tailMix *= 0.99986f;
            if (tailMix < 1e-5f) tailMix = 0.f;
        }
        const float fb = std::min(0.82f, fbTarget) * (on ? 1.f : 0.92f);
        const float delay = std::max(1.f, delaySm);
        float echoL = 0.f;
        float echoR = 0.f;
        float pushL = dryL * send;
        float pushR = dryR * send;
        if (effect == 0) {
            echoL = darken(fbL, left.readAt(delay));
            echoR = darken(fbR, right.readAt(delay));
            const float cut = 160.f + amount * 2600.f;
            toneL.set(cut, static_cast<float>(sampleRate), 0.85f, 1);
            toneR.set(cut, static_cast<float>(sampleRate), 0.85f, 1);
            echoL = toneL.process(echoL);
            echoR = toneR.process(echoR);
        } else if (effect == 2) {
            const float half = std::max(1.f, delay * 0.5f);
            echoL = darken(fbL, left.readAt(delay));
            echoR = darken(fbR, right.readAt(half));
            pushL = dryR * send;
            pushR = dryL * send;
        } else if (effect == 3) {
            const float spiral = std::max(8.f, delay * (1.f - 0.62f * (0.5f + 0.5f * std::sin(lfo))));
            echoL = darken(fbL, left.readAt(spiral));
            echoR = darken(fbR, right.readAt(spiral));
        } else if (effect == 11) {
            const float helix = std::max(16.f, delay * (0.62f + 0.38f * (0.5f + 0.5f * std::sin(lfo * 0.5f))));
            echoL = darken(fbL, left.readAt(helix));
            echoR = darken(fbR, right.readAt(std::max(8.f, helix * 0.5f)));
            pushL = dryR * send;
            pushR = dryL * send;
        } else if (effect == 12) {
            echoL = darken(fbL, left.readAt(delay));
            echoR = darken(fbR, right.readAt(std::max(8.f, delay * 1.5f)));
            pushL = dryL * send * 0.7f + echoR * fb;
            pushR = dryR * send * 0.7f + echoL * fb;
            left.push(pushL);
            right.push(pushR);
            const float duck = on ? tailMix * 0.4f : 0.f;
            wetL = dryL * (1.f - duck) + echoL * tailMix;
            wetR = dryR * (1.f - duck) + echoR * tailMix;
            return;
        } else {
            echoL = darken(fbL, left.readAt(delay));
            echoR = darken(fbR, right.readAt(delay));
        }
        left.push(pushL + echoL * fb);
        right.push(pushR + echoR * fb);
        const float duck = on ? tailMix * 0.42f : 0.f;
        wetL = dryL * (1.f - duck) + echoL * tailMix;
        wetR = dryR * (1.f - duck) + echoR * tailMix;
    }

    void reverbPair(float dryL, float dryR, float amount, bool snapNow, float &wetL, float &wetR) {
        const float send = sendSm.next(on ? 1.f : 0.f, snapNow && on);
        const float wetTarget = 0.12f + 0.5f * amount;
        if (on) tailMix = wetSm.next(wetTarget, snapNow);
        else {
            tailMix *= 0.9999f;
            if (tailMix < 1e-5f) tailMix = 0.f;
        }
        const int pre = std::max(8, sampleRate / 48);
        const float inL = preL.read(pre);
        const float inR = preR.read(pre);
        preL.push(dryL * send);
        preR.push(dryR * send);
        const float bodyL = reverb(inL, combL, combDampL, combPosL, 0, amount);
        const float bodyR = reverb(inR, combR, combDampR, combPosR, 1, amount);
        const float duck = on ? tailMix * 0.35f : 0.f;
        wetL = dryL * (1.f - duck) + bodyL * tailMix;
        wetR = dryR * (1.f - duck) + bodyR * tailMix;
    }

    float reverb(float in, std::vector<float> *combs, float *damp, int *posns, int channel, float amount) {
        float sum = 0.f;
        const float fb = 0.55f + 0.3f * amount;
        const float dampCoef = 0.16f + 0.2f * amount;
        for (int index = 0; index < 4; ++index) {
            auto &buf = combs[index];
            if (buf.empty()) continue;
            int &pos = posns[index];
            const float delayed = buf[static_cast<size_t>(pos)];
            damp[index] += (delayed - damp[index]) * dampCoef;
            flushDenorm(damp[index]);
            float write = in + damp[index] * fb;
            if (!std::isfinite(write) || std::fabs(write) > 4.f) write = 0.f;
            buf[static_cast<size_t>(pos)] = write;
            pos = (pos + 1) % static_cast<int>(buf.size());
            sum += delayed;
        }
        sum *= 0.22f;
        Biquad &first = ap[channel];
        Biquad &second = ap[channel + 2];
        if (clock % 64 == 0) {
            first.setAllpass(740.f, static_cast<float>(sampleRate), 0.6f);
            second.setAllpass(1280.f, static_cast<float>(sampleRate), 0.6f);
        }
        return second.process(first.process(sum));
    }

    float pitchOne(DelayLine &line, float in, float ratio) {
        const float base = 900.f;
        const float wobble = 140.f * std::sin(lfo * std::max(0.25f, ratio));
        const float delay = std::clamp(base + wobble, 64.f, 4000.f);
        const float y = line.readAt(delay);
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
    bool armSnap = false;
    int snapLeft = -1;
    int clock = 0;
    int sampleRate = 48000;
    DelayLine left;
    DelayLine right;
    Svf hpL, hpR, lpL, lpR, noiseF;
    uint32_t noise = 0x1234567u;
    std::vector<float> comb[4];
    float damp[4]{};
    int combPos[4]{};

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        const int base[4] = {1327, 1499, 1741, 1987};
        for (int index = 0; index < 4; ++index) {
            comb[index].assign(std::max(32, base[index] * sampleRate / 48000), 0.f);
            damp[index] = 0.f;
            combPos[index] = 0;
        }
    }

    void setHold(bool down) {
        if (down) {
            holding = true;
            releasing = false;
            armSnap = false;
            snapLeft = -1;
        } else if (holding || intensity > 0.f) {
            holding = false;
            releasing = intensity > 0.f;
            armSnap = releasing;
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
        const int beatN = std::max(1, static_cast<int>(beat));
        if (holding) intensity = std::min(ceiling, intensity + 1.f / ramp);
        else if (releasing) {
            if (armSnap && (clock % beatN) == 0) {
                armSnap = false;
                snapLeft = std::max(1, static_cast<int>(0.018f * sampleRate));
            }
            if (!armSnap && snapLeft > 0) {
                intensity = std::max(0.f, intensity - std::max(intensity, 0.001f) / static_cast<float>(snapLeft));
                snapLeft -= 1;
                if (snapLeft == 0 || intensity <= 0.0008f) {
                    intensity = 0.f;
                    releasing = false;
                }
            }
        }
        if (intensity <= 0.0008f && !holding) {
            inL = sanitize(dryL);
            inR = sanitize(dryR);
            clock += 1;
            return;
        }
        const float t = intensity;
        const float curve = std::pow(std::clamp(t, 0.f, 1.f), 1.35f);
        const int delay = std::clamp(static_cast<int>(beat), 1, 95999);
        const float sr = static_cast<float>(sampleRate);
        float wetL = dryL;
        float wetR = dryR;
        switch (scene) {
            case 0: {
                const float cut = 140.f * std::pow(48.f, curve);
                hpL.set(std::min(cut, sr * 0.4f), sr, 0.9f, 1);
                hpR.set(std::min(cut, sr * 0.4f), sr, 0.9f, 1);
                const float fb = std::min(0.84f, 0.18f + 0.62f * curve);
                float eL = hpL.process(fbL(left.read(delay)));
                float eR = hpR.process(fbR(right.read(delay)));
                left.push(dryL + eL * fb);
                right.push(dryR + eR * fb);
                const float rise = noiseF.process(noiseSample(noise)) * curve * curve * 0.07f;
                noiseF.set(400.f * std::pow(18.f, curve), sr, 0.9f, 2);
                wetL = dryL * (1.f - curve * 0.35f) + eL * (0.15f + 0.7f * curve) + rise;
                wetR = dryR * (1.f - curve * 0.35f) + eR * (0.15f + 0.7f * curve) + rise;
                break;
            }
            case 1: {
                const float cut = 180.f * std::pow(40.f, curve);
                noiseF.set(std::min(cut, sr * 0.42f), sr, 1.05f, 2);
                const float colored = noiseF.process(noiseSample(noise)) * (0.045f + 0.16f * curve);
                wetL = dryL * (1.f - curve * 0.28f) + colored;
                wetR = dryR * (1.f - curve * 0.28f) + colored;
                break;
            }
            case 2: {
                const int spiral = std::max(8, static_cast<int>(delay * (1.f - 0.78f * curve)));
                const float fb = std::min(0.86f, 0.22f + 0.58f * curve);
                const float eL = left.read(spiral);
                const float eR = right.read(spiral);
                left.push(dryL + fbL(eL) * fb);
                right.push(dryR + fbR(eR) * fb);
                wetL = dryL * (1.f - curve * 0.7f) + eL * curve;
                wetR = dryR * (1.f - curve * 0.7f) + eR * curve;
                break;
            }
            case 3: {
                const float cut = 90.f * std::pow(30.f, curve);
                hpL.set(std::min(cut, sr * 0.4f), sr, 0.85f, 1);
                hpR.set(std::min(cut, sr * 0.4f), sr, 0.85f, 1);
                wetL = hpL.process(dryL) * (1.f - curve * 0.25f) + verb(dryL, 0, curve) * (0.15f + 0.75f * curve);
                wetR = hpR.process(dryR) * (1.f - curve * 0.25f) + verb(dryR, 1, curve) * (0.15f + 0.75f * curve);
                break;
            }
            case 4: {
                const float cut = 9000.f * std::pow(0.08f, curve);
                lpL.set(std::max(80.f, cut), sr, 0.85f, 0);
                lpR.set(std::max(80.f, cut), sr, 0.85f, 0);
                wetL = lpL.process(dryL) * (1.f - curve * 0.4f) + verb(dryL, 0, curve) * (0.1f + 0.7f * curve);
                wetR = lpR.process(dryR) * (1.f - curve * 0.4f) + verb(dryR, 1, curve) * (0.1f + 0.7f * curve);
                break;
            }
            case 5: {
                const float cut = 10000.f * std::pow(0.06f, curve);
                lpL.set(std::max(70.f, cut), sr, 0.9f, 0);
                lpR.set(std::max(70.f, cut), sr, 0.9f, 0);
                const float fb = std::min(0.84f, 0.16f + 0.6f * curve);
                const float eL = lpL.process(fbL(left.read(delay)));
                const float eR = lpR.process(fbR(right.read(delay)));
                left.push(dryL + eL * fb);
                right.push(dryR + eR * fb);
                wetL = dryL * (1.f - curve * 0.55f) + eL * curve;
                wetR = dryR * (1.f - curve * 0.55f) + eR * curve;
                break;
            }
            case 6: {
                const float cut = 7000.f * std::pow(0.12f, curve);
                lpL.set(std::max(90.f, cut), sr, 0.8f, 0);
                lpR.set(std::max(90.f, cut), sr, 0.8f, 0);
                wetL = lpL.process(dryL);
                wetR = lpR.process(dryR);
                break;
            }
            default: {
                const int dub = std::max(8, (delay * 3) / 4);
                const float fb = std::min(0.86f, 0.3f + 0.5f * curve);
                const float eL = left.read(dub);
                const float eR = right.read(dub);
                lpL.set(5200.f * std::pow(0.35f, curve), sr, 0.8f, 0);
                lpR.set(5200.f * std::pow(0.35f, curve), sr, 0.8f, 0);
                left.push(lpL.process(dryL + eL * fb));
                right.push(lpR.process(dryR + eR * fb));
                wetL = dryL * (1.f - curve * 0.35f) + eL * (0.2f + 0.7f * curve);
                wetR = dryR * (1.f - curve * 0.35f) + eR * (0.2f + 0.7f * curve);
                break;
            }
        }
        inL = sanitize(wetL);
        inR = sanitize(wetR);
        clock += 1;
    }

private:
    float fbStateL = 0.f;
    float fbStateR = 0.f;

    float dark(float sample, float &state) {
        const float g = 1.f - std::exp(-6.2831853f * 5000.f / static_cast<float>(std::max(1, sampleRate)));
        state += (sample - state) * g;
        flushDenorm(state);
        return state;
    }

    float fbL(float sample) { return dark(sample, fbStateL); }
    float fbR(float sample) { return dark(sample, fbStateR); }

    float verb(float in, int channel, float curve) {
        float sum = 0.f;
        const float fb = 0.4f + 0.42f * curve;
        for (int index = 0; index < 2; ++index) {
            const int slot = channel * 2 + index;
            auto &buf = comb[slot];
            if (buf.empty()) continue;
            int &pos = combPos[slot];
            const float y = buf[static_cast<size_t>(pos)];
            damp[slot] += (y - damp[slot]) * 0.35f;
            flushDenorm(damp[slot]);
            float write = in + damp[slot] * fb;
            if (!std::isfinite(write) || std::fabs(write) > 4.f) write = 0.f;
            buf[static_cast<size_t>(pos)] = write;
            pos = (pos + 1) % static_cast<int>(buf.size());
            sum += y;
        }
        return sum * 0.45f;
    }
};

struct DrumBank {
    std::vector<float> voice[5];

    void set(int id, const float *data, int frames) {
        if (id < 0 || id > 4 || data == nullptr || frames < 8) return;
        voice[id].assign(data, data + frames);
        const int fade = std::min(64, frames / 2);
        for (int index = 0; index < fade; ++index) {
            voice[id][static_cast<size_t>(frames - 1 - index)] *= static_cast<float>(index) / static_cast<float>(fade);
        }
    }

    bool empty(int id) const { return id < 0 || id > 4 || voice[id].size() < 8; }

    void ensureModel(int sampleRate) {
        const int rate = sampleRate > 0 ? sampleRate : 48000;
        if (empty(0)) voice[0] = modelKick(rate);
        if (empty(1)) voice[1] = modelClap(rate);
        if (empty(2)) voice[2] = modelSnare(rate);
        if (empty(3)) voice[3] = modelHat(rate, 0.045f);
        if (empty(4)) voice[4] = modelHat(rate, 0.42f);
    }

    static std::vector<float> finish(std::vector<float> data) {
        if (data.size() < 16) return data;
        const int fadeIn = std::min(16, static_cast<int>(data.size()) / 4);
        for (int index = 0; index < fadeIn; ++index) data[static_cast<size_t>(index)] *= static_cast<float>(index) / fadeIn;
        const int fade = std::min(64, static_cast<int>(data.size()) / 3);
        for (int index = 0; index < fade; ++index) {
            data[data.size() - 1 - static_cast<size_t>(index)] *= static_cast<float>(index) / fade;
        }
        return data;
    }

    static std::vector<float> modelKick(int rate) {
        const int n = static_cast<int>(0.38f * rate);
        std::vector<float> out(static_cast<size_t>(n));
        uint32_t noise = 0x4B1u;
        float phase = 0.f;
        for (int index = 0; index < n; ++index) {
            const float t = static_cast<float>(index) / static_cast<float>(rate);
            const float freq = 50.f + (150.f - 50.f) * std::exp(-t * 28.f);
            phase += static_cast<float>(kTwoPi) * freq / static_cast<float>(rate);
            const float body = std::sin(phase) * std::exp(-t * 7.5f);
            const float click = (t < 0.004f) ? noiseSample(noise) * (1.f - t / 0.004f) : 0.f;
            out[static_cast<size_t>(index)] = std::tanh(1.35f * body + 0.55f * click);
        }
        return finish(out);
    }

    static std::vector<float> modelClap(int rate) {
        const int n = static_cast<int>(0.28f * rate);
        std::vector<float> out(static_cast<size_t>(n));
        uint32_t noise = 0xC1A0u;
        float band = 0.f;
        const float bursts[4] = {0.f, 0.010f, 0.021f, 0.033f};
        const float g = 1.f - std::exp(-static_cast<float>(kTwoPi) * 1400.f / static_cast<float>(rate));
        for (int index = 0; index < n; ++index) {
            const float t = static_cast<float>(index) / static_cast<float>(rate);
            float env = std::exp(-t * 10.f) * 0.22f;
            for (float burst : bursts) {
                const float d = t - burst;
                if (d >= 0.f && d < 0.012f) env += (1.f - d / 0.012f) * std::exp(-d * 40.f);
            }
            const float sample = noiseSample(noise);
            band += (sample - band) * g;
            out[static_cast<size_t>(index)] = std::tanh((sample - band) * env * 1.4f);
        }
        return finish(out);
    }

    static std::vector<float> modelSnare(int rate) {
        const int n = static_cast<int>(0.32f * rate);
        std::vector<float> out(static_cast<size_t>(n));
        uint32_t noise = 0x51A6u;
        float phase = 0.f;
        float hp = 0.f;
        const float g = 1.f - std::exp(-static_cast<float>(kTwoPi) * 1800.f / static_cast<float>(rate));
        for (int index = 0; index < n; ++index) {
            const float t = static_cast<float>(index) / static_cast<float>(rate);
            phase += static_cast<float>(kTwoPi) * 190.f / static_cast<float>(rate);
            const float tone = std::sin(phase) * std::exp(-t * 18.f);
            const float sample = noiseSample(noise);
            hp += (sample - hp) * g;
            const float noise = (sample - hp) * std::exp(-t * 12.f);
            out[static_cast<size_t>(index)] = std::tanh(tone * 0.55f + noise * 0.85f);
        }
        return finish(out);
    }

    static std::vector<float> modelHat(int rate, float seconds) {
        const int n = std::max(32, static_cast<int>(seconds * rate));
        std::vector<float> out(static_cast<size_t>(n));
        uint32_t noise = seconds < 0.1f ? 0x11u : 0x90u;
        float hp = 0.f;
        const float g = 1.f - std::exp(-static_cast<float>(kTwoPi) * 6500.f / static_cast<float>(rate));
        for (int index = 0; index < n; ++index) {
            const float t = static_cast<float>(index) / static_cast<float>(rate);
            const float sample = noiseSample(noise);
            hp += (sample - hp) * g;
            out[static_cast<size_t>(index)] = (sample - hp) * std::exp(-t * (seconds < 0.1f ? 48.f : 8.f)) * 0.7f;
        }
        return finish(out);
    }
};

inline DrumBank &drumBank() {
    static DrumBank bank;
    return bank;
}

inline double musicalBeat(double emittedSourcePos, int sourceRate, double bpm, double originSec) {
    if (sourceRate <= 0) return 0.0;
    const double use = bpm > 1.0 ? bpm : 120.0;
    const double seconds = emittedSourcePos / static_cast<double>(sourceRate);
    return (seconds - originSec) * use / 60.0;
}

struct XPad {
    int mode = 0;
    float beats = 0.25f;
    float build = 0.f;
    bool requested = false;
    bool engaged = false;
    float bpm = 120.f;
    int sampleRate = 48000;
    int clock = 0;
    int voice = -1;
    int env = 0;
    int envFull = 1;
    float phase = 0.f;
    float hatState = 0.f;
    Biquad hp;
    uint32_t noise = 0x51u;
    int drumVoice = 0;
    int drumBars = 8;
    bool drumGap = true;
    bool drumPitch = false;
    bool hats = true;
    bool layerHits = true;
    bool followGrid = false;
    bool phaseValid = false;
    double phaseBeat = 0.0;
    bool live = false;
    bool finished = false;
    bool prevValid = false;
    double prevBeat = 0.0;
    double originBeat = 0.0;
    double endBeat = 0.0;
    double nextHit = 1.0e300;
    double edges[5] = {};
    int latchedBars = 8;
    bool wasRequested = false;
    Svf drumHp;
    struct Shot {
        int id = 0;
        double cursor = 0.0;
        float vel = 0.f;
        float rate = 1.f;
        bool on = false;
    };
    Shot shots[24];

    void set(int nextMode, float nextBeats, bool on, float nextBpm, int rate, float nextBuild = 0.f, int nextVoice = 0,
             int nextBars = 8, bool nextGap = true, bool nextPitch = false) {
        mode = nextMode;
        beats = nextBeats > 0.f ? nextBeats : 0.25f;
        build = std::clamp(nextBuild, 0.f, 1.f);
        const bool edge = on && !requested;
        requested = on;
        bpm = nextBpm > 1.f ? nextBpm : 120.f;
        sampleRate = rate > 0 ? rate : 48000;
        drumVoice = std::clamp(nextVoice, 0, 3);
        drumBars = snapBars(nextBars);
        drumGap = nextGap;
        drumPitch = nextPitch;
        if (!on && !engaged) env = 0;
        if (edge) {
            live = false;
            finished = false;
            prevValid = false;
            nextHit = 1.0e300;
        }
        if (!on) finished = false;
    }

    static int snapBars(int bars) {
        if (bars >= 16) return 16;
        if (bars >= 8) return 8;
        return 4;
    }

    static void sectionBars(int bars, int out[4]) {
        if (bars >= 16) {
            out[0] = 4;
            out[1] = 4;
            out[2] = 7;
            out[3] = 1;
        } else if (bars >= 8) {
            out[0] = 2;
            out[1] = 2;
            out[2] = 3;
            out[3] = 1;
        } else {
            out[0] = 1;
            out[1] = 1;
            out[2] = 1;
            out[3] = 1;
        }
    }

    void setPhase(double beat, bool valid) {
        phaseBeat = beat;
        phaseValid = valid;
    }

    int sliceSamples() const {
        if (mode == 2 && build > 0.04f && !followGrid) {
            const float divs[4] = {0.25f, 0.125f, 0.0625f, 0.03125f};
            const int stage = std::min(3, static_cast<int>(build * 4.f));
            return std::max(1, static_cast<int>(samplesPerBeat(bpm, sampleRate) * divs[stage]));
        }
        return std::max(1, static_cast<int>(samplesPerBeat(bpm, sampleRate) * beats));
    }

    bool rollEngaged() const { return engaged && mode == 0; }

    void process(float &inL, float &inR) {
        if (mode == 2 && build > 0.04f && followGrid) {
            renderGrid(inL, inR);
            return;
        }
        const int beatN = std::max(1, static_cast<int>(samplesPerBeat(bpm, sampleRate)));
        const int slice = sliceSamples();
        if (requested && !engaged && (clock % slice) == 0) engaged = true;
        if (!requested && (clock % beatN) == 0) {
            engaged = false;
            env = 0;
        }
        if (!engaged) {
            clock += 1;
            inL = sanitize(inL);
            inR = sanitize(inR);
            return;
        }
        if ((clock % slice) == 0) {
            envFull = std::max(8, std::min(sampleRate / 12, std::max(8, slice / 2)));
            env = envFull;
            if (mode == 2 && build > 0.04f) voice = ((clock % beatN) == 0) ? 0 : 1;
            else voice = (voice + 1) & 3;
            phase = 0.f;
        }
        if (mode == 1) {
            const int openFor = slice / 2;
            const bool open = (clock % slice) < openFor;
            const float coef = 1.f - std::exp(-1.f / std::max(1.f, 0.005f * static_cast<float>(sampleRate)));
            hatState += ((open ? 1.f : 0.f) - hatState) * coef;
            inL = sanitize(inL * hatState);
            inR = sanitize(inR * hatState);
        } else if (mode == 2) {
            const float hit = drum();
            const float duck = build > 0.04f ? (0.15f + 0.2f * build) : 0.15f;
            inL = sanitize(inL * (1.f - duck) + hit);
            inR = sanitize(inR * (1.f - duck) + hit);
        } else if (mode == 3) {
            const bool open = (clock % slice) < (slice * 2) / 3;
            const float cut = open ? 900.f : 4200.f;
            hp.setHighpass(cut, static_cast<float>(sampleRate), 0.9f);
            inL = sanitize(hp.process(inL));
            inR = sanitize(inL);
        }
        clock += 1;
        if (env > 0) env -= 1;
    }

    void renderGrid(float &inL, float &inR) {
        const double beat = phaseBeat;
        if (!phaseValid) {
            if (!requested) {
                live = false;
                finished = false;
            }
            mixShots(inL, inR, 0.f);
            prevValid = false;
            return;
        }
        if (requested && !wasRequested) {
            live = false;
            finished = false;
            prevValid = false;
        }
        if (!requested) finished = false;
        wasRequested = requested;
        if (prevValid && beat + 1e-7 < prevBeat) {
            prevBeat = beat;
            if (live) nextHit = hitOnOrAfter(beat);
            mixShots(inL, inR, 0.f);
            return;
        }
        if (!prevValid) {
            prevBeat = beat;
            prevValid = true;
            if (requested && !live && !finished && std::abs(beat - std::round(beat)) < 1e-5) begin(std::round(beat));
            if (live) {
                nextHit = hitOnOrAfter(originBeat - 1e-12);
                if (nextHit <= beat + 1e-7) {
                    fire(nextHit);
                    nextHit = hitOnOrAfter(nextHit + 1e-9);
                }
            }
            mixShots(inL, inR, live ? progress(beat) : 0.f);
            return;
        }
        if (live && drumBars != latchedBars) {
            const double line = originBeat + (std::floor((prevBeat - originBeat) / 4.0) + 1.0) * 4.0;
            if (beat >= line) {
                latchedBars = drumBars;
                endBeat = originBeat + static_cast<double>(latchedBars) * 4.0;
                rebuildEdges();
                nextHit = hitOnOrAfter(prevBeat + 1e-12);
                if (beat >= endBeat) {
                    live = false;
                    finished = true;
                }
            }
        }
        if (!live && requested && !finished) {
            const double nextQuarter = std::floor(prevBeat) + 1.0;
            if (beat >= nextQuarter) begin(nextQuarter);
        }
        if (live && !requested) {
            const double nextQuarter = std::floor(prevBeat) + 1.0;
            if (beat >= nextQuarter) live = false;
        }
        if (live && beat >= endBeat) {
            live = false;
            finished = true;
        }
        if (live && prevBeat < nextHit && beat >= nextHit) {
            fire(nextHit);
            nextHit = hitOnOrAfter(nextHit + 1e-9);
        }
        const float prog = live ? progress(beat) : 0.f;
        mixShots(inL, inR, prog);
        prevBeat = beat;
    }

    float progress(double beat) const {
        const double span = std::max(1e-6, endBeat - originBeat);
        return std::clamp(static_cast<float>((beat - originBeat) / span), 0.f, 1.f);
    }

    void begin(double at) {
        live = true;
        finished = false;
        originBeat = at;
        latchedBars = drumBars;
        endBeat = originBeat + static_cast<double>(latchedBars) * 4.0;
        rebuildEdges();
        nextHit = hitOnOrAfter(originBeat - 1e-12);
    }

    void rebuildEdges() {
        int bars[4];
        sectionBars(latchedBars, bars);
        edges[0] = originBeat;
        for (int section = 0; section < 4; ++section) edges[section + 1] = edges[section] + static_cast<double>(bars[section]) * 4.0;
    }

    int sectionOf(double beat) const {
        for (int section = 0; section < 4; ++section) {
            if (beat < edges[section + 1] - 1e-9) return section;
        }
        return 3;
    }

    static double spacingOf(int section) {
        const double spacing[4] = {1.0, 0.5, 0.25, 0.125};
        return spacing[std::clamp(section, 0, 3)];
    }

    double hitOnOrAfter(double beat) const {
        const double limit = endBeat - (drumGap ? 0.5 : 0.0);
        double best = 1.0e300;
        for (int section = 0; section < 4; ++section) {
            const double start = edges[section];
            const double stop = std::min(edges[section + 1], limit);
            if (!(stop > start)) continue;
            const double spacing = spacingOf(section);
            double hit = start;
            if (beat > hit) {
                const double steps = std::ceil((beat - hit) / spacing - 1e-12);
                hit = start + steps * spacing;
            }
            if (hit < stop - 1e-9 && hit < best) best = hit;
        }
        return best;
    }

    void fire(double hitBeat) {
        drumBank().ensureModel(sampleRate > 0 ? sampleRate : 48000);
        const int section = sectionOf(hitBeat);
        const float prog = progress(hitBeat);
        const float vel = (0.45f + 0.55f * prog) * (0.62f + 0.38f * build);
        const float rate = drumPitch ? (1.f + 0.10f * prog * build) : 1.f;
        if (drumVoice == 3) {
            trigger(0, vel, rate);
            trigger(1, vel * 0.92f, rate);
        } else {
            trigger(drumVoice, vel, rate);
        }
        if (layerHits && section >= 2) {
            const int layer = (drumVoice == 1 || drumVoice == 3) ? 2 : 1;
            if (layer != drumVoice) trigger(layer, vel * 0.8f, rate);
        }
        if (hats && section >= 2) {
            const double frac = hitBeat - std::floor(hitBeat + 1e-9);
            if (std::abs(frac - 0.5) < 0.02) trigger(3, vel * 0.22f, 1.f);
            if (section == 3 && std::abs(hitBeat - edges[3]) < 1e-6) trigger(4, vel * 0.32f, 1.f);
        }
    }

    void trigger(int id, float vel, float rate) {
        if (id < 0 || id > 4 || vel <= 0.f) return;
        int slot = -1;
        int steal = 0;
        double oldest = -1.0;
        for (int index = 0; index < 24; ++index) {
            if (!shots[index].on) {
                slot = index;
                break;
            }
            if (shots[index].cursor > oldest) {
                oldest = shots[index].cursor;
                steal = index;
            }
        }
        if (slot < 0) slot = steal;
        shots[slot].id = id;
        shots[slot].cursor = 0.0;
        shots[slot].vel = vel;
        shots[slot].rate = std::max(0.25f, rate);
        shots[slot].on = true;
    }

    float readVoice(int id, double cursor) const {
        if (drumBank().empty(id)) return 0.f;
        const auto &pcm = drumBank().voice[id];
        if (cursor < 0.0) return 0.f;
        const int index = static_cast<int>(cursor);
        if (index >= static_cast<int>(pcm.size()) - 1) return 0.f;
        const float frac = static_cast<float>(cursor - index);
        float sample = pcm[static_cast<size_t>(index)] * (1.f - frac) + pcm[static_cast<size_t>(index + 1)] * frac;
        const int left = static_cast<int>(pcm.size()) - index;
        if (left < 64) sample *= static_cast<float>(left) / 64.f;
        return sample;
    }

    void mixShots(float &inL, float &inR, float prog) {
        const double step = (sampleRate > 0 ? 48000.0 / static_cast<double>(sampleRate) : 1.0);
        float drum = 0.f;
        for (Shot &shot : shots) {
            if (!shot.on) continue;
            drum += readVoice(shot.id, shot.cursor) * shot.vel;
            shot.cursor += step * static_cast<double>(shot.rate);
            const auto &pcm = drumBank().voice[shot.id];
            if (drumBank().empty(shot.id) || shot.cursor >= static_cast<double>(pcm.size()) - 1.0) shot.on = false;
        }
        if (std::fabs(drum) > 1e-6f) {
            const float cut = 40.f * std::pow(4.5f, std::clamp(prog, 0.f, 1.f) * build);
            const float mix = 0.40f * std::clamp(prog, 0.f, 1.f) * build;
            drumHp.set(cut, static_cast<float>(sampleRate), 0.7f, 1);
            const float high = drumHp.process(drum);
            drum = drum * (1.f - mix) + high * mix;
        } else if (prog < 0.001f) {
            drumHp.reset();
        }
        const float duck = live ? (0.08f + 0.16f * build) : 0.f;
        inL = sanitize(inL * (1.f - duck) + drum);
        inR = sanitize(inR * (1.f - duck) + drum);
    }

    float drum() {
        if (env <= 0 || envFull <= 0) return 0.f;
        const float t = 1.f - (static_cast<float>(env) / static_cast<float>(envFull));
        const float rise = build > 0.04f ? build : 0.f;
        const float pitch = 1.f + rise * 0.75f;
        const float level = (build > 0.04f ? (0.34f + 0.62f * rise) : 0.85f);
        const float decay = std::exp(-6.f * t);
        const int which = voice < 0 ? 0 : voice;
        float y = 0.f;
        if (which == 0) {
            phase += static_cast<float>(kTwoPi) * (150.f - 90.f * t) * pitch / static_cast<float>(sampleRate);
            y = std::sin(phase) * decay + noiseSample(noise) * decay * decay * 0.2f;
        } else if (which == 1) {
            const float body = std::sin(phase) * std::exp(-10.f * t);
            phase += static_cast<float>(kTwoPi) * (190.f * pitch) / static_cast<float>(sampleRate);
            const float g = 1.f - std::exp(-6.2831853f * (1800.f + 5000.f * rise) / static_cast<float>(sampleRate));
            const float n = noiseSample(noise);
            hatState += (n - hatState) * g;
            y = hatState * decay * 0.72f + body * 0.18f;
        } else if (which == 2) {
            const float burst = (t < 0.12f || (t > 0.2f && t < 0.3f) || (t > 0.38f && t < 0.46f)) ? 1.f : 0.18f;
            y = noiseSample(noise) * decay * burst * 0.5f;
        } else {
            const float g = 1.f - std::exp(-6.2831853f * 7000.f / static_cast<float>(sampleRate));
            const float n = noiseSample(noise);
            hatState += (n - hatState) * g;
            y = (n - hatState) * std::exp(-16.f * t) * 0.4f;
        }
        return y * level;
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
    Biquad lowA, lowB, restA, restB, midA, midB, highA, highB;
    Biquad rLowA, rLowB, rRestA, rRestB, rMidA, rMidB, rHighA, rHighB;

    void prepare(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        const float sr = static_cast<float>(sampleRate);
        auto arm = [&](Biquad &lp1, Biquad &lp2, Biquad &hp1, Biquad &hp2, float freq) {
            lp1.setLowpass(freq, sr, 0.7071f);
            lp2.setLowpass(freq, sr, 0.7071f);
            hp1.setHighpass(freq, sr, 0.7071f);
            hp2.setHighpass(freq, sr, 0.7071f);
        };
        arm(lowA, lowB, restA, restB, 200.f);
        arm(midA, midB, highA, highB, 2000.f);
        arm(rLowA, rLowB, rRestA, rRestB, 200.f);
        arm(rMidA, rMidB, rHighA, rHighB, 2000.f);
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
        flushDenorm(smL);
        flushDenorm(smM);
        flushDenorm(smH);
        if (unity() && std::abs(smL - 1.f) < 0.02f && std::abs(smM - 1.f) < 0.02f && std::abs(smH - 1.f) < 0.02f) {
            clock += 1;
            return;
        }
        auto split = [](Biquad &lp1, Biquad &lp2, Biquad &hp1, Biquad &hp2, Biquad &m1, Biquad &m2, Biquad &h1, Biquad &h2,
                        float in, float &lowOut, float &midOut, float &highOut) {
            lowOut = lp2.process(lp1.process(in));
            const float rest = hp2.process(hp1.process(in));
            midOut = m2.process(m1.process(rest));
            highOut = h2.process(h1.process(rest));
        };
        float lLow, lMid, lHigh, rLow, rMid, rHigh;
        split(lowA, lowB, restA, restB, midA, midB, highA, highB, inL, lLow, lMid, lHigh);
        split(rLowA, rLowB, rRestA, rRestB, rMidA, rMidB, rHighA, rHighB, inR, rLow, rMid, rHigh);
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
    float darkL = 0.f;
    float darkR = 0.f;
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
        darkL = darkR = 0.f;
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
            const float fb = 0.5f * (1.f - pos);
            const float g = 1.f - std::exp(-6.2831853f * 5500.f / static_cast<float>(sampleRate));
            float eL = left.read(delay);
            float eR = right.read(delay);
            darkL += (eL - darkL) * g;
            darkR += (eR - darkR) * g;
            flushDenorm(darkL);
            flushDenorm(darkR);
            eL = darkL;
            eR = darkR;
            left.push(inL * dry + eL * fb);
            right.push(inR * dry + eR * fb);
            inL = sanitize(inL * dry + eL * (1.f - dry));
            inR = sanitize(inR * dry + eR * (1.f - dry));
        }
        if (pos >= 1.f && kind != 0) kind = -1;
    }
};

struct ColorFilter {
    float norm = 0.5f;
    int sampleRate = 48000;
    bool snap = true;
    ParamSmooth freqSm;
    ParamSmooth mixSm;
    Svf left;
    Svf right;
    int mode = 0;

    void set(float value, int rate) {
        const float next = std::clamp(value, 0.f, 1.f);
        if (std::fabs(next - norm) > 0.2f) snap = true;
        norm = next;
        const int nextRate = rate > 0 ? rate : 48000;
        if (nextRate != sampleRate || freqSm.coef == 1.f) {
            sampleRate = nextRate;
            freqSm.setMs(32.f, sampleRate);
            mixSm.setMs(28.f, sampleRate);
        }
        sampleRate = nextRate;
    }

    void process(float &inL, float &inR) {
        int targetMode = 0;
        float freq = 1000.f;
        float mix = 0.f;
        mapFilterKnob(norm, static_cast<float>(sampleRate), targetMode, freq, mix);
        const bool snapNow = snap || !freqSm.primed;
        snap = false;
        const float smoothFreq = freqSm.next(freq, snapNow);
        const float smoothMix = mixSm.next(mix, snapNow);
        if (smoothMix < 0.001f) return;
        mode = targetMode;
        const float sr = static_cast<float>(sampleRate);
        left.set(smoothFreq, sr, 0.95f, mode);
        right.set(smoothFreq, sr, 0.95f, mode);
        inL = sanitize(inL * (1.f - smoothMix) + left.process(inL) * smoothMix);
        inR = sanitize(inR * (1.f - smoothMix) + right.process(inR) * smoothMix);
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
    int drumDeck = 0;

    void setSampleRate(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        xpad.sampleRate = sampleRate;
        iso[0].prepare(sampleRate);
        iso[1].prepare(sampleRate);
    }

    void setDrumPhase(double beat, bool valid, bool follow) {
        xpad.followGrid = follow;
        xpad.setPhase(beat, valid);
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
                int nextChannel, float bpm, float drumBuild = 0.f, int drumVoice = 0, int drumBars = 8, bool drumGap = true,
                bool drumPitch = false, int gridDeck = -1) {
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
        const int follow = gridDeck >= 0 ? gridDeck : (nextChannel == 1 ? 1 : 0);
        drumDeck = std::clamp(follow, 0, 1);
        xpad.set(xMode, xBeats, armed && xOn, bpm, sampleRate, std::clamp(drumBuild, 0.f, 1.f), drumVoice, drumBars,
                 drumGap, drumPitch);
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
