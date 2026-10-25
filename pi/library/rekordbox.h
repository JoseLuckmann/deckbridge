// Leitura do pendrive do rekordbox em C++ para o Pi (ADR-0008): porta de DeviceSql.kt (export.pdb), Anlz.kt (ANLZ
// .DAT/.EXT/.2EX, mergeAnalysis) e das funções de onda e frase que eles usam. Verificada por RekordboxGoldenTest:
// o Kotlin e o C++ leem o mesmo volume sintético e escrevem o mesmo resumo (pi/library/testdata/summary.txt).
// Só leitura (OD-23): o volume é lido por open(O_RDONLY); nada aqui escreve no pendrive.
#pragma once

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <unordered_set>
#include <vector>

namespace deckbridge::library {

using Bytes = std::vector<uint8_t>;

// ---------- leitura de bytes com o mesmo "fora da faixa = -1" do Binary.kt ----------
inline int u8(const Bytes &b, long at) { return at < 0 || at >= static_cast<long>(b.size()) ? -1 : b[static_cast<size_t>(at)]; }
inline int u16le(const Bytes &b, long at) { return at < 0 || at + 1 >= static_cast<long>(b.size()) ? -1 : u8(b, at) | (u8(b, at + 1) << 8); }
inline int64_t u32le(const Bytes &b, long at) {
    if (at < 0 || at + 3 >= static_cast<long>(b.size())) return -1;
    return static_cast<int64_t>(u8(b, at)) | (static_cast<int64_t>(u8(b, at + 1)) << 8) | (static_cast<int64_t>(u8(b, at + 2)) << 16) |
           (static_cast<int64_t>(u8(b, at + 3)) << 24);
}
inline int u16be(const Bytes &b, long at) { return at < 0 || at + 1 >= static_cast<long>(b.size()) ? -1 : (u8(b, at) << 8) | u8(b, at + 1); }
inline int64_t u32be(const Bytes &b, long at) {
    if (at < 0 || at + 3 >= static_cast<long>(b.size())) return -1;
    return (static_cast<int64_t>(u8(b, at)) << 24) | (static_cast<int64_t>(u8(b, at + 1)) << 16) | (static_cast<int64_t>(u8(b, at + 2)) << 8) |
           static_cast<int64_t>(u8(b, at + 3));
}
/** Long.toInt() do Kotlin: os 32 bits de baixo, com sinal. */
inline int toInt(int64_t v) { return static_cast<int32_t>(static_cast<uint32_t>(v & 0xFFFFFFFF)); }

// ---------- texto: mesmas regras do decodificador do Java (UTF-8 na saída) ----------
inline void putUtf8(std::string &out, uint32_t cp) {
    if (cp < 0x80) out += static_cast<char>(cp);
    else if (cp < 0x800) {
        out += static_cast<char>(0xC0 | (cp >> 6));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
        out += static_cast<char>(0xE0 | (cp >> 12));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (cp >> 18));
        out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (cp & 0x3F));
    }
}

/** US_ASCII do Java: byte acima de 0x7F vira U+FFFD. */
inline std::string ascii(const Bytes &b, size_t from, size_t len) {
    std::string out;
    for (size_t i = from; i < from + len && i < b.size(); ++i) {
        if (b[i] < 0x80) out += static_cast<char>(b[i]);
        else putUtf8(out, 0xFFFD);
    }
    return out;
}

/** UTF-16 do Java (le ou be): par substituto vira um ponto de código; sozinho, ou byte sobrando, vira U+FFFD. */
inline std::string utf16(const Bytes &b, size_t from, size_t len, bool little) {
    std::string out;
    const size_t units = len / 2;
    auto unit = [&](size_t i) -> uint32_t {
        const size_t at = from + i * 2;
        return little ? (b[at] | (b[at + 1] << 8)) : ((b[at] << 8) | b[at + 1]);
    };
    for (size_t i = 0; i < units; ++i) {
        const uint32_t u = unit(i);
        if (u >= 0xD800 && u <= 0xDBFF && i + 1 < units) {
            const uint32_t v = unit(i + 1);
            if (v >= 0xDC00 && v <= 0xDFFF) {
                putUtf8(out, 0x10000 + ((u - 0xD800) << 10) + (v - 0xDC00));
                ++i;
                continue;
            }
        }
        if (u >= 0xD800 && u <= 0xDFFF) putUtf8(out, 0xFFFD);
        else putUtf8(out, u);
    }
    if (len % 2) putUtf8(out, 0xFFFD);
    return out;
}

inline std::string trimNul(std::string s) {
    while (!s.empty() && s.back() == '\0') s.pop_back();
    return s;
}

