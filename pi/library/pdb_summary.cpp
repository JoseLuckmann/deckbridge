// Resumo de um export.pdb pelo leitor do Pi, em linhas TSV, para conferir contra outros leitores (o gravador do
// Library Manager de desktop é testado contra este programa). Só lê.
//   pdb_summary export.pdb
//   pdb_summary --anlz ARQ.DAT [ARQ.EXT [ARQ.2EX]]   grade e cues como o Pi os lê (B tempo_ms beat bpm100 | C hot tempo_ms loop_ms comentário rgb | W fonte | I problema)
#include "rekordbox.h"

#include <algorithm>
#include <cstdio>
#include <map>

using namespace deckbridge::library;

// Tabulação e quebra de linha dentro de um campo (há comentários reais com um dump em várias linhas) viram espaço.
static std::string tsv(std::string text) {
    for (char &c : text) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return text;
}

static int anlzMode(int argc, char **argv) {
    std::vector<AnlzFile> files;
    for (int i = 2; i < argc; ++i) {
        auto bytes = readFile(argv[i]);
        if (!bytes) {
            std::fprintf(stderr, "não consegui ler %s\n", argv[i]);
            return 1;
        }
        files.push_back(parseAnlz(*bytes));
    }
    if (files.empty()) return 2;
    const AnlzFile merged = mergeAnalysis(&files[0], files.size() > 1 ? &files[1] : nullptr, files.size() > 2 ? &files[2] : nullptr);
    for (const auto &b : merged.beats) std::printf("B\t%d\t%d\t%d\n", b.timeMs, b.beatInBar, b.bpm100);
    for (const auto &c : merged.cues) {
        std::printf("C\t%d\t%d\t%d\t%s\t%d\n", c.hotIndex ? *c.hotIndex + 1 : 0, c.timeMs, c.loopEndMs ? *c.loopEndMs : 0, c.comment.c_str(), c.colorRgb ? *c.colorRgb : -1);
    }
    std::printf("W\t%s\n", merged.waveSource.c_str());
    for (const auto &i : merged.issues) std::printf("I\t%s\n", i.c_str());
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 3 && std::string(argv[1]) == "--anlz") return anlzMode(argc, argv);
    if (argc < 2) {
        std::fprintf(stderr, "uso: %s export.pdb\n", argv[0]);
        return 2;
    }
    auto bytes = readFile(argv[1]);
    if (!bytes) {
        std::fprintf(stderr, "não consegui ler %s\n", argv[1]);
        return 1;
    }
    auto parsed = parseExportPdb(*bytes);
    if (!parsed.database) {
        std::fprintf(stderr, "recusado: %s\n", parsed.error.c_str());
        return 1;
    }
    const auto &db = *parsed.database;
    auto names = [](const std::vector<PdbName> &list) {
        std::map<int, std::string> m;
        for (const auto &n : list) m[n.id] = n.name;
        return m;
    };
    const auto artists = names(db.artists), albums = names(db.albums), genres = names(db.genres), keys = names(db.keys);
    auto name = [](const std::map<int, std::string> &m, int id) {
        auto it = m.find(id);
        return id != 0 && it != m.end() ? it->second : std::string();
    };
    auto tracks = db.tracks;
    std::sort(tracks.begin(), tracks.end(), [](const PdbTrack &a, const PdbTrack &b) { return a.id < b.id; });
    for (const auto &t : tracks) {
        std::printf("T\t%d\t%s\t%s\t%s\t%s\t%s\t%s\n", t.id, tsv(t.title).c_str(), tsv(name(artists, t.artistId)).c_str(), tsv(name(albums, t.albumId)).c_str(),
                    tsv(name(genres, t.genreId)).c_str(), tsv(name(keys, t.keyId)).c_str(), tsv(t.filePath).c_str());
    }
    auto playlists = db.playlists;
    std::sort(playlists.begin(), playlists.end(), [](const PdbPlaylist &a, const PdbPlaylist &b) { return a.id < b.id; });
    for (const auto &p : playlists) std::printf("P\t%d\t%d\t%s\t%d\t%d\n", p.id, p.parentId, tsv(p.name).c_str(), p.folder ? 1 : 0, p.sortOrder);
    auto entries = db.entries;
    std::sort(entries.begin(), entries.end(), [](const PdbEntry &a, const PdbEntry &b) {
        return std::tie(a.playlistId, a.position, a.trackId) < std::tie(b.playlistId, b.position, b.trackId);
    });
    for (const auto &e : entries) std::printf("E\t%d\t%d\t%d\n", e.playlistId, e.position, e.trackId);
    return 0;
}
