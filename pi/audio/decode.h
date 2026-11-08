// Faixas para o motor no Pi: WAV, MP3 (dr_mp3) e FLAC (dr_flac), direto para int16 estéreo, que é o formato que o
// motor guarda (engine.cpp, deckbridge::pcmValue). Sem cópia float da faixa inteira. O arquivo é lido por
// library::readFile (O_RDONLY): o pendrive do rekordbox nunca é aberto para escrita.
// AAC/M4A ainda não: no Android quem decodifica é o MediaCodec; no Pi falta escolher a biblioteca (ver pi/README.md).
#pragma once

#include "../library/rekordbox.h"

#include "third_party/dr_flac.h"
#ifndef DECKBRIDGE_NO_DR_MP3_IMPL
#define DR_MP3_IMPLEMENTATION
#endif
#define DR_MP3_NO_STDIO
#include "third_party/dr_mp3.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace deckbridge::audio {

struct Pcm16 {
    std::vector<int16_t> left;
    std::vector<int16_t> right;
    int rate = 0;
    bool truncated = false;
};

/** Teto do app (MAX_TRACK_MINUTES): faixas mais longas entram cortadas e o aviso sobe em `truncated`. */
constexpr int kMaxTrackMinutes = 15;

namespace detail {

inline uint32_t le32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24); }
inline uint16_t le16(const uint8_t *p) { return static_cast<uint16_t>(p[0] | (p[1] << 8)); }

inline int16_t fromFloat(float x) {
    if (!(x == x)) return 0;
    const float s = std::nearbyint(x * 32768.f);
    return static_cast<int16_t>(std::max(-32768.f, std::min(32767.f, s)));
}

inline void splitInterleaved(const int16_t *data, size_t frames, int channels, Pcm16 &out) {
    out.left.resize(frames);
    out.right.resize(frames);
    for (size_t i = 0; i < frames; ++i) {
        out.left[i] = data[i * static_cast<size_t>(channels)];
        out.right[i] = channels > 1 ? data[i * static_cast<size_t>(channels) + 1] : out.left[i];
    }
}

inline bool wav(const library::Bytes &bytes, Pcm16 &out, std::string &error) {
    if (bytes.size() < 44 || std::memcmp(bytes.data(), "RIFF", 4) != 0 || std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
        error = "não é WAV";
        return false;
    }
    int format = 0, channels = 0, bits = 0;
    size_t at = 12;
    const uint8_t *data = nullptr;
    size_t dataSize = 0;
    while (at + 8 <= bytes.size()) {
        const uint32_t size = le32(&bytes[at + 4]);
        const uint8_t *body = &bytes[at + 8];
        const size_t room = bytes.size() - (at + 8);
        if (std::memcmp(&bytes[at], "fmt ", 4) == 0 && size >= 16 && room >= 16) {
            format = le16(body);
            channels = le16(body + 2);
            out.rate = static_cast<int>(le32(body + 4));
            bits = le16(body + 14);
            if (format == 0xFFFE && size >= 26 && room >= 26) format = le16(body + 24);
        } else if (std::memcmp(&bytes[at], "data", 4) == 0) {
            data = body;
            dataSize = std::min<size_t>(size, room);
            break;
        }
        if (size > bytes.size()) break;
        at += 8 + size + (size & 1);
    }
    if (data == nullptr || channels < 1 || channels > 8 || out.rate <= 0 || out.rate > 384000) {
        error = "WAV sem fmt/data";
        return false;
    }
    if (!((format == 1 && (bits == 16 || bits == 24 || bits == 32)) || (format == 3 && bits == 32))) {
        error = "WAV não suportado (PCM 16/24/32 ou float 32)";
        return false;
    }
    const size_t width = static_cast<size_t>(bits / 8);
    size_t frames = dataSize / (width * static_cast<size_t>(channels));
    const size_t cap = static_cast<size_t>(out.rate) * 60u * kMaxTrackMinutes;
    if (frames > cap) {
        frames = cap;
        out.truncated = true;
    }
    out.left.resize(frames);
    out.right.resize(frames);
    auto sample = [&](const uint8_t *p) -> int16_t {
        if (format == 3) {
            float f;
            std::memcpy(&f, p, 4);
            return fromFloat(f);
        }
        if (bits == 16) return static_cast<int16_t>(le16(p));
        if (bits == 24) {
            int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
            if (v & 0x800000) v |= ~0xFFFFFF;
            return fromFloat(static_cast<float>(v) / 8388608.f);
        }
        return fromFloat(static_cast<float>(static_cast<int32_t>(le32(p))) / 2147483648.f);
    };
    for (size_t i = 0; i < frames; ++i) {
        const uint8_t *frame = data + i * width * static_cast<size_t>(channels);
        out.left[i] = sample(frame);
        out.right[i] = channels > 1 ? sample(frame + width) : out.left[i];
    }
    return true;
}