// ---------- export.pdb ----------
constexpr int kPageHeaderEnd = 40;
constexpr long kMaxPdbBytes = 32L * 1024 * 1024;
constexpr int kMaxPages = 20000;

struct PdbTrack {
    int id = 0, artistId = 0, albumId = 0, genreId = 0, keyId = 0;
    std::string title;
    double bpm = 0.0;
    int tempo100 = 0;  // BPM × 100 como está no arquivo
    int durationSeconds = 0, sampleRate = 0;
    std::string filePath, fileName, analyzePath;
    int artworkId = 0;
};
struct PdbName {
    int id = 0;
    std::string name;
    int artistId = 0;
};
struct PdbPlaylist {
    int id = 0, parentId = 0;
    std::string name;
    bool folder = false;
    int sortOrder = 0;
};
struct PdbEntry {
    int playlistId = 0, trackId = 0, position = 0;
};
struct PdbArtwork {
    int id = 0;
    std::string path;
};
struct PdbDatabase {
    std::vector<PdbTrack> tracks;
    std::vector<PdbName> artists, albums, genres, keys;
    std::vector<PdbPlaylist> playlists;
    std::vector<PdbEntry> entries;
    std::vector<PdbArtwork> artworks;
};
struct PdbParse {
    std::optional<PdbDatabase> database;
    std::string error;
};

