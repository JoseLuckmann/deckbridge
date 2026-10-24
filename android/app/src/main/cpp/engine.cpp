#include "dsp.h"
#include "drum_load.h"
#include "record.h"

#include <oboe/Oboe.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

// Tudo que chega do Kotlin passa por aqui: um NaN ou um valor enorme que viesse de uma conta errada de lá entraria nos
// filtros e nos atrasos e deixaria o áudio morto (ou com estouro de índice) até reiniciar o app. Não finito vira o
// padrão; o resto é preso à faixa útil.
// Guarda de saída: remove o não finito sem cortar o sinal (quem limita é o limiter, não esta função).
inline float safeOut(float x) { return std::isfinite(x) ? std::clamp(x, -16.f, 16.f) : 0.f; }
inline float fin(float v, float lo, float hi, float fallback) { return std::isfinite(v) ? std::clamp(v, lo, hi) : fallback; }
inline double finD(double v, double lo, double hi, double fallback) { return std::isfinite(v) ? std::clamp(v, lo, hi) : fallback; }

constexpr double kHz[4] = {440.0, 554.0, 659.0, 880.0};
constexpr float kAmplitude = 0.2f;
constexpr int kMaxHot = 8;

const char *sharingName(oboe::SharingMode mode) {
    switch (mode) {
        case oboe::SharingMode::Exclusive:
            return "Exclusive";
        case oboe::SharingMode::Shared:
            return "Shared";
        default:
            return "UNKNOWN";
    }
}

const char *performanceName(oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::LowLatency:
            return "LowLatency";
        case oboe::PerformanceMode::PowerSaving:
            return "PowerSaving";
        case oboe::PerformanceMode::None:
            return "None";
        default:
            return "UNKNOWN";
    }
}

struct Deck {
    std::vector<float> left;
    std::vector<float> right;
    int sampleRate = 48000;
    double position = 0.0;
    double pitch = 1.0;
    float volume = 1.f;
    bool playing = false;
    bool pfl = false;
    double cue = 0.0;
    double hot[kMaxHot]{};
    float bpm = 0.f;
    double gridOriginSec = 0.0;
    double gridBpm = 0.0;
    double lastEmitted = 0.0;
    double loopStart = -1.0;
    double loopEnd = -1.0;
    double rateMul = 1.0;
    double rollOrigin = -1.0;
    double rollLength = -1.0;
    double slip = -1.0;
    // Posição "ao vivo" durante o Roll, para o balanço original/roll; rollMix 1 = só o roll.
    double shadow = -1.0;
    float rollMix = 1.f;
    bool keyLock = false;
    int keyShift = 0;
    bool reversed = false;
    bool userSlip = false;
    double slipAnchor = -1.0;
    bool holdingCue = false;
    deckbridge::Wsola stretch;
    deckbridge::ScratchFollower scratch;
    deckbridge::MotorGain motorGain;
    double expectedPos = -1.0;
    int shiftCached = 999;
    double shiftFactor = 1.0;

    Deck() {
        for (double &item : hot) item = -1.0;
    }

    void wrapLoop() {
        position = deckbridge::wrapReverseLoop(position, loopStart, loopEnd, reversed);
    }

    void read(int outputRate, float &leftOut, float &rightOut) {
        leftOut = 0.f;
        rightOut = 0.f;
        if ((!playing && !scratch.driving()) || left.empty() || sampleRate <= 0 || outputRate <= 0) return;
        if (position < 0.0) position = 0.0;
        wrapLoop();
        if (scratch.driving()) {
            // Prato tocado ou solto girando (backspin): leitura direta na posição que o prato manda, sem Master Tempo.
            const int total = static_cast<int>(left.size());
            leftOut = deckbridge::readInterp(left.data(), total, position);
            rightOut = deckbridge::readInterp(right.data(), total, position);
            const double unit = rateMul * static_cast<double>(sampleRate) / static_cast<double>(outputRate);
            const double normal = playing ? deckbridge::directedStep(pitch, rateMul, sampleRate, outputRate, reversed) : 0.0;
            const double moved = scratch.step(outputRate, normal, unit);
            const float motor = motorGain.next(moved / (static_cast<double>(sampleRate) / outputRate), outputRate);
            leftOut *= motor;
            rightOut *= motor;
            position = std::min(std::max(0.0, position + moved), static_cast<double>(total - 2));
            lastEmitted = position;
            expectedPos = position;
            stretch.reset();
            return;
        }
        const int frames = static_cast<int>(left.size());
        const int index = static_cast<int>(position);
        if (index >= frames) {
            playing = false;
            position = 0.0;
            return;
        }
        lastEmitted = position;
        // Salto vindo de fora (seek, hot cue, deck pausado e movido): o grão em andamento não vale mais.
        if (expectedPos >= 0.0 && std::fabs(position - expectedPos) > 4096.0) stretch.reset();
        // Master Tempo e Key Shift: WSOLA lê o grão direto da faixa (tom pela taxa de leitura, tempo pela posição).
        // Sem nenhum dos dois, ou com o tempo a menos de 1% do original sob Master Tempo, a leitura é direta.
        const bool lockActive = keyLock && pitch > 0.05 && std::abs(pitch - 1.0) > 0.01;
        if (lockActive || keyShift != 0) {
            const double convert = rateMul * static_cast<double>(sampleRate) / static_cast<double>(outputRate);
            const double base = lockActive ? 1.0 : std::max(0.05, pitch);
            if (shiftCached != keyShift) {
                shiftCached = keyShift;
                shiftFactor = std::pow(2.0, static_cast<double>(keyShift) / 12.0);
            }
            const double grainRate = base * shiftFactor * convert;
            const double dir = reversed ? -1.0 : 1.0;
            const bool wrapped = loopEnd > loopStart || (rollLength > 1.0 && rollOrigin >= 0.0);
            auto readSource = [&](double at, float &a, float &b) {
                if (rollLength > 1.0 && rollOrigin >= 0.0) {
                    const double end = rollOrigin + rollLength;
                    if (at >= end) at = rollOrigin + std::fmod(at - rollOrigin, rollLength);
                } else if (loopEnd > loopStart) {
                    const double length = loopEnd - loopStart;
                    if (!reversed && at >= loopEnd) at = loopStart + std::fmod(at - loopStart, length);
                    else if (reversed && at < loopStart) at = loopEnd - std::fmod(loopStart - at, length);
                }
                a = deckbridge::readPerformance(left.data(), frames, at, loopStart, loopEnd, rollOrigin, rollLength, deckbridge::kFadeSamples);
                b = deckbridge::readPerformance(right.data(), frames, at, loopStart, loopEnd, rollOrigin, rollLength, deckbridge::kFadeSamples);
            };
            const double analysis = deckbridge::directedStep(pitch, rateMul, sampleRate, outputRate, reversed);
            stretch.next(readSource, position, dir, grainRate, analysis, wrapped, leftOut, rightOut);
        } else {
            leftOut = deckbridge::readPerformance(left.data(), frames, position, loopStart, loopEnd, rollOrigin, rollLength,
                                                 deckbridge::kFadeSamples);
            rightOut = deckbridge::readPerformance(right.data(), frames, position, loopStart, loopEnd, rollOrigin, rollLength,
                                                  deckbridge::kFadeSamples);
            // Ao voltar para a leitura direta, o próximo grão começa limpo.
            if (stretch.havePrev || stretch.phase < deckbridge::Wsola::kHop) stretch.reset();
        }
        const double step = deckbridge::directedStep(pitch, rateMul, sampleRate, outputRate, reversed);
        const float motor = motorGain.next(step / (static_cast<double>(sampleRate) / outputRate), outputRate);
        leftOut *= motor;
        rightOut *= motor;
        if (rollLength > 1.0 && rollOrigin >= 0.0 && shadow >= 0.0) {
            if (rollMix < 0.999f && shadow < frames - 1) {
                leftOut = deckbridge::rollBalance(leftOut, deckbridge::readInterp(left.data(), frames, shadow), rollMix);
                rightOut = deckbridge::rollBalance(rightOut, deckbridge::readInterp(right.data(), frames, shadow), rollMix);
            }
            shadow += step;
        }
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
        expectedPos = position;
    }
};

class ToneEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    std::string start(int deviceId, int route) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
        wantOpen_ = true;
        lastDevice_ = deviceId;
        lastRoute_ = route;
        route_.store(route);
        phonesSent_ = false;
        fallbackToTwo_ = false;
        error_.clear();
        for (double &item : phase_) item = 0.0;

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Exclusive);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(4);
        builder.setSampleRate(48000);
        if (bufferFrames_ >= 64) builder.setBufferCapacityInFrames(bufferFrames_);
        builder.setUsage(oboe::Usage::Game);
        builder.setContentType(oboe::ContentType::Music);
        builder.setDataCallback(this);
        builder.setErrorCallback(this);
        if (deviceId > 0) builder.setDeviceId(deviceId);

        oboe::Result result = builder.openStream(stream_);
        if (result != oboe::Result::OK) {
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(stream_);
        }
        if (result != oboe::Result::OK) {
            builder.setChannelCount(2);
            builder.setSharingMode(oboe::SharingMode::Exclusive);
            result = builder.openStream(stream_);
            if (result != oboe::Result::OK) {
                builder.setSharingMode(oboe::SharingMode::Shared);
                result = builder.openStream(stream_);
            }
            if (result == oboe::Result::OK) fallbackToTwo_ = true;
        }
        if (result != oboe::Result::OK || stream_ == nullptr) {
            error_ = oboe::convertToText(result);
            stream_.reset();
            opened_.store(false);
            return statsLocked();
        }

        openedChannels_.store(stream_->getChannelCount());
        sampleRate_ = stream_->getSampleRate();
        if (route == 2 && stream_->getChannelCount() < 4) {
            phonesSent_ = false;
            route_.store(0);
        } else if (route == 2) {
            phonesSent_ = true;
        } else if (route == 1 || route == 3) {
            phonesSent_ = false;
        }
        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            error_ = oboe::convertToText(result);
            stream_->close();
            stream_.reset();
            opened_.store(false);
            return statsLocked();
        }
        opened_.store(true);
        return statsLocked();
    }

    void stop() {
        recorder_.stop();
        std::lock_guard<std::mutex> lock(mutex_);
        wantOpen_ = false;
        route_.store(0);
        closeLocked();
    }

    bool startRecord(const std::string &path, bool side) {
        recorder_.setSide(side);
        return recorder_.start(path, liveRate_.load());
    }

    void stopRecord() { recorder_.stop(); }

    std::string pollRecord() { return recorder_.poll(); }

    int pullRecord(float *dst, int max) { return recorder_.pullSide(dst, max); }

    std::string stats() {
        std::lock_guard<std::mutex> lock(mutex_);
        return statsLocked();
    }

    void loadDeck(int deck, const float *data, int count, int channels, int rate, float bpm, float cueSeconds,
                  const float *hot, int hotCount, float loopStartSec, float loopEndSec) {
        if (deck < 0 || deck > 1 || data == nullptr) return;
        const int ch = channels == 2 ? 2 : 1;
        const int frames = std::max(0, ch == 2 ? count / 2 : count);
        std::vector<float> newLeft(static_cast<size_t>(frames));
        std::vector<float> newRight(static_cast<size_t>(frames));
        for (int index = 0; index < frames; ++index) {
            if (ch == 2) {
                newLeft[index] = data[index * 2];
                newRight[index] = data[index * 2 + 1];
            } else {
                newLeft[index] = data[index];
                newRight[index] = data[index];
            }
        }
        installDeck(deck, std::move(newLeft), std::move(newRight), rate, bpm, cueSeconds, hot, hotCount, loopStartSec, loopEndSec);
    }

    // A cópia de dezenas de MB acontece FORA do mutex do callback de áudio; dentro dele só se troca o vetor.
    // Antes, carregar uma faixa travava o áudio do outro deck pelo tempo da cópia.
    void installDeck(int deck, std::vector<float> newLeft, std::vector<float> newRight, int rate, float bpm, float cueSeconds,
                     const float *hot, int hotCount, float loopStartSec, float loopEndSec) {
        if (deck < 0 || deck > 1) return;
        std::vector<float> oldLeft;
        std::vector<float> oldRight;
        {
            std::lock_guard<std::mutex> lock(mixMutex_);
            Deck &swapTarget = decks_[deck];
            // Para a leitura antes da troca: o callback nunca vê uma posição de uma faixa na outra.
            swapTarget.playing = false;
            oldLeft.swap(swapTarget.left);
            oldRight.swap(swapTarget.right);
            swapTarget.left.swap(newLeft);
            swapTarget.right.swap(newRight);
        }
        // oldLeft/oldRight liberam a memória da faixa antiga aqui, fora do lock.
        std::lock_guard<std::mutex> lock(mixMutex_);
        Deck &target = decks_[deck];
        target.sampleRate = rate > 0 ? std::min(rate, 384000) : 48000;
        target.bpm = fin(bpm, 0.f, 400.f, 0.f);
        target.position = 0.0;
        target.playing = false;
        target.reversed = false;
        target.cue = finD(static_cast<double>(cueSeconds), 0.0, 36000.0, 0.0) * target.sampleRate;
        const double start = finD(static_cast<double>(loopStartSec), -1.0, 36000.0, -1.0) * target.sampleRate;
        const double end = finD(static_cast<double>(loopEndSec), -1.0, 36000.0, -1.0) * target.sampleRate;
        if (end > start && start >= 0.0) {
            target.loopStart = start;
            target.loopEnd = end;
        } else {
            target.loopStart = -1.0;
            target.loopEnd = -1.0;
        }
        for (double &item : target.hot) item = -1.0;
        int limit = std::min(hotCount, kMaxHot);
        for (int index = 0; index < limit; ++index) {
            if (hot != nullptr && std::isfinite(hot[index]) && hot[index] >= 0.f && hot[index] < 36000.f) {
                target.hot[index] = static_cast<double>(hot[index]) * target.sampleRate;
            }
        }
    }

    void transport(int deck, bool playing) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        Deck &target = decks_[deck];
        if (playing && target.userSlip && !target.playing && target.slipAnchor >= 0.0) {
            target.position = clampFrame(target, target.slipAnchor);
            target.holdingCue = false;
        }
        target.playing = playing && !target.left.empty();
    }

    void cue(int deck) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].playing = false;
        decks_[deck].position = decks_[deck].cue;
    }

    void hotCue(int deck, int index) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || index < 0 || index >= kMaxHot) return;
        Deck &target = decks_[deck];
        if (target.hot[index] < 0.0) return;
        target.position = target.hot[index];
        if (target.userSlip) target.holdingCue = true;
    }

    void releaseHotCue(int deck) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        Deck &target = decks_[deck];
        if (target.userSlip && target.holdingCue && target.slipAnchor >= 0.0) {
            target.position = clampFrame(target, target.slipAnchor);
        }
        target.holdingCue = false;
    }

    void setMix(float volA, float volB, float pitchA, float pitchB, float cross, bool pflA, bool pflB) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        decks_[0].volume = fin(volA, 0.f, 1.f, 0.f);
        decks_[1].volume = fin(volB, 0.f, 1.f, 0.f);
        decks_[0].pitch = finD(static_cast<double>(pitchA), 0.5, 2.0, 1.0);
        decks_[1].pitch = finD(static_cast<double>(pitchB), 0.5, 2.0, 1.0);
        cross_ = fin(cross, 0.f, 1.f, 0.5f);
        decks_[0].pfl = pflA;
        decks_[1].pfl = pflB;
    }

    void setTrim(int deck, float linear) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        trim_[deck] = fin(linear, 0.05f, 4.f, 1.f);
    }

    void seekSeconds(int deck, double seconds) {
        if (!std::isfinite(seconds)) return;
        std::lock_guard<std::mutex> lock(mixMutex_);
        placeSeconds(deck, seconds, false);
    }

    void scratchTouch(int deck, bool on) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].scratch.touch(on);
    }

    void scratchMove(int deck, double seconds) {
        if (!std::isfinite(seconds)) return;
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return;
        // Um passo de prato nunca passa de 2 s de faixa; mais que isso é lixo.
        decks_[deck].scratch.move(finD(seconds, -2.0, 2.0, 0.0) * decks_[deck].sampleRate);
    }

    void nudge(int deck, double seconds) {
        if (!std::isfinite(seconds)) return;
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return;
        // Prato solto ainda girando depois do backspin: o giro já é a inércia, não um empurrão.
        if (decks_[deck].scratch.coasting) return;
        if (seconds == 0.0 && decks_[deck].userSlip && decks_[deck].slipAnchor >= 0.0) {
            decks_[deck].position = clampFrame(decks_[deck], decks_[deck].slipAnchor);
            return;
        }
        const double now = decks_[deck].position / decks_[deck].sampleRate;
        placeSeconds(deck, now + seconds, true);
    }

    void setBeatFx(int effect, bool on, int level, int channel, float bpm, float beats) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setSampleRate(rateForFx());
        mixFx_.setBeat(std::clamp(effect, 0, 64), on, std::clamp(level / 127.f, 0.f, 1.f), channel, fin(bpm, 20.f, 400.f, 120.f),
                       fin(beats, 0.0625f, 64.f, 1.f));
    }

    void setFilter(int deck, float norm) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setSampleRate(rateForFx());
        mixFx_.setFilter(deck, fin(norm, 0.f, 1.f, 0.5f));
    }

    void setEq(int deck, float low, float mid, float high) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setSampleRate(rateForFx());
        mixFx_.setEq(deck, fin(low, 0.f, 1.f, 0.5f), fin(mid, 0.f, 1.f, 0.5f), fin(high, 0.f, 1.f, 0.5f));
    }

    // TRIM do canal, 0..1: −12 dB à esquerda, 0 dB no centro, +12 dB à direita.
    void setTrimKnob(int deck, float norm) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        const float k = fin(norm, 0.f, 1.f, 0.5f);
        trimKnob_[deck] = std::pow(10.f, (k - 0.5f) * 2.f * 12.f / 20.f);
    }

    // HEADPHONES MIX (0 = só cue, 1 = só master) e LEVEL (0..1, 0 dB em 0,75).
    void setHead(float mix, float level) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        headMix_ = fin(mix, 0.f, 1.f, 0.f);
        const float k = fin(level, 0.f, 1.f, 0.75f);
        headGain_ = k <= 0.01f ? 0.f : std::pow(10.f, (k - 0.75f) * 2.f * 12.f / 20.f);
    }

    void takeLevels(float *out) {
        for (int index = 0; index < 4; ++index) out[index] = levels_[index].exchange(0.f);
    }

    void setColorKind(int deck, int kind) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setSampleRate(rateForFx());
        mixFx_.setColorKind(deck, kind);
    }

    void setKeyLock(int deck, bool enabled) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].keyLock = enabled;
    }

    void setKeyShift(int deck, int semitones) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].keyShift = std::clamp(semitones, -12, 12);
    }

    void setUserSlip(int deck, bool on) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        Deck &target = decks_[deck];
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

    void setReverse(int deck, bool reversed) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        Deck &target = decks_[deck];
        if (!reversed && target.reversed && target.userSlip && target.slipAnchor >= 0.0) {
            target.position = clampFrame(target, target.slipAnchor);
        }
        target.reversed = reversed;
    }

    void setLoop(int deck, float startSec, float endSec) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return;
        Deck &target = decks_[deck];
        if (!std::isfinite(startSec) || !std::isfinite(endSec) || endSec > 36000.f) {
            startSec = -1.f;
            endSec = -1.f;
        }
        if (endSec > startSec && startSec >= 0.f) {
            target.loopStart = static_cast<double>(startSec) * target.sampleRate;
            target.loopEnd = static_cast<double>(endSec) * target.sampleRate;
        } else {
            const bool wasLooping = target.loopEnd > target.loopStart;
            target.loopStart = -1.0;
            target.loopEnd = -1.0;
            // Loop roll com SLIP: ao sair do loop a faixa volta ao ponto em que estaria sem ele.
            if (wasLooping && target.userSlip && target.slipAnchor >= 0.0) {
                const double frames = static_cast<double>(target.left.size());
                target.position = std::min(std::max(0.0, target.slipAnchor), frames > 1.0 ? frames - 1.0 : 0.0);
            }
        }
    }

    void setBufferFrames(int frames) { bufferFrames_ = frames; }

    // A taxa do stream aberto; antes de abrir, 48000. Os setters nunca pedem outra taxa que a do callback,
    // senão o MixerFx realocaria buffers dentro do áudio.
    int rateForFx() const { return sampleRate_ > 0 ? sampleRate_ : 48000; }

    void setRmx(const float *raw, int count) {
        if (raw == nullptr || count < 18) return;
        // Cópia limpa: o índice 17 é o BPM (120 se vier lixo), os demais 0; tudo preso a ±1000.
        float values[32];
        const int used = std::min(count, 32);
        for (int index = 0; index < used; ++index) values[index] = fin(raw[index], -1000.f, 1000.f, index == 17 ? 120.f : 0.f);
        values[17] = fin(values[17], 20.f, 400.f, 120.f);
        count = used;
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setSampleRate(rateForFx());
        const float drumBuild = count >= 19 ? values[18] : 0.f;
        const int drumVoice = count >= 20 ? static_cast<int>(values[19]) : 0;
        const int drumBars = count >= 21 ? static_cast<int>(values[20]) : 8;
        const bool drumGap = count < 22 || values[21] > 0.5f;
        const bool drumPitch = count >= 23 && values[22] > 0.5f;
        const int gridDeck = count >= 24 ? static_cast<int>(values[23]) : -1;
        mixFx_.setRmx(values[0] > 0.5f, static_cast<int>(values[1]), values[2] > 0.5f, values[3], values[4],
                      static_cast<int>(values[5]), values[6], values[7] > 0.5f, static_cast<int>(values[8]), values[9],
                      values[10], values[11], values[12], values[13], values[14], values[15] > 0.5f,
                      static_cast<int>(values[16]), values[17], drumBuild, drumVoice, drumBars, drumGap, drumPitch,
                      gridDeck);
    }

    void setConsole(const float *raw, int count) {
        if (raw == nullptr || count <= 0) return;
        float values[32];
        const int used = std::min(count, 32);
        for (int index = 0; index < used; ++index) values[index] = fin(raw[index], -1000.f, 1000.f, std::nanf(""));
        std::lock_guard<std::mutex> lock(mixMutex_);
        mixFx_.setConsole(values, used);
    }

    void previewLoad(const float *left, const float *right, int frames, int rate) {
        // A cópia (alguns MB) fica fora do lock; o áudio só espera a troca dos vetores.
        deckbridge::PreviewVoice next;
        next.load(left, right, frames, rate);
        std::lock_guard<std::mutex> lock(mixMutex_);
        std::swap(preview_, next);
    }

    void previewStop() {
        std::lock_guard<std::mutex> lock(mixMutex_);
        preview_.stop();
    }

    bool previewPlaying() {
        std::lock_guard<std::mutex> lock(mixMutex_);
        return preview_.playing;
    }

    void setGrid(int deck, float originSec, float bpm) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].gridOriginSec = finD(static_cast<double>(originSec), -3600.0, 36000.0, 0.0);
        const float clean = fin(bpm, 0.f, 400.f, 0.f);
        decks_[deck].gridBpm = clean > 1.f ? static_cast<double>(clean) : 0.0;
    }

    void loadDrum(int voice, const uint8_t *data, int size) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (data == nullptr || size <= 0) return;
        deckbridge::loadDrumFlac(data, static_cast<size_t>(size), voice);
    }

    float positionSeconds(int deck) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return 0.f;
        return static_cast<float>(decks_[deck].position / decks_[deck].sampleRate);
    }

    /** Conta uma falha quando o bloco demora mais que 90% do tempo que ele toca (o próximo bloco chegaria atrasado). */
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        const auto began = std::chrono::steady_clock::now();
        const auto result = processAudio(audioStream, audioData, numFrames);
        const int rate = audioStream->getSampleRate();
        if (rate > 0 && numFrames > 0) {
            const double spent = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
            if (spent > 0.9 * static_cast<double>(numFrames) / static_cast<double>(rate)) glitches_.fetch_add(1, std::memory_order_relaxed);
        }
        return result;
    }

    void setMasterGain(float linear) { masterGain_.store(fin(linear, 0.f, 4.f, 1.f), std::memory_order_relaxed); }

    int glitchCount() const { return glitches_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult processAudio(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) {
        auto *out = static_cast<float *>(audioData);
        const int channels = audioStream->getChannelCount();
        const int route = route_.load();
        const int rate = audioStream->getSampleRate();
        if (rate <= 0) return oboe::DataCallbackResult::Continue;
        if (route == 3) {
            std::lock_guard<std::mutex> lock(mixMutex_);
            const bool phonesOpen = channels >= 4;
            phonesAudible_.store(phonesOpen && (decks_[0].pfl || decks_[1].pfl || preview_.audible()));
            mixFx_.setSampleRate(rate);
            float peak[4] = {0.f, 0.f, 0.f, 0.f};
            for (int frame = 0; frame < numFrames; ++frame) {
                mixFx_.beginSample();
                if (mixFx_.release.active() && mixFx_.release.kind != 1) {
                    float ignoredL = 0.f;
                    float ignoredR = 0.f;
                    mixFx_.release.process(ignoredL, ignoredR);
                }
                armRoll(0);
                armRoll(1);
                float al = 0.f, ar = 0.f, bl = 0.f, br = 0.f;
                decks_[0].rateMul = mixFx_.rateFor(0);
                decks_[1].rateMul = mixFx_.rateFor(1);
                tickSlip(0, rate);
                tickSlip(1, rate);
                decks_[0].read(rate, al, ar);
                decks_[1].read(rate, bl, br);
                const int gridDeck = std::clamp(mixFx_.drumDeck, 0, 1);
                const Deck &grid = decks_[gridDeck];
                const double gridBpm = grid.gridBpm > 1.0 ? grid.gridBpm : static_cast<double>(grid.bpm);
                const bool gridLive = grid.playing && grid.sampleRate > 0 && gridBpm > 1.0;
                mixFx_.setDrumPhase(deckbridge::musicalBeat(grid.lastEmitted, grid.sampleRate, gridBpm, grid.gridOriginSec),
                                    gridLive, true);
                // TRIM antes do canal, como num mixer: o COMP e o EQ veem o nível já ajustado.
                al *= trim_[0] * trimKnob_[0];
                ar *= trim_[0] * trimKnob_[0];
                bl *= trim_[1] * trimKnob_[1];
                br *= trim_[1] * trimKnob_[1];
                mixFx_.processDeck(0, al, ar);
                mixFx_.processDeck(1, bl, br);
                // Crossfader e faders pelas curvas escolhidas (a lenta é aditiva: os dois inteiros no centro).
                float sideA = 1.f;
                float sideB = 1.f;
                mixFx_.sides(cross_, sideA, sideB);
                const float faderA = mixFx_.faderGain(decks_[0].volume);
                const float faderB = mixFx_.faderGain(decks_[1].volume);
                float masterL = al * sideA * faderA + bl * sideB * faderB;
                float masterR = ar * sideA * faderA + br * sideB * faderB;
                mixFx_.addSend(al * faderA, ar * faderA, bl * faderB, br * faderB, masterL, masterR);
                mixFx_.processMaster(masterL, masterR);
                // Ganho de saída do app (−12 a +6 dB): o DDJ-400 do mapa pinado não manda um knob de master em MIDI.
                const float outGain = masterGain_.load(std::memory_order_relaxed);
                // Última defesa: um NaN que passasse pelas guardas de entrada não pode chegar ao DAC nem ao limiter.
                masterL = safeOut(masterL * outGain);
                masterR = safeOut(masterR * outGain);
                masterLimit_.process(masterL, masterR);
                peak[0] = std::max(peak[0], std::max(std::fabs(al), std::fabs(ar)));
                peak[1] = std::max(peak[1], std::max(std::fabs(bl), std::fabs(br)));
                peak[2] = std::max(peak[2], std::fabs(masterL));
                peak[3] = std::max(peak[3], std::fabs(masterR));
                liveRate_.store(rate);
                recorder_.push(masterL, masterR);
                float phoneL = 0.f;
                float phoneR = 0.f;
                if (phonesOpen) {
                    // CUE antes do fader, como num mixer: com o fader embaixo a próxima faixa se ouve no fone.
                    if (decks_[0].pfl) {
                        phoneL += al;
                        phoneR += ar;
                    }
                    if (decks_[1].pfl) {
                        phoneL += bl;
                        phoneR += br;
                    }
                    if (decks_[0].pfl && decks_[1].pfl) {
                        phoneL *= 0.5f;
                        phoneR *= 0.5f;
                    }
                    // Pré-escuta da lista: entra no lado CUE do fone, por cima do que estiver no CUE.
                    float previewL = 0.f;
                    float previewR = 0.f;
                    preview_.read(rate, previewL, previewR);
                    phoneL += previewL;
                    phoneR += previewR;
                    phoneL = safeOut((phoneL * (1.f - headMix_) + masterL * headMix_) * headGain_);
                    phoneR = safeOut((phoneR * (1.f - headMix_) + masterR * headMix_) * headGain_);
                    // O fone também tem limiter: o CUE soma dois canais antes do fader com EQ e trim, e passava de 1,0.
                    phoneLimit_.process(phoneL, phoneR);
                }
                for (int channel = 0; channel < channels; ++channel) {
                    float sample = 0.f;
                    if (channel == 0) sample = masterL;
                    else if (channel == 1) sample = masterR;
                    else if (channel == 2) sample = phoneL;
                    else if (channel == 3) sample = phoneR;
                    out[frame * channels + channel] = sample;
                }
            }
            for (int index = 0; index < 4; ++index) {
                if (peak[index] > levels_[index].load()) levels_[index].store(peak[index]);
            }
            return oboe::DataCallbackResult::Continue;
        }
        for (int frame = 0; frame < numFrames; ++frame) {
            for (int channel = 0; channel < channels; ++channel) {
                float sample = 0.f;
                const bool master = route == 1 && channel < 2;
                const bool phones = route == 2 && channel >= 2 && channel < 4;
                if ((master || phones) && channel < 4) {
                    const double step = 2.0 * M_PI * kHz[channel] / static_cast<double>(rate);
                    sample = kAmplitude * static_cast<float>(std::sin(phase_[channel]));
                    phase_[channel] += step;
                    if (phase_[channel] > 2.0 * M_PI) phase_[channel] -= 2.0 * M_PI;
                }
                out[frame * channels + channel] = sample;
            }
        }
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override {
        glitches_.fetch_add(1, std::memory_order_relaxed);
        bool retry = false;
        int device = 0;
        int route = 0;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            opened_.store(false);
            error_ = oboe::convertToText(error);
            stream_.reset();
            retry = wantOpen_ && !restarting_.exchange(true);
            device = lastDevice_;
            route = lastRoute_;
        }
        // Desconexão ou troca de rota do áudio: tenta reabrir, para o som voltar sem mexer na tela.
        // As faixas e as posições dos decks não são tocadas. O hotplug de outro aparelho USB é tratado no Kotlin.
        if (retry) {
            std::thread([this, device, route]() {
                for (int attempt = 0; attempt < 6; ++attempt) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(300 + attempt * 400));
                    {
                        std::lock_guard<std::mutex> lock(mutex_);
                        if (!wantOpen_ || opened_.load()) break;
                    }
                    start(device, route);
                    if (opened_.load()) break;
                }
                restarting_.store(false);
            }).detach();
        }
    }

