// Mede o onset de cada hit do drum build contra a fase do deck.
// O relógio é o mesmo musicalBeat() do motor: posição da fonte, BPM da grade,
// pitch e rateMul. A latência de saída é comum ao deck e ao hit, então o erro
// relativo não soma o buffer. O bloco só muda o laço; o hit cai no sample.
#include "dsp.h"

#include <algorithm>
#include <cmath>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

int fails = 0;

void check(bool ok, const std::string &label) {
    if (ok) return;
    ++fails;
    std::cerr << "FAIL " << label << "\n";
}

struct Onset {
    int sample = 0;
    double beat = 0.0;
};

void render(double bpm, double pitch, double startBeat, int block, int bars, bool gap, std::vector<float> &out,
            std::vector<double> &beatAt) {
    const int rate = 48000;
    const double spb = deckbridge::samplesPerBeat(bpm, rate);
    double position = startBeat * spb;
    const double step = deckbridge::directedStep(pitch, 1.0, rate, rate, false);
    const int totalBeats = bars * 4 + 2;
    const int total = static_cast<int>(std::ceil(totalBeats * spb / std::max(0.05, pitch))) + block;
    out.assign(static_cast<size_t>(total), 0.f);
    beatAt.assign(static_cast<size_t>(total), 0.0);
    std::vector<float> impulse(128, 0.f);
    impulse[0] = 1.f;
    deckbridge::drumBank().set(0, impulse.data(), static_cast<int>(impulse.size()));
    deckbridge::XPad pad;
    pad.hats = false;
    pad.layerHits = false;
    pad.risers = false;  // o teste mede só o agendador dos golpes
    pad.followGrid = true;
    pad.set(2, 1.f, true, static_cast<float>(bpm), rate, 1.f, 0, bars, gap, false);
    for (int frame = 0; frame < total;) {
        const int count = std::min(block, total - frame);
        for (int index = 0; index < count; ++index) {
            const int at = frame + index;
            const double beat = deckbridge::musicalBeat(position, rate, bpm, 0.0);
            beatAt[static_cast<size_t>(at)] = beat;
            pad.setPhase(beat, true);
            float left = 0.f;
            float right = 0.f;
            pad.process(left, right);
            out[static_cast<size_t>(at)] = left;
            position += step;
        }
        frame += count;
    }
}

std::vector<int> onsetsOf(const std::vector<float> &out) {
    std::vector<int> hits;
    for (int index = 0; index < static_cast<int>(out.size()); ++index) {
        const float sample = out[static_cast<size_t>(index)];
        float before = 0.f;
        const int from = std::max(0, index - 8);
        for (int look = from; look < index; ++look) before = std::max(before, std::fabs(out[static_cast<size_t>(look)]));
        if (std::fabs(sample) > 0.12f && std::fabs(sample) > before * 4.f) hits.push_back(index);
    }
    return hits;
}

double maxErrorMs(double bpm, double pitch, int block, bool gap, int &hitCount, double &endBeat) {
    const int bars = 8;
    std::vector<float> out;
    std::vector<double> beatAt;
    render(bpm, pitch, 0.37, block, bars, gap, out, beatAt);
    const auto hits = onsetsOf(out);
    hitCount = static_cast<int>(hits.size());
    deckbridge::XPad plan;
    plan.drumGap = gap;
    plan.drumBars = bars;
    plan.begin(1.0);
    endBeat = plan.endBeat;
    double worst = 0.0;
    int matched = 0;
    double cursor = 1.0 - 1e-9;
    while (cursor < endBeat) {
        const double hit = plan.hitOnOrAfter(cursor);
        if (hit >= endBeat) break;
        int expected = -1;
        for (int index = 1; index < static_cast<int>(beatAt.size()); ++index) {
            if (beatAt[static_cast<size_t>(index - 1)] < hit && beatAt[static_cast<size_t>(index)] >= hit) {
                expected = index;
                break;
            }
        }
        if (expected < 0) return 1000.0;
        int nearest = -1;
        int best = 1000000;
        for (int onset : hits) {
            const int delta = std::abs(onset - expected);
            if (delta < best) {
                best = delta;
                nearest = onset;
            }
        }
        if (nearest < 0) return 1000.0;
        const double ms = std::abs(nearest - expected) * 1000.0 / 48000.0;
        worst = std::max(worst, ms);
        if (ms < 1.0) matched += 1;
        cursor = hit + 1e-9;
    }
    if (matched < 8) return 1000.0;
    return worst;
}

}  // namespace