namespace detail {

inline std::vector<long> rowStarts(const Bytes &b, long page, long pageLen) {
    std::vector<long> found;
    if (page + kPageHeaderEnd > static_cast<long>(b.size())) return found;
    const int64_t packed = u32le(b, page + 24);
    if (packed < 0) return found;
    const int numOffsets = static_cast<int>(packed & 0x1FFF);
    const int flags = static_cast<int>((packed >> 24) & 0xFF);
    if (numOffsets <= 0 || (flags & 0x40) != 0) return found;
    const int groups = (numOffsets - 1) / 16 + 1;
    for (int group = 0; group < groups; ++group) {
        const long base = page + pageLen - group * 0x24;
        const long flagsAt = base - 4;
        if (flagsAt < page || flagsAt + 1 >= static_cast<long>(b.size())) continue;
        const int present = u16le(b, flagsAt);
        const int rows = std::min(16, numOffsets - group * 16);
        for (int row = 0; row < rows; ++row) {
            if (((present >> row) & 1) == 0) continue;
            const long pos = base - (6 + 2 * row);
            if (pos < page || pos + 1 >= static_cast<long>(b.size())) continue;
            const int heap = u16le(b, pos);
            if (heap < 0) continue;
            const long start = page + kPageHeaderEnd + heap;
            if (start >= page && start < page + pageLen) found.push_back(start);
        }
    }
    return found;
}

template <class OnRow>
void readTable(const Bytes &b, long pageLen, int type, int first, int last, OnRow onRow) {
    int index = first;
    std::unordered_set<int> seen;
    int guard = 0;
    while (guard++ < kMaxPages && seen.insert(index).second) {
        if (index < 0) break;
        const long offset = static_cast<long>(index) * pageLen;
        if (offset < 0 || offset + pageLen > static_cast<long>(b.size())) break;
        if (toInt(u32le(b, offset + 8)) != type) break;
        const long pageEnd = offset + pageLen;
        for (long row : rowStarts(b, offset, pageLen)) onRow(row, pageEnd);
        if (index == last) break;
        const int next = toInt(u32le(b, offset + 12));
        if (next == index) break;
        index = next;
    }
}

inline std::string deviceString(const Bytes &b, long at, long limit) {
    if (at < 0 || at >= static_cast<long>(b.size()) || at >= limit) return "";
    const int kind = u8(b, at);
    if (kind == 0x40 || kind == 0x90) {
        if (at + 4 > static_cast<long>(b.size()) || at + 4 > limit) return "";
        const int declared = u16le(b, at + 1);
        const long textLen = declared - 4;
        const long start = at + 4;
        if (textLen < 0 || start + textLen > static_cast<long>(b.size()) || start + textLen > limit) return "";
        return trimNul(kind == 0x90 ? utf16(b, static_cast<size_t>(start), static_cast<size_t>(textLen), true)
                                    : ascii(b, static_cast<size_t>(start), static_cast<size_t>(textLen)));
    }
    if (kind % 2 == 0) return "";
    const long textLen = (kind >> 1) - 1;
    if (textLen < 0 || at + 1 + textLen > static_cast<long>(b.size()) || at + 1 + textLen > limit) return "";
    return ascii(b, static_cast<size_t>(at + 1), static_cast<size_t>(textLen));
}

inline std::optional<PdbTrack> readTrack(const Bytes &b, long at, long pageEnd) {
    if (at + 136 > static_cast<long>(b.size())) return std::nullopt;
    auto str = [&](int slot) -> std::string {
        const int ofs = u16le(b, at + 94 + slot * 2);
        if (ofs < 0) return "";
        return deviceString(b, at + ofs, pageEnd);
    };
    PdbTrack t;
    const int64_t tempo = u32le(b, at + 56);
    t.id = toInt(u32le(b, at + 72));
    t.title = str(17);
    t.artistId = toInt(u32le(b, at + 68));
    t.albumId = toInt(u32le(b, at + 64));
    t.genreId = toInt(u32le(b, at + 60));
    t.keyId = toInt(u32le(b, at + 32));
    t.bpm = static_cast<double>(tempo) / 100.0;
    t.tempo100 = static_cast<int>(tempo);
    t.durationSeconds = u16le(b, at + 84);
    t.sampleRate = toInt(u32le(b, at + 8));
    t.filePath = str(20);
    t.fileName = str(19);
    t.analyzePath = str(14);
    t.artworkId = toInt(u32le(b, at + 28));
    return t;
}

inline std::optional<PdbName> readNamed(const Bytes &b, long at, int stringAt, long pageEnd) {
    if (at + stringAt >= static_cast<long>(b.size())) return std::nullopt;
    return PdbName{toInt(u32le(b, at)), deviceString(b, at + stringAt, pageEnd), 0};
}

inline std::optional<PdbName> readArtist(const Bytes &b, long at, long pageEnd) {
    if (at + 10 > static_cast<long>(b.size())) return std::nullopt;
    const int subtype = u16le(b, at);
    const int nameAt = (subtype & 0x04) ? u16le(b, at + 0x0A) : u8(b, at + 9);
    if (nameAt < 0) return std::nullopt;
    return PdbName{toInt(u32le(b, at + 4)), deviceString(b, at + nameAt, pageEnd), 0};
}

inline std::optional<PdbName> readAlbum(const Bytes &b, long at, long pageEnd) {
    if (at + 22 > static_cast<long>(b.size())) return std::nullopt;
    const int subtype = u16le(b, at);
    const int nameAt = (subtype & 0x04) ? u16le(b, at + 0x16) : u8(b, at + 21);
    if (nameAt < 0) return std::nullopt;
    return PdbName{toInt(u32le(b, at + 12)), deviceString(b, at + nameAt, pageEnd), toInt(u32le(b, at + 8))};
}

inline std::optional<PdbPlaylist> readPlaylist(const Bytes &b, long at, long pageEnd) {
    if (at + 20 >= static_cast<long>(b.size())) return std::nullopt;
    return PdbPlaylist{toInt(u32le(b, at + 12)), toInt(u32le(b, at)), deviceString(b, at + 20, pageEnd), u32le(b, at + 16) != 0,
                       toInt(u32le(b, at + 8))};
}

inline std::optional<PdbEntry> readEntry(const Bytes &b, long at) {
    if (at + 12 > static_cast<long>(b.size())) return std::nullopt;
    return PdbEntry{toInt(u32le(b, at + 8)), toInt(u32le(b, at + 4)), toInt(u32le(b, at))};
}

inline std::optional<PdbArtwork> readArtwork(const Bytes &b, long at, long pageEnd) {
    if (at + 5 > static_cast<long>(b.size())) return std::nullopt;
    const int id = toInt(u32le(b, at));
    if (id == 0) return std::nullopt;
    std::string path = deviceString(b, at + 4, pageEnd);
    if (path.find_first_not_of(" \t\r\n") == std::string::npos) return std::nullopt;
    return PdbArtwork{id, path};
}

}  // namespace detail

inline PdbParse parseExportPdb(const Bytes &b) {
    PdbParse out;
    if (b.size() < 28 || static_cast<long>(b.size()) > kMaxPdbBytes) {
        out.error = "E_RB_UNSUPPORTED_VARIANT";
        return out;
    }
    const int pageLen = toInt(u32le(b, 4));
    const int numTables = toInt(u32le(b, 8));
    if (pageLen < 128 || pageLen > 65536 || numTables < 1 || numTables > 64 || 28L + numTables * 16L > static_cast<long>(b.size())) {
        out.error = "E_RB_UNSUPPORTED_VARIANT";
        return out;
    }
    PdbDatabase db;
    for (int table = 0; table < numTables; ++table) {
        const long base = 28 + table * 16;
        const int type = toInt(u32le(b, base));
        const int first = toInt(u32le(b, base + 8));
        const int last = toInt(u32le(b, base + 12));
        detail::readTable(b, pageLen, type, first, last, [&](long row, long pageEnd) {
            switch (type) {
                case 0: if (auto t = detail::readTrack(b, row, pageEnd)) db.tracks.push_back(*t); break;
                case 1: if (auto n = detail::readNamed(b, row, 4, pageEnd)) db.genres.push_back(*n); break;
                case 2: if (auto n = detail::readArtist(b, row, pageEnd)) db.artists.push_back(*n); break;
                case 3: if (auto n = detail::readAlbum(b, row, pageEnd)) db.albums.push_back(*n); break;
                case 5: if (auto n = detail::readNamed(b, row, 8, pageEnd)) db.keys.push_back(*n); break;
                case 7: if (auto p = detail::readPlaylist(b, row, pageEnd)) db.playlists.push_back(*p); break;
                case 8: if (auto e = detail::readEntry(b, row)) db.entries.push_back(*e); break;
                case 0x0d: if (auto a = detail::readArtwork(b, row, pageEnd)) db.artworks.push_back(*a); break;
                default: break;
            }
        });
    }
    out.database = std::move(db);
    return out;
}