private:
    void armRoll(int deck) {
        Deck &target = decks_[deck];
        const bool want = mixFx_.rollDeck[deck];
        if (want && target.rollLength < 0.0 && target.sampleRate > 0) {
            const double bpm = target.bpm > 1.f ? static_cast<double>(target.bpm) * target.pitch : 120.0;
            const double slice = deckbridge::samplesPerBeat(bpm, target.sampleRate) * std::max(0.0625, mixFx_.rollBeats);
            const double origin = std::floor(target.position / slice) * slice;
            target.rollOrigin = origin;
            target.rollLength = slice;
            target.shadow = target.position;
            if (mixFx_.slipDeck[deck]) target.slip = target.position;
        }
        if (want) target.rollMix = mixFx_.rollMix;
        if (!want && target.rollLength > 0.0) {
            if (target.slip >= 0.0) target.position = target.slip;
            target.slip = -1.0;
            target.rollOrigin = -1.0;
            target.rollLength = -1.0;
            target.shadow = -1.0;
        }
    }

    void tickSlip(int deck, int outputRate) {
        Deck &target = decks_[deck];
        if (!target.userSlip || target.slipAnchor < 0.0 || target.sampleRate <= 0 || outputRate <= 0) return;
        const double forward = (std::max(0.0, target.pitch) * std::max(0.0, target.rateMul) * target.sampleRate) /
                               static_cast<double>(outputRate);
        target.slipAnchor += forward;
    }

    static double clampFrame(const Deck &target, double frame) {
        const double frames = static_cast<double>(target.left.size());
        const double limit = frames > 1.0 ? frames - 1.0 : 0.0;
        return std::min(std::max(0.0, frame), limit);
    }

    void placeSeconds(int deck, double seconds, bool wrap) {
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return;
        Deck &target = decks_[deck];
        double pos = seconds * target.sampleRate;
        const double frames = static_cast<double>(target.left.size());
        if (pos < 0.0) pos = 0.0;
        if (frames > 1.0 && pos > frames - 1.0) pos = frames - 1.0;
        target.position = pos;
        if (wrap) target.wrapLoop();
    }

    void closeLocked() {
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
        opened_.store(false);
        phonesAudible_.store(false);
    }

    std::string statsLocked() const {
        std::ostringstream out;
        const bool open = stream_ != nullptr && opened_.load();
        const int route = route_.load();
        const bool phones = route == 3 ? phonesAudible_.load() : phonesSent_;
        out << "opened=" << (open ? "true" : "false") << "\n";
        out << "requested_channels=4\n";
        out << "opened_channels=" << (stream_ ? stream_->getChannelCount() : 0) << "\n";
        out << "sample_rate=" << (stream_ ? stream_->getSampleRate() : 0) << "\n";
        out << "device_id=" << (stream_ ? stream_->getDeviceId() : 0) << "\n";
        out << "sharing=" << (stream_ ? sharingName(stream_->getSharingMode()) : "UNKNOWN") << "\n";
        out << "performance=" << (stream_ ? performanceName(stream_->getPerformanceMode()) : "UNKNOWN") << "\n";
        out << "fallback_to_two=" << (fallbackToTwo_ ? "true" : "false") << "\n";
        out << "route=" << route << "\n";
        out << "phones_sent=" << (phones ? "true" : "false") << "\n";
        out << "mode=" << (route == 3 ? "mixer" : "tone") << "\n";
        bool xrunsKnown = false;
        int32_t xruns = 0;
        bool latencyKnown = false;
        double latency = 0.0;
        if (stream_) {
            auto xrunResult = stream_->getXRunCount();
            if (xrunResult) {
                xrunsKnown = true;
                xruns = xrunResult.value();
            }
            auto latencyResult = stream_->calculateLatencyMillis();
            if (latencyResult) {
                latencyKnown = true;
                latency = latencyResult.value();
            }
        }
        out << "xruns_known=" << (xrunsKnown ? "true" : "false") << "\n";
        out << "xruns=" << (xrunsKnown ? xruns : -1) << "\n";
        out << "latency_known=" << (latencyKnown ? "true" : "false") << "\n";
        out << "latency_ms=" << (latencyKnown ? latency : -1.0) << "\n";
        out << "error=" << error_ << "\n";
        return out.str();
    }

    std::mutex mutex_;
    std::mutex mixMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::atomic<int> glitches_{0};
    std::atomic<float> masterGain_{1.f};
    std::atomic<int> route_{0};
    std::atomic<int> openedChannels_{0};
    std::atomic<bool> opened_{false};
    std::atomic<bool> phonesAudible_{false};
    // Picos desde a última leitura: canal 1, canal 2, master E, master D. A thread de áudio só sobe; a tela zera ao ler.
    std::atomic<float> levels_[4] = {0.f, 0.f, 0.f, 0.f};
    Deck decks_[2];
    deckbridge::MixerFx mixFx_;
    deckbridge::PeakLimiter masterLimit_;
    deckbridge::PeakLimiter phoneLimit_;
    float trim_[2]{1.f, 1.f};
    float trimKnob_[2]{1.f, 1.f};
    deckbridge::PreviewVoice preview_;
    float headMix_ = 0.f;
    float headGain_ = 1.f;
    deckbridge::MasterRecorder recorder_;
    std::atomic<int> liveRate_{48000};
    int bufferFrames_ = 256;
    float cross_ = 0.5f;
    double phase_[4]{};
    int sampleRate_ = 0;
    bool wantOpen_ = false;
    int lastDevice_ = 0;
    int lastRoute_ = 0;
    std::atomic<bool> restarting_{false};
    bool phonesSent_ = false;
    bool fallbackToTwo_ = false;
    std::string error_;
};

