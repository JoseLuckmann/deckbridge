// Medição de CPU do callback REAL (engine.cpp) e dos módulos de dsp.h, no host, com o Oboe de mentira.
// Não é o tablet nem o Pi: os números são do processador onde roda.
//
//   g++ -std=c++17 -O2 -pthread -I tools/host_stubs -I android/app/src/main/cpp -I "$JAVA_HOME/include" \
//       -I "$JAVA_HOME/include/linux" -o perf_harness tools/perf_harness.cpp && ./perf_harness [scenes|modules|denormal|all]
//   FTZ=1 liga flush-to-zero/denormals-are-zero (x86 SSE) antes de medir.
#include "../android/app/src/main/cpp/engine.cpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <new>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>
#include <vector>
#if defined(__x86_64__) || defined(__i386__)
#include <xmmintrin.h>
#include <pmmintrin.h>
#endif

// Conta alocações feitas DENTRO do callback (operator new global). O callback de áudio não deveria alocar nunca.
static std::atomic<bool> gInCallback{false};
static std::atomic<long> gCallbackNews{0};
static std::atomic<long> gCallbackBytes{0};
void *operator new(size_t n) {
    if (gInCallback.load(std::memory_order_relaxed)) {
        gCallbackNews.fetch_add(1);
        gCallbackBytes.fetch_add(static_cast<long>(n));
    }
    void *p = std::malloc(n ? n : 1);
    if (!p) throw std::bad_alloc();
    return p;
}
void operator delete(void *p) noexcept { std::free(p); }
void operator delete(void *p, size_t) noexcept { std::free(p); }

namespace {

constexpr int kRate = 48000;
constexpr int kBlock = 256;
constexpr double kPi = 3.14159265358979323846;
volatile float gSink = 0.f;

using Clock = std::chrono::steady_clock;

std::vector<float> makeTrack(int rate, double seconds, double bpm) {
    const int frames = static_cast<int>(seconds * rate);
    std::vector<float> data(static_cast<size_t>(frames) * 2);
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
        data[static_cast<size_t>(i) * 2] = static_cast<float>(x);
        data[static_cast<size_t>(i) * 2 + 1] = static_cast<float>(x * 0.97);
    }
    return data;
}

struct BlockStats {
    double meanUs = 0, p50 = 0, p99 = 0, maxUs = 0;
    double nsPerFrame() const { return meanUs * 1000.0 / kBlock; }
};

BlockStats summarize(std::vector<double> us) {
    BlockStats s;
    std::sort(us.begin(), us.end());
    double sum = 0;
    for (double v : us) sum += v;
    s.meanUs = sum / us.size();
    s.p50 = us[us.size() / 2];
    s.p99 = us[us.size() * 99 / 100];
    s.maxUs = us.back();
    return s;
}

const double kBudgetUs = 1e6 * kBlock / kRate;  // 5333 us

void printScene(const char *name, const BlockStats &s) {
    std::printf("%-34s %8.1f ns/quadro  %6.2f%% orç.  bloco256: média %6.1f us p50 %6.1f p99 %6.1f max %7.1f\n", name, s.nsPerFrame(),
                100.0 * s.meanUs / kBudgetUs, s.meanUs, s.p50, s.p99, s.maxUs);
}

// --------------------------------------------------------------------------------------------------------------------
// Cenas do motor inteiro.

struct Rig {
    ToneEngine engine;
    std::shared_ptr<oboe::AudioStream> stream;
    std::vector<float> out;
    Rig() {
        oboe::FakeDevice::maxChannels = 4;
        oboe::FakeDevice::allowExclusive = true;
        oboe::FakeDevice::failOpen = false;
        engine.start(0, 3);
        stream = oboe::FakeDevice::last;
        out.assign(static_cast<size_t>(kBlock) * 4, 0.f);
    }
    double block() {
        const auto t0 = Clock::now();
        gInCallback.store(true);
        engine.onAudioReady(stream.get(), out.data(), kBlock);
        gInCallback.store(false);
        const auto t1 = Clock::now();
        gSink = gSink + out[0];
        return std::chrono::duration<double, std::micro>(t1 - t0).count();
    }
};

double trackSeconds() {
    const char *e = std::getenv("TRACKSEC");
    return e ? std::atof(e) : 120.0;
}
std::vector<float> &trackA() {
    static std::vector<float> t = makeTrack(44100, trackSeconds(), 128.0);
    return t;
}
std::vector<float> &trackB() {
    static std::vector<float> t = makeTrack(44100, trackSeconds(), 126.0);
    return t;
}