// ---------- ondas e frases (WaveColor.kt, ClubFloor.kt) ----------
inline int scale3(int bits) { return std::clamp(bits, 0, 7) * 255 / 7; }
inline int scale5(int bits) { return std::clamp(bits, 0, 31) * 255 / 31; }
inline int decodeMonoByte(int raw) { return scale5(raw & 0x1F); }
inline int blendRgb(int low, int mid, int high) { return (std::clamp(low, 0, 255) << 16) | (std::clamp(mid, 0, 255) << 8) | std::clamp(high, 0, 255); }

struct WaveBands {
    Bytes low, mid, high;
    int columns() const { return static_cast<int>(std::min({low.size(), mid.size(), high.size()})); }
};
struct ColorWave {
    Bytes height;
    std::vector<int32_t> rgb;
    double perSecond = 0.0;
    std::string source;
};
struct PhraseSpan {
    int startMs = 0, endMs = 0;
    std::string kind, label, mood;
    std::optional<int> fillMs;
};

inline std::string phraseFamily(const std::string &label) {
    for (const char *f : {"Intro", "Up", "Down", "Chorus", "Bridge", "Verse", "Outro"}) {
        if (label.rfind(f, 0) == 0) return f;
    }
    return label;
}

inline std::string pssiLabel(int mood, int kind, int k1, int k2, int k3) {
    if (mood == 1) {
        switch (kind) {
            case 1: return k1 == 0 ? "Intro 2" : "Intro 1";
            case 2:
                if (k2 == 0 && k3 == 0) return "Up 1";
                if (k2 == 0 && k3 == 1) return "Up 2";
                return "Up 3";
            case 3: return "Down";
            case 5: return k1 == 0 ? "Chorus 2" : "Chorus 1";
            case 6: return k1 == 0 ? "Outro 2" : "Outro 1";
            default: return "Up";
        }
    }
    static const char *low[] = {"Intro", "Verse 1", "Verse 1", "Verse 1", "Verse 2", "Verse 2", "Verse 2", "Bridge", "Chorus", "Outro"};
    static const char *mid[] = {"Intro", "Verse 1", "Verse 2", "Verse 3", "Verse 4", "Verse 5", "Verse 6", "Bridge", "Chorus", "Outro"};
    if (kind < 1 || kind > 10) return "Verse";
    return mood == 3 ? low[kind - 1] : mid[kind - 1];
}

inline void unmaskPssi(Bytes &body, int count) {
    static const int base[] = {0xCB, 0xE1, 0xEE, 0xFA, 0xE5, 0xEE, 0xAD, 0xEE, 0xE9, 0xD2, 0xE9, 0xEB, 0xE1, 0xE9, 0xF3, 0xE8, 0xE9, 0xF4, 0xE1};
    for (size_t i = 0; i < body.size(); ++i) body[i] = static_cast<uint8_t>(body[i] ^ ((base[i % 19] + (count & 0xFF)) & 0xFF));
}

// ---------- ANLZ ----------
struct AnlzBeat {
    int beatInBar = 0;
    int bpm100 = 0;  // BPM × 100 como está no arquivo (o Kotlin guarda bpm = bpm100 / 100.0)
    int timeMs = 0;
    double bpm() const { return bpm100 / 100.0; }
};
struct AnlzCue {
    std::optional<int> hotIndex;
    int timeMs = 0;
    std::optional<int> loopEndMs;
    std::string comment;
    std::optional<int> colorRgb;
};
struct AnlzFile {
    std::vector<AnlzBeat> beats;
    std::vector<AnlzCue> cues;
    Bytes waveform;
    std::optional<WaveBands> bands;
    std::string waveSource;
    std::vector<std::string> issues;
    std::optional<ColorWave> rgbDetail, rgbPreview;
    std::optional<WaveBands> bandDetail, bandPreview;
    Bytes blueDetail, bluePreview;
    std::vector<PhraseSpan> phrases;
    std::string phraseMood;
};

