// Testes do motor REAL (engine.cpp) no PC, com o Oboe de mentira de tools/host_stubs. O que se valida aqui é o código do
// app: o mesmo callback de áudio, o mesmo mixer, as mesmas travas. O que não se valida: o Oboe de verdade, o aparelho e
// o tempo do tablet.
//
//   g++ -std=c++17 -O2 -pthread -I tools/host_stubs -I android/app/src/main/cpp -I "$JAVA_HOME/include" \
//       -I "$JAVA_HOME/include/linux" -o engine_host_test tools/engine_host_test.cpp && ./engine_host_test
//   (com -fsanitize=address,undefined ou -fsanitize=thread para os testes de memória e de corrida)
#include "../android/app/src/main/cpp/engine.cpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <string>
#include <thread>
#include <vector>

namespace {
int fails = 0;
void check(bool ok, const std::string &name) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", name.c_str());
    if (!ok) ++fails;
}

constexpr double kPi = 3.14159265358979323846;

/** Faixa estéreo sintética: bumbo a cada batida, baixo contínuo e um pouco de ruído nos contratempos. */
std::vector<float> makeTrack(int rate, double seconds, double bpm, bool stereo = true) {
    const int frames = static_cast<int>(seconds * rate);
    std::vector<float> data(static_cast<size_t>(frames) * (stereo ? 2 : 1));
    const double beat = 60.0 / bpm;
    uint32_t noise = 1;
    for (int i = 0; i < frames; ++i) {
        const double t = static_cast<double>(i) / rate;
        const double inBeat = std::fmod(t, beat);
        double x = 0.55 * std::exp(-inBeat * 28.0) * std::sin(2 * kPi * (48.0 + 90.0 * std::exp(-inBeat * 45.0)) * inBeat);
        x += 0.12 * std::sin(2 * kPi * 55.0 * t);
        noise = noise * 1664525u + 1013904223u;
        const double off = std::fmod(t + beat / 2, beat);
        x += 0.1 * (static_cast<double>((noise >> 9) & 0x3FFF) / 8192.0 - 1.0) * std::exp(-off * 55.0);
        x += 0.05 * std::sin(2 * kPi * 330.0 * t);
        if (stereo) {
            data[static_cast<size_t>(i) * 2] = static_cast<float>(x);
            data[static_cast<size_t>(i) * 2 + 1] = static_cast<float>(x * 0.97);
        } else {
            data[static_cast<size_t>(i)] = static_cast<float>(x);
        }
    }
    return data;
}

struct Stats {
    bool finite = true;
    float peak = 0.f;
    float masterPeak = 0.f;
    float phonePeak = 0.f;
    double dcMaster = 0.0;
    long frames = 0;
};

/** Passa um bloco pelo callback real e junta as estatísticas; o canário depois do buffer pega escrita fora dos limites. */
void pump(ToneEngine &engine, oboe::AudioStream &stream, int frames, Stats &stats, std::vector<float> *capture = nullptr) {
    const int channels = stream.getChannelCount();
    std::vector<float> buffer(static_cast<size_t>(frames) * channels + 64, 12345.f);
    engine.onAudioReady(&stream, buffer.data(), frames);
    for (size_t k = static_cast<size_t>(frames) * channels; k < buffer.size(); ++k) {
        if (buffer[k] != 12345.f) {
            std::printf("  canário violado no canal %d, índice %zu\n", channels, k);
            stats.finite = false;
            break;
        }
    }
    for (int i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) {
            const float x = buffer[static_cast<size_t>(i) * channels + c];
            if (!std::isfinite(x)) stats.finite = false;
            const float a = std::fabs(x);
            stats.peak = std::max(stats.peak, a);
            if (c < 2) stats.masterPeak = std::max(stats.masterPeak, a);
            else stats.phonePeak = std::max(stats.phonePeak, a);
            if (c == 0) stats.dcMaster += x;
        }
    }
    stats.frames += frames;
    if (capture) capture->insert(capture->end(), buffer.begin(), buffer.begin() + static_cast<size_t>(frames) * channels);
}

