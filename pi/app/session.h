// Sessão do Pi: catálogo do pendrive (só leitura) e carga de faixas no motor, com a mesma regra do DeckPage:
// BPM e grade da análise, cue principal, hot cues e a onda de detalhe só das faixas que estão nos decks
// (como o catálogo leve do app, loadRekordboxLight). Usada pelo programa de console e pela tela nativa.
#pragma once

#include "../audio/decode.h"
#include "../library/rekordbox.h"

#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

namespace deckbridge::app {

struct TrackInfo {
    library::PdbTrack row;
    std::string artist, album, genre, key;
};

struct LoadedTrack {
    TrackInfo info;
    library::AnlzFile analysis;  // juntada (.DAT/.EXT/.2EX), com o detalhe
    double gridOriginSec = 0.0;
    double bpm = 0.0;
    double durationSec = 0.0;
    float mainCueSec = 0.f;
    bool truncated = false;
};

class Catalog {
public:
    bool open(const std::string &mountPoint, std::string &error) {
        root_ = mountPoint;
        for (const char *candidate : {"PIONEER/rekordbox/export.pdb", "rekordbox/export.pdb", "export.pdb"}) {
            if (auto bytes = library::readFile(root_ + "/" + candidate)) {
                auto parsed = library::parseExportPdb(*bytes);
                if (!parsed.database) {
                    error = parsed.error;
                    return false;
                }
                db_ = std::move(*parsed.database);
                build();
                return true;
            }
        }
        error = "E_RB_EXPORT_NOT_FOUND";
        return false;
    }

    const std::vector<TrackInfo> &tracks() const { return tracks_; }
    const std::vector<library::PdbPlaylist> &playlists() const { return db_.playlists; }
    const std::string &root() const { return root_; }

    /** Análise juntada de uma faixa (relida do ANLZ dela; o catálogo não guarda ondas). */
    std::optional<library::AnlzFile> analysis(const TrackInfo &track) const {
        const std::string path = library::normalizeRekordboxPath(track.row.analyzePath);
        if (path.empty()) return std::nullopt;
        std::string stem = path;
        if (stem.size() >= 4) {
            std::string tail = stem.substr(stem.size() - 4);
            for (auto &c : tail) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
            if (tail == ".DAT") stem.resize(stem.size() - 4);
        }
        std::optional<library::AnlzFile> dat, ext, ex2;
        if (auto b = library::readFile(root_ + "/" + path)) dat = library::parseAnlz(*b);
        if (auto b = library::readFile(root_ + "/" + stem + ".EXT")) ext = library::parseAnlz(*b);
        if (auto b = library::readFile(root_ + "/" + stem + ".2EX")) ex2 = library::parseAnlz(*b);
        if (!dat && !ext && !ex2) return std::nullopt;
        return library::mergeAnalysis(dat ? &*dat : nullptr, ext ? &*ext : nullptr, ex2 ? &*ex2 : nullptr);
    }

private:
    void build() {
        auto name = [](const std::vector<library::PdbName> &list, int id) -> std::string {
            for (const auto &n : list) {
                if (n.id == id) return n.name;
            }
            return "";
        };
        tracks_.clear();
        for (const auto &row : db_.tracks) {
            TrackInfo t;
            t.row = row;
            t.row.filePath = library::normalizeRekordboxPath(row.filePath);
            t.artist = name(db_.artists, row.artistId);
            t.album = name(db_.albums, row.albumId);
            t.genre = name(db_.genres, row.genreId);
            t.key = name(db_.keys, row.keyId);
            tracks_.push_back(std::move(t));
        }
    }

    std::string root_;
    library::PdbDatabase db_;
    std::vector<TrackInfo> tracks_;
};

/** Origem da grade como o DeckPage: primeira batida menos as batidas até o tempo 1 do compasso. */
inline double gridOrigin(const std::vector<library::AnlzBeat> &beats, double bpm) {
    if (beats.empty() || bpm <= 1.0) return 0.0;
    const auto &first = beats.front();
    return first.timeMs / 1000.0 - (std::clamp(first.beatInBar, 1, 4) - 1) * (60.0 / bpm);
}

/** Carrega uma faixa do catálogo no motor. `Engine` precisa de installDeck, setGrid e seekSeconds. */
template <class Engine>
bool loadTrack(Engine &engine, int deck, const Catalog &catalog, const TrackInfo &track, LoadedTrack &out, std::string &error) {
    audio::Pcm16 pcm;
    if (!audio::decodeFile(catalog.root() + "/" + track.row.filePath, pcm, error)) return false;
    out = LoadedTrack{};
    out.info = track;
    out.bpm = track.row.bpm;
    out.truncated = pcm.truncated;
    out.durationSec = pcm.rate > 0 ? static_cast<double>(pcm.left.size()) / pcm.rate : 0.0;
    float hot[8];
    for (float &h : hot) h = -1.f;
    if (auto analysis = catalog.analysis(track)) {
        out.analysis = std::move(*analysis);
        for (const auto &cue : out.analysis.cues) {
            if (cue.hotIndex && *cue.hotIndex >= 0 && *cue.hotIndex < 8) hot[*cue.hotIndex] = cue.timeMs / 1000.f;
        }
        // Cue principal: primeira memória sem loop (a mesma regra do analysisFor do app).
        int main = 0;
        int best = -1;
        for (const auto &cue : out.analysis.cues) {
            if (!cue.hotIndex && !cue.loopEndMs && (best < 0 || cue.timeMs < best)) best = cue.timeMs;
        }
        if (best >= 0) main = best;
        out.mainCueSec = main / 1000.f;
        out.gridOriginSec = gridOrigin(out.analysis.beats, out.bpm);
    }
    const int rate = pcm.rate;
    engine.installDeck(deck, std::move(pcm.left), std::move(pcm.right), rate, static_cast<float>(out.bpm), out.mainCueSec, hot, 8, -1.f, -1.f);
    engine.setGrid(deck, static_cast<float>(out.gridOriginSec), static_cast<float>(out.bpm));
    if (out.mainCueSec > 0.f) engine.seekSeconds(deck, out.mainCueSec);
    return true;
}

}  // namespace deckbridge::app
