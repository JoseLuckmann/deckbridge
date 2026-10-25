// Tela nativa do DeckBridge no Pi (ADR-0008): SDL2 por software, no layout do app Android (que segue o leitor de
// club de duas telas): barra de estado, navegação, as duas ondas com grade, painel lateral, hot cues A–H e o cartão
// de cada deck com título, REMAIN, TEMPO, BPM e a visão geral. Feita para 1024×600 e 800×480 (tela de 7").
// O motor, a sessão (pendrive só leitura), a DDJ-400 (MIDI e LEDs) são os mesmos do deckbridge_pi.
//
//   deckbridge_ui --usb /media/PENDRIVE [--track 3 --track 7] [--size 1024x600] [--device hw:..] [--midi DDJ]
//                 [--map ddj400-map.json] [--seconds N] [--shot arquivo.bmp --shot-at 4.5] [--browse] [--play] [--midi-demo]
//                 [--script roteiro.txt] [--record quadros.bgra]
//
// --script: bytes MIDI da DDJ-400 no tempo da música (ver pi/demo/README.md). Linhas:
//   at SEG BYTES...        segundos desde o começo do roteiro
//   bar D COMPASSO BYTES...  quando o deck D (1 ou 2) chega ao compasso (pela grade da faixa)
//   ramp D DE ATE STATUS NOTA V0 V1   CC de V0 a V1 entre os compassos DE e ATE (a cada 40 ms)
//   panel D COMPASSO 0|1   abre/fecha o painel RMX (o toque no botão RMX da tela)
//   end D COMPASSO         termina a demonstração
// --record: quadros BGRA crus a 30 qps de tempo de relógio (para o ffmpeg; o quadro se repete se a tela atrasar).

#define DECKBRIDGE_NO_JNI 1
#include "../../android/app/src/main/cpp/engine.cpp"
#include "../app/session.h"
#include "../control/deck_control.h"
#include "leds.h"
#include "../control/midi_in.h"

#include <SDL2/SDL.h>
#include <SDL2/SDL_ttf.h>
#include <sys/mman.h>

#include <cstdio>
#include <deque>
#include <fstream>
#include <functional>
#include <sstream>
#include <list>
#include <unordered_map>

namespace {

using deckbridge::app::Catalog;
using deckbridge::app::LoadedTrack;
using deckbridge::app::TrackInfo;
using deckbridge::control::Action;
using deckbridge::control::Kind;

// Cores do app (StagePalette) e das letras de hot cue.
constexpr SDL_Color kBg{0, 0, 0, 255}, kPanel{18, 18, 22, 255}, kPanelRaised{30, 31, 36, 255}, kLine{52, 54, 60, 255},
    kInk{235, 235, 238, 255}, kMuted{140, 143, 150, 255}, kOrange{255, 138, 30, 255}, kBlue{76, 140, 255, 255}, kRed{255, 42, 42, 255},
    kGreen{61, 220, 106, 255};
constexpr size_t kRows = 8;  // linhas da lista por página
constexpr uint32_t kLetterColors[8] = {0xFF3B3B, 0x2EC5FF, 0x3DDC6A, 0xB44DFF, 0x3DDC6A, 0xFF8A1E, 0x3B6CFF, 0xFFE03B};

SDL_Color rgb(uint32_t c) { return SDL_Color{static_cast<Uint8>(c >> 16), static_cast<Uint8>(c >> 8), static_cast<Uint8>(c), 255}; }

/** Textos já desenhados ficam como textura (LRU de 512): o quadro estável não aloca nem rasteriza nada. */
class TextCache {
public:
    TextCache(SDL_Renderer *r, TTF_Font *regular, TTF_Font *bold, TTF_Font *big) : r_(r), fonts_{regular, bold, big} {}
    ~TextCache() {
        for (auto &item : lru_) SDL_DestroyTexture(item.second.texture);
    }

    /** Desenha em (x, y); align 0 = esquerda, 1 = centro, 2 = direita. Devolve a largura. */
    int draw(const std::string &text, int x, int y, SDL_Color color, int font = 0, int align = 0) {
        if (text.empty()) return 0;
        const std::string key = std::to_string(font) + "|" + std::to_string(color.r) + "," + std::to_string(color.g) + "," +
                                std::to_string(color.b) + "|" + text;
        auto it = index_.find(key);
        Entry entry;
        if (it != index_.end()) {
            entry = it->second->second;
            lru_.splice(lru_.begin(), lru_, it->second);
        } else {
            SDL_Surface *s = TTF_RenderUTF8_Blended(fonts_[font], text.c_str(), color);
            if (s == nullptr) return 0;
            entry.texture = SDL_CreateTextureFromSurface(r_, s);
            entry.w = s->w;
            entry.h = s->h;
            SDL_FreeSurface(s);
            lru_.emplace_front(key, entry);
            index_[key] = lru_.begin();
            if (lru_.size() > 512) {
                SDL_DestroyTexture(lru_.back().second.texture);
                index_.erase(lru_.back().first);
                lru_.pop_back();
            }
        }
        const int left = align == 1 ? x - entry.w / 2 : align == 2 ? x - entry.w : x;
        SDL_Rect dst{left, y, entry.w, entry.h};
        SDL_RenderCopy(r_, entry.texture, nullptr, &dst);
        return entry.w;
    }

private:
    struct Entry {
        SDL_Texture *texture = nullptr;
        int w = 0, h = 0;
    };
    SDL_Renderer *r_;
    TTF_Font *fonts_[3];
    std::list<std::pair<std::string, Entry>> lru_;
    std::unordered_map<std::string, std::list<std::pair<std::string, Entry>>::iterator> index_;
};

struct DeckView {
    bool loaded = false;
    LoadedTrack track;
};

/** Pedidos da DDJ-400 que mexem na tela (lista, LOAD): vão da thread do MIDI para a da tela por esta fila. */
class UiQueue {
public:
    void push(const Action &a) {
        std::lock_guard<std::mutex> lock(m_);
        if (q_.size() < 256) q_.push_back(a);
    }
    bool pop(Action &a) {
        std::lock_guard<std::mutex> lock(m_);
        if (q_.empty()) return false;
        a = q_.front();
        q_.pop_front();
        return true;
    }

private:
    std::mutex m_;
    std::deque<Action> q_;
};

std::string clock(double seconds, bool millis) {
    if (seconds < 0) seconds = 0;
    const int m = static_cast<int>(seconds / 60);
    const double s = seconds - m * 60;
    char buf[32];
    if (millis) std::snprintf(buf, sizeof buf, "%02d:%06.3f", m, s);
    else std::snprintf(buf, sizeof buf, "%02d:%02d", m, static_cast<int>(s));
    return buf;
}

class Screen {
public:
    Screen(SDL_Renderer *r, TextCache &text, int w, int h) : r_(r), text_(text), w_(w), h_(h) {}