void loadBoth(ToneEngine &engine, int rate = 48000, double seconds = 60.0) {
    const auto a = makeTrack(rate, seconds, 128.0);
    const auto b = makeTrack(rate, seconds, 126.0);
    engine.loadDeck(0, a.data(), static_cast<int>(a.size()), 2, rate, 128.f, 0.f, nullptr, 0, -1.f, -1.f);
    engine.loadDeck(1, b.data(), static_cast<int>(b.size()), 2, rate, 126.f, 0.f, nullptr, 0, -1.f, -1.f);
}

std::shared_ptr<oboe::AudioStream> openMixer(ToneEngine &engine, int device = 0) {
    oboe::FakeDevice::maxChannels = 4;
    oboe::FakeDevice::allowExclusive = true;
    oboe::FakeDevice::failOpen = false;
    engine.start(device, 3);
    return oboe::FakeDevice::last;
}

// ---------------------------------------------------------------------------------------------------------------------

void basics() {
    ToneEngine engine;
    auto stream = openMixer(engine);
    check(stream && stream->getChannelCount() == 4, "abre o stream com 4 canais");
    Stats silent;
    for (int i = 0; i < 20; ++i) pump(engine, *stream, 256, silent);
    check(silent.finite && silent.peak == 0.f, "sem faixa carregada, a saída é silêncio exato");

    loadBoth(engine);
    engine.setMix(1.f, 0.f, 1.f, 1.f, 0.f, false, false);
    engine.transport(0, true);
    Stats play;
    for (int i = 0; i < 48000 * 5 / 256; ++i) pump(engine, *stream, 256, play);
    check(play.finite && play.masterPeak > 0.1f && play.masterPeak <= 0.981f, "tocando: saída finita, audível e abaixo do teto do limiter (" +
                                                                                    std::to_string(play.masterPeak) + ")");
    const double pos = engine.positionSeconds(0);
    check(std::fabs(pos - 5.0) < 0.02, "a posição avança 1x: " + std::to_string(pos) + " s depois de 5 s");
    check(std::fabs(play.dcMaster / play.frames) < 0.01, "sem tensão contínua no master");

    // O deck parado e o fader fechado não vazam para o master.
    engine.transport(0, false);
    engine.setMix(0.f, 0.f, 1.f, 1.f, 0.5f, false, false);
    Stats quiet;
    for (int i = 0; i < 40; ++i) pump(engine, *stream, 256, quiet);
    check(quiet.masterPeak < 1e-4f, "deck parado com fader fechado: master mudo (" + std::to_string(quiet.masterPeak) + ")");
}

