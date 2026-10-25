// Oboe sobre ALSA, para o motor rodar no Linux (Raspberry Pi OS 64 bit, ADR-0007 opção C) sem mudar engine.cpp.
// Só a parte da API do Oboe que o motor usa. Uma thread de áudio (SCHED_FIFO quando o sistema deixa) chama o callback
// a cada período e escreve no PCM; o formato do aparelho é o primeiro que ele aceitar entre float, S32, S24_3LE e S16.
// Desconexão (-ENODEV, -EBADFD, -ESTRPIPE) fecha o stream e chama onErrorAfterClose numa thread separada, como o Oboe.
#pragma once

#include <alsa/asoundlib.h>
#include <pthread.h>
#include <sched.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace oboe {

enum class Result { OK = 0, ErrorDisconnected = -899, ErrorInvalidFormat = -892, ErrorUnavailable = -886, ErrorInternal = -896 };
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
        case Result::ErrorInternal: return "ErrorInternal";
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

/** Configuração do lado Linux. O deviceId do Oboe não existe no ALSA: o aparelho é escolhido pelo nome. */
struct AlsaConfig {
    /** Nome do PCM: "default", "hw:DDJ400", "plughw:1,0"... A variável DECKBRIDGE_ALSA_DEVICE tem precedência. */
    static inline std::string device = "default";
    /** Quadros por período (o bloco do callback). 256 a 48 kHz = 5,3 ms. */
    static inline int periodFrames = 256;
    /** Períodos no buffer do ALSA. 3 dá folga a um núcleo lento sem passar de ~16 ms. */
    static inline int periods = 3;
    /** Prioridade de tempo real pedida para a thread de áudio (precisa de rtprio no limits.conf ou CAP_SYS_NICE). */
    static inline int rtPriority = 70;
    /** Do último stream aberto, para o diagnóstico: formato aceito e se a thread conseguiu SCHED_FIFO. */
    static inline std::atomic<const char *> lastFormat{"?"};
    static inline std::atomic<bool> lastRealtime{false};

    /** Bloqueia no relógio quando o aparelho não bloqueia (o PCM "null" do ALSA aceita tudo na hora): o teste sem
     *  placa de som anda em tempo real, como uma placa, e a CPU medida é a de verdade. */
    static bool paced(const std::string &name) { return name == "null" || name.rfind("file:", 0) == 0; }

    static std::string deviceName() {
        const char *env = std::getenv("DECKBRIDGE_ALSA_DEVICE");
        return env != nullptr && *env != '\0' ? std::string(env) : device;
    }
};

class AudioStream {
public:
    ~AudioStream() { close(); }

    int getChannelCount() const { return channels_; }
    int getSampleRate() const { return rate_; }
    int getDeviceId() const { return 0; }
    SharingMode getSharingMode() const { return SharingMode::Exclusive; }
    PerformanceMode getPerformanceMode() const { return PerformanceMode::LowLatency; }
    ResultWithValue<int32_t> getXRunCount() { return {Result::OK, xruns_.load()}; }
    ResultWithValue<double> calculateLatencyMillis() {
        if (rate_ <= 0) return {Result::ErrorUnavailable, -1.0};
        return {Result::OK, 1000.0 * static_cast<double>(bufferFrames_) / static_cast<double>(rate_)};
    }
    /** Formato aceito pelo aparelho (para o diagnóstico). */
    const char *formatName() const { return snd_pcm_format_name(format_); }
    bool realtime() const { return realtime_.load(); }
    long periodFrames() const { return static_cast<long>(period_); }

    Result requestStart() {
        if (pcm_ == nullptr) return Result::ErrorInternal;
        if (thread_.joinable()) return Result::OK;
        running_.store(true);
        thread_ = std::thread([this]() { loop(); });
        return Result::OK;
    }

    Result requestStop() {
        running_.store(false);
        if (thread_.joinable() && thread_.get_id() != std::this_thread::get_id()) thread_.join();
        return Result::OK;
    }

    Result close() {
        requestStop();
        if (pcm_ != nullptr) {
            snd_pcm_drop(pcm_);
            snd_pcm_close(pcm_);
            pcm_ = nullptr;
        }
        return Result::OK;
    }

private:
    friend class AudioStreamBuilder;

