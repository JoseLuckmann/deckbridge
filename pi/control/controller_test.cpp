// A porta C++ da lógica de controle tem de tirar dos mesmos bytes MIDI as mesmas ações que o app Kotlin
// (pi/control/testdata/golden.txt, gerado por ControllerGoldenTest). Também mede o custo por mensagem.
//   g++ -std=c++17 -O2 -o controller_test pi/control/controller_test.cpp && ./controller_test
#include "controller.h"
#include "deck_control.h"

#include <chrono>
#include <cstdio>
#include <cstdlib>

using namespace deckbridge::control;

namespace {

std::string format(const MidiMessage &m, const std::optional<Action> &a) {
    char head[64];
    std::snprintf(head, sizeof head, "M %d %d %d | ", m.status, m.data1, m.data2);
    if (!a) return std::string(head) + "-";
    return std::string(head) + kindName(a->kind) + " " + std::to_string(a->deck) + " " + std::to_string(a->hotIndex);
}

}  // namespace

int main(int argc, char **argv) {
    const std::string root = argc > 1 ? argv[1] : ".";
    const MidiMap map = MidiMap::fromFile(root + "/android/app/src/main/resources/ddj400-map.json");
    std::ifstream golden(root + "/pi/control/testdata/golden.txt");
    if (map.all().size() < 150 || !golden) {
        std::printf("FAIL mapa (%zu controles) ou golden.txt ausente\n", map.all().size());
        return 1;
    }
    MidiAssembler assembler;
    TempoAssembler tempo;
    std::vector<MidiMessage> pending;
    size_t at = 0;
    long checked = 0, withAction = 0, failures = 0;
    std::string line;
    while (std::getline(golden, line)) {
        if (line.empty() || line[0] == '#') continue;
        if (line[0] == 'B') {
            std::vector<uint8_t> bytes;
            std::istringstream in(line.substr(1));
            std::string hex;
            while (in >> hex) bytes.push_back(static_cast<uint8_t>(std::strtol(hex.c_str(), nullptr, 16)));
            if (at < pending.size() && failures++ < 10) std::printf("FAIL o C++ montou %zu mensagem(ns) a mais\n", pending.size() - at);
            pending.clear();
            at = 0;
            assembler.push(bytes.data(), bytes.size(), pending);
            continue;
        }
        if (line[0] != 'M') continue;
        if (at >= pending.size()) {
            if (failures++ < 10) std::printf("FAIL o Kotlin montou uma mensagem a mais: %s\n", line.c_str());
            continue;
        }
        const MidiMessage m = pending[at++];
        const auto action = interpretPerformance(map.lookup(m.status, m.data1), m.data2, tempo);
        // Compara tipo, deck e índice como texto; o slider como número (o Kotlin escreve 1.0E-5, o C++ 1e-05).
        const auto bar = line.find(" | ");
        std::string want = line;
        double wantSlider = 0.0;
        bool wantHasSlider = false;
        if (line.compare(bar + 3, 1, "-") != 0) {
            std::istringstream in(line.substr(bar + 3));
            std::string kind, deck, hot, slider;
            in >> kind >> deck >> hot >> slider;
            want = line.substr(0, bar + 3) + kind + " " + deck + " " + hot;
            wantHasSlider = slider != "null";
            if (wantHasSlider) wantSlider = std::strtod(slider.c_str(), nullptr);
        }
        const std::string got = format(m, action);
        bool ok = got == want;
        if (ok && action) {
            ok = action->slider.has_value() == wantHasSlider && (!wantHasSlider || std::fabs(*action->slider - wantSlider) <= 1e-12);
            ++withAction;
        }
        if (!ok && failures++ < 10) {
            std::printf("FAIL esperado [%s] obtido [%s]%s\n", line.c_str(), got.c_str(),
                        action && action->slider ? (" slider " + std::to_string(*action->slider)).c_str() : "");
        }
        ++checked;
    }
    std::printf("%s %ld mensagens, %ld com ação, iguais ao Kotlin\n", failures == 0 ? "PASS" : "FAIL", checked, withAction);

    // O redutor aplica ao motor com a regra do DeckPage. Motor falso: anota a última chamada de cada tipo.
    struct FakeEngine {
        bool playing[2]{};
        int cues = 0, hot = -1;
        float vol[2]{}, pitch[2]{1.f, 1.f}, cross = 0.5f, eq[3]{}, filter = -1.f, headMix = -1.f;
        bool pfl[2]{};
        double scratched = 0.0, nudged = 0.0;
        bool touching = false;
        long calls = 0;
        bool finite = true;
        void transport(int d, bool p) { playing[d] = p; ++calls; }
        void cue(int) { ++cues; ++calls; }
        void hotCue(int, int i) { hot = i; ++calls; }
        void setEq(int, float l, float m, float h) { eq[0] = l; eq[1] = m; eq[2] = h; finite = finite && std::isfinite(l + m + h); ++calls; }
        void setTrimKnob(int, float k) { finite = finite && std::isfinite(k); ++calls; }
        void seekSeconds(int, double) { ++calls; }
        void setHead(float m, float) { headMix = m; ++calls; }
        void scratchTouch(int, bool on) { touching = on; ++calls; }
        void scratchMove(int, double s) { scratched += s; finite = finite && std::isfinite(s); ++calls; }
        void nudge(int, double s) { nudged += s; finite = finite && std::isfinite(s); ++calls; }
        void setFilter(int, float f) { filter = f; ++calls; }
        int fxEffect = -1, rmxCalls = 0;
        bool fxOn = false;
        float rmxScene = -1.f, rmxHolding = 0.f, loopStart = -2.f, loopEnd = -2.f;
        double positionSeconds(int) const { return 10.0; }
        void setBeatFx(int effect, bool on, int, int, float bpm, float beats) {
            fxEffect = effect;
            fxOn = on;
            finite = finite && std::isfinite(bpm + beats);
            ++calls;
        }
        void setRmx(const float *raw, int count) {
            for (int i = 0; i < count; ++i) finite = finite && std::isfinite(raw[i]);
            rmxScene = raw[1];
            rmxHolding = raw[2];
            ++rmxCalls;
            ++calls;
        }
        void setLoop(int, float a, float b) {
            loopStart = a;
            loopEnd = b;
            ++calls;
        }
        void setMix(float a, float b, float pa, float pb, float c, bool fa, bool fb) {
            vol[0] = a; vol[1] = b; pitch[0] = pa; pitch[1] = pb; cross = c; pfl[0] = fa; pfl[1] = fb;
            finite = finite && std::isfinite(a + b + pa + pb + c);
            ++calls;
        }
    };
    auto expect = [&](bool ok, const char *name) {
        std::printf("%s %s\n", ok ? "PASS" : "FAIL", name);
        if (!ok) ++failures;
    };
    {
        FakeEngine e;
        DeckControl<FakeEngine> c(e);
        TempoAssembler t;
        auto send = [&](int st, int d1, int d2) {
            if (auto a = interpretPerformance(map.lookup(st, d1), d2, t)) c.apply(*a);
        };
        send(0x90, 0x0B, 127);  // PLAY deck 1 (o mapa: status 144, nota 11)
        expect(e.playing[0] && c.playing(0), "PLAY liga o deck 1 no motor");
        send(0x90, 0x0B, 0);
        expect(e.playing[0], "soltar PLAY não muda nada");
        send(0x90, 0x0B, 127);
        expect(!e.playing[0], "PLAY de novo pausa");
        // Toque no prato (modo vinil) e giro: scratch; sem toque, o giro é nudge.
        for (const auto &hit : map.all()) {
            if (hit.key == "PioneerDDJ400.jogTouch" && hit.group == "[Channel1]") send(hit.status, hit.midino, 127);
        }
        expect(e.touching, "toque no topo do prato liga o scratch");
        for (const auto &hit : map.all()) {
            if (hit.key == "PioneerDDJ400.jogTurn" && hit.group == "[Channel1]") send(hit.status, hit.midino, 64 + 10);
        }
        expect(e.scratched > 0.0 && e.nudged == 0.0, "giro com o prato tocado vira scratch");
        // Beat FX: SELECT avança o efeito, ON/OFF liga (regra do reduceBeatFx).
        auto pressKey = [&](const char *key, int value = 127) {
            for (const auto &hit : map.all()) {
                if (hit.key == key) {
                    send(hit.status, hit.midino, value);
                    return;
                }
            }
        };
        pressKey("PioneerDDJ400.beatFxSelectPressed");
        pressKey("PioneerDDJ400.beatFxOnOffPressed");
        expect(e.fxEffect == 1 && e.fxOn && c.beatFx().on, "Beat FX: SELECT vai para Echo e ON/OFF liga no motor");
        // RMX: com o painel aberto, o pad 1 do SAMPLER segura a cena 1 e a rampa sobe; solto, a cena cai.
        c.setRmxPanel(true);
        expect(!e.fxOn, "painel RMX aberto: Beat FX desligado no motor, como no app");
        send(0x97, 0x30, 127);
        for (int i = 0; i < 25; ++i) c.tick(0.04);
        expect(e.rmxScene == 0.f && e.rmxHolding == 1.f && c.rmx().intensity > 0.05f, "pad do SAMPLER segura a cena e a intensidade sobe");
        send(0x97, 0x30, 0);
        for (int i = 0; i < 40; ++i) c.tick(0.04);
        expect(e.rmxHolding == 0.f && c.rmx().intensity == 0.f, "solto, a cena cai a zero");
        c.setRmxPanel(false);
        // Beat loop de 4 batidas a 120 BPM: 2 s a partir da posição; de novo, sai do loop.
        c.setGrid(0, 0.0, 120.0);
        pressKey("beatloop_4_toggle");
        expect(std::fabs(e.loopStart - 10.f) < 1e-4f && std::fabs(e.loopEnd - 12.f) < 1e-4f, "beat loop de 4 a 120 BPM: 10 s → 12 s");
        pressKey("beatloop_4_toggle");
        expect(e.loopStart == -1.f && c.loop(0).memoryEnd > 11.f, "o mesmo pad sai do loop e guarda a memória");
        // Fuzz: todas as mensagens do golden pelo redutor, sem valor não finito.
        std::ifstream again(root + "/pi/control/testdata/golden.txt");
        std::string l;
        long applied = 0;
        while (std::getline(again, l)) {
            if (l.rfind("M ", 0) != 0) continue;
            int st = 0, d1 = 0, d2 = 0;
            if (std::sscanf(l.c_str(), "M %d %d %d", &st, &d1, &d2) == 3) {
                if (auto a = interpretPerformance(map.lookup(st, d1), d2, t)) {
                    c.apply(*a);
                    ++applied;
                }
            }
        }
        std::printf("INFO redutor: %ld ações aplicadas, %ld ainda sem suporte no Pi (última: %s), %ld chamadas ao motor\n", applied,
                    c.unsupported(), c.lastUnsupported().c_str(), e.calls);
        expect(e.finite && applied > 2000, "todas as ações do golden pelo redutor: só valores finitos no motor");
    }

    // Custo: um giro de prato manda centenas de mensagens por segundo; aqui, um milhão delas.
    const uint8_t jog[] = {0xB0, 0x22, 0x41};
    std::vector<MidiMessage> out;
    out.reserve(4);
    TempoAssembler t2;
    long acts = 0;
    const auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < 1000000; ++i) {
        out.clear();
        assembler.push(jog, 3, out);
        for (const auto &m : out) acts += interpretPerformance(map.lookup(m.status, m.data1), m.data2, t2).has_value();
    }
    const double ns = std::chrono::duration<double, std::nano>(std::chrono::steady_clock::now() - start).count() / 1e6;
    std::printf("INFO %.0f ns por mensagem de prato (montar + mapa + ação), %ld ações\n", ns, acts);
    std::printf("FAILS %ld\n", failures);
    return failures == 0 ? 0 : 1;
}