void loadDecks(ToneEngine &e) {
    e.loadDeck(0, trackA().data(), static_cast<int>(trackA().size()), 2, 44100, 128.f, 0.f, nullptr, 0, -1.f, -1.f);
    e.loadDeck(1, trackB().data(), static_cast<int>(trackB().size()), 2, 44100, 126.f, 0.f, nullptr, 0, -1.f, -1.f);
    e.setGrid(0, 0.f, 128.f);
    e.setGrid(1, 0.f, 126.f);
}

void consoleArray(float *c, float comp, bool iso, int sendType, float sendLvl, float bandLow, float bandMid, float bandHi) {
    for (int i = 0; i < 18; ++i) c[i] = 0.f;
    c[0] = 2; c[1] = 0; c[2] = comp; c[3] = comp; c[4] = iso ? 1.f : 0.f; c[5] = 0.3f; c[6] = 0.6f; c[7] = 0.7f;
    c[8] = sendLvl; c[9] = sendLvl; c[10] = static_cast<float>(sendType); c[11] = 0.6f; c[12] = 0.5f; c[13] = 0.7f; c[14] = 0.7f;
    c[15] = bandLow; c[16] = bandMid; c[17] = bandHi;
}

using Setup = std::function<void(ToneEngine &)>;

BlockStats runScene(const Setup &setup, int seconds, int warmBlocks = 200) {
    Rig rig;
    setup(rig.engine);
    for (int i = 0; i < warmBlocks; ++i) rig.block();
    std::vector<double> us;
    const int blocks = kRate * seconds / kBlock;
    us.reserve(blocks);
    for (int i = 0; i < blocks; ++i) us.push_back(rig.block());
    return summarize(us);
}

void sceneIdle(ToneEngine &e) { (void)e; }

void sceneLoadedStopped(ToneEngine &e) {
    loadDecks(e);
    e.setMix(1.f, 1.f, 1.f, 1.f, 0.5f, false, false);
}

void sceneTypical(ToneEngine &e) {
    loadDecks(e);
    e.setMix(1.f, 1.f, 1.f, 1.0159f, 0.5f, true, false);
    e.setEq(0, 0.45f, 0.5f, 0.55f);
    e.setEq(1, 0.5f, 0.5f, 0.5f);
    e.setFilter(1, 0.3f);
    e.setHead(0.5f, 0.75f);
    e.transport(0, true);
    e.transport(1, true);
}

void previewOn(ToneEngine &e) {
    static std::vector<float> l, r;
    if (l.empty()) {
        const auto &t = trackA();
        const int frames = std::min(44100 * 30, static_cast<int>(trackA().size() / 2));
        l.resize(frames);
        r.resize(frames);
        for (int i = 0; i < frames; ++i) { l[i] = t[i * 2]; r[i] = t[i * 2 + 1]; }
    }
    e.previewLoad(l.data(), r.data(), static_cast<int>(l.size()), 44100);
}

void worstCommon(ToneEngine &e) {
    loadDecks(e);
    e.setMix(1.f, 1.f, 1.03f, 0.98f, 0.5f, true, true);
    e.setKeyLock(0, true);
    e.setKeyLock(1, true);
    e.setKeyShift(1, 2);
    e.setEq(0, 0.2f, 0.8f, 0.3f);
    e.setEq(1, 0.7f, 0.1f, 0.9f);
    e.setColorKind(0, deckbridge::kColorSpace);
    e.setColorKind(1, deckbridge::kColorDubEcho);
    e.setFilter(0, 0.95f);
    e.setFilter(1, 0.1f);
    e.setTrimKnob(0, 0.6f);
    e.setHead(0.5f, 0.75f);
    previewOn(e);
    e.transport(0, true);
    e.transport(1, true);
}

int gBeatEffect = 15;

void sceneWorstBeat(ToneEngine &e) {
    worstCommon(e);
    float c[18];
    consoleArray(c, 0.7f, true, 3, 0.5f, 1.f, 0.f, 1.f);  // MID sem efeito -> 4 BandSplit3 ativos
    e.setConsole(c, 18);
    e.setBeatFx(gBeatEffect, true, 100, 2, 128.f, 1.f);
}

void sceneWorstRmx(ToneEngine &e) {
    worstCommon(e);
    float c[18];
    consoleArray(c, 0.7f, true, 3, 0.5f, 1.f, 1.f, 1.f);
    e.setConsole(c, 18);
    // Cena 2 (SPIRAL UP: grão + FDN + atraso) segurada, X-PAD em drum build seguindo a grade, isolador com gate.
    const float rmx[24] = {1, 2, 1, 1.f, 8, 2, 0.25f, 1, -1, 0.6f, 1.4f, 0.8f, 0.7f, 1.2f, 1.1f, 1, 2, 128.f, 1.f, 3, 8, 1, 1, 0};
    e.setRmx(rmx, 24);
}