namespace detail {

inline AnlzCue cueFrom(int hot, int type, int timeMs, int loopMs, std::string comment, std::optional<int> color) {
    AnlzCue c;
    c.hotIndex = hot > 0 ? std::optional<int>(hot - 1) : std::nullopt;
    c.timeMs = timeMs;
    c.loopEndMs = (type == 2 && loopMs >= timeMs) ? std::optional<int>(loopMs) : std::nullopt;
    c.comment = std::move(comment);
    c.colorRgb = color;
    return c;
}

inline std::vector<AnlzBeat> readBeats(const Bytes &b, long at, long end) {
    std::vector<AnlzBeat> out;
    if (at + 12 > end) return out;
    const int count = toInt(u32be(b, at + 8));
    if (count < 0 || count > 100000) return out;
    long cursor = at + 12;
    for (int i = 0; i < count; ++i) {
        if (cursor + 8 > end) return out;
        out.push_back({u16be(b, cursor), u16be(b, cursor + 2), toInt(u32be(b, cursor + 4))});
        cursor += 8;
    }
    return out;
}

inline std::vector<AnlzCue> readCues(const Bytes &b, long at, long end) {
    std::vector<AnlzCue> out;
    if (at + 12 > end) return out;
    const int count = u16be(b, at + 6);
    long cursor = at + 12;
    for (int i = 0; i < count; ++i) {
        if (cursor + 56 > end) return out;
        if (ascii(b, static_cast<size_t>(cursor), 4) == "PCPT") {
            out.push_back(cueFrom(toInt(u32be(b, cursor + 12)), u8(b, cursor + 28), toInt(u32be(b, cursor + 32)), toInt(u32be(b, cursor + 36)), "",
                                  std::nullopt));
        }
        cursor += 56;
    }
    return out;
}

inline std::vector<AnlzCue> readCues2(const Bytes &b, long at, long end) {
    std::vector<AnlzCue> out;
    if (at + 8 > end) return out;
    const int count = u16be(b, at + 4);
    long cursor = at + 8;
    for (int i = 0; i < count; ++i) {
        if (cursor + 12 > end) return out;
        const int lenEntry = toInt(u32be(b, cursor + 8));
        if (lenEntry < 40 || cursor + lenEntry > end) return out;
        if (ascii(b, static_cast<size_t>(cursor), 4) != "PCP2") {
            cursor += lenEntry;
            continue;
        }
        const int hot = toInt(u32be(b, cursor + 12));
        const int type = u8(b, cursor + 16);
        const int timeMs = toInt(u32be(b, cursor + 20));
        const int loopMs = toInt(u32be(b, cursor + 24));
        std::string comment;
        std::optional<int> color;
        if (lenEntry > 43 && cursor + 44 <= end) {
            const int lenComment = toInt(u32be(b, cursor + 40));
            const long commentAt = cursor + 44;
            if (lenComment >= 0 && lenComment <= 4096 && commentAt + lenComment <= cursor + lenEntry) {
                comment = trimNul(utf16(b, static_cast<size_t>(commentAt), static_cast<size_t>(lenComment), false));
                const long colorAt = commentAt + lenComment;
                if (lenEntry - lenComment > 47 && colorAt + 4 <= cursor + lenEntry) {
                    color = (u8(b, colorAt + 1) << 16) | (u8(b, colorAt + 2) << 8) | u8(b, colorAt + 3);
                }
            }
        }
        out.push_back(cueFrom(hot, type, timeMs, loopMs, comment, color));
        cursor += lenEntry;
    }
    return out;
}

inline Bytes slice(const Bytes &b, long from, long to) { return Bytes(b.begin() + from, b.begin() + to); }

inline std::optional<WaveBands> readBands(const Bytes &b, long at, long end) {
    if (at + 4 > end) return std::nullopt;
    const int count = toInt(u32be(b, at));
    const long start = at + 4;
    if (count <= 0 || count > 4000 || start + static_cast<long>(count) * 3 > end) return std::nullopt;
    return WaveBands{slice(b, start, start + count), slice(b, start + count, start + 2L * count), slice(b, start + 2L * count, start + 3L * count)};
}

inline std::optional<ColorWave> readPwv5(const Bytes &b, long at, long end) {
    if (at + 24 > end) return std::nullopt;
    const int entry = toInt(u32be(b, at + 12));
    const int count = toInt(u32be(b, at + 16));
    if (entry != 2 || count <= 0 || count > 500000) return std::nullopt;
    const long start = at + 24;
    if (start + static_cast<long>(count) * 2 > end) return std::nullopt;
    ColorWave w;
    w.height.resize(static_cast<size_t>(count));
    w.rgb.resize(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const int word = u16be(b, start + i * 2L);
        if (word < 0) return std::nullopt;
        const int red = scale3((word >> 13) & 7), green = scale3((word >> 10) & 7), blue = scale3((word >> 7) & 7);
        w.height[static_cast<size_t>(i)] = static_cast<uint8_t>(scale5((word >> 2) & 31));
        w.rgb[static_cast<size_t>(i)] = (red << 16) | (green << 8) | blue;
    }
    w.perSecond = 150.0;
    w.source = "PWV5";
    return w;
}

inline std::optional<ColorWave> readPwv4(const Bytes &b, long at, long end) {
    if (at + 24 > end) return std::nullopt;
    const int entry = toInt(u32be(b, at + 12));
    const int count = toInt(u32be(b, at + 16));
    if (entry != 6 || count <= 0 || count > 4000) return std::nullopt;
    const long start = at + 24;
    if (start + static_cast<long>(count) * 6 > end) return std::nullopt;
    ColorWave w;
    w.height.resize(static_cast<size_t>(count));
    w.rgb.resize(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const long column = start + i * 6L;
        const int low = u8(b, column + 3), mid = u8(b, column + 4), high = u8(b, column + 5);
        if (low < 0 || mid < 0 || high < 0) return std::nullopt;
        w.height[static_cast<size_t>(i)] = static_cast<uint8_t>(std::max({low, mid, high}));
        w.rgb[static_cast<size_t>(i)] = blendRgb(low, mid, high);
    }
    w.perSecond = 0.0;
    w.source = "PWV4";
    return w;
}

inline Bytes readPwv3(const Bytes &b, long at, long end) {
    if (at + 24 > end) return {};
    const int entry = toInt(u32be(b, at + 12));
    const int count = toInt(u32be(b, at + 16));
    if (entry != 1 || count <= 0 || count > 500000) return {};
    const long start = at + 24;
    if (start + count > end) return {};
    Bytes out(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) out[static_cast<size_t>(i)] = static_cast<uint8_t>(decodeMonoByte(u8(b, start + i)));
    return out;
}

inline WaveBands bandsMidHighLow(const Bytes &b, long start, int count) {
    WaveBands w;
    w.low.resize(static_cast<size_t>(count));
    w.mid.resize(static_cast<size_t>(count));
    w.high.resize(static_cast<size_t>(count));
    for (int i = 0; i < count; ++i) {
        const size_t at = static_cast<size_t>(start + i * 3L);
        w.mid[static_cast<size_t>(i)] = b[at];
        w.high[static_cast<size_t>(i)] = b[at + 1];
        w.low[static_cast<size_t>(i)] = b[at + 2];
    }
    return w;
}

inline std::optional<WaveBands> readPwv67(const Bytes &b, long at, long end, int header, int maxCount) {
    if (at + header > end) return std::nullopt;
    const int entry = toInt(u32be(b, at + 12));
    const int count = toInt(u32be(b, at + 16));
    if (entry != 3 || count <= 0 || count > maxCount) return std::nullopt;
    const long start = at + header;
    if (start + static_cast<long>(count) * 3 > end) return std::nullopt;
    return bandsMidHighLow(b, start, count);
}

inline Bytes previewHeights(const Bytes &raw) {
    Bytes out(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) out[i] = static_cast<uint8_t>(decodeMonoByte(raw[i]));
    return out;
}

inline Bytes readWave(const Bytes &b, long at, long end) {
    if (at + 8 > end) return {};
    const int len = toInt(u32be(b, at));
    const long start = at + 8;
    if (len <= 0 || start + len > end || len > 200000) return {};
    return slice(b, start, start + len);
}

inline std::optional<std::pair<std::vector<PhraseSpan>, std::string>> readPssi(const Bytes &tag, const std::vector<AnlzBeat> &beats) {
    if (tag.size() < 18 + 14) return std::nullopt;
    if (toInt(u32be(tag, 12)) != 24) return std::nullopt;
    const int count = u16be(tag, 16);
    if (count <= 0 || count > 512) return std::nullopt;
    Bytes body(tag.begin() + 18, tag.end());
    if (body.size() >= 8 && u16be(body, 6) > 20) unmaskPssi(body, count);
    if (body.size() < 14) return std::nullopt;
    const int mood = u16be(body, 0);
    if (mood < 1 || mood > 3) return std::nullopt;
    const int endBeat = u16be(body, 8);
    const std::string moodName = mood == 1 ? "high" : mood == 3 ? "low" : "mid";
    std::vector<std::array<int, 7>> raw;
    long cursor = 14;
    for (int i = 0; i < count; ++i) {
        if (cursor + 24 > static_cast<long>(body.size())) continue;
        raw.push_back({u16be(body, cursor + 2), u16be(body, cursor + 4), u8(body, cursor + 7), u8(body, cursor + 9), u8(body, cursor + 19),
                       u8(body, cursor + 21), u16be(body, cursor + 22)});
        cursor += 24;
    }
    if (raw.empty()) return std::nullopt;
    auto beatMs = [&](int beat) -> int {
        if (beat <= 0) return 0;
        if (beat - 1 < static_cast<int>(beats.size())) return beats[static_cast<size_t>(beat - 1)].timeMs;
        const double bpm = beats.empty() ? 120.0 : beats.front().bpm();
        return static_cast<int>((beat - 1) * 60000.0 / std::max(bpm, 1.0));
    };
    std::vector<PhraseSpan> spans;
    for (size_t i = 0; i < raw.size(); ++i) {
        const auto &row = raw[i];
        PhraseSpan p;
        p.label = pssiLabel(mood, row[1], row[2], row[3], row[4]);
        p.startMs = beatMs(row[0]);
        const int endBeatNo = i + 1 < raw.size() ? raw[i + 1][0] : endBeat;
        p.endMs = std::max(beatMs(endBeatNo), p.startMs);
        if (row[5] != 0) p.fillMs = beatMs(row[6]);
        p.kind = phraseFamily(p.label);
        p.mood = moodName;
        spans.push_back(p);
    }
    return std::make_pair(spans, moodName);
}

}  // namespace detail

