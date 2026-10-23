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

// Balanço do Roll e do Slip Roll: LEVEL/DEPTH vai do som original (esquerda) ao roll (direita), como no mixer de
// referência. O 0,5 padrão do knob dá 70% de roll para o roll dominar sem o DJ mexer. O original que sobra é a faixa
// seguindo ao vivo por baixo (posição-sombra).
inline float rollMixFromLevel(float level) {
    return std::clamp(1.6f * level - 0.1f, 0.f, 1.f);
}

inline float rollBalance(float rolled, float live, float mix) {
    return rolled * mix + live * (1.f - mix);
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

    void reset() {
        std::fill(std::begin(buffer), std::end(buffer), 0.f);
        write = 0;
        readA = 0.f;
        readB = static_cast<float>(kSize / 2);
    }

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

// Deslocador de tom por dois grãos (atraso que varre uma janela, cruzados por Hann).
// ratio > 1 sobe, < 1 desce. Vai nos efeitos. O key lock e o Key Shift dos decks usam o Wsola (o PitchShift acima
// ficou só no demo_mix antigo: no teste isolado ele não segura a afinação).
struct GrainShift {
    static constexpr int kWindow = 2048;
    static constexpr int kSize = 4096;
    float buffer[kSize]{};
    int write = 0;
    float phase = 0.f;

    void reset() {
        std::fill(std::begin(buffer), std::end(buffer), 0.f);
        write = 0;
        phase = 0.f;
    }

    float tap(float delay) const {
        float at = static_cast<float>(write) - delay;
        while (at < 0.f) at += static_cast<float>(kSize);
        const int i0 = static_cast<int>(at) % kSize;
        const int i1 = (i0 + 1) % kSize;
        const float frac = at - std::floor(at);
        return buffer[i0] * (1.f - frac) + buffer[i1] * frac;
    }

    float process(float x, float ratio) {
        ratio = std::clamp(ratio, 0.25f, 4.f);
        buffer[write] = x;
        phase += (1.f - ratio) / static_cast<float>(kWindow);
        phase -= std::floor(phase);
        const float other = phase + 0.5f - std::floor(phase + 0.5f);
        const float w1 = 0.5f - 0.5f * std::cos(6.2831853f * phase);
        const float w2 = 0.5f - 0.5f * std::cos(6.2831853f * other);
        const float y = tap(1.f + phase * kWindow) * w1 + tap(1.f + other * kWindow) * w2;
        write = (write + 1) % kSize;
        return sanitize(y);
    }
};

// Master Tempo por WSOLA (sobreposição e soma com busca de correlação), lendo direto da faixa.
// A faixa tem acesso aleatório, então não há latência: o grão que começa na posição de análise `position` é
// emendado ao anterior no deslocamento (±kSearch amostras) que melhor continua a forma de onda. A taxa de
// leitura do grão (`rate`) decide o tom; o avanço da posição de análise (feito pelo motor) decide o tempo.
// Janela de 2048 amostras, salto de 1024, Hann periódica (soma 1 com 50% de sobreposição).
struct Wsola {
    static constexpr int kHop = 1024;
    static constexpr int kWin = 2 * kHop;
    static constexpr int kSearch = 220;
    static constexpr int kCand = 4608;
    float accL[kWin]{};
    float accR[kWin]{};
    float win[kWin]{};
    float cand[kCand]{};
    float target[kHop / 2]{};
    int phase = kHop;
    bool havePrev = false;
    bool windowReady = false;
    double prevStart = 0.0;
    double lastHopPos = 0.0;

    void reset() {
        std::fill(std::begin(accL), std::end(accL), 0.f);
        std::fill(std::begin(accR), std::end(accR), 0.f);
        phase = kHop;
        havePrev = false;
    }

    // read(pos, l, r) devolve a amostra interpolada já com o loop e o roll aplicados à posição.
    // `step` é o avanço (com sinal) da posição de análise por amostra de saída; `wrapped` diz que há loop ou roll.
    template <class Read>
    void next(Read &&read, double position, double dir, double rate, double step, bool wrapped, float &l, float &r) {
        if (!windowReady) {
            for (int i = 0; i < kWin; ++i) win[i] = 0.5f - 0.5f * std::cos(6.2831853f * static_cast<float>(i) / kWin);
            windowReady = true;
        }
        rate = std::clamp(rate, 0.25, 3.0);
        if (phase >= kHop) {
            // O que sobrou da segunda metade do grão anterior vira o começo; o resto zera.
            for (int i = 0; i < kHop; ++i) {
                accL[i] = accL[i + kHop];
                accR[i] = accR[i + kHop];
                accL[i + kHop] = 0.f;
                accR[i + kHop] = 0.f;
            }
            if (havePrev && !wrapped) {
                const double expected = lastHopPos + step * kHop;
                if (std::fabs(position - expected) > 0.5 * kHop * std::max(1.0, std::fabs(step)) + kSearch) {
                    havePrev = false;
                    std::fill(std::begin(accL), std::end(accL), 0.f);
                    std::fill(std::begin(accR), std::end(accR), 0.f);
                }
            }
            auto mono = [&](double pos) {
                float a = 0.f;
                float b = 0.f;
                read(pos, a, b);
                return 0.5f * (a + b);
            };
            int delta = 0;
            if (havePrev) {
                const int length = std::min(kCand, 2 * kSearch + static_cast<int>(kHop * rate) + 2);
                for (int m = 0; m < length; ++m) cand[m] = mono(position + dir * static_cast<double>(m - kSearch));
                for (int j = 0; j < kHop / 2; ++j) target[j] = mono(prevStart + dir * (kHop + 2 * j) * rate);
                auto score = [&](int d) {
                    double dot = 0.0;
                    double energy = 1e-9;
                    for (int j = 0; j < kHop / 2; ++j) {
                        const int at = kSearch + d + static_cast<int>(2.0 * j * rate + 0.5);
                        const float c = at >= 0 && at < length ? cand[at] : 0.f;
                        dot += static_cast<double>(target[j]) * c;
                        energy += static_cast<double>(c) * c;
                    }
                    // Leve preferência pelo centro: sem som, não vaga pela janela.
                    return dot / std::sqrt(energy) * (1.0 - 0.0004 * std::abs(d));
                };
                double best = -1e30;
                for (int d = -kSearch; d <= kSearch; d += 4) {
                    const double value = score(d);
                    if (value > best) {
                        best = value;
                        delta = d;
                    }
                }
                const int center = delta;
                for (int d = std::max(-kSearch, center - 3); d <= std::min(kSearch, center + 3); ++d) {
                    const double value = score(d);
                    if (value > best) {
                        best = value;
                        delta = d;
                    }
                }
            }
            const double start = position + dir * static_cast<double>(delta);
            // Grão de partida (início, salto de hot cue): sem subida de Hann, para não amaciar o primeiro ataque.
            const bool fresh = !havePrev;
            for (int i = 0; i < kWin; ++i) {
                float a = 0.f;
                float b = 0.f;
                read(start + dir * i * rate, a, b);
                const float gain = fresh && i < kHop ? 1.f : win[i];
                accL[i] += a * gain;
                accR[i] += b * gain;
            }
            prevStart = start;
            lastHopPos = position;
            havePrev = true;
            phase = 0;
        }
        l = sanitize(accL[phase]);
        r = sanitize(accR[phase]);
        phase += 1;
    }
};

// Reverb de oito linhas com matriz de Hadamard (FDN). Linhas moduladas devagar,
// amortecimento por linha, difusão na entrada. Comprimentos nossos, não de um aparelho.
struct Fdn {
    static constexpr int kLines = 8;
    std::vector<float> line[kLines];
    int pos[kLines]{};
    int len[kLines]{};
    float lp[kLines]{};
    float gain[kLines]{};
    float phase[kLines]{};
    float lastRt = -1.f;
    std::vector<float> diffA;
    std::vector<float> diffB;
    int diffPosA = 0;
    int diffPosB = 0;
    int sampleRate = 48000;

    void prepare(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        static const int base[kLines] = {1327, 1613, 1913, 2269, 2677, 3121, 3557, 4001};
        for (int index = 0; index < kLines; ++index) {
            len[index] = std::max(64, base[index] * sampleRate / 48000);
            line[index].assign(static_cast<size_t>(len[index] + 24), 0.f);
            pos[index] = 0;
            lp[index] = 0.f;
            phase[index] = 0.37f * static_cast<float>(index);
        }
        diffA.assign(static_cast<size_t>(std::max(16, 142 * sampleRate / 48000)), 0.f);
        diffB.assign(static_cast<size_t>(std::max(16, 107 * sampleRate / 48000)), 0.f);
        diffPosA = diffPosB = 0;
        lastRt = -1.f;
        setRt60(1.5f);
    }

    void clear() {
        for (int index = 0; index < kLines; ++index) {
            std::fill(line[index].begin(), line[index].end(), 0.f);
            lp[index] = 0.f;
        }
        std::fill(diffA.begin(), diffA.end(), 0.f);
        std::fill(diffB.begin(), diffB.end(), 0.f);
    }

    bool ready() const { return !diffA.empty(); }

    void setRt60(float seconds) {
        seconds = std::clamp(seconds, 0.15f, 12.f);
        if (std::fabs(seconds - lastRt) < 0.01f) return;
        lastRt = seconds;
        for (int index = 0; index < kLines; ++index) {
            const float exponent = -3.f * static_cast<float>(len[index]) / (static_cast<float>(sampleRate) * seconds);
            gain[index] = std::pow(10.f, exponent);
        }
    }

    static float allpass(std::vector<float> &buf, int &at, float in) {
        const float delayed = buf[static_cast<size_t>(at)];
        const float write = in + delayed * 0.6f;
        buf[static_cast<size_t>(at)] = write;
        at = (at + 1) % static_cast<int>(buf.size());
        return delayed - write * 0.6f;
    }

    // damp: 1 = sem amortecimento; 0,2 = escuro.
    void process(float inL, float inR, float damp, float &outL, float &outR) {
        if (!ready()) {
            outL = outR = 0.f;
            return;
        }
        const float mono = allpass(diffB, diffPosB, allpass(diffA, diffPosA, (inL + inR) * 0.5f));
        float x[kLines];
        float tapL = 0.f;
        float tapR = 0.f;
        const float inc = 6.2831853f * 0.45f / static_cast<float>(sampleRate);
        for (int index = 0; index < kLines; ++index) {
            phase[index] += inc * (0.6f + 0.13f * static_cast<float>(index));
            if (phase[index] > 6.2831853f) phase[index] -= 6.2831853f;
            const auto &buf = line[index];
            const int size = static_cast<int>(buf.size());
            const float back = static_cast<float>(len[index]) + 6.f * std::sin(phase[index]);
            const float at = static_cast<float>(pos[index]) - back;
            float wrapped = at;
            while (wrapped < 0.f) wrapped += static_cast<float>(size);
            const int i0 = static_cast<int>(wrapped) % size;
            const int i1 = (i0 + 1) % size;
            const float frac = wrapped - std::floor(wrapped);
            const float value = buf[static_cast<size_t>(i0)] * (1.f - frac) + buf[static_cast<size_t>(i1)] * frac;
            if (index & 1) tapR += value;
            else tapL += value;
            lp[index] += (value - lp[index]) * damp;
            flushDenorm(lp[index]);
            x[index] = lp[index] * gain[index];
        }
        // Hadamard 8x8 normalizada.
        for (int step = 1; step < kLines; step <<= 1) {
            for (int base = 0; base < kLines; base += step << 1) {
                for (int k = base; k < base + step; ++k) {
                    const float a = x[k];
                    const float b = x[k + step];
                    x[k] = a + b;
                    x[k + step] = a - b;
                }
            }
        }
        const float norm = 0.35355339f;
        for (int index = 0; index < kLines; ++index) {
            float write = x[index] * norm + ((index & 2) ? -mono : mono) * 0.5f;
            if (!std::isfinite(write) || std::fabs(write) > 8.f) write = 0.f;
            line[index][static_cast<size_t>(pos[index])] = write;
            pos[index] = (pos[index] + 1) % static_cast<int>(line[index].size());
        }
        outL = sanitize(tapL * 0.42f);
        outR = sanitize(tapR * 0.42f);
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
    Fdn verbTank;
    GrainShift spiralL;
    GrainShift spiralR;
    GrainShift pitchL;
    GrainShift pitchR;
    float freezeMix = 0.f;
    float verbHpL = 0.f;
    float verbHpR = 0.f;

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        const int pre = std::max(32, sampleRate / 40);
        preL.prepare(pre + 8);
        preR.prepare(pre + 8);
        verbTank.prepare(sampleRate);
        spiralL.reset();
        spiralR.reset();
        pitchL.reset();
        pitchR.reset();
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
        verbTank.clear();
        spiralL.reset();
        spiralR.reset();
        pitchL.reset();
        pitchR.reset();
        freezeMix = 0.f;
        verbHpL = verbHpR = 0.f;
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
                // Deslocador de grão: −1 oitava à esquerda, +1 à direita, original no centro.
                const float travel = (amount - 0.5f) * 2.f;
                const float ratio = std::pow(2.f, freqSm.next(travel, snapNow));
                const float far = std::clamp(std::fabs(travel) * 6.f, 0.f, 1.f);
                const float wet = 0.85f * far;
                wetL = dryL * (1.f - wet) + pitchL.process(dryL, ratio) * wet;
                wetR = dryR * (1.f - wet) + pitchR.process(dryR, ratio) * wet;
                break;
            }
            case 13: {
                int mode = 0;
                float freq = 1000.f;
                float mix = 0.f;
                mapFilterKnob(amount, sr, mode, freq, mix);
                // O corte anda no ciclo da fração: até ±1,3 oitava em torno do ponto do knob.
                freq *= std::pow(2.f, 1.3f * std::sin(lfo) * std::min(1.f, mix * 1.6f));
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
        float fbBoost = 0.f;
        float fb = std::min(0.82f, fbTarget) * (on ? 1.f : 0.92f);
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
            // Cada repetição volta mais aguda: deslocador de grão dentro do laço de feedback.
            const float semis = 0.5f + 3.f * amount;
            const float ratio = std::pow(2.f, semis / 12.f);
            echoL = spiralL.process(darken(fbL, left.readAt(delay)), ratio);
            echoR = spiralR.process(darken(fbR, right.readAt(delay * 1.04f)), ratio);
        } else if (effect == 11) {
            const float helix = std::max(16.f, delay * (0.62f + 0.38f * (0.5f + 0.5f * std::sin(lfo * 0.5f))));
            // Knob todo à direita trava o que está no laço: sem entrada nova, sem escurecer, feedback quase unitário.
            freezeMix += ((amount > 0.94f ? 1.f : 0.f) - freezeMix) * 0.002f;
            // Travado, o atraso deixa de oscilar: um laço de tamanho fixo não perde energia.
            const float lockedL = helix * (1.f - freezeMix) + delay * freezeMix;
            const float rawL = left.readAt(lockedL);
            const float rawR = right.readAt(std::max(8.f, lockedL * 0.5f));
            echoL = darken(fbL, rawL) * (1.f - freezeMix) + rawL * freezeMix;
            echoR = darken(fbR, rawR) * (1.f - freezeMix) + rawR * freezeMix;
            pushL = dryR * send;
            pushR = dryL * send;
            pushL *= (1.f - freezeMix);
            pushR *= (1.f - freezeMix);
            fbBoost = freezeMix;
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
        fb = fb + (0.995f - fb) * fbBoost;
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
        verbTank.setRt60(0.7f + 2.6f * amount);
        float bodyL = 0.f;
        float bodyR = 0.f;
        verbTank.process(inL, inR, 0.22f + 0.5f * amount, bodyL, bodyR);
        const float hpCoef = 1.f - std::exp(-6.2831853f * 180.f / static_cast<float>(sampleRate));
        verbHpL += (bodyL - verbHpL) * hpCoef;
        verbHpR += (bodyR - verbHpR) * hpCoef;
        bodyL -= verbHpL;
        bodyR -= verbHpR;
        const float duck = on ? tailMix * 0.35f : 0.f;
        wetL = dryL * (1.f - duck) + bodyL * tailMix;
        wetR = dryR * (1.f - duck) + bodyR * tailMix;
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
    // 0 = comportamento antigo (corta no downbeat). > 0: a cauda do eco ou da reverb continua por esses tempos.
    float tailBeats = 0.f;
    bool holding = false;
    bool releasing = false;
    bool armSnap = false;
    bool tailing = false;
    int snapLeft = -1;
    int clock = 0;
    int sampleRate = 48000;
    float frozen = 0.f;
    float relMix = 0.f;
    float tailGain = 1.f;
    float tailT = 0.f;
    float tremPhase = 0.f;
    float delaySm = 0.f;
    // Fase de batida do deck de referência. Com ela a soltura cai na batida da faixa, não no relógio interno.
    double gridBeat = 0.0;
    double prevGridBeat = 0.0;
    bool gridValid = false;
    bool prevGridValid = false;
    DelayLine left;
    DelayLine right;
    Svf hpL, hpR, lpL, lpR, thinL, thinR, loopL, loopR, noiseL, noiseR;
    GrainShift shiftL;
    GrainShift shiftR;
    Fdn tank;
    uint32_t noise = 0x1234567u;
    uint32_t noiseB = 0x9e3779b9u;

    void prepare() {
        left.prepare(96000);
        right.prepare(96000);
        tank.prepare(sampleRate);
        shiftL.reset();
        shiftR.reset();
        delaySm = 0.f;
    }

    void setHold(bool down) {
        if (down) {
            holding = true;
            releasing = false;
            armSnap = false;
            snapLeft = -1;
            tailing = false;
            tailGain = 1.f;
            tailT = 0.f;
            relMix = 0.f;
        } else if (holding || intensity > 0.f) {
            holding = false;
            releasing = intensity > 0.f;
            armSnap = releasing;
        }
    }

    void setCeiling(float value) { ceiling = std::clamp(value, 0.f, 1.f); }

    void setGrid(double beat, bool valid) {
        gridBeat = beat;
        gridValid = valid;
    }

    float pitchMul() const {
        if (scene == 6) return std::clamp(1.f - intensity, 0.f, 1.f);
        return 1.f;
    }

    bool useTail() const { return tailBeats > 0.f && scene != 1 && scene != 6; }

    void finishRelease() {
        intensity = 0.f;
        releasing = false;
        tailing = false;
        tailGain = 1.f;
        tailT = 0.f;
        relMix = 0.f;
        if (useTail()) {
            left.clear();
            right.clear();
            tank.clear();
            shiftL.reset();
            shiftR.reset();
        }
    }

    void process(float &inL, float &inR) {
        if (left.data.empty()) prepare();
        const float dryL = inL;
        const float dryR = inR;
        const float beat = static_cast<float>(samplesPerBeat(bpm, sampleRate));
        const float ramp = std::max(1.f, bars * 4.f * beat);
        const int beatN = std::max(1, static_cast<int>(beat));
        const float sr = static_cast<float>(sampleRate);
        const bool crossed = gridValid && prevGridValid && gridBeat >= prevGridBeat &&
                             std::floor(gridBeat + 1e-9) > std::floor(prevGridBeat + 1e-9);
        const bool onBeat = gridValid ? crossed : (clock % beatN) == 0;
        prevGridBeat = gridBeat;
        prevGridValid = gridValid;
        if (holding) intensity = std::min(ceiling, intensity + 1.f / ramp);
        else if (releasing) {
            if (armSnap && onBeat) {
                armSnap = false;
                snapLeft = std::max(1, static_cast<int>(0.018f * sr));
                if (useTail()) {
                    tailing = true;
                    frozen = intensity;
                    relMix = 0.f;
                    tailGain = 1.f;
                    tailT = 0.f;
                }
            }
            if (!armSnap && tailing) {
                relMix = std::min(1.f, relMix + 1.f / std::max(1.f, 0.018f * sr));
                // A cauda segue o decaimento natural do eco ou da reverb; o fade só aperta perto do fim (−40 dB em tailBeats).
                tailT += 1.f / std::max(1.f, beat);
                const float x = tailT / std::max(0.5f, tailBeats);
                tailGain = std::exp(-4.6f * x * x);
                if (x >= 1.f) finishRelease();
            } else if (!armSnap && snapLeft > 0) {
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
        const float t = tailing ? frozen : intensity;
        const float curve = std::pow(std::clamp(t, 0.f, 1.f), 1.35f);
        const float feed = tailing ? 0.f : 1.f;
        const float inputL = dryL * feed;
        const float inputR = dryR * feed;
        const int delay = std::clamp(static_cast<int>(beat), 1, 95999);
        const float dotted = std::clamp(beat * 0.75f, 1.f, 95999.f);
        // Parte seca da cena (filtrada, abaixada) e parte do efeito (retornos, ruído).
        float partL = dryL;
        float partR = dryR;
        float fxL = 0.f;
        float fxR = 0.f;
        // Nas subidas o grave da música sai de cena; é o que um DJ faz com o EQ.
        auto thin = [&](float amount) {
            const float mix = std::clamp(amount * 2.5f, 0.f, 1.f);
            const float cut = 28.f * std::pow(9.f, amount);
            thinL.set(cut, sr, 0.7f, 1);
            thinR.set(cut, sr, 0.7f, 1);
            partL = dryL * (1.f - mix) + thinL.process(dryL) * mix;
            partR = dryR * (1.f - mix) + thinR.process(dryR) * mix;
        };
        auto riseNoise = [&](float level, float centre, float &outL, float &outR) {
            noiseL.set(std::min(centre, sr * 0.42f), sr, 1.05f, 2);
            noiseR.set(std::min(centre * 1.04f, sr * 0.42f), sr, 1.05f, 2);
            outL = noiseL.process(noiseSample(noise)) * level;
            outR = noiseR.process(noiseSample(noiseB)) * level;
        };
        switch (scene) {
            case 0: {
                thin(curve * 0.8f);
                const float cut = 140.f * std::pow(48.f, curve);
                loopL.set(std::min(cut, sr * 0.4f), sr, 0.9f, 1);
                loopR.set(std::min(cut, sr * 0.4f), sr, 0.9f, 1);
                const float fb = std::min(0.84f, 0.18f + 0.62f * curve);
                const float eL = loopL.process(fbL(left.readAt(dotted)));
                const float eR = loopR.process(fbR(right.readAt(dotted)));
                // Ping-pong: a entrada vai à esquerda, cada repetição muda de lado.
                left.push((inputL + inputR) * 0.5f + eR * fb);
                right.push(eL * fb);
                float rL = 0.f;
                float rR = 0.f;
                riseNoise(curve * curve * 0.05f, 400.f * std::pow(18.f, curve), rL, rR);
                partL *= 1.f - curve * 0.3f;
                partR *= 1.f - curve * 0.3f;
                fxL = eL * (0.15f + 0.75f * curve) + rL;
                fxR = eR * (0.15f + 0.75f * curve) + rR;
                break;
            }
            case 1: {
                thin(curve * 0.9f);
                // Subida de ruído: faixa que abre em oitavas, pulso que acelera de 1/4 até 1/32.
                const float rate = std::max(0.05f, bpm / 60.f) * (1.f + 7.f * curve);
                tremPhase += static_cast<float>(kTwoPi) * rate / sr;
                if (tremPhase > static_cast<float>(kTwoPi)) tremPhase -= static_cast<float>(kTwoPi);
                const float gate = 1.f - 0.4f * curve * (0.5f + 0.5f * std::sin(tremPhase));
                const float level = (0.02f + 0.2f * std::pow(curve, 1.4f)) * gate;
                riseNoise(level, 220.f * std::pow(45.f, curve), fxL, fxR);
                partL *= 1.f - curve * 0.28f;
                partR *= 1.f - curve * 0.28f;
                break;
            }
            case 2: {
                thin(curve * 0.8f);
                // O laço encolhe e cada volta sobe um intervalo: o parafuso do SPIRAL UP.
                const float target = std::max(8.f, beat * (0.75f - 0.5f * curve));
                if (delaySm <= 0.f) delaySm = target;
                delaySm += (target - delaySm) * (1.f - std::exp(-1.f / std::max(1.f, 0.08f * sr)));
                const float ratio = std::pow(2.f, (1.5f + 5.5f * curve) / 12.f);
                const float fb = std::min(0.88f, 0.3f + 0.55f * curve);
                const float eL = shiftL.process(fbL(left.readAt(delaySm)), ratio);
                const float eR = shiftR.process(fbR(right.readAt(delaySm * 1.05f)), ratio);
                left.push(inputL + eL * fb);
                right.push(inputR + eR * fb);
                tank.setRt60(1.2f + 1.8f * curve);
                float vL = 0.f;
                float vR = 0.f;
                tank.process(eL * 0.5f, eR * 0.5f, 0.4f, vL, vR);
                partL *= 1.f - curve * 0.55f;
                partR *= 1.f - curve * 0.55f;
                fxL = eL * curve * 1.0f + vL * curve * 1.1f;
                fxR = eR * curve * 1.0f + vR * curve * 1.1f;
                break;
            }
            case 3: {
                const float cut = 90.f * std::pow(30.f, curve);
                hpL.set(std::min(cut, sr * 0.4f), sr, 0.85f, 1);
                hpR.set(std::min(cut, sr * 0.4f), sr, 0.85f, 1);
                const float highL = hpL.process(dryL);
                const float highR = hpR.process(dryR);
                partL = highL * (1.f - curve * 0.25f);
                partR = highR * (1.f - curve * 0.25f);
                tank.setRt60(1.3f + 3.2f * curve);
                float vL = 0.f;
                float vR = 0.f;
                tank.process(highL * feed, highR * feed, 0.6f, vL, vR);
                float rL = 0.f;
                float rR = 0.f;
                riseNoise(curve * curve * 0.04f, 700.f * std::pow(14.f, curve), rL, rR);
                fxL = vL * (0.25f + 1.5f * curve) + rL;
                fxR = vR * (0.25f + 1.5f * curve) + rR;
                break;
            }
            case 4: {
                const float cut = 9000.f * std::pow(0.08f, curve);
                lpL.set(std::max(80.f, cut), sr, 0.85f, 0);
                lpR.set(std::max(80.f, cut), sr, 0.85f, 0);
                partL = lpL.process(dryL) * (1.f - curve * 0.4f);
                partR = lpR.process(dryR) * (1.f - curve * 0.4f);
                tank.setRt60(1.0f + 3.4f * curve);
                float vL = 0.f;
                float vR = 0.f;
                tank.process(inputL, inputR, 0.3f, vL, vR);
                fxL = vL * (0.16f + 1.45f * curve);
                fxR = vR * (0.16f + 1.45f * curve);
                break;
            }
            case 5: {
                const float cut = 10000.f * std::pow(0.06f, curve);
                lpL.set(std::max(70.f, cut), sr, 0.9f, 0);
                lpR.set(std::max(70.f, cut), sr, 0.9f, 0);
                const float fb = std::min(0.84f, 0.16f + 0.6f * curve);
                const float eL = lpL.process(fbL(left.readAt(dotted)));
                const float eR = lpR.process(fbR(right.readAt(dotted)));
                left.push((inputL + inputR) * 0.5f + eR * fb);
                right.push(eL * fb);
                partL *= 1.f - curve * 0.55f;
                partR *= 1.f - curve * 0.55f;
                fxL = eL * curve * 1.15f;
                fxR = eR * curve * 1.15f;
                break;
            }
            case 6: {
                const float cut = 7000.f * std::pow(0.12f, curve);
                lpL.set(std::max(90.f, cut), sr, 0.8f, 0);
                lpR.set(std::max(90.f, cut), sr, 0.8f, 0);
                partL = lpL.process(dryL);
                partR = lpR.process(dryR);
                break;
            }
            default: {
                const int dub = std::max(8, (delay * 3) / 4);
                const float fb = std::min(0.86f, 0.3f + 0.5f * curve);
                const float eL = left.read(dub);
                const float eR = right.read(dub);
                lpL.set(5200.f * std::pow(0.35f, curve), sr, 0.8f, 0);
                lpR.set(5200.f * std::pow(0.35f, curve), sr, 0.8f, 0);
                left.push(lpL.process(inputL + eL * fb));
                right.push(lpR.process(inputR + eR * fb));
                partL *= 1.f - curve * 0.35f;
                partR *= 1.f - curve * 0.35f;
                fxL = eL * (0.2f + 0.7f * curve);
                fxR = eR * (0.2f + 0.7f * curve);
                break;
            }
        }
        const float rel = tailing ? relMix : 0.f;
        const float scale = tailing ? tailGain : 1.f;
        inL = sanitize(partL * (1.f - rel) + dryL * rel + fxL * scale);
        inR = sanitize(partR * (1.f - rel) + dryR * rel + fxR * scale);
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
    // Camadas de subida (ruído, tom, impacto no drop). Os testes de onset as desligam para ouvir só os golpes.
    bool risers = true;
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
    // Camadas da subida: ruído em faixa que abre, tom que sobe, grave da música que sai, bombeio por golpe, silêncio e impacto no drop.
    Svf riseL, riseR, musicHpL, musicHpR, impactHp, sendHp, returnHpL, returnHpR;
    // Espaço do roll: reverb própria dos golpes (como o roll do RMX, que passa por reverb), largura e saturação leve.
    Fdn drumTank;
    DelayLine wideLine;
    int tankHold = 0;
    uint32_t riseSeedA = 0xA5A5A5u;
    uint32_t riseSeedB = 0x5A5A5A5u;
    float risePhase = 0.f;
    float duckEnv = 0.f;
    float musicGain = 1.f;
    double curBeat = 0.0;
    int impactAge = -1;
    float impactPhase = 0.f;
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
        if (!drumTank.ready() || drumTank.sampleRate != sampleRate) {
            drumTank.prepare(sampleRate);
            wideLine.prepare(sampleRate / 20);
        }
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

    void startImpact() {
        if (build > 0.04f && risers) {
            impactAge = 0;
            impactPhase = 0.f;
            impactHp.reset();
        }
    }

    void renderGrid(float &inL, float &inR) {
        const double beat = phaseBeat;
        curBeat = beat;
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
                    startImpact();
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
            startImpact();
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
        // O bumbo sobe devagar e em curva: o começo quase não muda, o fim chega a +8 semitons com PITCH (+2 sem).
        const float semis = (drumPitch ? 8.f : 2.f) * std::pow(prog, 1.3f) * build;
        const float rate = std::pow(2.f, semis / 12.f);
        duckEnv = 1.f;
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
        float wide = 0.f;
        for (Shot &shot : shots) {
            if (!shot.on) continue;
            const float value = readVoice(shot.id, shot.cursor) * shot.vel;
            if (shot.id == 1 || shot.id == 2) wide += value;
            else drum += value;
            shot.cursor += step * static_cast<double>(shot.rate);
            const auto &pcm = drumBank().voice[shot.id];
            if (drumBank().empty(shot.id) || shot.cursor >= static_cast<double>(pcm.size()) - 1.0) shot.on = false;
        }
        if (std::fabs(drum) > 1e-6f) {
            // O passa-altas só afina o bumbo no fim do gesto; o corpo continua (até 25%).
            const float cut = 40.f * std::pow(3.5f, std::clamp(prog, 0.f, 1.f) * build);
            const float mix = 0.25f * std::clamp(prog, 0.f, 1.f) * build;
            drumHp.set(cut, static_cast<float>(sampleRate), 0.7f, 1);
            const float high = drumHp.process(drum);
            drum = drum * (1.f - mix) + high * mix;
        } else if (prog < 0.001f) {
            drumHp.reset();
        }
        float busL = drum + wide;
        float busR = drum + wide;
        if (risers && sampleRate > 0 && wideLine.data.size() > 8) {
            // Palma e caixa abrem no estéreo (Haas de 9 ms); bumbo e chimbal ficam no centro.
            const int haas = std::max(1, sampleRate * 9 / 1000);
            const float late = wideLine.read(haas);
            wideLine.push(wide);
            busR = drum + wide * 0.35f + late * 0.75f;
            busL = drum + wide * 0.85f;
            // Saturação suave: cola e dá corpo sem mudar o nível dos golpes fracos.
            busL = std::tanh(1.5f * busL) / 1.5f * 1.18f;
            busR = std::tanh(1.5f * busR) / 1.5f * 1.18f;
        }
        const float p0 = std::clamp(prog, 0.f, 1.f);
        if (risers && drumTank.ready() && build > 0.04f) {
            const float srf = static_cast<float>(sampleRate);
            if (std::fabs(drum) + std::fabs(wide) > 1e-5f) tankHold = sampleRate * 4;
            if (tankHold > 0) {
                tankHold -= 1;
                sendHp.set(220.f, srf, 0.7f, 1);
                const float send = sendHp.process((busL + busR) * 0.5f);
                drumTank.setRt60(1.1f + 1.4f * p0);
                float vL = 0.f;
                float vR = 0.f;
                drumTank.process(send, send, 0.5f, vL, vR);
                returnHpL.set(140.f, srf, 0.7f, 1);
                returnHpR.set(140.f, srf, 0.7f, 1);
                const float wet = (0.28f + 0.5f * p0) * build;
                busL += returnHpL.process(vL) * wet;
                busR += returnHpR.process(vR) * wet;
            }
        }
        const float sr = static_cast<float>(sampleRate > 0 ? sampleRate : 48000);
        const float p = std::clamp(prog, 0.f, 1.f);
        float extraL = 0.f;
        float extraR = 0.f;
        float musL = inL;
        float musR = inR;
        if (live && build > 0.04f && risers) {
            const float centre = 300.f * std::pow(28.f, p);
            riseL.set(std::min(centre, sr * 0.42f), sr, 1.0f, 2);
            riseR.set(std::min(centre * 1.03f, sr * 0.42f), sr, 1.0f, 2);
            const float noiseLevel = (0.015f + 0.16f * p * p) * build;
            extraL = riseL.process(noiseSample(riseSeedA)) * noiseLevel;
            extraR = riseR.process(noiseSample(riseSeedB)) * noiseLevel;
            const float freq = 110.f * std::pow(17.f, std::pow(p, 1.15f));
            risePhase += static_cast<float>(kTwoPi) * freq / sr;
            if (risePhase > static_cast<float>(kTwoPi)) risePhase -= static_cast<float>(kTwoPi);
            const float tone = (std::sin(risePhase) + 0.35f * std::sin(2.f * risePhase + 0.6f)) * (0.05f * p * p * build);
            extraL += tone;
            extraR += tone * 0.9f;
            const float cut = 30.f * std::pow(9.f, p * build);
            musicHpL.set(cut, sr, 0.7f, 1);
            musicHpR.set(cut, sr, 0.7f, 1);
            const float thin = std::min(1.f, p * 2.5f);
            musL = inL * (1.f - thin) + musicHpL.process(inL) * thin;
            musR = inR * (1.f - thin) + musicHpR.process(inR) * thin;
        } else {
            musicHpL.reset();
            musicHpR.reset();
            riseL.reset();
            riseR.reset();
        }
        duckEnv *= std::exp(-1.f / (0.12f * sr));
        if (duckEnv < 1e-4f) duckEnv = 0.f;
        const float depth = (live && build > 0.04f && risers) ? (0.10f + 0.38f * p * build) : 0.f;
        float gainTarget = 1.f - depth * duckEnv;
        // Último meio tempo antes do drop: a música some e só o ruído e o tom seguram a tensão.
        if (live && risers && drumGap && curBeat >= endBeat - 0.5) gainTarget = 0.03f;
        const float coef = 1.f - std::exp(-1.f / ((gainTarget < musicGain ? 0.004f : 0.003f) * sr));
        musicGain += (gainTarget - musicGain) * coef;
        flushDenorm(musicGain);
        if (impactAge >= 0) {
            const float t = static_cast<float>(impactAge) / sr;
            const float freq = 38.f + 42.f * std::exp(-t * 9.f);
            impactPhase += static_cast<float>(kTwoPi) * freq / sr;
            if (impactPhase > static_cast<float>(kTwoPi)) impactPhase -= static_cast<float>(kTwoPi);
            const float level = 0.5f + 0.5f * build;
            const float boom = std::sin(impactPhase) * std::exp(-t * 3.2f) * 0.38f * level;
            impactHp.set(5200.f, sr, 0.8f, 1);
            const float crash = impactHp.process(noiseSample(riseSeedA)) * std::exp(-t * 2.6f) * 0.12f * level;
            extraL += boom + crash;
            extraR += boom + crash;
            impactAge += 1;
            if (t > 1.8f) impactAge = -1;
        }
        inL = sanitize(musL * musicGain + busL + extraL);
        inR = sanitize(musR * musicGain + busR + extraR);
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

// Seis cores do Sound Color FX por canal: SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER.
// O centro do knob deixa o som original em todas. As curvas são nossas; o manual não as publica.
enum ColorKind { kColorSpace = 0, kColorDubEcho = 1, kColorSweep = 2, kColorNoise = 3, kColorCrush = 4, kColorFilter = 5 };

struct ColorFilter {
    float norm = 0.5f;
    int sampleRate = 48000;
    bool snap = true;
    int kind = kColorFilter;
    float bpm = 120.f;
    bool ready = false;
    ParamSmooth freqSm;
    ParamSmooth mixSm;
    ParamSmooth travelSm;
    Svf left;
    Svf right;
    Svf lowL, lowR, highL, highR;
    int mode = 0;
    Fdn tank;
    DelayLine dubL;
    DelayLine dubR;
    float dubDelay = 0.f;
    float dubWet = 0.f;
    float dubSend = 0.f;
    float dubFb = 0.4f;
    uint32_t seedA = 0x2468ACEu;
    uint32_t seedB = 0x13579BDu;
    float holdL = 0.f;
    float holdR = 0.f;
    float holdCount = 0.f;

    void prepare(int rate) {
        sampleRate = rate > 0 ? rate : 48000;
        tank.prepare(sampleRate);
        dubL.prepare(sampleRate * 2);
        dubR.prepare(sampleRate * 2);
        travelSm.setMs(30.f, sampleRate);
        clearKind();
        ready = true;
    }

    void clearKind() {
        tank.clear();
        dubL.clear();
        dubR.clear();
        dubDelay = 0.f;
        dubWet = 0.f;
        dubSend = 0.f;
        holdL = holdR = holdCount = 0.f;
        lowL.reset();
        lowR.reset();
        highL.reset();
        highR.reset();
        left.reset();
        right.reset();
    }

    void setKind(int next) {
        next = std::clamp(next, 0, 5);
        if (next == kind) return;
        kind = next;
        if (ready) clearKind();
        snap = true;
    }

    void set(float value, int rate) {
        const float next = std::clamp(value, 0.f, 1.f);
        if (std::fabs(next - norm) > 0.2f) snap = true;
        norm = next;
        const int nextRate = rate > 0 ? rate : 48000;
        if (nextRate != sampleRate || freqSm.coef == 1.f) {
            sampleRate = nextRate;
            freqSm.setMs(32.f, sampleRate);
            mixSm.setMs(28.f, sampleRate);
            travelSm.setMs(30.f, sampleRate);
        }
        sampleRate = nextRate;
    }

    void process(float &inL, float &inR) {
        if (kind == kColorFilter) {
            processFilter(inL, inR);
            return;
        }
        if (!ready) return;
        // Deslocamento com sinal: esquerda negativa, direita positiva, zona morta no centro.
        float target = 0.f;
        if (norm <= 0.47f) target = -(0.47f - norm) / 0.47f;
        else if (norm >= 0.53f) target = (norm - 0.53f) / 0.47f;
        const float signedTravel = travelSm.next(target, !travelSm.primed);
        const float mag = std::clamp(std::fabs(signedTravel), 0.f, 1.f);
        const float lean = signedTravel;
        const float sr = static_cast<float>(sampleRate);
        switch (kind) {
            case kColorSpace: spaceFx(inL, inR, mag, lean, sr); break;
            case kColorDubEcho: dubFx(inL, inR, mag, lean, sr); break;
            case kColorSweep: sweepFx(inL, inR, mag, sr); break;
            case kColorNoise: noiseFx(inL, inR, mag, lean, sr); break;
            default: crushFx(inL, inR, mag, lean, sr); break;
        }
        snap = false;
        inL = sanitize(inL);
        inR = sanitize(inR);
    }

private:
    void processFilter(float &inL, float &inR) {
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

    // SPACE: reverb longa. Esquerda escurece o retorno, direita tira o grave dele.
    void spaceFx(float &inL, float &inR, float mag, float lean, float sr) {
        if (mag < 0.002f && tank.lastRt > 0.f && dubWet < 1e-5f) return;
        tank.setRt60(1.0f + 3.4f * mag);
        float vL = 0.f;
        float vR = 0.f;
        const float send = mag > 0.004f ? 1.f : 0.f;
        tank.process(inL * send, inR * send, 0.55f, vL, vR);
        const float lowCut = 14000.f * std::pow(0.1f, std::max(0.f, -lean));
        const float highCut = 40.f * std::pow(14.f, std::max(0.f, lean));
        lowL.set(lowCut, sr, 0.7f, 0);
        lowR.set(lowCut, sr, 0.7f, 0);
        highL.set(highCut, sr, 0.7f, 1);
        highR.set(highCut, sr, 0.7f, 1);
        vL = highL.process(lowL.process(vL));
        vR = highR.process(lowR.process(vR));
        const float targetWet = std::pow(mag, 0.8f) * 1.5f;
        // A cauda sobe depressa e desce devagar quando o knob volta ao centro.
        if (targetWet > dubWet) dubWet += (targetWet - dubWet) * (1.f - std::exp(-1.f / (0.03f * sr)));
        else dubWet += (targetWet - dubWet) * (1.f - std::exp(-1.f / (0.9f * sr)));
        flushDenorm(dubWet);
        inL = inL * (1.f - 0.12f * std::min(1.f, dubWet)) + vL * dubWet;
        inR = inR * (1.f - 0.12f * std::min(1.f, dubWet)) + vR * dubWet;
    }

    // DUB ECHO: 3/4 de tempo. Esquerda escurece as repetições, direita as afina.
    void dubFx(float &inL, float &inR, float mag, float lean, float sr) {
        const float beat = static_cast<float>(samplesPerBeat(bpm, sampleRate));
        const float want = std::clamp(beat * 0.75f, 16.f, static_cast<float>(sampleRate) * 2.f - 8.f);
        if (dubDelay <= 0.f) dubDelay = want;
        dubDelay += (want - dubDelay) * (1.f - std::exp(-1.f / (0.15f * sr)));
        const bool active = mag > 0.004f;
        if (active) dubFb = 0.3f + 0.5f * mag;
        dubSend += ((active ? 1.f : 0.f) - dubSend) * (1.f - std::exp(-1.f / (0.01f * sr)));
        const float targetWet = std::pow(mag, 0.7f) * 1.0f;
        if (targetWet > dubWet) dubWet += (targetWet - dubWet) * (1.f - std::exp(-1.f / (0.02f * sr)));
        else dubWet += (targetWet - dubWet) * (1.f - std::exp(-1.f / (1.1f * sr)));
        flushDenorm(dubWet);
        const float lowCut = 9000.f * std::pow(0.18f, std::max(0.f, -lean));
        const float highCut = 80.f * std::pow(9.f, std::max(0.f, lean));
        lowL.set(lowCut, sr, 0.7f, 0);
        lowR.set(lowCut, sr, 0.7f, 0);
        highL.set(highCut, sr, 0.7f, 1);
        highR.set(highCut, sr, 0.7f, 1);
        const float eL = highL.process(lowL.process(dubL.readAt(dubDelay)));
        const float eR = highR.process(lowR.process(dubR.readAt(dubDelay * 1.01f)));
        dubL.push(inL * dubSend + eL * dubFb);
        dubR.push(inR * dubSend + eR * dubFb);
        inL += eL * dubWet;
        inR += eR * dubWet;
    }

    // SWEEP: passa-faixa ressonante que anda com o knob inteiro, de 90 Hz a 12 kHz.
    void sweepFx(float &inL, float &inR, float mag, float sr) {
        const float depth = std::clamp(mag * 3.2f, 0.f, 1.f);
        const float freq = freqSm.next(90.f * std::pow(130.f, norm), !freqSm.primed);
        const float amount = mixSm.next(depth, !mixSm.primed);
        if (amount < 0.001f) return;
        left.set(freq, sr, 1.8f, 2);
        right.set(freq, sr, 1.8f, 2);
        const float bandL = left.process(inL) * 0.8f;
        const float bandR = right.process(inR) * 0.8f;
        inL = inL * (1.f - amount * 0.88f) + bandL * amount * 1.15f;
        inR = inR * (1.f - amount * 0.88f) + bandR * amount * 1.15f;
    }

    // NOISE: ruído em faixa por cima da música. Esquerda é um sopro grave, direita um chiado agudo.
    void noiseFx(float &inL, float &inR, float mag, float lean, float sr) {
        const float centre = lean >= 0.f ? 1200.f * std::pow(8.f, mag) : 900.f * std::pow(0.3f, mag);
        left.set(std::min(centre, sr * 0.42f), sr, 0.9f, 2);
        right.set(std::min(centre * 1.03f, sr * 0.42f), sr, 0.9f, 2);
        const float level = std::pow(mag, 1.2f) * 0.3f;
        const float nL = left.process(noiseSample(seedA)) * level;
        const float nR = right.process(noiseSample(seedB)) * level;
        const float keep = 1.f - 0.25f * mag;
        inL = inL * keep + nL;
        inR = inR * keep + nR;
    }

    // CRUSH: menos bits nos dois lados, mais redução de taxa à direita.
    void crushFx(float &inL, float &inR, float mag, float lean, float sr) {
        const float mix = std::clamp(mag * 4.f, 0.f, 1.f) * 0.9f;
        if (mix < 0.001f) {
            holdCount = 0.f;
            return;
        }
        const float bits = 16.f - 13.f * std::pow(mag, 0.8f);
        const float levels = std::pow(2.f, bits - 1.f);
        const float hold = 1.f + (lean > 0.f ? 14.f * std::pow(mag, 1.5f) : 4.f * mag);
        holdCount += 1.f;
        if (holdCount >= hold) {
            holdCount -= hold;
            holdL = std::floor(inL * levels + 0.5f) / levels;
            holdR = std::floor(inR * levels + 0.5f) / levels;
        }
        lowL.set(11000.f, sr, 0.7f, 0);
        lowR.set(11000.f, sr, 0.7f, 0);
        const float cL = lowL.process(holdL);
        const float cR = lowR.process(holdR);
        inL = inL * (1.f - mix) + cL * mix;
        inR = inR * (1.f - mix) + cR * mix;
    }
};

// Knob do EQ de canal (0..1) para ganho linear: esquerda toda = kill, centro = 1, direita toda = +6 dB.
// Curva nossa, no estilo isolador; o manual da DDJ-400 não publica a curva.
inline float eqGainFromKnob(float knob) {
    const float k = std::clamp(knob, 0.f, 1.f);
    if (k <= 0.03f) return 0.f;
    if (k < 0.5f) return std::pow((k - 0.03f) / 0.47f, 1.6f);
    return std::pow(10.f, (k - 0.5f) * 2.f * 6.f / 20.f);
}

struct MixerFx {
    BeatInsert beat;
    SceneFx scene;
    XPad xpad;
    Isolator iso[2];
    Isolator eq[2];
    ReleaseFx release;
    ColorFilter color[2];
    bool rmx = false;
    int channel = 0;
    int sampleRate = 48000;
    bool rollDeck[2]{};
    bool slipDeck[2]{};
    double rollOrigin[2]{-1.0, -1.0};
    double rollBeats = 0.25;
    float rollMix = 1.f;
    int drumDeck = 0;
    float tempo = 120.f;

    void setSampleRate(int rate) {
        const int next = rate > 0 ? rate : 48000;
        const bool changed = next != sampleRate || !color[0].ready;
        sampleRate = next;
        xpad.sampleRate = sampleRate;
        iso[0].prepare(sampleRate);
        iso[1].prepare(sampleRate);
        if (changed) {
            eq[0].prepare(sampleRate);
            eq[1].prepare(sampleRate);
            for (auto &item : color) item.prepare(sampleRate);
        }
    }

    // EQ de canal de 3 bandas (LOW, MID, HI), knobs 0..1. Roda antes do Color FX, como num mixer.
    void setEq(int deck, float low, float mid, float high) {
        if (deck < 0 || deck > 1) return;
        eq[deck].set(eqGainFromKnob(low), eqGainFromKnob(mid), eqGainFromKnob(high), false);
    }

    void setColorKind(int deck, int kind) {
        if (deck < 0 || deck > 1) return;
        color[deck].setKind(kind);
    }

    void setDrumPhase(double beat, bool valid, bool follow) {
        xpad.followGrid = follow;
        xpad.setPhase(beat, valid);
        scene.setGrid(beat, valid && follow);
    }

    void setBeat(int effect, bool on, float level, int nextChannel, float bpm, float beats) {
        channel = std::clamp(nextChannel, 0, 2);
        noteTempo(bpm);
        beat.configure(effect, on && !rmx, level, bpm, beats, sampleRate);
        const bool transport = on && !rmx && (effect == 9 || effect == 10 || effect == 14);
        rollBeats = beats;
        rollMix = rollMixFromLevel(level);
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
        noteTempo(bpm);
        rmx = armed;
        scene.tailBeats = 8.f;
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

    void noteTempo(float bpm) {
        if (!(bpm > 1.f)) return;
        tempo = bpm;
        color[0].bpm = bpm;
        color[1].bpm = bpm;
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
        eq[deck].process(left, right);
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
