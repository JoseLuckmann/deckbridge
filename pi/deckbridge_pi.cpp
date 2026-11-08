// DeckBridge no Linux (Raspberry Pi OS 64 bit, ADR-0007 opção C), fase 1: o motor do app (engine.cpp, sem mudança)
// tocando pelo ALSA, sem tela. Carrega faixas do pendrive, de arquivos ou sintéticas, toca e imprime a cada segundo
// a posição, os x-runs, as falhas de bloco, a CPU do processo e a RAM residente.
//
//   deckbridge_pi [--device hw:DDJ400] [--period 256] [--seconds 30] [--wav a.mp3] [--wav b.flac] [--fx]
//                 [--usb /media/PENDRIVE --track 3 --track 7]  (catálogo rekordbox, só leitura; faixas pela ordem)
//                 [--midi DDJ] [--map ddj400-map.json]   (DDJ-400 pelo ALSA rawmidi; ADR-0008)
//
// O pendrive do rekordbox é só leitura: este programa só lê os arquivos que recebe e não grava nada.

#define DECKBRIDGE_NO_JNI 1
#include "../android/app/src/main/cpp/engine.cpp"
#include "control/deck_control.h"
#include "leds.h"
#include "control/midi_in.h"
#include "app/session.h"

#include <sys/mman.h>
#include <sys/resource.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <fstream>

namespace {

/** Faixa sintética (bumbo, baixo e chimbal a 128 BPM) para testar sem música. */
deckbridge::audio::Pcm16 synthTrack(int seconds, double bpm, double bassHz) {
    deckbridge::audio::Pcm16 out;
    out.rate = 44100;
    const size_t frames = static_cast<size_t>(seconds) * 44100u;
    out.left.resize(frames);
    out.right.resize(frames);
    const double beat = 60.0 / bpm;
    uint32_t noise = 1u;
    for (size_t i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / 44100.0;
        const double in = std::fmod(t, beat);
        double x = 0.6 * std::exp(-in * 28.0) * std::sin(2.0 * M_PI * (48.0 + 90.0 * std::exp(-in * 40.0)) * in);
        x += 0.22 * std::sin(2.0 * M_PI * bassHz * t);
        noise = noise * 1664525u + 1013904223u;
        const double off = std::fmod(t + beat / 2, beat);
        x += 0.08 * (static_cast<double>(noise >> 9) / 4194304.0 - 1.0) * std::exp(-off * 70.0);
        out.left[i] = deckbridge::toPcm16(static_cast<float>(x));
        out.right[i] = out.left[i];
    }
    return out;
}

long rssKb() {
    std::ifstream statm("/proc/self/statm");
    long pages = 0, resident = 0;
    statm >> pages >> resident;
    return resident * (sysconf(_SC_PAGESIZE) / 1024);
}

double cpuSeconds() {
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    return usage.ru_utime.tv_sec + usage.ru_stime.tv_sec + (usage.ru_utime.tv_usec + usage.ru_stime.tv_usec) / 1e6;
}

std::string field(const std::string &stats, const std::string &key) {
    const auto at = stats.find(key + "=");
    if (at == std::string::npos) return "?";
    const auto end = stats.find('\n', at);
    return stats.substr(at + key.size() + 1, end - at - key.size() - 1);
}

}  // namespace

