// O leitor C++ do pendrive tem de ler o volume sintético (pi/library/testdata/usb) e escrever o mesmo resumo que o
// Kotlin escreveu (pi/library/testdata/summary.txt, RekordboxGoldenTest). Também mede o tempo de leitura.
#include "rekordbox.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>

using namespace deckbridge::library;

namespace {

uint32_t crc32(const uint8_t *data, size_t n) {
    static uint32_t table[256];
    static bool ready = false;
    if (!ready) {
        for (uint32_t i = 0; i < 256; ++i) {
            uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        ready = true;
    }
    uint32_t c = 0xFFFFFFFFu;
    for (size_t i = 0; i < n; ++i) c = table[(c ^ data[i]) & 0xFF] ^ (c >> 8);
    return c ^ 0xFFFFFFFFu;
}

std::string hex(uint32_t v) {
    char buf[16];
    std::snprintf(buf, sizeof buf, "%x", v);
    return buf;
}
std::string crc(const Bytes &b) { return hex(crc32(b.data(), b.size())); }
std::string crc(const std::string &s) { return hex(crc32(reinterpret_cast<const uint8_t *>(s.data()), s.size())); }
std::string crcInts(const std::vector<int32_t> &v) {
    Bytes out;
    for (int32_t x : v) {
        const uint32_t u = static_cast<uint32_t>(x);
        out.push_back(static_cast<uint8_t>(u >> 24));
        out.push_back(static_cast<uint8_t>(u >> 16));
        out.push_back(static_cast<uint8_t>(u >> 8));
        out.push_back(static_cast<uint8_t>(u));
    }
    return crc(out);
}
std::string bands(const std::optional<WaveBands> &w) {
    if (!w) return "-";
    return std::to_string(w->low.size()) + ":" + crc(w->low) + ":" + crc(w->mid) + ":" + crc(w->high);
}
std::string color(const std::optional<ColorWave> &w) {
    if (!w) return "-";
    return std::to_string(w->height.size()) + ":" + crc(w->height) + ":" + crcInts(w->rgb) + ":" + w->source + ":" +
           std::to_string(static_cast<int>(w->perSecond * 100));
}
std::string opt(const std::optional<int> &v) { return v ? std::to_string(*v) : "-"; }

std::string summary(const std::string &usb) {
    std::ostringstream out;
    out << "# Gerado por RekordboxGoldenTest (Kotlin): o que o app lê do volume em testdata/usb.\n";
    const auto pdb = readFile(usb + "/PIONEER/rekordbox/export.pdb");
    if (!pdb) return "sem export.pdb";
    const auto parsed = parseExportPdb(*pdb);
    if (!parsed.database) return "erro " + parsed.error;
    const PdbDatabase &db = *parsed.database;
    for (const auto &t : db.tracks) {
        out << "T " << t.id << "|" << t.title << "|" << t.artistId << "|" << t.albumId << "|" << t.genreId << "|" << t.keyId << "|"
            << std::llround(t.bpm * 100) << "|" << t.durationSeconds << "|" << t.sampleRate << "|" << t.filePath << "|" << t.fileName << "|"
            << t.analyzePath << "|" << t.artworkId << "\n";
    }
    const std::pair<const char *, const std::vector<PdbName> *> names[] = {{"artist", &db.artists}, {"album", &db.albums}, {"genre", &db.genres}, {"key", &db.keys}};
    for (const auto &[kind, list] : names) {
        for (const auto &n : *list) out << "N " << kind << " " << n.id << "|" << n.name << "|" << n.artistId << "\n";
    }
    for (const auto &p : db.playlists) out << "P " << p.id << "|" << p.parentId << "|" << p.name << "|" << (p.folder ? "true" : "false") << "|" << p.sortOrder << "\n";
    for (const auto &e : db.entries) out << "E " << e.playlistId << "|" << e.trackId << "|" << e.position << "\n";
    for (const auto &a : db.artworks) out << "A " << a.id << "|" << a.path << "\n";
    for (const auto &t : db.tracks) {
        const std::string path = normalizeRekordboxPath(t.analyzePath);
        if (path.empty()) continue;
        std::string stem = path;
        if (stem.size() >= 4) {
            std::string tail = stem.substr(stem.size() - 4);
            for (auto &c : tail) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
            if (tail == ".DAT") stem.resize(stem.size() - 4);
        }
        std::optional<AnlzFile> dat, ext, ex2;
        if (auto b = readFile(usb + "/" + path)) dat = parseAnlz(*b);
        if (auto b = readFile(usb + "/" + stem + ".EXT")) ext = parseAnlz(*b);
        if (auto b = readFile(usb + "/" + stem + ".2EX")) ex2 = parseAnlz(*b);
        const AnlzFile m = mergeAnalysis(dat ? &*dat : nullptr, ext ? &*ext : nullptr, ex2 ? &*ex2 : nullptr);
        std::string issues;
        for (size_t i = 0; i < m.issues.size(); ++i) issues += (i ? "," : "") + m.issues[i];
        out << "Z " << t.id << " source=" << m.waveSource << " issues=" << issues << "\n";
        std::string beats;
        for (size_t i = 0; i < m.beats.size(); ++i) {
            const auto &b = m.beats[i];
            beats += (i ? "," : "") + std::to_string(b.beatInBar) + "/" + std::to_string(std::llround(b.bpm() * 100)) + "/" + std::to_string(b.timeMs);
        }
        out << "  beats " << m.beats.size() << " " << crc(beats) << "\n";
        for (const auto &c : m.cues) out << "  cue " << opt(c.hotIndex) << "|" << c.timeMs << "|" << opt(c.loopEndMs) << "|" << c.comment << "|" << opt(c.colorRgb) << "\n";
        out << "  wave " << m.waveform.size() << ":" << crc(m.waveform) << " blueP " << m.bluePreview.size() << ":" << crc(m.bluePreview) << " blueD "
            << m.blueDetail.size() << ":" << crc(m.blueDetail) << "\n";
        out << "  rgbD " << color(m.rgbDetail) << " rgbP " << color(m.rgbPreview) << "\n";
        out << "  bandD " << bands(m.bandDetail) << " bandP " << bands(m.bandPreview) << " bands " << bands(m.bands) << "\n";
        out << "  mood " << m.phraseMood << "\n";
        for (const auto &p : m.phrases) out << "  phrase " << p.startMs << "|" << p.endMs << "|" << p.kind << "|" << p.label << "|" << p.mood << "|" << opt(p.fillMs) << "\n";
    }
    return out.str();
}

}  // namespace

int main(int argc, char **argv) {
    const std::string root = argc > 1 ? argv[1] : ".";
    const auto t0 = std::chrono::steady_clock::now();
    const std::string got = summary(root + "/pi/library/testdata/usb");
    const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    std::ifstream in(root + "/pi/library/testdata/summary.txt");
    std::stringstream want;
    want << in.rdbuf();
    if (got != want.str()) {
        std::istringstream a(want.str()), b(got);
        std::string la, lb;
        int line = 0;
        while (true) {
            const bool ra = static_cast<bool>(std::getline(a, la)), rb = static_cast<bool>(std::getline(b, lb));
            ++line;
            if (!ra && !rb) break;
            if (la != lb) {
                std::printf("FAIL linha %d:\n  Kotlin: %s\n  C++:    %s\n", line, la.c_str(), lb.c_str());
                break;
            }
        }
        std::printf("FAILS 1\n");
        return 1;
    }
    std::printf("PASS resumo do pendrive igual ao Kotlin (%zu bytes), lido em %.1f ms\n", got.size(), ms);
    // Pendrive estragado: as mesmas mutações do LibraryFuzzTest (bits, cortes, campos de 32 bits enormes, lixo).
    // Com ASan/UBSan, qualquer leitura fora do vetor derruba o teste.
    const std::string usb = root + "/pi/library/testdata/usb";
    std::vector<Bytes> seeds;
    if (auto b = readFile(usb + "/PIONEER/rekordbox/export.pdb")) seeds.push_back(*b);
    for (int i = 1; i <= 8; ++i) {
        char name[64];
        std::snprintf(name, sizeof name, "/PIONEER/USBANLZ/ANLZ%04d", i);
        for (const char *ext : {".DAT", ".EXT", ".2EX"}) {
            if (auto b = readFile(usb + name + ext)) seeds.push_back(*b);
        }
    }
    uint32_t rng = 2026;
    auto next = [&](uint32_t n) { rng = rng * 1664525u + 1013904223u; return n ? (rng >> 8) % n : 0u; };
    long runs = 0;
    for (int round = 0; round < 3000; ++round) {
        Bytes b = seeds[next(static_cast<uint32_t>(seeds.size()))];
        switch (next(5)) {
            case 0: for (int k = 0; k < 16; ++k) if (!b.empty()) b[next(static_cast<uint32_t>(b.size()))] ^= static_cast<uint8_t>(1u << next(8)); break;
            case 1: b.resize(next(static_cast<uint32_t>(b.size()) + 1)); break;
            case 2: for (int k = 0; k < 4 && b.size() > 4; ++k) {
                        const size_t at = next(static_cast<uint32_t>(b.size() - 4));
                        const uint32_t v = std::array<uint32_t, 4>{0xFFFFFFFFu, 0x7FFFFFFFu, 0x7FFF0000u, rng}[next(4)];
                        for (int j = 0; j < 4; ++j) b[at + static_cast<size_t>(j)] = static_cast<uint8_t>(v >> (8 * j));
                    }
                    break;
            case 3: b.assign(next(64), 0); for (auto &x : b) x = static_cast<uint8_t>(next(256)); break;
            default: for (int k = 0; k < 256; ++k) b.push_back(static_cast<uint8_t>(next(256))); break;
        }
        (void)parseExportPdb(b);
        (void)parseAnlz(b);
        ++runs;
    }
    std::printf("PASS %ld arquivos estragados lidos sem exceção nem leitura fora da faixa\nFAILS 0\n", runs);
    return 0;
}