// --------------------------------------------------------------------------------------------------------------------
// Microbenchmarks por módulo: ns por quadro estéreo (melhor de 5 rodadas de 2 s de áudio).

struct Noise {
    uint32_t s = 12345;
    float next() { return deckbridge::noiseSample(s) * 0.3f; }
};

template <class F>
double timeModule(F &&perFrame, int seconds = 2, int reps = 5) {
    const int frames = kRate * seconds;
    double best = 1e30;
    for (int rep = 0; rep < reps; ++rep) {
        const auto t0 = Clock::now();
        for (int i = 0; i < frames; ++i) perFrame(i);
        const auto t1 = Clock::now();
        best = std::min(best, std::chrono::duration<double, std::nano>(t1 - t0).count() / frames);
    }
    return best;
}

void report(const char *name, double ns) {
    std::printf("  %-44s %8.1f ns/quadro  %6.3f%% de 1 quadro a 48 kHz (20833 ns)\n", name, ns, 100.0 * ns / (1e9 / kRate));
}

void modules() {
    std::printf("== Módulos (ns por quadro estéreo, -O2, melhor de 5) ==\n");
    // Leitura de deck
    {
        Deck d;
        auto &t = trackA();
        const int frames = static_cast<int>(t.size() / 2);
        d.left.resize(frames);
        d.right.resize(frames);
        for (int i = 0; i < frames; ++i) { d.left[i] = t[i * 2]; d.right[i] = t[i * 2 + 1]; }
        d.sampleRate = 44100;
        d.playing = true;
        d.pitch = 1.02;
        float l, r;
        report("Deck::read direta (pitch 1.02)", timeModule([&](int) { d.read(kRate, l, r); gSink = l + r; if (d.position > frames - 50000) d.position = 0; }));
        d.keyLock = true;
        report("Deck::read Master Tempo (WSOLA)", timeModule([&](int) { d.read(kRate, l, r); gSink = l + r; if (d.position > frames - 50000) d.position = 0; }));
        d.keyLock = false;
        d.keyShift = 2;
        report("Deck::read Key Shift +2 (WSOLA)", timeModule([&](int) { d.read(kRate, l, r); gSink = l + r; if (d.position > frames - 50000) d.position = 0; }));
        d.keyShift = 0;
        d.loopStart = 44100.0 * 4;
        d.loopEnd = 44100.0 * 4 + 200;  // loop curto: crossfade de borda a cada 200 amostras
        d.position = d.loopStart;
        report("Deck::read loop curto (cos/sin na borda)", timeModule([&](int) { d.read(kRate, l, r); gSink = l + r; }));
    }
    Noise n;
    {
        deckbridge::ChannelComp c;
        c.set(0.7f, kRate);
        report("ChannelComp (knob 0.7)", timeModule([&](int) { float l = n.next(), r = n.next(); c.process(l, r); gSink = l + r; }));
    }
    {
        deckbridge::Isolator eq;
        eq.prepare(kRate);
        eq.set(0.3f, 1.4f, 0.8f, false);
        report("Isolator/EQ (fora de unity, 16 biquads)", timeModule([&](int) { float l = n.next(), r = n.next(); eq.process(l, r); gSink = l + r; }));
        deckbridge::Isolator un;
        un.prepare(kRate);
        report("Isolator/EQ (unity, atalho)", timeModule([&](int) { float l = n.next(), r = n.next(); un.process(l, r); gSink = l + r; }));
    }
    {
        const char *names[] = {"SPACE", "DUB ECHO", "SWEEP", "NOISE", "CRUSH", "FILTER"};
        for (int k = 0; k < 6; ++k) {
            deckbridge::ColorFilter cf;
            cf.prepare(kRate);
            cf.setKind(k);
            cf.set(0.9f, kRate);
            cf.bpm = 128.f;
            char label[80];
            std::snprintf(label, sizeof label, "ColorFilter %s (knob 0.9)", names[k]);
            report(label, timeModule([&](int) { float l = n.next(), r = n.next(); cf.process(l, r); gSink = l + r; }));
        }
    }
    {
        const char *names[] = {"0 LOW CUT ECHO", "1 ECHO", "2 PING PONG", "3 SPIRAL", "4 REVERB", "5 TRANS", "6 FLANGER", "7 PHASER", "8 PITCH",
                               "9 SLIP ROLL", "10 ROLL", "11 HELIX", "12 MOBIUS", "13 FILTER", "14 VINYL BRAKE", "15 SHIMMER", "16 DELAY"};
        for (int k = 0; k <= 16; ++k) {
            deckbridge::BeatInsert b;
            b.configure(k, true, 0.8f, 128.f, 1.f, kRate);
            char label[80];
            std::snprintf(label, sizeof label, "BeatInsert efeito %s", names[k]);
            report(label, timeModule([&](int) { float l = n.next(), r = n.next(); b.process(l, r); gSink = l + r; }));
        }
    }
    {
        deckbridge::BandSplit3 s;
        s.prepare(kRate);
        report("BandSplit3 (1 canal; FX FREQ usa 4)", timeModule([&](int) { float a, b, c; s.split(n.next(), a, b, c); gSink = a + b + c; }));
    }
    {
        for (int sc = 0; sc <= 7; ++sc) {
            deckbridge::SceneFx s;
            s.sampleRate = kRate;
            s.bpm = 128.f;
            s.scene = sc;
            s.bars = 1.f;
            s.setHold(true);
            float l0 = 0, r0 = 0;
            for (int i = 0; i < kRate * 3; ++i) { l0 = n.next(); r0 = n.next(); s.process(l0, r0); }  // intensidade no teto
            char label[80];
            std::snprintf(label, sizeof label, "SceneFx cena %d (segurada)", sc);
            report(label, timeModule([&](int) { float l = n.next(), r = n.next(); s.process(l, r); gSink = l + r; }));
        }
    }
    {
        deckbridge::XPad x;
        x.set(2, 0.25f, true, 128.f, kRate, 1.f, 3, 8, true, true);
        x.followGrid = true;
        double beat = 0.0;
        // Aquecer: os modelos de bateria são gerados na 1a batida (dentro do áudio!).
        for (int i = 0; i < kRate; ++i) { beat += 128.0 / 60.0 / kRate; x.setPhase(beat, true); float l = 0, r = 0; x.process(l, r); }
        report("XPad drum build (grade, risers, tanque)", timeModule([&](int) {
            beat += 128.0 / 60.0 / kRate;
            if (beat > 30.0) { beat = 0.0; x.set(2, 0.25f, false, 128.f, kRate, 1.f, 3, 8, true, true); x.set(2, 0.25f, true, 128.f, kRate, 1.f, 3, 8, true, true); }
            x.setPhase(beat, true);
            float l = n.next(), r = n.next();
            x.process(l, r);
            gSink = l + r;
        }));
        deckbridge::XPad roll;
        roll.set(1, 0.25f, true, 128.f, kRate);
        report("XPad modo 1 (trans)", timeModule([&](int) { float l = n.next(), r = n.next(); roll.process(l, r); gSink = l + r; }));
    }
    {
        const char *names[] = {"SHORT DELAY", "LONG DELAY", "DUB ECHO", "REVERB"};
        for (int k = 0; k < 4; ++k) {
            deckbridge::SendReturn s;
            s.prepare(kRate);
            s.configure(k, 0.6f, 0.5f, 0.7f);
            char label[80];
            std::snprintf(label, sizeof label, "SendReturn %s (TONE 0.7)", names[k]);
            report(label, timeModule([&](int) { float l, r; s.process(n.next(), n.next(), l, r); gSink = l + r; }));
        }
    }
    {
        deckbridge::Fdn f;
        f.prepare(kRate);
        f.setRt60(3.f);
        report("Fdn (8 linhas) sozinho", timeModule([&](int) { float l, r; f.process(n.next(), n.next(), 0.5f, l, r); gSink = l + r; }));
    }
    {
        deckbridge::GrainShift g;
        report("GrainShift (1 canal)", timeModule([&](int) { gSink = g.process(n.next(), 1.5f); }));
    }
    {
        deckbridge::PeakLimiter p;
        report("PeakLimiter", timeModule([&](int) { float l = n.next() * 4, r = n.next() * 4; p.process(l, r); gSink = l + r; }));
        deckbridge::MotorGain m;
        report("MotorGain::next (exp por amostra)", timeModule([&](int i) { gSink = m.next(1.0 + (i & 1) * 1e-3, kRate); }));
        deckbridge::PreviewVoice v;
        std::vector<float> clip(kRate * 30, 0.2f);
        v.load(clip.data(), clip.data(), static_cast<int>(clip.size()), 44100);
        report("PreviewVoice::read", timeModule([&](int) { float l, r; v.read(kRate, l, r); gSink = l + r; if (!v.playing) v.load(clip.data(), clip.data(), 4, 44100); }));
        deckbridge::ParamSmooth ps;
        ps.setMs(30.f, kRate);
        report("ParamSmooth::next", timeModule([&](int i) { gSink = ps.next((i & 1024) ? 1.f : 0.f, false); }));
        deckbridge::Svf svf;
        report("Svf::set (tanf) + process", timeModule([&](int i) { svf.set(500.f + (i & 255), kRate, 0.9f, 0); gSink = svf.process(n.next()); }));
        report("Svf::process sozinho", timeModule([&](int) { gSink = svf.process(n.next()); }));
        deckbridge::Biquad bq;
        report("Biquad::setAllpass (cos+sin) + process", timeModule([&](int i) { bq.setAllpass(500.f + (i & 255), kRate, 0.65f); gSink = bq.process(n.next()); }));
        report("noise (referência do gerador)", timeModule([&](int) { gSink = n.next() + n.next(); }));
    }
}

