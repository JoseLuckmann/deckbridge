#pragma once

// dr_flac is public domain / MIT-0 (mackron). One implementation per binary.
#define DR_FLAC_NO_STDIO
#define DR_FLAC_IMPLEMENTATION
#include "third_party/dr_flac.h"

#include "dsp.h"

#include <cstdint>
#include <vector>

namespace deckbridge {

// Decodifica um one-shot FLAC para mono a 48 kHz. Não mexe no banco: pode rodar fora do lock do áudio.
inline std::vector<float> decodeDrumFlac(const void *data, size_t size) {
    if (data == nullptr || size < 8) return {};
    drflac *flac = drflac_open_memory(data, size, nullptr);
    if (flac == nullptr) return {};
    const int channels = static_cast<int>(flac->channels);
    const int rate = static_cast<int>(flac->sampleRate);
    const drflac_uint64 frames = flac->totalPCMFrameCount;
    if (channels < 1 || rate <= 0 || frames < 8 || frames > 48000ull * 5ull) {
        drflac_close(flac);
        return {};
    }
    std::vector<float> interleaved(static_cast<size_t>(frames) * static_cast<size_t>(channels));
    const drflac_uint64 got = drflac_read_pcm_frames_f32(flac, frames, interleaved.data());
    drflac_close(flac);
    if (got < 8) return {};
    std::vector<float> mono(static_cast<size_t>(got));
    for (drflac_uint64 index = 0; index < got; ++index) {
        float sum = 0.f;
        for (int channel = 0; channel < channels; ++channel) {
            sum += interleaved[static_cast<size_t>(index) * static_cast<size_t>(channels) + static_cast<size_t>(channel)];
        }
        mono[static_cast<size_t>(index)] = sum / static_cast<float>(channels);
    }
    if (rate != 48000) {
        const double ratio = 48000.0 / static_cast<double>(rate);
        const int outFrames = std::max(8, static_cast<int>(static_cast<double>(mono.size()) * ratio));
        std::vector<float> resampled(static_cast<size_t>(outFrames));
        const int last = static_cast<int>(mono.size()) - 1;
        for (int index = 0; index < outFrames; ++index) {
            const double source = static_cast<double>(index) / ratio;
            const int left = std::min(last, static_cast<int>(source));
            const int right = std::min(last, left + 1);
            const float frac = static_cast<float>(source - left);
            resampled[static_cast<size_t>(index)] = mono[static_cast<size_t>(left)] * (1.f - frac) + mono[static_cast<size_t>(right)] * frac;
        }
        mono.swap(resampled);
    }
    return mono;
}

inline bool loadDrumFlac(const void *data, size_t size, int voice) {
    if (voice < 0 || voice > 4) return false;
    std::vector<float> mono = decodeDrumFlac(data, size);
    if (mono.size() < 8) return false;
    drumBank().take(voice, std::move(mono));
    return !drumBank().empty(voice);
}

}  // namespace deckbridge