    Result open(const std::string &name, int channels, int rate, AudioStreamDataCallback *data, AudioStreamErrorCallback *error) {
        name_ = name;
        data_ = data;
        error_ = error;
        if (snd_pcm_open(&pcm_, name.c_str(), SND_PCM_STREAM_PLAYBACK, 0) < 0) {
            pcm_ = nullptr;
            return Result::ErrorUnavailable;
        }
        snd_pcm_hw_params_t *hw = nullptr;
        snd_pcm_hw_params_alloca(&hw);
        snd_pcm_hw_params_any(pcm_, hw);
        if (snd_pcm_hw_params_set_access(pcm_, hw, SND_PCM_ACCESS_RW_INTERLEAVED) < 0) return fail(Result::ErrorInvalidFormat);
        const snd_pcm_format_t formats[] = {SND_PCM_FORMAT_FLOAT_LE, SND_PCM_FORMAT_S32_LE, SND_PCM_FORMAT_S24_3LE, SND_PCM_FORMAT_S16_LE};
        bool formatOk = false;
        for (auto f : formats) {
            if (snd_pcm_hw_params_test_format(pcm_, hw, f) == 0 && snd_pcm_hw_params_set_format(pcm_, hw, f) == 0) {
                format_ = f;
                formatOk = true;
                break;
            }
        }
        if (!formatOk) return fail(Result::ErrorInvalidFormat);
        // Canais exatos: se o aparelho não dá 4, o motor pede 2 de novo (mesmo caminho do tablet).
        if (snd_pcm_hw_params_set_channels(pcm_, hw, static_cast<unsigned>(channels)) < 0) return fail(Result::ErrorInvalidFormat);
        unsigned r = static_cast<unsigned>(rate);
        if (snd_pcm_hw_params_set_rate_near(pcm_, hw, &r, nullptr) < 0) return fail(Result::ErrorInvalidFormat);
        snd_pcm_uframes_t period = static_cast<snd_pcm_uframes_t>(std::max(32, AlsaConfig::periodFrames));
        snd_pcm_hw_params_set_period_size_near(pcm_, hw, &period, nullptr);
        snd_pcm_uframes_t buffer = period * static_cast<snd_pcm_uframes_t>(std::max(2, AlsaConfig::periods));
        snd_pcm_hw_params_set_buffer_size_near(pcm_, hw, &buffer);
        if (snd_pcm_hw_params(pcm_, hw) < 0) return fail(Result::ErrorInvalidFormat);
        snd_pcm_hw_params_get_period_size(hw, &period, nullptr);
        snd_pcm_hw_params_get_buffer_size(hw, &buffer);
        snd_pcm_sw_params_t *sw = nullptr;
        snd_pcm_sw_params_alloca(&sw);
        snd_pcm_sw_params_current(pcm_, sw);
        snd_pcm_sw_params_set_start_threshold(pcm_, sw, buffer - period);
        snd_pcm_sw_params_set_avail_min(pcm_, sw, period);
        snd_pcm_sw_params(pcm_, sw);
        if (snd_pcm_prepare(pcm_) < 0) return fail(Result::ErrorInternal);
        channels_ = channels;
        AlsaConfig::lastFormat.store(snd_pcm_format_name(format_));
        rate_ = static_cast<int>(r);
        period_ = period;
        bufferFrames_ = buffer;
        // Tudo alocado aqui: a thread de áudio não aloca.
        floats_.assign(static_cast<size_t>(period) * static_cast<size_t>(channels), 0.f);
        bytes_.assign(static_cast<size_t>(period) * static_cast<size_t>(channels) * 4, 0);
        return Result::OK;
    }

    Result fail(Result r) {
        snd_pcm_close(pcm_);
        pcm_ = nullptr;
        return r;
    }

    static int32_t toInt(float x, float scale, float lo, float hi) {
        if (!(x == x)) return 0;
        return static_cast<int32_t>(std::lrint(std::clamp(x * scale, lo, hi)));
    }