int main(int argc, char **argv) {
    int seconds = 20;
    bool fx = false;
    std::vector<std::string> wavs;
    std::string usb;
    std::vector<int> trackNumbers;
    std::string midiFilter;
    bool midi = false;
    std::string mapPath = "ddj400-map.json";
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (arg == "--device") oboe::AlsaConfig::device = next();
        else if (arg == "--period") oboe::AlsaConfig::periodFrames = std::max(32, std::atoi(next().c_str()));
        else if (arg == "--periods") oboe::AlsaConfig::periods = std::max(2, std::atoi(next().c_str()));
        else if (arg == "--seconds") seconds = std::max(1, std::atoi(next().c_str()));
        else if (arg == "--wav") wavs.push_back(next());
        else if (arg == "--fx") fx = true;
        else if (arg == "--usb") usb = next();
        else if (arg == "--track") trackNumbers.push_back(std::atoi(next().c_str()));
        else if (arg == "--midi") {
            midi = true;
            midiFilter = next();
        } else if (arg == "--map") mapPath = next();
        else {
            std::fprintf(stderr, "uso: %s [--device NOME] [--period N] [--periods N] [--seconds N] [--wav a.mp3] [--wav b.flac] [--usb PASTA --track N --track M] [--fx] [--midi FILTRO] [--map ARQ]\n", argv[0]);
            return 2;
        }
    }
    // Sem paginação no meio do set: o que o motor usa fica na RAM (precisa de memlock; sem ele só avisa).
    // Pilha de 512 KB para as threads novas (áudio, MIDI, carga): com mlockall, a pilha padrão de 8 MB de cada thread
    // ficava toda travada na RAM (+29 MB medidos na tela). O motor usa pouca pilha; 512 KB sobra.
    {
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 512 * 1024);
        pthread_setattr_default_np(&attr);
        pthread_attr_destroy(&attr);
    }
    if (mlockall(MCL_CURRENT | MCL_FUTURE) != 0) std::fprintf(stderr, "aviso: mlockall recusado (%s); siga sem travar a RAM\n", std::strerror(errno));

    ToneEngine &engine = ::engine();
    // Faixas: do pendrive (--usb/--track), de arquivos (--wav, WAV/MP3/FLAC) ou sintéticas.
    deckbridge::app::Catalog catalog;
    double deckBpm[2] = {128.0, 128.0};
    if (!usb.empty()) {
        std::string error;
        if (!catalog.open(usb, error)) {
            std::fprintf(stderr, "pendrive %s: %s\n", usb.c_str(), error.c_str());
            return 1;
        }
        std::printf("pendrive %s: %zu faixas, %zu playlists (só leitura)\n", usb.c_str(), catalog.tracks().size(), catalog.playlists().size());
    }
    for (int deck = 0; deck < 2; ++deck) {
        if (!usb.empty() && deck < static_cast<int>(trackNumbers.size())) {
            const int n = trackNumbers[static_cast<size_t>(deck)];
            if (n < 1 || n > static_cast<int>(catalog.tracks().size())) {
                std::fprintf(stderr, "deck %d: faixa %d fora do catálogo\n", deck + 1, n);
                return 1;
            }
            deckbridge::app::LoadedTrack loaded;
            std::string error;
            if (!deckbridge::app::loadTrack(engine, deck, catalog, catalog.tracks()[static_cast<size_t>(n - 1)], loaded, error)) {
                std::fprintf(stderr, "deck %d: %s: %s\n", deck + 1, catalog.tracks()[static_cast<size_t>(n - 1)].row.filePath.c_str(), error.c_str());
                return 1;
            }
            deckBpm[deck] = loaded.bpm;
            std::printf("deck %d: %s — %s, %.2f BPM, %.1f s, %zu batidas, %zu cues%s\n", deck + 1, loaded.info.row.title.c_str(),
                        loaded.info.artist.c_str(), loaded.bpm, loaded.durationSec, loaded.analysis.beats.size(), loaded.analysis.cues.size(),
                        loaded.truncated ? " (cortada no teto)" : "");
            continue;
        }
        deckbridge::audio::Pcm16 track;
        if (deck < static_cast<int>(wavs.size())) {
            std::string error;
            if (!deckbridge::audio::decodeFile(wavs[static_cast<size_t>(deck)], track, error)) {
                std::fprintf(stderr, "deck %d: %s: %s\n", deck + 1, wavs[static_cast<size_t>(deck)].c_str(), error.c_str());
                return 1;
            }
        } else {
            track = synthTrack(std::max(seconds + 5, 30), 128.0, deck == 0 ? 55.0 : 49.0);
        }
        const int rate = track.rate;
        engine.installDeck(deck, std::move(track.left), std::move(track.right), rate, 128.f, 0.f, nullptr, 0, -1.f, -1.f);
    }
    const std::string opened = engine.start(0, 3);
    std::printf("aparelho %s: aberto=%s canais=%s taxa=%s latência=%s ms formato=%s erro=%s\n",
                oboe::AlsaConfig::deviceName().c_str(), field(opened, "opened").c_str(), field(opened, "opened_channels").c_str(),
                field(opened, "sample_rate").c_str(), field(opened, "latency_ms").c_str(),
                oboe::AlsaConfig::lastFormat.load(), field(opened, "error").c_str());
    if (field(opened, "opened") != "true") return 1;
    engine.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, false, false);
    engine.transport(0, true);
    engine.transport(1, true);
    if (fx) engine.setBeatFx(15, true, 90, 2, 128.f, 1.f);  // SHIMMER no master: carga pesada de teste.
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    // DDJ-400: bytes do rawmidi → mapa oficial → ação → motor, na thread do MIDI (a mesma regra do app Android).
    deckbridge::control::MidiIn midiIn;
    deckbridge::control::DeckControl<ToneEngine> control(engine);
    deckbridge::control::TempoAssembler tempo;
    std::atomic<long> midiMessages{0};
    if (midi) {
        static const deckbridge::control::MidiMap map = deckbridge::control::MidiMap::fromFile(mapPath);
        if (map.all().empty()) {
            std::fprintf(stderr, "mapa MIDI vazio ou ausente: %s\n", mapPath.c_str());
            return 1;
        }
        control.setBpm(0, deckBpm[0]);
        control.setBpm(1, deckBpm[1]);
        const std::string port = midiIn.open(midiFilter, [&](const deckbridge::control::MidiMessage &m) {
            midiMessages.fetch_add(1);
            if (auto action = deckbridge::control::interpretPerformance(map.lookup(m.status, m.data1), m.data2, tempo)) control.apply(*action);
        });
        std::printf("MIDI: %s\n", port.empty() ? "nenhuma porta de entrada encontrada (DECKBRIDGE_MIDI_DEVICE ou --midi FILTRO)" : port.c_str());
    }
    // LEDs da DDJ-400 a 20 Hz, só o que mudou (a mesma regra do app: deckbridge::control::LedDiff).
    deckbridge::control::MidiOut midiOut;
    std::atomic<bool> ledsRunning{midi};
    std::thread leds;
    if (midi) {
        const std::string out = midiOut.open(midiFilter);
        std::printf("LEDs: %s\n", out.empty() ? "sem porta de saída" : out.c_str());
        leds = std::thread([&]() {
            deckbridge::control::LedDiff diff;
            std::vector<uint8_t> bytes;
            float peaks[4]{};
            float raw[4]{};
            while (ledsRunning.load()) {
                engine.takeLevels(raw);
                for (int i = 0; i < 4; ++i) peaks[i] = std::max(raw[i], peaks[i] * 0.8f);
                deckbridge::control::LedState state;
                for (int d = 0; d < 2; ++d) {
                    auto &led = state.decks[static_cast<size_t>(d)];
                    led.loaded = true;
                    led.playing = control.playing(d);
                    led.pfl = control.pfl(d);
                    led.peak = peaks[d];
                }
                bytes.clear();
                diff.messages(state, bytes);
                midiOut.send(bytes);
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
            }
        });
    }
    std::printf("tempo real: %s\n", oboe::AlsaConfig::lastRealtime.load() ? "sim (SCHED_FIFO)" : "não (sem permissão de rtprio)");
    double lastCpu = cpuSeconds();
    auto lastWall = std::chrono::steady_clock::now();
    for (int s = 1; s <= seconds; ++s) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        const double cpu = cpuSeconds();
        const auto wall = std::chrono::steady_clock::now();
        const double share = (cpu - lastCpu) / std::chrono::duration<double>(wall - lastWall).count();
        lastCpu = cpu;
        lastWall = wall;
        const std::string st = engine.stats();
        std::printf("t=%3ds deck1=%6.2fs deck2=%6.2fs xruns=%s falhas=%d cpu=%5.1f%% rss=%ld KB midi=%ld\n", s, engine.positionSeconds(0),
                    engine.positionSeconds(1), field(st, "xruns").c_str(), engine.glitchCount(), share * 100.0, rssKb(), midiMessages.load());
        std::fflush(stdout);
    }
    ledsRunning.store(false);
    if (leds.joinable()) leds.join();
    engine.stop();
    return 0;
}