// --------------------------------------------------------------------------------------------------------------------
// Denormais: cauda de reverb/eco decaindo em silêncio. Mede o custo por quadro ao longo do tempo.

void denormal() {
    std::printf("== Cauda em silêncio (denormais). Custo por janela de 2 s depois de cortar a entrada ==\n");
    auto tail = [](const char *name, auto &&proc) {
        Noise n;
        for (int i = 0; i < kRate * 2; ++i) proc(n.next(), n.next());
        std::printf("  %s:", name);
        for (int w = 0; w < 30; ++w) {
            const auto t0 = Clock::now();
            for (int i = 0; i < kRate * 2; ++i) proc(0.f, 0.f);
            const auto t1 = Clock::now();
            if (w % 3 == 0) std::printf(" t=%ds %.0f", 2 * w, std::chrono::duration<double, std::nano>(t1 - t0).count() / (kRate * 2));
        }
        std::printf(" ns/quadro\n");
    };
    {
        deckbridge::Fdn f;
        f.prepare(kRate);
        f.setRt60(12.f);
        tail("Fdn RT60 12 s", [&](float a, float b) { float l, r; f.process(a, b, 0.9f, l, r); gSink = l + r; });
    }
    {
        deckbridge::SendReturn s;
        s.prepare(kRate);
        s.configure(1, 0.9f, 0.5f, 0.5f);
        tail("SendReturn LONG DELAY fb 0.77", [&](float a, float b) { float l, r; s.process(a, b, l, r); gSink = l + r; });
    }
    {
        deckbridge::Isolator eq;
        eq.prepare(kRate);
        eq.set(0.3f, 1.4f, 0.8f, false);
        tail("Isolator (biquads)", [&](float a, float b) { float l = a, r = b; eq.process(l, r); gSink = l + r; });
    }
    {
        deckbridge::BeatInsert b;
        b.configure(4, true, 0.9f, 128.f, 1.f, kRate);
        tail("BeatInsert REVERB", [&](float a, float c) { float l = a, r = c; b.process(l, r); gSink = l + r; });
    }
    {
        deckbridge::ColorFilter cf;
        cf.prepare(kRate);
        cf.setKind(0);
        cf.set(1.0f, kRate);
        tail("ColorFilter SPACE", [&](float a, float c) { float l = a, r = c; cf.process(l, r); gSink = l + r; });
    }
}

