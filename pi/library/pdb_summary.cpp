// Resumo de um export.pdb pelo leitor do Pi, em linhas TSV, para conferir contra outros leitores (o gravador do
// Library Manager de desktop é testado contra este programa). Só lê.
//   pdb_summary export.pdb
#include "rekordbox.h"

#include <algorithm>
#include <cstdio>
#include <map>

using namespace deckbridge::library;

int main(int argc, char **argv) {
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
        std::printf("T\t%d\t%s\t%s\t%s\t%s\t%s\t%s\n", t.id, t.title.c_str(), name(artists, t.artistId).c_str(), name(albums, t.albumId).c_str(),
                    name(genres, t.genreId).c_str(), name(keys, t.keyId).c_str(), t.filePath.c_str());
    }
    auto playlists = db.playlists;
    std::sort(playlists.begin(), playlists.end(), [](const PdbPlaylist &a, const PdbPlaylist &b) { return a.id < b.id; });
    for (const auto &p : playlists) std::printf("P\t%d\t%d\t%s\t%d\t%d\n", p.id, p.parentId, p.name.c_str(), p.folder ? 1 : 0, p.sortOrder);
    auto entries = db.entries;
    std::sort(entries.begin(), entries.end(), [](const PdbEntry &a, const PdbEntry &b) {
        return std::tie(a.playlistId, a.position, a.trackId) < std::tie(b.playlistId, b.position, b.trackId);
    });
    for (const auto &e : entries) std::printf("E\t%d\t%d\t%d\n", e.playlistId, e.position, e.trackId);
    return 0;
}