void blockSizesAreEquivalent() {
    // A mesma cena em blocos de 64 a 4096 quadros tem de dar exatamente os mesmos números: nada pode depender da divisão.
    std::vector<std::vector<float>> outputs;
    const int sizes[] = {64, 128, 256, 512, 1024, 2048, 4096};
    for (int size : sizes) {
        ToneEngine engine;
        auto stream = openMixer(engine);
        loadBoth(engine);
        Stats stats;
        std::vector<float> out;
        const int total = 4096 * 40;
        int done = 0;
        auto stepTo = [&](int target) {
            while (done < target) {
                pump(engine, *stream, size, stats, &out);
                done += size;
            }
        };
        // Ações só em múltiplos de 4096.
        engine.setMix(1.f, 1.f, 1.f, 1.0159f, 0.5f, true, false);
        engine.setKeyLock(1, true);
        engine.transport(0, true);
        engine.transport(1, true);
        stepTo(4096 * 6);
        engine.setBeatFx(1, true, 90, 2, 128.f, 0.5f);
        engine.setEq(0, 0.2f, 0.5f, 0.8f);
        stepTo(4096 * 12);
        engine.setLoop(0, 4.f, 6.f);
        engine.setFilter(1, 0.2f);
        stepTo(4096 * 18);
        engine.setLoop(0, -1.f, -1.f);
        engine.setBeatFx(1, false, 90, 2, 128.f, 0.5f);
        const float rmx[24] = {1, 3, 1, 0.9f, 4, 0, 0.25f, 0, -1, 1, 1, 1, 1, 1, 1, 0, 0, 128.f, 0, 3, 8, 1, 0, 0};
        engine.setRmx(rmx, 24);
        stepTo(4096 * 26);
        engine.scratchTouch(1, true);
        engine.scratchMove(1, 0.05);
        stepTo(4096 * 28);
        engine.scratchTouch(1, false);
        stepTo(total);
        outputs.push_back(std::move(out));
    }
    bool same = true;
    for (size_t k = 1; k < outputs.size(); ++k) {
        if (outputs[k].size() != outputs[0].size() || std::memcmp(outputs[k].data(), outputs[0].data(), outputs[0].size() * sizeof(float)) != 0) {
            same = false;
            double worst = 0.0;
            for (size_t i = 0; i < std::min(outputs[k].size(), outputs[0].size()); ++i) worst = std::max(worst, static_cast<double>(std::fabs(outputs[k][i] - outputs[0][i])));
            std::printf("  bloco %d difere do de 64: pior diferença %g\n", sizes[k], worst);
        }
    }
    check(same, "blocos de 64 a 4096 quadros dão exatamente o mesmo áudio");
}

void channelCountsAndRates() {
    // Aparelho que só entrega 2 canais: cai para 2, avisa, e o callback não escreve fora do buffer.
    {
        ToneEngine engine;
        oboe::FakeDevice::maxChannels = 2;
        engine.start(0, 3);
        auto stream = oboe::FakeDevice::last;
        const std::string st = engine.stats();
        check(stream && stream->getChannelCount() == 2 && st.find("fallback_to_two=true") != std::string::npos,
              "aparelho de 2 canais: o motor abre em 2 e avisa (fallback_to_two)");
        loadBoth(engine);
        engine.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, true, true);
        engine.transport(0, true);
        Stats stats;
        for (int i = 0; i < 200; ++i) pump(engine, *stream, 256, stats);
        check(stats.finite && stats.masterPeak > 0.05f, "2 canais: master sai, sem escrita fora do buffer");
    }
    // 6 canais (interface maior): os canais extras ficam mudos e o canário continua intacto.
    for (int channels : {1, 3, 6, 8}) {
        ToneEngine engine;
        oboe::FakeDevice::maxChannels = 4;
        engine.start(0, 3);
        auto stream = std::make_shared<oboe::AudioStream>();
        stream->channels = channels;
        loadBoth(engine);
        engine.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, true, true);
        engine.transport(0, true);
        engine.transport(1, true);
        Stats stats;
        for (int i = 0; i < 100; ++i) pump(engine, *stream, 300, stats);
        check(stats.finite, "stream de " + std::to_string(channels) + " canais: sem escrita fora do buffer nem NaN");
    }
    oboe::FakeDevice::maxChannels = 4;
    // Taxas de saída diferentes de 48 kHz e faixas em outras taxas.
    for (int outRate : {44100, 48000, 96000}) {
        for (int fileRate : {22050, 44100, 48000, 96000}) {
            ToneEngine engine;
            engine.start(0, 3);
            auto stream = oboe::FakeDevice::last;
            stream->rate = outRate;
            loadBoth(engine, fileRate, 20.0);
            engine.setMix(1.f, 0.f, 1.f, 1.f, 0.f, false, false);
            engine.transport(0, true);
            Stats stats;
            const int blocks = outRate * 4 / 256;
            for (int i = 0; i < blocks; ++i) pump(engine, *stream, 256, stats);
            const double pos = engine.positionSeconds(0);
            check(stats.finite && std::fabs(pos - 4.0) < 0.03,
                  "saída " + std::to_string(outRate) + " Hz, faixa " + std::to_string(fileRate) + " Hz: 4 s de áudio em " + std::to_string(pos) + " s");
        }
    }
}