    // Coordenadas do desenho base de 1024×600, escaladas para a tela real.
    int X(double v) const { return static_cast<int>(v * w_ / 1024.0); }
    int Y(double v) const { return static_cast<int>(v * h_ / 600.0); }
    SDL_Rect R(double x, double y, double w, double h) const { return SDL_Rect{X(x), Y(y), X(x + w) - X(x), Y(y + h) - Y(y)}; }

    void fill(const SDL_Rect &rect, SDL_Color c) {
        SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, c.a);
        SDL_RenderFillRect(r_, &rect);
    }
    void frame(const SDL_Rect &rect, SDL_Color c) {
        SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, c.a);
        SDL_RenderDrawRect(r_, &rect);
    }
    void vline(int x, int y0, int y1, SDL_Color c) {
        SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, c.a);
        SDL_RenderDrawLine(r_, x, y0, x, y1);
    }
    void button(const SDL_Rect &rect, const std::string &label, bool selected = false, int font = 1) {
        fill(rect, selected ? kBlue : kPanelRaised);
        frame(rect, selected ? kBlue : kLine);
        text_.draw(label, rect.x + rect.w / 2, rect.y + rect.h / 2 - Y(7), selected ? kBg : kBlue, font, 1);
    }

    /** Onda de detalhe em volta da cabeça (no meio), com a grade e as hot cues. Uma coluna por pixel. */
    void detailWave(const SDL_Rect &area, const DeckView &deck, double position, double windowSec) {
        fill(area, kBg);
        frame(area, kLine);
        if (!deck.loaded) return;
        const auto &a = deck.track.analysis;
        const int top = area.y + area.h * 16 / 100, bottom = area.y + area.h * 82 / 100, mid = (top + bottom) / 2, half = (bottom - top) / 2;
        const double start = position - windowSec * 0.5;
        const int columns = static_cast<int>(a.rgbDetail ? a.rgbDetail->height.size()
                                                         : a.bandDetail ? static_cast<size_t>(a.bandDetail->columns()) : a.blueDetail.size());
        for (int x = 0; x < area.w; ++x) {
            const double t0 = start + windowSec * x / area.w, t1 = start + windowSec * (x + 1) / area.w;
            int i0 = static_cast<int>(std::floor(t0 * 150.0)), i1 = std::max(i0 + 1, static_cast<int>(std::ceil(t1 * 150.0)));
            if (i1 <= 0 || i0 >= columns) continue;
            i0 = std::max(i0, 0);
            i1 = std::min(i1, columns);
            int height = 0;
            uint32_t color = 0x3B6CFF;
            for (int i = i0; i < i1; ++i) {
                const auto at = static_cast<size_t>(i);
                if (a.rgbDetail) {
                    if (a.rgbDetail->height[at] > height) {
                        height = a.rgbDetail->height[at];
                        color = static_cast<uint32_t>(a.rgbDetail->rgb[at]);
                    }
                } else if (a.bandDetail) {
                    const int l = a.bandDetail->low[at], m = a.bandDetail->mid[at], h = a.bandDetail->high[at];
                    const int peak = std::max({l, m, h});
                    if (peak > height) {
                        height = peak;
                        color = static_cast<uint32_t>(deckbridge::library::blendRgb(l, m, h));
                    }
                } else {
                    height = std::max(height, static_cast<int>(a.blueDetail[at]));
                }
            }
            const int span = height * half / 255;
            const SDL_Color c = rgb(color);
            SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, 255);
            SDL_RenderDrawLine(r_, area.x + x, mid - span, area.x + x, mid + span);
        }
        // Grade: batida branca, tempo 1 vermelho, número do compasso a cada 4.
        int bar = 0;
        for (const auto &beat : a.beats) {
            if (beat.beatInBar == 1) ++bar;
            const double t = beat.timeMs / 1000.0;
            if (t < start || t > start + windowSec) continue;
            const int x = area.x + static_cast<int>((t - start) / windowSec * area.w);
            const bool down = beat.beatInBar == 1;
            vline(x, area.y + 2, top - 2, down ? kRed : kInk);
            vline(x, bottom + 2, area.y + area.h - Y(14), down ? kRed : kInk);
            if (down && bar % 4 == 1) text_.draw(std::to_string(bar), x, area.y + area.h - Y(14), kInk, 0, 1);
        }
        for (const auto &cue : a.cues) {
            if (!cue.hotIndex || *cue.hotIndex < 0 || *cue.hotIndex > 7) continue;
            const double t = cue.timeMs / 1000.0;
            if (t < start || t > start + windowSec) continue;
            const int x = area.x + static_cast<int>((t - start) / windowSec * area.w);
            const SDL_Color c = rgb(kLetterColors[*cue.hotIndex]);
            for (int k = 0; k < Y(9); ++k) {
                SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, 255);
                SDL_RenderDrawLine(r_, x - k / 2, top + k, x + k / 2, top + k);
            }
        }
        vline(area.x + area.w / 2, area.y + 1, area.y + area.h - 2, kInk);
    }

    /** Visão geral da faixa inteira (prévia do ANLZ), o tocado escurecido e a cabeça em branco. */
    void overview(const SDL_Rect &area, const DeckView &deck, double position) {
        fill(area, kBg);
        if (!deck.loaded || deck.track.durationSec <= 0) return;
        const auto &a = deck.track.analysis;
        const int n = a.rgbPreview ? static_cast<int>(a.rgbPreview->height.size())
                                   : a.bandPreview ? a.bandPreview->columns() : static_cast<int>(a.bluePreview.size());
        if (n <= 0) return;
        const int base = area.y + area.h, played = static_cast<int>(position / deck.track.durationSec * area.w);
        for (int x = 0; x < area.w; ++x) {
            const auto i = static_cast<size_t>(std::min(n - 1, x * n / area.w));
            int h;
            uint32_t color;
            if (a.rgbPreview) {
                h = a.rgbPreview->height[i];
                color = static_cast<uint32_t>(a.rgbPreview->rgb[i]);
            } else if (a.bandPreview) {
                const int l = a.bandPreview->low[i], m = a.bandPreview->mid[i], hi = a.bandPreview->high[i];
                h = std::max({l, m, hi});
                color = static_cast<uint32_t>(deckbridge::library::blendRgb(l, m, hi));
            } else {
                h = a.bluePreview[i];
                color = 0x3B6CFF;
            }
            SDL_Color c = rgb(color);
            if (x < played) c = SDL_Color{static_cast<Uint8>(c.r / 3), static_cast<Uint8>(c.g / 3), static_cast<Uint8>(c.b / 3), 255};
            SDL_SetRenderDrawColor(r_, c.r, c.g, c.b, 255);
            SDL_RenderDrawLine(r_, area.x + x, base - 1, area.x + x, base - 1 - h * (area.h - 2) / 255);
        }
        for (const auto &cue : a.cues) {
            if (!cue.hotIndex || *cue.hotIndex < 0 || *cue.hotIndex > 7) continue;
            const int x = area.x + static_cast<int>(cue.timeMs / 1000.0 / deck.track.durationSec * area.w);
            text_.draw(std::string(1, static_cast<char>('A' + *cue.hotIndex)), x, area.y - Y(13), rgb(kLetterColors[*cue.hotIndex]), 0, 1);
        }
        vline(area.x + played, area.y, area.y + area.h, kInk);
    }

    SDL_Renderer *r_;
    TextCache &text_;
    int w_, h_;
};

}  // namespace