inline bool mp3(const library::Bytes &bytes, Pcm16 &out, std::string &error) {
    drmp3 dec;
    if (!drmp3_init_memory(&dec, bytes.data(), bytes.size(), nullptr)) {
        error = "MP3 inválido";
        return false;
    }
    const int channels = static_cast<int>(dec.channels);
    out.rate = static_cast<int>(dec.sampleRate);
    if (channels < 1 || channels > 2 || out.rate <= 0) {
        drmp3_uninit(&dec);
        error = "MP3 com formato fora do esperado";
        return false;
    }
    // Em blocos: não precisa saber a duração antes, e o vetor final é o único do tamanho da faixa.
    const size_t cap = static_cast<size_t>(out.rate) * 60u * kMaxTrackMinutes;
    std::vector<int16_t> chunk(4096u * static_cast<size_t>(channels));
    while (out.left.size() < cap) {
        const drmp3_uint64 got = drmp3_read_pcm_frames_s16(&dec, 4096, chunk.data());
        if (got == 0) break;
        for (drmp3_uint64 i = 0; i < got && out.left.size() < cap; ++i) {
            out.left.push_back(chunk[i * static_cast<size_t>(channels)]);
            out.right.push_back(channels > 1 ? chunk[i * static_cast<size_t>(channels) + 1] : chunk[i * static_cast<size_t>(channels)]);
        }
    }
    if (out.left.size() >= cap) out.truncated = drmp3_read_pcm_frames_s16(&dec, 1, chunk.data()) > 0;
    drmp3_uninit(&dec);
    out.left.shrink_to_fit();
    out.right.shrink_to_fit();
    if (out.left.empty()) {
        error = "MP3 sem áudio";
        return false;
    }
    return true;
}

inline bool flac(const library::Bytes &bytes, Pcm16 &out, std::string &error) {
    drflac *dec = drflac_open_memory(bytes.data(), bytes.size(), nullptr);
    if (dec == nullptr) {
        error = "FLAC inválido";
        return false;
    }
    const int channels = static_cast<int>(dec->channels);
    out.rate = static_cast<int>(dec->sampleRate);
    size_t frames = static_cast<size_t>(dec->totalPCMFrameCount);
    const size_t cap = static_cast<size_t>(out.rate) * 60u * kMaxTrackMinutes;
    if (channels < 1 || channels > 8 || out.rate <= 0 || frames == 0) {
        drflac_close(dec);
        error = "FLAC com formato fora do esperado";
        return false;
    }
    if (frames > cap) {
        frames = cap;
        out.truncated = true;
    }
    std::vector<int16_t> chunk(4096u * static_cast<size_t>(channels));
    out.left.resize(frames);
    out.right.resize(frames);
    size_t done = 0;
    while (done < frames) {
        const drflac_uint64 want = std::min<size_t>(4096, frames - done);
        const drflac_uint64 got = drflac_read_pcm_frames_s16(dec, want, chunk.data());
        if (got == 0) break;
        for (drflac_uint64 i = 0; i < got; ++i) {
            out.left[done + i] = chunk[i * static_cast<size_t>(channels)];
            out.right[done + i] = channels > 1 ? chunk[i * static_cast<size_t>(channels) + 1] : out.left[done + i];
        }
        done += got;
    }
    drflac_close(dec);
    out.left.resize(done);
    out.right.resize(done);
    return done > 0;
}

inline std::string lowerExt(const std::string &path) {
    const auto dot = path.find_last_of('.');
    std::string ext = dot == std::string::npos ? "" : path.substr(dot + 1);
    for (auto &c : ext) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return ext;
}

}  // namespace detail

/** Decodifica pelo conteúdo (cabeçalho), com a extensão só de desempate. */
inline bool decodeBytes(const library::Bytes &bytes, const std::string &nameForHint, Pcm16 &out, std::string &error) {
    out = Pcm16{};
    if (bytes.size() >= 12 && std::memcmp(bytes.data(), "RIFF", 4) == 0) return detail::wav(bytes, out, error);
    if (bytes.size() >= 4 && std::memcmp(bytes.data(), "fLaC", 4) == 0) return detail::flac(bytes, out, error);
    const std::string ext = detail::lowerExt(nameForHint);
    if (ext == "m4a" || ext == "aac" || ext == "mp4" || ext == "alac") {
        error = "AAC/M4A ainda não decodifica no Pi";
        return false;
    }
    return detail::mp3(bytes, out, error);
}

inline bool decodeFile(const std::string &path, Pcm16 &out, std::string &error) {
    const auto bytes = library::readFile(path);
    if (!bytes) {
        error = "não li o arquivo";
        return false;
    }
    return decodeBytes(*bytes, path, out, error);
}

}  // namespace deckbridge::audio
