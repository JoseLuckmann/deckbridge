#include <oboe/Oboe.h>

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

namespace {

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
    double loopStart = -1.0;
    double loopEnd = -1.0;

    Deck() {
        for (double &item : hot) item = -1.0;
    }

    void wrapLoop() {
        if (!(loopEnd > loopStart) || position < loopEnd) return;
        const double length = loopEnd - loopStart;
        double over = std::fmod(position - loopEnd, length);
        if (over < 0.0) over += length;
        position = loopStart + over;
    }

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
        const int next = std::min(index + 1, frames - 1);
        const float frac = static_cast<float>(position - index);
        leftOut = left[index] * (1.f - frac) + left[next] * frac;
        rightOut = right[index] * (1.f - frac) + right[next] * frac;
        position += (pitch * static_cast<double>(sampleRate)) / static_cast<double>(outputRate);
        wrapLoop();
    }
};

class ToneEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    std::string start(int deviceId, int route) {
        std::lock_guard<std::mutex> lock(mutex_);
        closeLocked();
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
        std::lock_guard<std::mutex> lock(mutex_);
        route_.store(0);
        closeLocked();
    }

    std::string stats() {
        std::lock_guard<std::mutex> lock(mutex_);
        return statsLocked();
    }

    void loadDeck(int deck, const float *data, int count, int channels, int rate, float bpm, float cueSeconds,
                  const float *hot, int hotCount, float loopStartSec, float loopEndSec) {
        if (deck < 0 || deck > 1 || data == nullptr) return;
        std::lock_guard<std::mutex> lock(mixMutex_);
        Deck &target = decks_[deck];
        const int ch = channels == 2 ? 2 : 1;
        const int frames = std::max(0, ch == 2 ? count / 2 : count);
        target.left.assign(frames, 0.f);
        target.right.assign(frames, 0.f);
        for (int index = 0; index < frames; ++index) {
            if (ch == 2) {
                target.left[index] = data[index * 2];
                target.right[index] = data[index * 2 + 1];
            } else {
                target.left[index] = data[index];
                target.right[index] = data[index];
            }
        }
        target.sampleRate = rate > 0 ? rate : 48000;
        target.bpm = bpm;
        target.position = 0.0;
        target.playing = false;
        target.cue = std::max(0.0, static_cast<double>(cueSeconds) * target.sampleRate);
        const double start = static_cast<double>(loopStartSec) * target.sampleRate;
        const double end = static_cast<double>(loopEndSec) * target.sampleRate;
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
            if (hot != nullptr && hot[index] >= 0.f) target.hot[index] = static_cast<double>(hot[index]) * target.sampleRate;
        }
    }

    void transport(int deck, bool playing) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1) return;
        decks_[deck].playing = playing && !decks_[deck].left.empty();
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
        if (decks_[deck].hot[index] < 0.0) return;
        decks_[deck].position = decks_[deck].hot[index];
    }

    void setMix(float volA, float volB, float pitchA, float pitchB, float cross, bool pflA, bool pflB) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        decks_[0].volume = std::clamp(volA, 0.f, 1.f);
        decks_[1].volume = std::clamp(volB, 0.f, 1.f);
        decks_[0].pitch = std::clamp(static_cast<double>(pitchA), 0.5, 2.0);
        decks_[1].pitch = std::clamp(static_cast<double>(pitchB), 0.5, 2.0);
        cross_ = std::clamp(cross, 0.f, 1.f);
        decks_[0].pfl = pflA;
        decks_[1].pfl = pflB;
    }

    float positionSeconds(int deck) {
        std::lock_guard<std::mutex> lock(mixMutex_);
        if (deck < 0 || deck > 1 || decks_[deck].sampleRate <= 0) return 0.f;
        return static_cast<float>(decks_[deck].position / decks_[deck].sampleRate);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        auto *out = static_cast<float *>(audioData);
        const int channels = audioStream->getChannelCount();
        const int route = route_.load();
        const int rate = audioStream->getSampleRate();
        if (rate <= 0) return oboe::DataCallbackResult::Continue;
        if (route == 3) {
            std::lock_guard<std::mutex> lock(mixMutex_);
            const bool phonesOpen = channels >= 4;
            phonesAudible_.store(phonesOpen && (decks_[0].pfl || decks_[1].pfl));
            for (int frame = 0; frame < numFrames; ++frame) {
                float al = 0.f, ar = 0.f, bl = 0.f, br = 0.f;
                decks_[0].read(rate, al, ar);
                decks_[1].read(rate, bl, br);
                const float masterL = al * (1.f - cross_) * decks_[0].volume + bl * cross_ * decks_[1].volume;
                const float masterR = ar * (1.f - cross_) * decks_[0].volume + br * cross_ * decks_[1].volume;
                float phoneL = 0.f;
                float phoneR = 0.f;
                if (phonesOpen) {
                    if (decks_[0].pfl) {
                        phoneL += al * decks_[0].volume;
                        phoneR += ar * decks_[0].volume;
                    }
                    if (decks_[1].pfl) {
                        phoneL += bl * decks_[1].volume;
                        phoneR += br * decks_[1].volume;
                    }
                    if (decks_[0].pfl && decks_[1].pfl) {
                        phoneL *= 0.5f;
                        phoneR *= 0.5f;
                    }
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
        std::lock_guard<std::mutex> lock(mutex_);
        opened_.store(false);
        error_ = oboe::convertToText(error);
        stream_.reset();
    }

private:
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
    std::atomic<int> route_{0};
    std::atomic<int> openedChannels_{0};
    std::atomic<bool> opened_{false};
    std::atomic<bool> phonesAudible_{false};
    Deck decks_[2];
    float cross_ = 0.5f;
    double phase_[4]{};
    int sampleRate_ = 0;
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
Java_os_deckbridge_android_NativeEngine_nativeSetMix(
        JNIEnv *, jobject, jfloat volA, jfloat volB, jfloat pitchA, jfloat pitchB, jfloat cross, jboolean pflA, jboolean pflB) {
    engine().setMix(volA, volB, pitchA, pitchB, cross, pflA == JNI_TRUE, pflB == JNI_TRUE);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_os_deckbridge_android_NativeEngine_nativePosition(JNIEnv *, jobject, jint deck) {
    return engine().positionSeconds(deck);
}