int main(int argc, char **argv) {
    int width = 1024, height = 600, seconds = 0;
    std::string usb, mapPath = "ddj400-map.json", midiFilter = "DDJ", shot;
    double shotAt = 3.0;
    bool midi = false, browse = false, play = false, midiDemo = false;
    std::string scriptPath, recordPath;
    std::vector<int> trackNumbers;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (arg == "--usb") usb = next();
        else if (arg == "--track") trackNumbers.push_back(std::atoi(next().c_str()));
        else if (arg == "--size") std::sscanf(next().c_str(), "%dx%d", &width, &height);
        else if (arg == "--device") oboe::AlsaConfig::device = next();
        else if (arg == "--midi") {
            midi = true;
            midiFilter = next();
        } else if (arg == "--map") mapPath = next();
        else if (arg == "--seconds") seconds = std::atoi(next().c_str());
        else if (arg == "--shot") shot = next();
        else if (arg == "--shot-at") shotAt = std::atof(next().c_str());
        else if (arg == "--browse") browse = true;
        else if (arg == "--play") play = true;
        else if (arg == "--midi-demo") midiDemo = true;
        else if (arg == "--script") scriptPath = next();
        else if (arg == "--record") recordPath = next();
        else {
            std::fprintf(stderr, "uso: %s --usb PASTA [--track N] [--size 1024x600] [--device NOME] [--midi FILTRO] [--map ARQ] [--seconds N] [--shot a.bmp --shot-at S] [--browse]\n", argv[0]);
            return 2;
        }
    }
    // Pilha de 512 KB para as threads novas (áudio, MIDI, carga): com mlockall, a pilha padrão de 8 MB de cada thread
    // ficava toda travada na RAM (+29 MB medidos na tela). O motor usa pouca pilha; 512 KB sobra.
    {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 512 * 1024);
        pthread_setattr_default_np(&attr);
        pthread_attr_destroy(&attr);
    }
    if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) std::fprintf(stderr, "aviso: mlockall recusado; siga sem travar a RAM\n");

    ToneEngine &engine = ::engine();
    Catalog catalog;
    std::string status = "Sem pendrive";
    if (!usb.empty()) {
        std::string error;
        status = catalog.open(usb, error) ? "USB " + std::to_string(catalog.tracks().size()) + " faixas · só leitura" : "Pendrive: " + error;
    }
    DeckView decks[2];
    std::mutex deckMutex;  // a carga roda numa thread; a tela lê `decks` no quadro
    std::atomic<int> loading{0};
    deckbridge::control::DeckControl<ToneEngine> control(engine);
    auto load = [&](int deck, size_t index) {
        if (index >= catalog.tracks().size()) return;
        loading.fetch_add(1);
        std::thread([&, deck, index]() {
            LoadedTrack loaded;
            std::string error;
            if (deckbridge::app::loadTrack(engine, deck, catalog, catalog.tracks()[index], loaded, error)) {
                control.setGrid(deck, loaded.gridOriginSec, loaded.bpm);
                std::lock_guard<std::mutex> lock(deckMutex);
                decks[deck].track = std::move(loaded);
                decks[deck].loaded = true;
            } else {
                std::fprintf(stderr, "deck %d: %s\n", deck + 1, error.c_str());
            }
            loading.fetch_sub(1);
        }).detach();
    };
    for (size_t d = 0; d < trackNumbers.size() && d < 2; ++d) load(static_cast<int>(d), static_cast<size_t>(trackNumbers[d] - 1));
    while (loading.load() > 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));

    const std::string opened = engine.start(0, 3);
    const auto audioOpenedAt = std::chrono::steady_clock::now();
    const bool audioOk = opened.find("opened=true") != std::string::npos;
    engine.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, false, false);
    if (play) {
        // Demonstração sem controladora: as mesmas ações que o PLAY da DDJ-400 manda.
        for (int d = 0; d < 2; ++d) {
            if (decks[d].loaded) control.apply(deckbridge::control::act(d, Kind::PLAY));
        }
    }

    // DDJ-400: o mesmo caminho do deckbridge_pi; lista e LOAD vão para a fila da tela.
    UiQueue uiQueue;
    deckbridge::control::MidiIn midiIn;
    deckbridge::control::MidiOut midiOut;
    deckbridge::control::TempoAssembler tempo;
    std::unique_ptr<deckbridge::control::MidiMap> map;
    std::function<void(const deckbridge::control::MidiMessage &)> onMessage;
    if (midi || midiDemo || !scriptPath.empty()) {
        map = std::make_unique<deckbridge::control::MidiMap>(deckbridge::control::MidiMap::fromFile(mapPath));
        onMessage = [&](const deckbridge::control::MidiMessage &m) {
            auto a = deckbridge::control::interpretPerformance(map->lookup(m.status, m.data1), m.data2, tempo);
            if (!a) return;
            if (a->kind == Kind::BROWSE_MOVE || a->kind == Kind::BROWSE_ENTER || a->kind == Kind::BROWSE_BACK || a->kind == Kind::LOAD) uiQueue.push(*a);
            else control.apply(*a);
        };
    }
    if (midi) {
        midiIn.open(midiFilter, onMessage);
        midiOut.open(midiFilter);
    }
    // Demonstração sem controladora: os bytes que a DDJ-400 manda (mapa oficial), por uma thread, no mesmo `onMessage`:
    // BROWSE gira 3 passos, LOAD no deck 2, PLAY nos dois decks.
    std::thread demo;
    if (midiDemo) {
        demo = std::thread([&]() {
            deckbridge::control::MidiAssembler assembler;
            std::vector<deckbridge::control::MidiMessage> out;
            const std::vector<std::pair<int, std::vector<uint8_t>>> script = {
                {600, {0xB6, 0x40, 0x01}}, {900, {0xB6, 0x40, 0x01}}, {1200, {0xB6, 0x40, 0x01}},
                {1800, {0x96, 0x47, 0x7F}}, {1850, {0x96, 0x47, 0x00}},
                {3200, {0x90, 0x0B, 0x7F}}, {3250, {0x90, 0x0B, 0x00}}, {3300, {0x91, 0x0B, 0x7F}}, {3350, {0x91, 0x0B, 0x00}},
            };
            const auto start = std::chrono::steady_clock::now();
            for (const auto &[ms, bytes] : script) {
                std::this_thread::sleep_until(start + std::chrono::milliseconds(ms));
                out.clear();
                assembler.push(bytes.data(), bytes.size(), out);
                for (const auto &m : out) onMessage(m);
            }
        });
    }

    // Roteiro no tempo da música: o mesmo `onMessage` da DDJ-400, agendado pelo compasso da grade de cada deck.
    std::atomic<bool> scriptDone{false};
    std::thread scriptThread;
    if (!scriptPath.empty()) {
        scriptThread = std::thread([&]() {
            std::ifstream in(scriptPath);
            deckbridge::control::MidiAssembler assembler;
            std::vector<deckbridge::control::MidiMessage> out;
            const auto start = std::chrono::steady_clock::now();
            std::mutex sendMutex;  // o roteiro e as rampas mandam pelo mesmo montador
            auto send = [&](const std::vector<uint8_t> &bytes) {
                std::lock_guard<std::mutex> lock(sendMutex);
                out.clear();
                assembler.push(bytes.data(), bytes.size(), out);
                for (const auto &m : out) onMessage(m);
            };
            auto barTime = [&](int deck, double bar) {
                return control.gridOrigin(deck) + bar * 240.0 / std::max(1.0, control.bpm(deck));
            };
            // Relógio "tocado" de cada deck: anda com a posição, mas um salto para trás (volta do loop, beat jump)
            // conta como tempo tocado na velocidade de antes. Assim "bar 1 11" depois de um loop no 9 chega na
            // hora musical certa, em vez de esperar uma posição que o loop nunca deixa alcançar.
            std::atomic<double> played[2]{0.0, 0.0};
            std::atomic<bool> clockRunning{true};
            std::thread clock([&]() {
                double last[2], speed[2]{0.0, 0.0};
                auto prev = std::chrono::steady_clock::now();
                for (int d = 0; d < 2; ++d) played[d] = last[d] = engine.positionSeconds(d);
                while (clockRunning.load()) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                    const auto now = std::chrono::steady_clock::now();
                    const double dt = std::chrono::duration<double>(now - prev).count();
                    prev = now;
                    for (int d = 0; d < 2; ++d) {
                        const double pos = engine.positionSeconds(d), delta = pos - last[d];
                        last[d] = pos;
                        if (!control.playing(d)) {
                            played[d] = pos;  // parado (carga, cue): o relógio volta a ser a posição
                            speed[d] = 0.0;
                        } else if (delta < 0.0 || delta > 0.25) {
                            played[d] = played[d] + speed[d] * dt;
                        } else {
                            played[d] = played[d] + delta;
                            if (dt > 0.0) speed[d] = delta / dt;
                        }
                    }
                }
            });
            auto waitBar = [&](int deck, double bar) {
                const double target = barTime(deck, bar);
                for (int guard = 0; guard < 300000 && played[deck].load() < target; ++guard) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(2));
                }
            };
            auto bytesOf = [](std::istringstream &words) {
                std::vector<uint8_t> bytes;
                std::string w;
                while (words >> w) bytes.push_back(static_cast<uint8_t>(std::strtol(w.c_str(), nullptr, 16)));
                return bytes;
            };
            std::vector<std::thread> ramps;
            std::string line;
            while (std::getline(in, line)) {
                std::istringstream words(line);
                std::string op;
                if (!(words >> op) || op[0] == '#') continue;
                if (op == "at") {
                    double sec = 0;
                    words >> sec;
                    std::this_thread::sleep_until(start + std::chrono::microseconds(static_cast<long long>(sec * 1e6)));
                    send(bytesOf(words));
                } else if (op == "bar") {
                    int deck = 1;
                    double bar = 0;
                    words >> deck >> bar;
                    waitBar(deck - 1, bar);
                    send(bytesOf(words));
                } else if (op == "ramp") {
                    // A rampa corre numa thread própria (fader, EQ, filtro ao mesmo tempo que os botões).
                    int deck = 1, v0 = 0, v1 = 0;
                    double from = 0, to = 0;
                    std::string st, d1;
                    words >> deck >> from >> to >> st >> d1 >> v0 >> v1;
                    const int status = static_cast<int>(std::strtol(st.c_str(), nullptr, 16));
                    const int data1 = static_cast<int>(std::strtol(d1.c_str(), nullptr, 16));
                    waitBar(deck - 1, from);
                    ramps.emplace_back([&, deck, from, to, status, data1, v0, v1]() {
                        const double t0 = barTime(deck - 1, from), t1 = barTime(deck - 1, to);
                        int last = -1;
                        while (true) {
                            const double now = played[deck - 1].load();
                            const double k = std::clamp((now - t0) / std::max(0.01, t1 - t0), 0.0, 1.0);
                            const int value = static_cast<int>(std::lround(v0 + (v1 - v0) * k));
                            if (value != last) {
                                send({static_cast<uint8_t>(status), static_cast<uint8_t>(data1), static_cast<uint8_t>(value)});
                                last = value;
                            }
                            if (k >= 1.0) break;
                            std::this_thread::sleep_for(std::chrono::milliseconds(40));
                        }
                    });
                } else if (op == "panel") {
                    int deck = 1, open = 0;
                    double bar = 0;
                    words >> deck >> bar >> open;
                    waitBar(deck - 1, bar);
                    control.setRmxPanel(open != 0);
                } else if (op == "end") {
                    int deck = 1;
                    double bar = 0;
                    words >> deck >> bar;
                    waitBar(deck - 1, bar);
                    break;
                }
            }
            for (auto &r : ramps) r.join();
            clockRunning.store(false);
            clock.join();
            scriptDone.store(true);
        });
    }

    SDL_SetHint(SDL_HINT_FRAMEBUFFER_ACCELERATION, "0");
    SDL_SetHint(SDL_HINT_RENDER_DRIVER, "software");
    if (SDL_Init(SDL_INIT_VIDEO) != 0 || TTF_Init() != 0) {
        std::fprintf(stderr, "SDL: %s\n", SDL_GetError());
        return 1;
    }
    SDL_Window *window = SDL_CreateWindow("DeckBridge", SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED, width, height, 0);
    SDL_Renderer *renderer = SDL_CreateRenderer(window, -1, SDL_RENDERER_SOFTWARE);
    const char *regularPath = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf";
    const char *boldPath = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf";
    TTF_Font *regular = TTF_OpenFont(regularPath, std::max(9, 11 * height / 600));
    TTF_Font *bold = TTF_OpenFont(boldPath, std::max(10, 12 * height / 600));
    TTF_Font *big = TTF_OpenFont(boldPath, std::max(18, 28 * height / 600));
    if (window == nullptr || renderer == nullptr || regular == nullptr || bold == nullptr || big == nullptr) {
        std::fprintf(stderr, "SDL/TTF: %s\n", SDL_GetError());
        return 1;
    }
    {
        TextCache text(renderer, regular, bold, big);
        Screen s(renderer, text, width, height);
        size_t cursor = 0;
        int frames = 0;
        double cpuStart = 0;
        const auto t0 = std::chrono::steady_clock::now();
        bool running = true, shotDone = false;
        deckbridge::control::LedDiff ledDiff;
        std::vector<uint8_t> ledBytes;
        float peaks[4]{}, raw[4]{};
        // Gravação: quadros BGRA no relógio (30 qps de tempo real). Se a tela atrasa, o quadro se repete e o vídeo
        // continua com a mesma duração do áudio. O buffer é um só, alocado aqui.
        std::FILE *record = recordPath.empty() ? nullptr : std::fopen(recordPath.c_str(), "wb");
        std::vector<uint8_t> pixels(record != nullptr ? static_cast<size_t>(width) * height * 4 : 0);
        long written = 0;
        if (record != nullptr) {
            std::fprintf(stderr, "gravação: o 1º quadro sai %.3f s depois de abrir o áudio\n",
                         std::chrono::duration<double>(t0 - audioOpenedAt).count());
        }
        auto lastTick = t0;
        while (running) {
            SDL_Event e;
            while (SDL_PollEvent(&e)) {
                if (e.type == SDL_QUIT) running = false;
                if (e.type == SDL_MOUSEBUTTONDOWN) {
                    // Toque: botões da navegação e linhas da lista.
                    const double bx = e.button.x * 1024.0 / width, by = e.button.y * 600.0 / height;
                    if (by >= 26 && by <= 68 && bx >= 174 && bx <= 340) browse = !browse;
                    else if (browse && by > 92 && by < 92 + kRows * 34 && !catalog.tracks().empty()) {
                        cursor = std::min(catalog.tracks().size() - 1, cursor - cursor % kRows + static_cast<size_t>((by - 92) / 34));
                    } else if (browse && by >= 450) load(bx < 512 ? 0 : 1, cursor);
                }
            }
            Action a;
            while (uiQueue.pop(a)) {
                if (a.kind == Kind::BROWSE_MOVE && !catalog.tracks().empty()) {
                    browse = true;
                    const long n = static_cast<long>(catalog.tracks().size());
                    cursor = static_cast<size_t>(std::clamp(static_cast<long>(cursor) + std::lround(a.slider.value_or(0)), 0L, n - 1));
                } else if (a.kind == Kind::BROWSE_ENTER) browse = true;
                else if (a.kind == Kind::BROWSE_BACK) browse = false;
                else if (a.kind == Kind::LOAD && a.deck >= 0 && !control.playing(a.deck)) {
                    // Como no leitor: carregar volta para a tela das ondas. Deck tocando não carrega (bloqueio de carga).
                    load(a.deck, cursor);
                    browse = false;
                }
            }
            const auto nowTick = std::chrono::steady_clock::now();
            control.tick(std::chrono::duration<double>(nowTick - lastTick).count());  // rampa da cena RMX
            lastTick = nowTick;
            const double elapsed = std::chrono::duration<double>(nowTick - t0).count();
            if (frames == 30) cpuStart = static_cast<double>(std::clock()) / CLOCKS_PER_SEC;

            s.fill(SDL_Rect{0, 0, width, height}, kBg);
            // Barra de estado.
            s.fill(s.R(0, 0, 1024, 22), kPanel);
            text.draw(status + (audioOk ? "" : " · SEM ÁUDIO") + (browse ? " · LOAD na DDJ-400 (ou toque no cartão do deck) carrega a faixa marcada" : ""),
                      s.X(6), s.Y(4), audioOk ? kMuted : kOrange);
            const std::time_t now = std::time(nullptr);
            char hhmm[16];
            std::strftime(hhmm, sizeof hhmm, "%H:%M", std::localtime(&now));
            text.draw(hhmm, s.X(1018), s.Y(3), kInk, 1, 2);
            // Navegação.
            const char *nav[] = {"SOURCE", "BROWSE", "TAG LIST", "PLAYLIST", "SEARCH", "MENU"};
            for (int i = 0; i < 6; ++i) s.button(s.R(4 + i * 170, 26, 166, 42), nav[i], i == 1 && browse);

            std::lock_guard<std::mutex> lock(deckMutex);
            double pos[2];
            for (int d = 0; d < 2; ++d) pos[d] = engine.positionSeconds(d);
            if (browse) {
                // Lista do pendrive, 8 linhas por página, no lugar das ondas (o resto da tela não muda).
                s.fill(s.R(4, 72, 1016, 294), kPanel);
                const size_t page = cursor - cursor % kRows;
                text.draw("TÍTULO", s.X(16), s.Y(76), kMuted);
                text.draw("ARTISTA", s.X(470), s.Y(76), kMuted);
                text.draw("BPM", s.X(800), s.Y(76), kMuted, 0, 2);
                text.draw("TOM", s.X(880), s.Y(76), kMuted, 0, 2);
                for (size_t i = page; i < page + kRows && i < catalog.tracks().size(); ++i) {
                    const auto &t = catalog.tracks()[i];
                    const SDL_Rect row = s.R(8, 92 + (i - page) * 34.0, 1008, 32);
                    if (i == cursor) s.fill(row, SDL_Color{26, 58, 104, 255});
                    text.draw(t.row.title, s.X(16), row.y + s.Y(9), kInk, 1);
                    text.draw(t.artist, s.X(470), row.y + s.Y(9), kMuted);
                    char bpm[16];
                    std::snprintf(bpm, sizeof bpm, "%.2f", t.row.bpm);
                    text.draw(bpm, s.X(800), row.y + s.Y(9), kInk, 0, 2);
                    text.draw(t.key, s.X(880), row.y + s.Y(9), kInk, 0, 2);
                }
            } else {
                for (int d = 0; d < 2; ++d) {
                    const double y = 72 + d * 148;
                    const SDL_Rect info = s.R(4, y, 112, 144);
                    s.fill(info, kPanel);
                    s.frame(info, kLine);
                    text.draw("DECK " + std::to_string(d + 1), info.x + s.X(4), info.y + s.Y(4), kInk, 1);
                    if (decks[d].loaded) {
                        const auto &t = decks[d].track;
                        text.draw("USB", info.x + s.X(4), info.y + s.Y(22), kInk);
                        text.draw("KEY", info.x + s.X(4), info.y + s.Y(40), kMuted);
                        text.draw(t.info.key, info.x + s.X(40), info.y + s.Y(38), kInk, 1);
                        if (t.bpm > 1) {
                            const double bars = (t.durationSec - pos[d]) * t.bpm / 240.0;
                            char b[32];
                            std::snprintf(b, sizeof b, "%.1f Bars", bars);
                            text.draw(b, info.x + s.X(4), info.y + s.Y(58), kOrange, 1);
                        }
                    }
                    const double windowSec = decks[d].loaded && decks[d].track.bpm > 1 ? 8 * 240.0 / decks[d].track.bpm : 15.0;
                    const SDL_Rect waveArea = s.R(120, y, 738, 144);
                    s.detailWave(waveArea, decks[d], pos[d], windowSec);
                    const auto loop = control.loop(d);
                    if (loop.end > loop.start) {
                        // Loop ativo: faixa laranja translúcida sobre a onda, como no app.
                        const double start = pos[d] - windowSec * 0.5;
                        const int x0 = waveArea.x + static_cast<int>(std::clamp((loop.start - start) / windowSec, 0.0, 1.0) * waveArea.w);
                        const int x1 = waveArea.x + static_cast<int>(std::clamp((loop.end - start) / windowSec, 0.0, 1.0) * waveArea.w);
                        SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_BLEND);
                        s.fill(SDL_Rect{x0, waveArea.y + 1, std::max(1, x1 - x0), waveArea.h - 2}, SDL_Color{255, 138, 30, 60});
                        SDL_SetRenderDrawBlendMode(renderer, SDL_BLENDMODE_NONE);
                        s.fill(SDL_Rect{x0, waveArea.y + 1, std::max(1, x1 - x0), s.Y(3)}, kOrange);
                    }
                }
                // Painel lateral (tempo do master e estado da controladora).
                const SDL_Rect side = s.R(864, 72, 156, 292);
                s.fill(side, kPanel);
                const auto fx = control.beatFx();
                const auto rmx = control.rmx();
                s.button(s.R(868, 78, 72, 22), "BEAT FX", !rmx.panel);
                s.button(s.R(944, 78, 72, 22), "RMX", rmx.panel);
                const int lead = control.playing(1) && !control.playing(0) ? 1 : 0;
                char bpmText[32];
                std::snprintf(bpmText, sizeof bpmText, "%.2f BPM", decks[lead].track.bpm * control.pitch(lead));
                if (!rmx.panel) {
                    // Beat FX: efeito, fração, canal, nível e ON (como o painel do app).
                    const SDL_Rect name = s.R(868, 106, 148, 30);
                    s.fill(name, fx.on ? SDL_Color{26, 58, 104, 255} : kPanelRaised);
                    s.frame(name, fx.on ? kOrange : kBlue);
                    text.draw(deckbridge::control::kBeatFxNames[fx.effect], name.x + name.w / 2, name.y + s.Y(8), fx.on ? kOrange : kInk, 1, 1);
                    text.draw(bpmText, side.x + s.X(6), side.y + s.Y(72), kInk, 1);
                    text.draw(std::string("FRAÇÃO ") + deckbridge::control::kFractionLabels[fx.fraction], side.x + s.X(6), side.y + s.Y(94), kMuted);
                    const char *ch[] = {"CH 1", "CH 2", "MST"};
                    for (int c = 0; c < 3; ++c) s.button(s.R(868 + c * 50, 196, 46, 22), ch[c], fx.channel == c);
                    text.draw("LEVEL/DEPTH", side.x + s.X(6), side.y + s.Y(152), kMuted);
                    const SDL_Rect bar = s.R(870, 240, 144, 8);
                    s.fill(bar, kPanelRaised);
                    s.fill(SDL_Rect{bar.x, bar.y, bar.w * fx.level / 127, bar.h}, kOrange);
                    s.button(s.R(868, 256, 148, 26), fx.on ? "ON" : "OFF", fx.on);
                } else {
                    // RMX: as oito cenas (as quatro primeiras nos pads), intensidade e X-PAD.
                    text.draw(bpmText, side.x + s.X(6), side.y + s.Y(32), kInk, 1);
                    // Uma cena por linha: "Reverb DOWN" não cabe em meia largura do painel.
                    for (int k = 0; k < 8; ++k) {
                        const bool active = rmx.scene == k && (rmx.holding || rmx.intensity > 0.02f);
                        s.button(s.R(868, 118 + k * 18, 148, 17), deckbridge::control::kSceneNames[k], active, 0);
                    }
                    const SDL_Rect bar = s.R(870, 266, 144, 6);
                    s.fill(bar, kPanelRaised);
                    s.fill(SDL_Rect{bar.x, bar.y, static_cast<int>(bar.w * rmx.intensity), bar.h}, kOrange);
                    const char *xpad[] = {"ROLL", "TRANS", "DRUM", "HI-PASS"};
                    text.draw(std::string("X-PAD ") + (rmx.xOn ? xpad[std::clamp(rmx.xMode, 0, 3)] : "—"), side.x + s.X(6), side.y + s.Y(206), rmx.xOn ? kOrange : kMuted, 1);
                    text.draw(std::string("CH ") + (rmx.channel == 1 ? "2" : rmx.channel == 2 ? "MST" : "1"), side.x + s.X(6), side.y + s.Y(226), kMuted);
                }
                text.draw(loading.load() > 0 ? "Carregando…" : (midi ? "DDJ-400: MIDI ligado" : "DDJ-400: roteiro/sem MIDI"), side.x + s.X(6),
                          side.y + s.Y(272), loading.load() > 0 ? kOrange : kMuted);
            }
            // Hot cues A–H de cada deck.
            for (int d = 0; d < 2; ++d) {
                const double x0 = d * 512.0;
                text.draw("HOT CUE A", s.X(x0 + 4), s.Y(368), kInk, 1);
                for (int k = 0; k < 8; ++k) {
                    const SDL_Rect cell = s.R(x0 + 4 + (k % 4) * 127, 382 + (k / 4) * 32, 123, 30);
                    s.fill(cell, kPanel);
                    s.frame(cell, kLine);
                    text.draw(std::string(1, static_cast<char>('A' + k)), cell.x + s.X(5), cell.y + s.Y(8), rgb(kLetterColors[k]), 1);
                    if (decks[d].loaded) {
                        for (const auto &cue : decks[d].track.analysis.cues) {
                            if (cue.hotIndex && *cue.hotIndex == k) {
                                text.draw(cue.comment.empty() ? clock(cue.timeMs / 1000.0, false) : cue.comment, cell.x + s.X(20), cell.y + s.Y(8), kInk);
                            }
                        }
                    }
                }
            }
            // Cartão de cada deck.
            for (int d = 0; d < 2; ++d) {
                const SDL_Rect card = s.R(4 + d * 512.0, 450, 504, 146);
                s.fill(card, kPanel);
                s.frame(card, kLine);
                const bool on = control.playing(d);
                s.button(SDL_Rect{card.x + s.X(6), card.y + s.Y(8), s.X(54), s.Y(18)}, "DECK " + std::to_string(d + 1), on);
                if (!decks[d].loaded) {
                    text.draw("Sem faixa. BROWSE e LOAD carregam do pendrive.", card.x + s.X(70), card.y + s.Y(10), kMuted);
                    continue;
                }
                const auto &t = decks[d].track;
                text.draw(t.info.row.title, card.x + s.X(70), card.y + s.Y(6), kInk, 1);
                text.draw(t.info.artist, card.x + s.X(70), card.y + s.Y(24), kMuted);
                text.draw("REMAIN", card.x + s.X(70), card.y + s.Y(44), kMuted);
                text.draw(clock(t.durationSec - pos[d], true), card.x + s.X(70), card.y + s.Y(56), kInk, 2);
                const double pct = (control.pitch(d) - 1.0) * 100.0;
                char tempoText[32], bpmBig[32];
                std::snprintf(tempoText, sizeof tempoText, "%+.2f%%", pct);
                std::snprintf(bpmBig, sizeof bpmBig, "%.1f", t.bpm * control.pitch(d));
                text.draw("TEMPO", card.x + s.X(392), card.y + s.Y(44), kMuted, 0, 2);
                text.draw(tempoText, card.x + s.X(392), card.y + s.Y(58), kInk, 1, 2);
                const SDL_Rect bpmBox{card.x + s.X(400), card.y + s.Y(48), s.X(96), s.Y(34)};
                s.frame(bpmBox, on ? kOrange : kInk);
                text.draw(bpmBig, bpmBox.x + bpmBox.w / 2, bpmBox.y + s.Y(4), kInk, 2, 1);
                s.overview(SDL_Rect{card.x + s.X(6), card.y + s.Y(104), card.w - s.X(12), s.Y(34)}, decks[d], pos[d]);
            }
            SDL_RenderPresent(renderer);
            ++frames;
            if (record != nullptr) {
                SDL_RenderReadPixels(renderer, nullptr, SDL_PIXELFORMAT_ARGB8888, pixels.data(), width * 4);
                const long due = static_cast<long>(std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() * 30.0) + 1;
                while (written < due) {
                    std::fwrite(pixels.data(), 1, pixels.size(), record);
                    ++written;
                }
            }
            if (!scriptPath.empty() && scriptDone.load()) running = false;

            // LEDs a cada 3 quadros (~10–20 Hz conforme a taxa de quadros).
            if (midi && frames % 3 == 0) {
                engine.takeLevels(raw);
                for (int i = 0; i < 4; ++i) peaks[i] = std::max(raw[i], peaks[i] * 0.8f);
                deckbridge::control::LedState state;
                for (int d = 0; d < 2; ++d) {
                    auto &led = state.decks[static_cast<size_t>(d)];
                    led.loaded = decks[d].loaded;
                    led.playing = control.playing(d);
                    led.pfl = control.pfl(d);
                    led.peak = peaks[d];
                    if (decks[d].loaded) {
                        for (const auto &cue : decks[d].track.analysis.cues) {
                            if (cue.hotIndex && *cue.hotIndex >= 0 && *cue.hotIndex < 8) led.hotCues |= static_cast<uint8_t>(1u << *cue.hotIndex);
                        }
                    }
                }
                ledBytes.clear();
                ledDiff.messages(state, ledBytes);
                midiOut.send(ledBytes);
            }
            if (!shot.empty() && !shotDone && elapsed >= shotAt) {
                SDL_Surface *surface = SDL_CreateRGBSurfaceWithFormat(0, width, height, 32, SDL_PIXELFORMAT_ARGB8888);
                SDL_RenderReadPixels(renderer, nullptr, SDL_PIXELFORMAT_ARGB8888, surface->pixels, surface->pitch);
                SDL_SaveBMP(surface, shot.c_str());
                SDL_FreeSurface(surface);
                shotDone = true;
                if (seconds == 0) running = false;
            }
            if (seconds > 0 && elapsed >= seconds) running = false;
            // 30 qps: a onda anda 5 colunas por quadro a 150 colunas/s; mais que isso só gasta CPU no Pi.
            const auto next = t0 + std::chrono::microseconds(static_cast<long long>(frames * 1e6 / 30));
            std::this_thread::sleep_until(next);
        }
        if (record != nullptr) std::fclose(record);
        const double cpu = static_cast<double>(std::clock()) / CLOCKS_PER_SEC - cpuStart;
        const double wall = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() - 1.0;
        std::printf("quadros %d, CPU do processo depois do 1º segundo: %.1f%% de um núcleo\n", frames, wall > 0 ? 100.0 * cpu / wall : 0.0);
    }
    if (demo.joinable()) demo.join();
    if (scriptThread.joinable()) scriptThread.join();
    while (loading.load() > 0) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    engine.stop();
    TTF_CloseFont(big);
    TTF_CloseFont(bold);
    TTF_CloseFont(regular);
    SDL_DestroyRenderer(renderer);
    SDL_DestroyWindow(window);
    TTF_Quit();
    SDL_Quit();
    return 0;
}