int main(int argc, char **argv) {
    const char *dir = argc > 1 ? argv[1] : "/tmp/drum-grid";
    std::string mkdir = std::string("mkdir -p ") + dir;
    if (std::system(mkdir.c_str()) != 0) return 2;

    const double bpms[] = {120.0, 128.0, 140.0, 174.0};
    const double pitches[] = {1.0, 0.92, 1.08};
    const int blocks[] = {1, 256};
    double worst = 0.0;
    std::vector<std::string> rows;
    for (double bpm : bpms) {
        for (double pitch : pitches) {
            for (int block : blocks) {
                int count = 0;
                double endBeat = 0.0;
                const double error = maxErrorMs(bpm, pitch, block, true, count, endBeat);
                worst = std::max(worst, error);
                std::cout << "ERR bpm=" << bpm << " pitch=" << pitch << " block=" << block << " hits=" << count
                          << " ms=" << error << "\n";
                check(error < 1.0, "erro de onset abaixo de 1 ms");
                check(count > 16, "o roll produz hits");
                rows.push_back(std::to_string(bpm) + " " + std::to_string(pitch) + " " + std::to_string(block) + " " +
                               std::to_string(error));
            }
        }
    }

    std::vector<float> gapped;
    std::vector<double> beats;
    render(128.0, 1.0, 0.37, 128, 8, true, gapped, beats);
    const auto gapHits = onsetsOf(gapped);
    int insideGap = 0;
    for (int onset : gapHits) {
        const double beat = beats[static_cast<size_t>(onset)];
        if (beat > 32.5 && beat < 33.0) insideGap += 1;
    }
    check(insideGap == 0, "gap no ultimo meio tempo");

    std::vector<float> tight;
    render(120.0, 1.08, 0.1, 256, 4, false, tight, beats);
    const auto tightHits = onsetsOf(tight);
    int onEnd = 0;
    for (int onset : tightHits) {
        if (std::abs(beats[static_cast<size_t>(onset)] - 17.0) < 0.002) onEnd += 1;
    }
    check(onEnd == 0, "nao dispara no downbeat do drop");
    check(!tightHits.empty() && (tightHits.front() % 256) != 0, "o primeiro hit nao cola no inicio do bloco");

    deckbridge::XPad sections;
    sections.drumGap = false;
    sections.begin(0.0);
    const double firstEighth = sections.hitOnOrAfter(8.0 - 1e-9);
    const double after = sections.hitOnOrAfter(8.0 + 1e-6);
    check(std::abs(firstEighth - 8.0) < 1e-9, "a colcheia comeca no compasso");
    check(std::abs(after - 8.5) < 1e-9, "a divisao nova so vale depois da fronteira");

    std::ofstream svg(std::string(dir) + "/drum-onset.svg");
    svg << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"760\" height=\"280\" viewBox=\"0 0 760 280\">\n";
    svg << "<rect width=\"760\" height=\"280\" fill=\"#10141c\"/>\n";
    svg << "<text x=\"20\" y=\"28\" fill=\"#f4f7fb\" font-family=\"sans-serif\" font-size=\"16\">Erro de onset do drum build (ms)</text>\n";
    svg << "<text x=\"20\" y=\"48\" fill=\"#9aa6b8\" font-family=\"sans-serif\" font-size=\"12\">pior caso " << worst
        << " ms · limite 1 ms · 120, 128, 140 e 174 BPM · pitch 0%, -8% e +8% · blocos de 1 e 256</text>\n";
    const double limitY = 250.0 - (1.0 / 1.2) * 190.0;
    svg << "<line x1=\"48\" y1=\"" << limitY << "\" x2=\"740\" y2=\"" << limitY << "\" stroke=\"#e35d6a\" stroke-dasharray=\"4 4\"/>\n";
    svg << "<text x=\"620\" y=\"" << (limitY - 6) << "\" fill=\"#e35d6a\" font-family=\"sans-serif\" font-size=\"11\">1 ms</text>\n";
    int column = 0;
    for (double bpm : bpms) {
        for (double pitch : pitches) {
            double local = 0.0;
            for (int block : blocks) {
                int count = 0;
                double endBeat = 0.0;
                local = std::max(local, maxErrorMs(bpm, pitch, block, true, count, endBeat));
            }
            const double h = std::max(2.0, (local / 1.2) * 190.0);
            const double x = 56.0 + column * 28.0;
            const char *color = pitch < 0.99 ? "#7eb6ff" : (pitch > 1.01 ? "#f0c674" : "#9ddea8");
            svg << "<rect x=\"" << x << "\" y=\"" << (250.0 - h) << "\" width=\"18\" height=\"" << h << "\" fill=\"" << color
                << "\"/>\n";
            column += 1;
        }
        column += 1;
    }
    svg << "<text x=\"56\" y=\"270\" fill=\"#9aa6b8\" font-family=\"sans-serif\" font-size=\"11\">120</text>\n";
    svg << "<text x=\"250\" y=\"270\" fill=\"#9aa6b8\" font-family=\"sans-serif\" font-size=\"11\">128</text>\n";
    svg << "<text x=\"444\" y=\"270\" fill=\"#9aa6b8\" font-family=\"sans-serif\" font-size=\"11\">140</text>\n";
    svg << "<text x=\"638\" y=\"270\" fill=\"#9aa6b8\" font-family=\"sans-serif\" font-size=\"11\">174</text>\n";
    svg << "</svg>\n";
    std::cout << "WORST_MS " << worst << "\nFAILS " << fails << "\n";
    return fails == 0 ? 0 : 1;
}