inline AnlzFile parseAnlz(const Bytes &b) {
    AnlzFile f;
    if (b.size() < 12 || ascii(b, 0, 4) != "PMAI") {
        f.issues.push_back("E_ANLZ_MAGIC");
        return f;
    }
    const int header = toInt(u32be(b, 4));
    if (header < 12 || header > static_cast<int>(b.size())) {
        f.issues.push_back("E_ANLZ_HEADER");
        return f;
    }
    // Os ANLZ reais guardam os mesmos cues em PCOB (básico) e em PCO2 (estendido): vale um só, o estendido.
    std::vector<AnlzCue> basicCues;
    std::optional<Bytes> phraseTag;
    std::optional<WaveBands> pwv7, pwv6, pwb3;
    std::optional<ColorWave> pwv5, pwv4;
    Bytes pwv3;
    long cursor = header;
    while (cursor + 12 <= static_cast<long>(b.size())) {
        const std::string tag = ascii(b, static_cast<size_t>(cursor), 4);
        const int lenTag = toInt(u32be(b, cursor + 8));
        if (lenTag < 12 || cursor + lenTag > static_cast<long>(b.size())) {
            f.issues.push_back("E_ANLZ_SECTION");
            break;
        }
        const long body = cursor + 12, bodyEnd = cursor + lenTag;
        if (tag == "PQTZ") {
            auto more = detail::readBeats(b, body, bodyEnd);
            f.beats.insert(f.beats.end(), more.begin(), more.end());
        } else if (tag == "PCOB") {
            auto more = detail::readCues(b, body, bodyEnd);
            basicCues.insert(basicCues.end(), more.begin(), more.end());
        } else if (tag == "PCO2") {
            auto more = detail::readCues2(b, body, bodyEnd);
            f.cues.insert(f.cues.end(), more.begin(), more.end());
        } else if (tag == "PWAV" || tag == "PWV2") {
            if (f.waveform.empty()) f.waveform = detail::readWave(b, body, bodyEnd);
        } else if (tag == "PWV5") pwv5 = detail::readPwv5(b, cursor, bodyEnd);
        else if (tag == "PWV4") pwv4 = detail::readPwv4(b, cursor, bodyEnd);
        else if (tag == "PWV3") pwv3 = detail::readPwv3(b, cursor, bodyEnd);
        else if (tag == "PWV7") pwv7 = detail::readPwv67(b, cursor, bodyEnd, 24, 500000);
        else if (tag == "PWV6") pwv6 = detail::readPwv67(b, cursor, bodyEnd, 20, 4000);
        else if (tag == "PWB3") pwb3 = detail::readBands(b, body, bodyEnd);
        else if (tag == "PSSI") phraseTag = detail::slice(b, cursor, bodyEnd);
        cursor += lenTag;
    }
    f.bands = pwv7 ? pwv7 : pwb3;
    if (pwv7) f.waveSource = "PWV7";
    else if (pwv5) f.waveSource = "PWV5";
    else if (!pwv3.empty()) f.waveSource = "PWV3";
    else if (pwb3) f.waveSource = "PWB3";
    else if (!f.waveform.empty()) f.waveSource = "PWAV";
    f.bluePreview = detail::previewHeights(f.waveform);
    if (phraseTag) {
        if (auto parsed = detail::readPssi(*phraseTag, f.beats)) {
            f.phrases = parsed->first;
            f.phraseMood = parsed->second;
        }
    }
    f.rgbDetail = pwv5;
    f.rgbPreview = pwv4;
    f.bandDetail = pwv7;
    f.bandPreview = pwv6;
    f.blueDetail = pwv3;
    if (f.cues.empty()) f.cues = basicCues;
    return f;
}

