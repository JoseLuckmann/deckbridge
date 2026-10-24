// Stub de host do Oboe, só o que engine.cpp usa. O stream é falso: o teste chama processAudio direto, simula a
// desconexão (onErrorAfterClose) e escolhe quantos canais o "aparelho" aceita. Não é o Oboe.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>

namespace oboe {

enum class Result { OK = 0, ErrorDisconnected = -899, ErrorInvalidFormat = -892, ErrorUnavailable = -886 };
enum class Direction { Output };
enum class PerformanceMode { None, PowerSaving, LowLatency };
enum class SharingMode { Exclusive, Shared };
enum class AudioFormat { Float };
enum class Usage { Game };
enum class ContentType { Music };
enum class DataCallbackResult { Continue, Stop };

inline const char *convertToText(Result r) {
    switch (r) {
        case Result::OK: return "OK";
        case Result::ErrorDisconnected: return "ErrorDisconnected";
        case Result::ErrorInvalidFormat: return "ErrorInvalidFormat";
        default: return "ErrorUnavailable";
    }
}

template <class T>
struct ResultWithValue {
    Result result;
    T val;
    explicit operator bool() const { return result == Result::OK; }
    T value() const { return val; }
};

class AudioStream;

class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream *, void *, int32_t) = 0;
};

class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream *, Result) {}
};

class AudioStream {
public:
    int channels = 4;
    int rate = 48000;
    int deviceId = 0;
    SharingMode sharing = SharingMode::Exclusive;
    PerformanceMode performance = PerformanceMode::LowLatency;
    bool started = false;
    bool closed = false;

    int getChannelCount() const { return channels; }
    int getSampleRate() const { return rate; }
    int getDeviceId() const { return deviceId; }
    SharingMode getSharingMode() const { return sharing; }
    PerformanceMode getPerformanceMode() const { return performance; }
    ResultWithValue<int32_t> getXRunCount() { return {Result::OK, 0}; }
    ResultWithValue<double> calculateLatencyMillis() { return {Result::OK, 10.0}; }
    Result requestStart() { started = true; return Result::OK; }
    Result requestStop() { started = false; return Result::OK; }
    Result close() { closed = true; return Result::OK; }
};

/** O que o "aparelho" aceita: o teste muda para simular um tablet que só entrega 2 canais ou que recusa o modo exclusivo. */
struct FakeDevice {
    static inline int maxChannels = 4;
    static inline bool allowExclusive = true;
    static inline bool failOpen = false;
    static inline int opens = 0;
    static inline std::shared_ptr<AudioStream> last;
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder &setDirection(Direction) { return *this; }
    AudioStreamBuilder &setPerformanceMode(PerformanceMode m) { performance_ = m; return *this; }
    AudioStreamBuilder &setSharingMode(SharingMode m) { sharing_ = m; return *this; }
    AudioStreamBuilder &setFormat(AudioFormat) { return *this; }
    AudioStreamBuilder &setChannelCount(int c) { channels_ = c; return *this; }
    AudioStreamBuilder &setSampleRate(int r) { rate_ = r; return *this; }
    AudioStreamBuilder &setBufferCapacityInFrames(int) { return *this; }
    AudioStreamBuilder &setUsage(Usage) { return *this; }
    AudioStreamBuilder &setContentType(ContentType) { return *this; }
    AudioStreamBuilder &setDataCallback(AudioStreamDataCallback *) { return *this; }
    AudioStreamBuilder &setErrorCallback(AudioStreamErrorCallback *) { return *this; }
    AudioStreamBuilder &setDeviceId(int d) { device_ = d; return *this; }

    Result openStream(std::shared_ptr<AudioStream> &out) {
        FakeDevice::opens += 1;
        if (FakeDevice::failOpen) return Result::ErrorUnavailable;
        if (sharing_ == SharingMode::Exclusive && !FakeDevice::allowExclusive) return Result::ErrorUnavailable;
        if (channels_ > FakeDevice::maxChannels) return Result::ErrorInvalidFormat;
        auto stream = std::make_shared<AudioStream>();
        stream->channels = channels_;
        stream->rate = rate_;
        stream->deviceId = device_;
        stream->sharing = sharing_;
        stream->performance = performance_;
        out = stream;
        FakeDevice::last = stream;
        return Result::OK;
    }

private:
    PerformanceMode performance_ = PerformanceMode::None;
    SharingMode sharing_ = SharingMode::Shared;
    int channels_ = 2;
    int rate_ = 48000;
    int device_ = 0;
};

}  // namespace oboe