    const void *convert(size_t samples) {
        switch (format_) {
            case SND_PCM_FORMAT_FLOAT_LE:
                return floats_.data();
            case SND_PCM_FORMAT_S32_LE: {
                auto *out = reinterpret_cast<int32_t *>(bytes_.data());
                for (size_t i = 0; i < samples; ++i) out[i] = toInt(floats_[i], 2147483648.f, -2147483648.f, 2147483520.f);
                return out;
            }
            case SND_PCM_FORMAT_S24_3LE: {
                auto *out = bytes_.data();
                for (size_t i = 0; i < samples; ++i) {
                    const int32_t v = toInt(floats_[i], 8388608.f, -8388608.f, 8388607.f);
                    out[i * 3] = static_cast<uint8_t>(v & 0xFF);
                    out[i * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                    out[i * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
                }
                return out;
            }
            default: {
                auto *out = reinterpret_cast<int16_t *>(bytes_.data());
                for (size_t i = 0; i < samples; ++i) out[i] = static_cast<int16_t>(toInt(floats_[i], 32768.f, -32768.f, 32767.f));
                return out;
            }
        }
    }

    void loop() {
        sched_param param{};
        param.sched_priority = AlsaConfig::rtPriority;
        realtime_.store(pthread_setschedparam(pthread_self(), SCHED_FIFO, &param) == 0);
        AlsaConfig::lastRealtime.store(realtime_.load());
        const size_t samples = static_cast<size_t>(period_) * static_cast<size_t>(channels_);
        bool lost = false;
        const bool pace = AlsaConfig::paced(name_);
        const auto periodTime = std::chrono::nanoseconds(static_cast<long long>(1e9 * static_cast<double>(period_) / rate_));
        auto deadline = std::chrono::steady_clock::now();
        while (running_.load(std::memory_order_relaxed)) {
            if (pace) {
                deadline += periodTime;
                std::this_thread::sleep_until(deadline);
            }
            std::fill(floats_.begin(), floats_.end(), 0.f);
            if (data_ != nullptr && data_->onAudioReady(this, floats_.data(), static_cast<int32_t>(period_)) == DataCallbackResult::Stop) break;
            const uint8_t *at = static_cast<const uint8_t *>(convert(samples));
            const size_t frameBytes = static_cast<size_t>(snd_pcm_frames_to_bytes(pcm_, 1));
            snd_pcm_uframes_t left = period_;
            while (left > 0 && running_.load(std::memory_order_relaxed)) {
                const snd_pcm_sframes_t wrote = snd_pcm_writei(pcm_, at, left);
                if (wrote >= 0) {
                    left -= static_cast<snd_pcm_uframes_t>(wrote);
                    at += static_cast<size_t>(wrote) * frameBytes;
                } else if (wrote == -EPIPE) {
                    xruns_.fetch_add(1);
                    snd_pcm_prepare(pcm_);
                } else if (wrote == -EAGAIN || wrote == -EINTR) {
                    continue;
                } else {
                    lost = true;  // -ENODEV, -EBADFD, -ESTRPIPE...: o aparelho saiu.
                    break;
                }
            }
            if (lost) break;
        }
        running_.store(false);
        if (lost && error_ != nullptr) {
            // Como no Oboe: o aviso vem depois de fechar e fora da thread de áudio (o motor pode destruir o stream nele).
            snd_pcm_drop(pcm_);
            snd_pcm_close(pcm_);
            pcm_ = nullptr;
            AudioStreamErrorCallback *cb = error_;
            std::thread([cb, self = this]() { cb->onErrorAfterClose(self, Result::ErrorDisconnected); }).detach();
        }
    }

    std::string name_;
    snd_pcm_t *pcm_ = nullptr;
    snd_pcm_format_t format_ = SND_PCM_FORMAT_FLOAT_LE;
    int channels_ = 0;
    int rate_ = 0;
    snd_pcm_uframes_t period_ = 0;
    snd_pcm_uframes_t bufferFrames_ = 0;
    AudioStreamDataCallback *data_ = nullptr;
    AudioStreamErrorCallback *error_ = nullptr;
    std::vector<float> floats_;
    std::vector<uint8_t> bytes_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> realtime_{false};
    std::atomic<int32_t> xruns_{0};
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder &setDirection(Direction) { return *this; }
    AudioStreamBuilder &setPerformanceMode(PerformanceMode) { return *this; }
    AudioStreamBuilder &setSharingMode(SharingMode) { return *this; }
    AudioStreamBuilder &setFormat(AudioFormat) { return *this; }
    AudioStreamBuilder &setChannelCount(int c) { channels_ = c; return *this; }
    AudioStreamBuilder &setSampleRate(int r) { rate_ = r; return *this; }
    AudioStreamBuilder &setBufferCapacityInFrames(int) { return *this; }
    AudioStreamBuilder &setUsage(Usage) { return *this; }
    AudioStreamBuilder &setContentType(ContentType) { return *this; }
    AudioStreamBuilder &setDataCallback(AudioStreamDataCallback *cb) { data_ = cb; return *this; }
    AudioStreamBuilder &setErrorCallback(AudioStreamErrorCallback *cb) { error_ = cb; return *this; }
    AudioStreamBuilder &setDeviceId(int) { return *this; }

    Result openStream(std::shared_ptr<AudioStream> &out) {
        auto stream = std::make_shared<AudioStream>();
        const Result r = stream->open(AlsaConfig::deviceName(), channels_, rate_, data_, error_);
        if (r != Result::OK) return r;
        out = stream;
        return Result::OK;
    }

private:
    int channels_ = 2;
    int rate_ = 48000;
    AudioStreamDataCallback *data_ = nullptr;
    AudioStreamErrorCallback *error_ = nullptr;
};

}  // namespace oboe