/** mergeAnalysis do Kotlin: .DAT, .EXT e .2EX da mesma faixa juntos, com a mesma precedência. */
inline AnlzFile mergeAnalysis(const AnlzFile *dat, const AnlzFile *ext, const AnlzFile *ex2) {
    AnlzFile m;
    if (dat && !dat->beats.empty()) m.beats = dat->beats;
    else if (ext && !ext->beats.empty()) m.beats = ext->beats;
    else if (ex2) m.beats = ex2->beats;
    m.cues = (ext && !ext->cues.empty()) ? ext->cues : (dat ? dat->cues : std::vector<AnlzCue>{});
    if (dat && !dat->waveform.empty()) m.waveform = dat->waveform;
    else if (ext) m.waveform = ext->waveform;
    else if (ex2) m.waveform = ex2->waveform;
    auto first = [](auto pick, const AnlzFile *a, const AnlzFile *b2, const AnlzFile *c) -> decltype(pick(*a)) {
        for (const AnlzFile *f : {a, b2, c}) {
            if (f && pick(*f)) return pick(*f);
        }
        return std::nullopt;
    };
    m.rgbDetail = first([](const AnlzFile &f) { return f.rgbDetail; }, ext, dat, ex2);
    m.rgbPreview = first([](const AnlzFile &f) { return f.rgbPreview; }, ext, dat, ex2);
    m.bandDetail = first([](const AnlzFile &f) { return f.bandDetail; }, ex2, ext, dat);
    m.bandPreview = first([](const AnlzFile &f) { return f.bandPreview; }, ex2, ext, dat);
    for (const AnlzFile *f : {ext, dat, ex2}) {
        if (f && !f->blueDetail.empty()) {
            m.blueDetail = f->blueDetail;
            break;
        }
    }
    m.bluePreview = detail::previewHeights(m.waveform);
    m.bands = m.bandDetail ? m.bandDetail : first([](const AnlzFile &f) { return f.bands; }, dat, ext, ex2);
    if (m.bandDetail) m.waveSource = "PWV7";
    else if (m.rgbDetail) m.waveSource = "PWV5";
    else if (!m.blueDetail.empty()) m.waveSource = "PWV3";
    else if (m.bands) m.waveSource = "PWB3";
    else if (!m.waveform.empty()) m.waveSource = "PWAV";
    for (const AnlzFile *f : {dat, ext, ex2}) {
        if (f) m.issues.insert(m.issues.end(), f->issues.begin(), f->issues.end());
    }
    for (const AnlzFile *f : {ext, dat, ex2}) {
        if (f && !f->phrases.empty()) {
            m.phrases = f->phrases;
            m.phraseMood = f->phraseMood;
            break;
        }
    }
    return m;
}