ToneEngine &engine() {
    static ToneEngine instance;
    return instance;
}

std::string startSafe(int deviceId, int route) {
    try {
        return engine().start(deviceId, route);
    } catch (const std::exception &ex) {
        return std::string("opened=false\nrequested_channels=4\nopened_channels=0\n") +
               "sample_rate=0\ndevice_id=0\nsharing=UNKNOWN\nperformance=UNKNOWN\n" +
               "fallback_to_two=false\nroute=0\nphones_sent=false\nmode=tone\n" +
               "xruns_known=false\nxruns=-1\nlatency_known=false\nlatency_ms=-1\nerror=" +
               ex.what() + "\n";
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_os_deckbridge_android_NativeEngine_nativeStart(JNIEnv *env, jobject, jint deviceId, jint route) {
    const std::string text = startSafe(deviceId, route);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeLoadDeck(
        JNIEnv *env, jobject, jint deck, jfloatArray samples, jint channels, jint rate, jfloat bpm, jfloat cueSeconds,
        jfloatArray hot, jfloat loopStart, jfloat loopEnd) {
    if (samples == nullptr) return;
    jsize count = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    jfloat *hotData = nullptr;
    jsize hotCount = 0;
    if (hot != nullptr) {
        hotCount = env->GetArrayLength(hot);
        hotData = env->GetFloatArrayElements(hot, nullptr);
    }
    engine().loadDeck(deck, data, count, channels, rate, bpm, cueSeconds, hotData, hotCount, loopStart, loopEnd);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    if (hotData != nullptr) env->ReleaseFloatArrayElements(hot, hotData, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeLoadDeckStereo(
        JNIEnv *env, jobject, jint deck, jfloatArray left, jfloatArray right, jint frames, jint rate, jfloat bpm,
        jfloat cueSeconds, jfloatArray hot, jfloat loopStart, jfloat loopEnd) {
    if (left == nullptr || right == nullptr || frames <= 0) return;
    const jsize available = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
    const int count = std::min<int>(frames, available);
    // Copia direto do array Java para o vetor do motor, sem um buffer intermediário.
    std::vector<float> newLeft(static_cast<size_t>(count));
    std::vector<float> newRight(static_cast<size_t>(count));
    env->GetFloatArrayRegion(left, 0, count, newLeft.data());
    env->GetFloatArrayRegion(right, 0, count, newRight.data());
    std::vector<float> hotValues;
    if (hot != nullptr) {
        const jsize hotCount = env->GetArrayLength(hot);
        hotValues.resize(static_cast<size_t>(hotCount));
        if (hotCount > 0) env->GetFloatArrayRegion(hot, 0, hotCount, hotValues.data());
    }
    engine().installDeck(deck, std::move(newLeft), std::move(newRight), rate, bpm, cueSeconds,
                         hotValues.empty() ? nullptr : hotValues.data(), static_cast<int>(hotValues.size()), loopStart, loopEnd);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeTransport(JNIEnv *, jobject, jint deck, jboolean playing) {
    engine().transport(deck, playing == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeCue(JNIEnv *, jobject, jint deck) {
    engine().cue(deck);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeHotCue(JNIEnv *, jobject, jint deck, jint index) {
    engine().hotCue(deck, index);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeHotCueRelease(JNIEnv *, jobject, jint deck) {
    engine().releaseHotCue(deck);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetMix(
        JNIEnv *, jobject, jfloat volA, jfloat volB, jfloat pitchA, jfloat pitchB, jfloat cross, jboolean pflA, jboolean pflB) {
    engine().setMix(volA, volB, pitchA, pitchB, cross, pflA == JNI_TRUE, pflB == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetTrim(JNIEnv *, jobject, jint deck, jfloat linear) {
    engine().setTrim(deck, linear);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_os_deckbridge_android_NativeEngine_nativePosition(JNIEnv *, jobject, jint deck) {
    return engine().positionSeconds(deck);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSeek(JNIEnv *, jobject, jint deck, jfloat seconds) {
    engine().seekSeconds(deck, seconds);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeScratchTouch(JNIEnv *, jobject, jint deck, jboolean on) {
    engine().scratchTouch(deck, on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeScratchMove(JNIEnv *, jobject, jint deck, jfloat seconds) {
    engine().scratchMove(deck, seconds);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeNudge(JNIEnv *, jobject, jint deck, jfloat seconds) {
    engine().nudge(deck, seconds);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetBeatFx(
        JNIEnv *, jobject, jint effect, jboolean on, jint level, jint channel, jfloat bpm, jfloat beats) {
    engine().setBeatFx(effect, on == JNI_TRUE, level, channel, bpm, beats);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetFilter(JNIEnv *, jobject, jint deck, jfloat norm) {
    engine().setFilter(deck, norm);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetEq(JNIEnv *, jobject, jint deck, jfloat low, jfloat mid, jfloat high) {
    engine().setEq(deck, low, mid, high);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetTrimKnob(JNIEnv *, jobject, jint deck, jfloat norm) {
    engine().setTrimKnob(deck, norm);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetHead(JNIEnv *, jobject, jfloat mix, jfloat level) {
    engine().setHead(mix, level);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetColorKind(JNIEnv *, jobject, jint deck, jint kind) {
    engine().setColorKind(deck, kind);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetKeyLock(JNIEnv *, jobject, jint deck, jboolean enabled) {
    engine().setKeyLock(deck, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetKeyShift(JNIEnv *, jobject, jint deck, jint semitones) {
    engine().setKeyShift(deck, semitones);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetSlip(JNIEnv *, jobject, jint deck, jboolean enabled) {
    engine().setUserSlip(deck, enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetLoop(JNIEnv *, jobject, jint deck, jfloat startSec, jfloat endSec) {
    engine().setLoop(deck, startSec, endSec);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetReverse(JNIEnv *, jobject, jint deck, jboolean reversed) {
    engine().setReverse(deck, reversed == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetBuffer(JNIEnv *, jobject, jint frames) {
    engine().setBufferFrames(frames);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetGrid(JNIEnv *, jobject, jint deck, jfloat originSec, jfloat bpm) {
    engine().setGrid(deck, originSec, bpm);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeLoadDrum(JNIEnv *env, jobject, jint voice, jbyteArray bytes) {
    if (bytes == nullptr) return;
    const jsize count = env->GetArrayLength(bytes);
    if (count <= 0) return;
    jbyte *data = env->GetByteArrayElements(bytes, nullptr);
    if (data != nullptr) engine().loadDrum(voice, reinterpret_cast<const uint8_t *>(data), count);
    env->ReleaseByteArrayElements(bytes, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetRmx(JNIEnv *env, jobject, jfloatArray values) {
    if (values == nullptr) return;
    const jsize count = env->GetArrayLength(values);
    jfloat *data = env->GetFloatArrayElements(values, nullptr);
    engine().setRmx(data, count);
    env->ReleaseFloatArrayElements(values, data, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativePreviewLoad(JNIEnv *env, jobject, jfloatArray left, jfloatArray right, jint frames, jint rate) {
    if (left == nullptr || right == nullptr) return;
    const jsize count = std::min(std::min(env->GetArrayLength(left), env->GetArrayLength(right)), frames);
    jfloat *l = env->GetFloatArrayElements(left, nullptr);
    jfloat *r = env->GetFloatArrayElements(right, nullptr);
    engine().previewLoad(l, r, count, rate);
    env->ReleaseFloatArrayElements(left, l, JNI_ABORT);
    env->ReleaseFloatArrayElements(right, r, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativePreviewStop(JNIEnv *, jobject) {
    engine().previewStop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_os_deckbridge_android_NativeEngine_nativePreviewPlaying(JNIEnv *, jobject) {
    return engine().previewPlaying() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetConsole(JNIEnv *env, jobject, jfloatArray values) {
    if (values == nullptr) return;
    const jsize count = env->GetArrayLength(values);
    jfloat *data = env->GetFloatArrayElements(values, nullptr);
    engine().setConsole(data, count);
    env->ReleaseFloatArrayElements(values, data, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_os_deckbridge_android_NativeEngine_nativeRecordStart(JNIEnv *env, jobject, jstring path, jboolean side) {
    if (path == nullptr) return JNI_FALSE;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    const bool ok = engine().startRecord(chars == nullptr ? "" : chars, side == JNI_TRUE);
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeRecordStop(JNIEnv *, jobject) {
    engine().stopRecord();
}

extern "C" JNIEXPORT jstring JNICALL
Java_os_deckbridge_android_NativeEngine_nativeRecordPoll(JNIEnv *env, jobject) {
    const std::string line = engine().pollRecord();
    return env->NewStringUTF(line.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeSetMasterGain(JNIEnv *, jobject, jfloat linear) {
    engine().setMasterGain(linear);
}

extern "C" JNIEXPORT jint JNICALL
Java_os_deckbridge_android_NativeEngine_nativeGlitches(JNIEnv *, jobject) {
    return engine().glitchCount();
}

extern "C" JNIEXPORT void JNICALL
Java_os_deckbridge_android_NativeEngine_nativeLevels(JNIEnv *env, jobject, jfloatArray dest) {
    if (dest == nullptr || env->GetArrayLength(dest) < 4) return;
    float values[4];
    engine().takeLevels(values);
    env->SetFloatArrayRegion(dest, 0, 4, values);
}

extern "C" JNIEXPORT jint JNICALL
Java_os_deckbridge_android_NativeEngine_nativeRecordPull(JNIEnv *env, jobject, jfloatArray dest) {
    if (dest == nullptr) return 0;
    const jsize count = env->GetArrayLength(dest);
    if (count < 2) return 0;
    jfloat *data = env->GetFloatArrayElements(dest, nullptr);
    const int frames = engine().pullRecord(data, count / 2);
    env->ReleaseFloatArrayElements(dest, data, 0);
    return frames;
}