void disconnectAndRetry() {
    ToneEngine engine;
    auto stream = openMixer(engine);
    loadBoth(engine);
    engine.setMix(1.f, 0.f, 1.f, 1.f, 0.f, false, false);
    engine.transport(0, true);
    Stats stats;
    for (int i = 0; i < 400; ++i) pump(engine, *stream, 256, stats);
    const double before = engine.positionSeconds(0);
    // O cabo sai: o Oboe chama onErrorAfterClose. Os primeiros reabres falham.
    oboe::FakeDevice::failOpen = true;
    engine.onErrorAfterClose(stream.get(), oboe::Result::ErrorDisconnected);
    check(engine.stats().find("opened=false") != std::string::npos, "desconectou: o motor sabe que o stream caiu");
    std::this_thread::sleep_for(std::chrono::milliseconds(1100));
    oboe::FakeDevice::failOpen = false;
    bool back = false;
    for (int i = 0; i < 100 && !back; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        back = engine.stats().find("opened=true") != std::string::npos;
    }
    check(back, "o cabo volta: o motor reabre sozinho depois de reabres que falharam");
    check(std::fabs(engine.positionSeconds(0) - before) < 0.01, "a posição da faixa não anda nem volta durante a queda");
    auto reopened = oboe::FakeDevice::last;
    Stats after;
    for (int i = 0; i < 400; ++i) pump(engine, *reopened, 256, after);
    check(after.finite && after.masterPeak > 0.1f && engine.positionSeconds(0) > before + 1.5,
          "depois de reabrir, a faixa segue tocando de onde estava");

    // Desiste depois de 6 tentativas e continua utilizável por start().
    oboe::FakeDevice::failOpen = true;
    engine.onErrorAfterClose(reopened.get(), oboe::Result::ErrorDisconnected);
    std::this_thread::sleep_for(std::chrono::milliseconds(8500));
    check(engine.stats().find("opened=false") != std::string::npos, "sem aparelho por muito tempo: desiste sem travar");
    oboe::FakeDevice::failOpen = false;
    engine.start(0, 3);
    check(engine.stats().find("opened=true") != std::string::npos, "e um start() manual depois disso abre normalmente");
    engine.stop();
}

struct Rng {
    std::mt19937 gen;
    explicit Rng(unsigned seed) : gen(seed) {}
    float f(float lo, float hi) { return std::uniform_real_distribution<float>(lo, hi)(gen); }
    int i(int lo, int hi) { return std::uniform_int_distribution<int>(lo, hi)(gen); }
    bool b() { return i(0, 1) == 1; }
    /** Valores "de rua": normais, extremos e os que um erro de conta no Kotlin poderia mandar. */
    float wild() {
        switch (i(0, 9)) {
            case 0: return std::nanf("");
            case 1: return INFINITY;
            case 2: return -INFINITY;
            case 3: return 0.f;
            case 4: return -1.f;
            case 5: return 1e30f;
            case 6: return -1e30f;
            default: return f(-3.f, 3.f);
        }
    }
};