// ---------- volume (só leitura) ----------
/** Lê um arquivo do volume. O(RDONLY) e nada mais: o pendrive nunca é aberto para escrita. */
inline std::optional<Bytes> readFile(const std::string &path, size_t maxBytes = 256u * 1024 * 1024) {
    const int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return std::nullopt;
    struct stat st {};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || static_cast<size_t>(st.st_size) > maxBytes) {
        ::close(fd);
        return std::nullopt;
    }
    Bytes out(static_cast<size_t>(st.st_size));
    size_t got = 0;
    while (got < out.size()) {
        const ssize_t n = ::read(fd, out.data() + got, out.size() - got);
        if (n <= 0) break;
        got += static_cast<size_t>(n);
    }
    ::close(fd);
    out.resize(got);
    return out;
}

inline std::string normalizeRekordboxPath(std::string path) {
    std::replace(path.begin(), path.end(), '\\', '/');
    const auto a = path.find_first_not_of(" \t\r\n");
    const auto z = path.find_last_not_of(" \t\r\n");
    path = a == std::string::npos ? "" : path.substr(a, z - a + 1);
    const auto drive = path.find(":/");
    if (drive != std::string::npos && drive <= 2) path = path.substr(drive + 2);
    while (!path.empty() && path[0] == '/') path.erase(0, 1);
    return path;
}

}  // namespace deckbridge::library