void scenes() {
    std::printf("== Motor inteiro (ToneEngine::onAudioReady, bloco 256, 4 canais, 48 kHz) ==\n");
    printScene("ocioso (stream aberto, sem faixa)", runScene(sceneIdle, 20));
    printScene("2 faixas carregadas, paradas", runScene(sceneLoadedStopped, 20));
    printScene("típica (2 decks, EQ, filtro, CUE)", runScene(sceneTypical, 20));
    printScene("pior Beat FX 15 SHIMMER + bandas", runScene(sceneWorstBeat, 20));
    gBeatEffect = 7;
    printScene("pior Beat FX 7 PHASER + bandas", runScene(sceneWorstBeat, 20));
    gBeatEffect = 13;
    printScene("pior Beat FX 13 FILTER + bandas", runScene(sceneWorstBeat, 20));
    gBeatEffect = 15;
    printScene("pior RMX (cena 2 + drum build)", runScene(sceneWorstRmx, 20));
}

}  // namespace

int blocksFor(int secs) {
    const char *e = std::getenv("BLOCKS");
    return e ? std::atoi(e) : kRate * secs / kBlock;
}

int main(int argc, char **argv) {
    const std::string what = argc > 1 ? argv[1] : "all";
    if (const char *f = std::getenv("FTZ"); f && f[0] == '1') {
#if defined(__x86_64__) || defined(__i386__)
        _MM_SET_FLUSH_ZERO_MODE(_MM_FLUSH_ZERO_ON);
        _MM_SET_DENORMALS_ZERO_MODE(_MM_DENORMALS_ZERO_ON);
        std::printf("(FTZ/DAZ ligados)\n");
#endif
    }
    if (what == "scenes" || what == "all") scenes();
    if (what == "modules" || what == "all") modules();
    if (what == "denormal" || what == "all") denormal();
    if (what == "worst") {
        // Só a pior cena, para callgrind.
        const int secs = argc > 2 ? std::atoi(argv[2]) : 3;
        Rig rig;
        sceneWorstRmx(rig.engine);
        for (int i = 0; i < blocksFor(secs); ++i) rig.block();
    }
    if (what == "worstbeat") {
        const int secs = argc > 2 ? std::atoi(argv[2]) : 3;
        Rig rig;
        sceneWorstBeat(rig.engine);
        for (int i = 0; i < blocksFor(secs); ++i) rig.block();
    }
    if (what == "dump") {
        // Saída crua de muitas cenas, para comparar bit a bit antes/depois de uma otimização (cmp dos arquivos).
        const char *path = argc > 2 ? argv[2] : "dump.f32";
        FILE *f = std::fopen(path, "wb");
        auto runDump = [&](const Setup &setup, int seconds) {
            Rig rig;
            setup(rig.engine);
            for (int i = 0; i < kRate * seconds / kBlock; ++i) {
                rig.engine.onAudioReady(rig.stream.get(), rig.out.data(), kBlock);
                std::fwrite(rig.out.data(), sizeof(float), rig.out.size(), f);
            }
        };
        runDump(sceneIdle, 1);
        runDump(sceneTypical, 4);
        for (int k = 0; k <= 16; ++k) { gBeatEffect = k; runDump(sceneWorstBeat, 3); }
        for (int k = 0; k <= 16; ++k) {
            gBeatEffect = k;
            runDump([](ToneEngine &e) {
                sceneTypical(e);
                float c[18];
                consoleArray(c, 0.f, false, -1, 0.f, 1.f, 1.f, 1.f);
                e.setConsole(c, 18);
                e.setBeatFx(gBeatEffect, true, 90, 0, 128.f, 0.5f);
            }, 3);
        }
        for (int sc = 0; sc <= 7; ++sc) {
            static int gScene;
            gScene = sc;
            runDump([](ToneEngine &e) {
                worstCommon(e);
                const float rmx[24] = {1, static_cast<float>(gScene), 1, 1.f, 1, 2, 0.25f, 1, -1, 0.6f, 1.4f, 0.8f, 0.7f, 1.2f, 1.1f, 1, 2, 128.f, 1.f, 3, 8, 1, 1, 0};
                e.setRmx(rmx, 24);
            }, 4);
        }
        for (int ck = 0; ck <= 5; ++ck) {
            static int gKind;
            gKind = ck;
            runDump([](ToneEngine &e) {
                sceneTypical(e);
                e.setColorKind(0, gKind);
                e.setColorKind(1, gKind);
                e.setFilter(0, 0.9f);
                e.setFilter(1, 0.15f);
                float c[18];
                consoleArray(c, 0.5f, true, gKind % 4, 0.4f, 1.f, 1.f, 1.f);
                e.setConsole(c, 18);
            }, 3);
        }
        std::fclose(f);
    }
    if (what == "tail") {
        // Pior cena por 3 s, depois os decks param e a pré-escuta some: só as caudas (reverb, eco, send) decaindo.
        Rig rig;
        sceneWorstBeat(rig.engine);
        for (int i = 0; i < kRate * 3 / kBlock; ++i) rig.block();
        rig.engine.transport(0, false);
        rig.engine.transport(1, false);
        rig.engine.previewStop();
        for (int w = 0; w < 12; ++w) {
            std::vector<double> us;
            for (int i = 0; i < kRate * 5 / kBlock; ++i) us.push_back(rig.block());
            const BlockStats st = summarize(us);
            std::printf("  cauda t=%2d-%2d s: %7.1f ns/quadro (p50 bloco %.1f us)\n", w * 5, w * 5 + 5, st.nsPerFrame(), st.p50);
        }
    }
    if (what == "mem") {
        auto rss = [](const char *label) {
            FILE *f = std::fopen("/proc/self/status", "r");
            char line[256];
            long rssKb = 0, hwmKb = 0;
            while (f && std::fgets(line, sizeof line, f)) {
                if (!std::strncmp(line, "VmRSS:", 6)) rssKb = std::atol(line + 6);
                if (!std::strncmp(line, "VmHWM:", 6)) hwmKb = std::atol(line + 6);
            }
            if (f) std::fclose(f);
            std::printf("  %-58s RSS %8.1f MB  pico %8.1f MB\n", label, rssKb / 1024.0, hwmKb / 1024.0);
        };
        rss("processo vazio");
        auto *rig = new Rig();
        rss("ToneEngine aberto (sem faixa, sem efeito preparado)");
        for (int i = 0; i < 10; ++i) rig->block();
        rss("depois de 10 callbacks (preparos preguiçosos)");
        {
            // Duas faixas de 6 min a 44,1 kHz, entregues como o JNI entrega: L e R separados (nativeLoadDeckStereo).
            const int frames = 44100 * 360;
            std::vector<float> l(frames), r(frames);
            for (int i = 0; i < frames; ++i) { l[i] = 0.1f * std::sin(i * 0.01f); r[i] = l[i]; }
            rss("+ L/R de 6 min em memória de teste (lado 'Java')");
            std::vector<float> a(l), b(r);
            rig->engine.installDeck(0, std::move(a), std::move(b), 44100, 128.f, 0.f, nullptr, 0, -1.f, -1.f);
            std::vector<float> c(l), d(r);
            rig->engine.installDeck(1, std::move(c), std::move(d), 44100, 126.f, 0.f, nullptr, 0, -1.f, -1.f);
            rss("+ 2 decks carregados (vetores nativos)");
        }
        rss("lado 'Java' liberado: só o motor com 2 faixas");
        {
            ToneEngine &e = rig->engine;
            e.setMix(1.f, 1.f, 1.03f, 0.98f, 0.5f, true, true);
            e.setKeyLock(0, true);
            e.setKeyLock(1, true);
            e.setColorKind(0, deckbridge::kColorSpace);
            e.setColorKind(1, deckbridge::kColorDubEcho);
            e.setFilter(0, 0.95f);
            e.setFilter(1, 0.1f);
            previewOn(e);
            e.transport(0, true);
            e.transport(1, true);
            float c[18];
            consoleArray(c, 0.7f, true, 3, 0.5f, 1.f, 0.f, 1.f);
            e.setConsole(c, 18);
            e.setBeatFx(15, true, 100, 2, 128.f, 1.f);
            for (int i = 0; i < 400; ++i) rig->block();
            rss("+ Beat FX, Color FX, SEND, pré-escuta de 30 s tocando");
            const float rmx[24] = {1, 2, 1, 1.f, 8, 2, 0.25f, 1, 1, 0.6f, 1.4f, 0.8f, 0.7f, 1.2f, 1.1f, 1, 2, 128.f, 1.f, 3, 8, 1, 1, 0};
            e.setRmx(rmx, 24);
            for (int i = 0; i < 400; ++i) rig->block();
            rss("+ RMX (cena, X-PAD, release) preparados");
        }
    }
    if (what == "loaddrum") {
        // ToneEngine::loadDrum decodifica o FLAC com o mutex do callback seguro.
        Rig rig;
        const char *names[] = {"kick", "clap", "snare", "hat-closed", "hat-open"};
        for (int v = 0; v < 5; ++v) {
            std::string path = std::string("android/app/src/main/assets/drums/") + names[v] + ".flac";
            FILE *f = std::fopen(path.c_str(), "rb");
            if (!f) continue;
            std::vector<uint8_t> bytes;
            uint8_t buf[65536];
            size_t n;
            while ((n = std::fread(buf, 1, sizeof buf, f)) > 0) bytes.insert(bytes.end(), buf, buf + n);
            std::fclose(f);
            const auto t0 = Clock::now();
            rig.engine.loadDrum(v, bytes.data(), static_cast<int>(bytes.size()));
            const auto t1 = Clock::now();
            std::printf("  loadDrum %-10s %6zu bytes FLAC: %7.1f us com o lock do áudio (%zu amostras)\n", names[v], bytes.size(),
                        std::chrono::duration<double, std::micro>(t1 - t0).count(), deckbridge::drumBank().voice[v].size());
        }
    }
    if (what == "drummodel") {
        const auto t0 = Clock::now();
        deckbridge::drumBank().ensureModel(48000);
        const auto t1 = Clock::now();
        std::printf("  drumBank().ensureModel(48000), 1a vez (no callback se nenhum FLAC de bateria veio): %.1f us\n",
                    std::chrono::duration<double, std::micro>(t1 - t0).count());
    }
    if (what == "alloc") {
        // Alocações e o pior bloco ao ENTRAR em cada estado (primeiro uso), sem aquecimento.
        auto probe = [](const char *name, const Setup &setup, int seconds) {
            Rig rig;
            setup(rig.engine);
            gCallbackNews = 0;
            gCallbackBytes = 0;
            double worst = 0;
            int worstAt = -1;
            for (int i = 0; i < kRate * seconds / kBlock; ++i) {
                const double us = rig.block();
                if (us > worst) { worst = us; worstAt = i; }
            }
            std::printf("  %-46s alocações no callback: %4ld (%8ld bytes)  pior bloco %7.1f us (bloco %d)\n", name, gCallbackNews.load(),
                        gCallbackBytes.load(), worst, worstAt);
        };
        probe("ocioso, sem setBeatFx antes do 1o callback", sceneIdle, 2);
        probe("típica", sceneTypical, 4);
        probe("RMX cena 2 + drum build (1o uso)", sceneWorstRmx, 6);
        probe("Beat FX SHIMMER", sceneWorstBeat, 3);
        // Tempo de setters que seguram o mesmo mutex do callback.
        Rig rig;
        sceneTypical(rig.engine);
        for (int i = 0; i < 50; ++i) rig.block();
        auto timed = [&](const char *name, auto &&f) {
            const auto t0 = Clock::now();
            f();
            const auto t1 = Clock::now();
            std::printf("  setter %-44s %8.1f us (com o lock do áudio)\n", name, std::chrono::duration<double, std::micro>(t1 - t0).count());
        };
        timed("setBeatFx 1a vez (prepare: 2x96000 + FDN)", [&] { rig.engine.setBeatFx(4, true, 90, 0, 128.f, 1.f); });
        timed("setBeatFx troca de efeito (clear)", [&] { rig.engine.setBeatFx(1, true, 90, 0, 128.f, 1.f); });
        float c[18];
        consoleArray(c, 0.f, false, 1, 0.5f, 1, 1, 1);
        timed("setConsole troca de SEND (clear linhas+tanque)", [&] { rig.engine.setConsole(c, 18); });
        const float rmxRel[24] = {1, 0, 0, 0.f, 1, 0, 0.25f, 0, 1, 1, 1, 1, 1, 1, 1, 0, 2, 128.f, 0, 0, 8, 1, 0, 0};
        timed("setRmx com release (trigger: 2x96000 assign+clear)", [&] { rig.engine.setRmx(rmxRel, 24); });
        timed("drumBank().ensureModel (gerado no 1o golpe)", [&] { deckbridge::drumBank().ensureModel(48000); });
        {
            deckbridge::SceneFx sc;
            sc.sampleRate = kRate;
            const auto t0 = Clock::now();
            sc.prepare();
            const auto t1 = Clock::now();
            std::printf("  SceneFx::prepare (feito no callback no 1o uso) %8.1f us\n", std::chrono::duration<double, std::micro>(t1 - t0).count());
        }
        {
            deckbridge::BeatInsert bi;
            const auto t0 = Clock::now();
            bi.prepare();
            const auto t1 = Clock::now();
            std::printf("  BeatInsert::prepare (no callback se setBeatFx não veio antes) %8.1f us\n", std::chrono::duration<double, std::micro>(t1 - t0).count());
        }
        std::printf("  sizeof(ToneEngine) = %zu bytes; sizeof(MixerFx) = %zu; sizeof(Deck) = %zu; sizeof(Wsola) = %zu\n", sizeof(ToneEngine),
                    sizeof(deckbridge::MixerFx), sizeof(Deck), sizeof(deckbridge::Wsola));
    }
    if (what == "idle") {
        const int secs = argc > 2 ? std::atoi(argv[2]) : 3;
        Rig rig;
        for (int i = 0; i < blocksFor(secs); ++i) rig.block();
    }
    if (what == "hop") {
        // Custo concentrado do WSOLA: o grão novo (a cada 1024 quadros) é calculado numa amostra só.
        Deck d;
        auto &t = trackA();
        const int frames = static_cast<int>(t.size() / 2);
        d.left.resize(frames);
        d.right.resize(frames);
        for (int i = 0; i < frames; ++i) { d.left[i] = t[i * 2]; d.right[i] = t[i * 2 + 1]; }
        d.sampleRate = 44100;
        d.playing = true;
        d.pitch = 1.03;
        d.keyLock = true;
        float l, r;
        std::vector<double> per;
        for (int i = 0; i < kRate * 10; ++i) {
            const auto t0 = Clock::now();
            d.read(kRate, l, r);
            const auto t1 = Clock::now();
            gSink = l + r;
            per.push_back(std::chrono::duration<double, std::micro>(t1 - t0).count());
        }
        std::vector<double> sorted = per;
        std::sort(sorted.begin(), sorted.end());
        double hopSum = 0; int hops = 0;
        for (double v : per) if (v > 20.0) { hopSum += v; ++hops; }
        std::printf("WSOLA: quadro p50 %.3f us, p99.9 %.1f us, max %.1f us; %d quadros >20 us (grão), média deles %.1f us\n",
                    sorted[sorted.size() / 2], sorted[sorted.size() * 999 / 1000], sorted.back(), hops, hops ? hopSum / hops : 0.0);
    }
    if (what == "typical") {
        const int secs = argc > 2 ? std::atoi(argv[2]) : 3;
        Rig rig;
        sceneTypical(rig.engine);
        for (int i = 0; i < blocksFor(secs); ++i) rig.block();
    }
    return 0;
}