/** Uma ação aleatória sobre o motor, com valores normais ou absurdos. `wild` liga NaN/infinito/enormes. */
int randomAction(ToneEngine &e, Rng &r, bool wild) {
    auto v = [&](float lo, float hi) { return wild && r.i(0, 5) == 0 ? r.wild() : r.f(lo, hi); };
    const int deck = r.i(0, 1);
    const int which = r.i(0, 30);
    switch (which) {
        case 0: e.transport(deck, r.b()); break;
        case 1: e.cue(deck); break;
        case 2: e.hotCue(deck, r.i(-1, 9)); break;
        case 3: e.releaseHotCue(deck); break;
        case 4: e.setMix(v(0.f, 1.f), v(0.f, 1.f), v(0.5f, 2.f), v(0.5f, 2.f), v(0.f, 1.f), r.b(), r.b()); break;
        case 5: e.seekSeconds(deck, wild ? static_cast<double>(v(-50.f, 200.f)) : r.f(0.f, 60.f)); break;
        case 6: e.nudge(deck, static_cast<double>(v(-0.5f, 0.5f))); break;
        case 7: e.scratchTouch(deck, r.b()); break;
        case 8: e.scratchMove(deck, static_cast<double>(v(-0.3f, 0.3f))); break;
        case 9: e.setBeatFx(r.i(wild ? -2 : 0, 18), r.b(), r.i(0, 127), r.i(0, 2), v(60.f, 200.f), v(0.0625f, 8.f)); break;
        case 10: e.setFilter(deck, v(0.f, 1.f)); break;
        case 11: e.setEq(deck, v(0.f, 1.f), v(0.f, 1.f), v(0.f, 1.f)); break;
        case 12: e.setTrimKnob(deck, v(0.f, 1.f)); break;
        case 13: e.setHead(v(0.f, 1.f), v(0.f, 1.f)); break;
        case 14: e.setColorKind(deck, r.i(wild ? -1 : 0, 6)); break;
        case 15: e.setKeyLock(deck, r.b()); break;
        case 16: e.setKeyShift(deck, r.i(wild ? -30 : -7, wild ? 30 : 7)); break;
        case 17: e.setUserSlip(deck, r.b()); break;
        case 18: e.setReverse(deck, r.b()); break;
        case 19: {
            const float a = v(0.f, 55.f);
            e.setLoop(deck, a, a + v(-1.f, 8.f));
            break;
        }
        case 20: {
            float values[24];
            for (float &x : values) x = v(0.f, 1.f);
            values[0] = r.b() ? 1.f : 0.f;
            values[1] = static_cast<float>(r.i(0, 7));
            values[8] = static_cast<float>(r.i(-1, 2));
            values[17] = v(60.f, 200.f);
            e.setRmx(values, r.i(18, 24));
            break;
        }
        case 21: {
            float values[18];
            for (float &x : values) x = v(0.f, 1.f);
            values[0] = static_cast<float>(r.i(0, 2));
            values[1] = static_cast<float>(r.i(0, 2));
            values[10] = static_cast<float>(r.i(-1, 3));
            e.setConsole(values, r.i(1, 18));
            break;
        }
        case 22: e.setGrid(deck, v(0.f, 2.f), v(60.f, 200.f)); break;
        case 23: e.setMasterGain(v(0.f, 4.f)); break;
        case 24: e.setTrim(deck, v(0.f, 4.f)); break;
        case 25: {
            std::vector<float> clip(static_cast<size_t>(r.i(0, 50000)), 0.3f);
            e.previewLoad(clip.data(), clip.data(), static_cast<int>(clip.size()), r.i(8000, 96000));
            break;
        }
        case 26: e.previewStop(); break;
        case 27: {
            // Troca de faixa no deck: curta, vazia, mono, com taxa estranha.
            const int rate = r.i(0, 3) == 0 ? 8000 : (r.b() ? 44100 : 48000);
            const bool stereo = r.b();
            const auto t = makeTrack(rate, r.f(wild ? 0.f : 1.f, 30.f), v(80.f, 170.f) > 0 ? 128.0 : 120.0, stereo);
            e.loadDeck(deck, t.data(), static_cast<int>(t.size()), stereo ? 2 : 1, rate, v(60.f, 200.f), v(0.f, 5.f), nullptr, 0, -1.f, -1.f);
            break;
        }
        case 28: e.setKeyLock(deck, false); break;
        case 29: e.setBufferFrames(r.i(32, 2048)); break;
        default: e.setReverse(deck, false); break;
    }
    return which;
}

void fuzz(unsigned seed, bool wild, int seconds) {
    ToneEngine engine;
    auto stream = openMixer(engine);
    loadBoth(engine);
    Rng rng(seed);
    Stats stats;
    std::string history;
    const int blocks = 48000 * seconds / 256;
    for (int block = 0; block < blocks; ++block) {
        const int actions = rng.i(0, 3);
        std::string applied;
        for (int k = 0; k < actions; ++k) applied += std::to_string(randomAction(engine, rng, wild)) + " ";
        const bool wasFinite = stats.finite;
        pump(engine, *stream, rng.b() ? 256 : rng.i(16, 700), stats);
        if (wasFinite && !stats.finite) std::printf("  primeiro não finito no bloco %d, ações: %s\n", block, applied.c_str());
        if (!applied.empty()) history = applied + "| " + history.substr(0, 120);
    }
    const std::string label = std::string(wild ? "valores absurdos" : "valores normais") + ", semente " + std::to_string(seed) + ", " +
                              std::to_string(seconds) + " s de áudio";
    check(stats.finite, "fuzz com " + label + ": só números finitos e nenhuma escrita fora do buffer");
    check(stats.masterPeak <= 0.9801f, "fuzz com " + label + ": o master nunca passa do teto do limiter (" + std::to_string(stats.masterPeak) + ")");
    check(stats.phonePeak <= 0.9801f, "fuzz com " + label + ": os fones também ficam sob o teto (" + std::to_string(stats.phonePeak) + ")");
    std::printf("  (picos: master %.3f, fones %.3f)\n", stats.masterPeak, stats.phonePeak);
}

void threadStress(int seconds) {
    ToneEngine engine;
    auto stream = openMixer(engine);
    loadBoth(engine);
    engine.transport(0, true);
    engine.transport(1, true);
    std::atomic<bool> stop{false};
    std::atomic<bool> bad{false};
    std::atomic<long> blocks{0};
    // "Thread de áudio": o mais rápido que der, com bloco de 256, como o callback faz.
    std::thread audio([&]() {
        Stats stats;
        while (!stop.load()) {
            pump(engine, *stream, 256, stats);
            blocks.fetch_add(1);
            if (!stats.finite) {
                bad.store(true);
                break;
            }
        }
    });
    // "Thread da tela" e "thread do MIDI": ações o tempo todo.
    auto controller = [&](unsigned seed) {
        Rng rng(seed);
        while (!stop.load()) {
            randomAction(engine, rng, false);
            std::this_thread::sleep_for(std::chrono::microseconds(rng.i(0, 400)));
        }
    };
    std::thread ui(controller, 11u);
    std::thread midi(controller, 12u);
    std::thread poll([&]() {
        float lv[4];
        while (!stop.load()) {
            engine.takeLevels(lv);
            (void)engine.positionSeconds(0);
            (void)engine.stats();
            std::this_thread::sleep_for(std::chrono::milliseconds(3));
        }
    });
    std::this_thread::sleep_for(std::chrono::seconds(seconds));
    stop.store(true);
    audio.join();
    ui.join();
    midi.join();
    poll.join();
    check(!bad.load() && blocks.load() > 100, "quatro threads mexendo no motor ao mesmo tempo (" + std::to_string(blocks.load()) + " blocos): sem NaN e sem travar");
}
}  // namespace

int main(int argc, char **argv) {
    const std::string only = argc > 1 ? argv[1] : "all";
    const bool all = only == "all";
    if (all || only == "basics") basics();
    if (all || only == "blocks") blockSizesAreEquivalent();
    if (all || only == "channels") channelCountsAndRates();
    if (all || only == "disconnect") disconnectAndRetry();
    if (all || only == "fuzz") {
        for (unsigned seed : {1u, 2u, 3u}) fuzz(seed, false, 60);
        for (unsigned seed : {101u, 102u, 103u}) fuzz(seed, true, 60);
    }
    if (all || only == "threads") threadStress(10);
    std::printf("FAILS %d\n", fails);
    return fails == 0 ? 0 : 1;
}
