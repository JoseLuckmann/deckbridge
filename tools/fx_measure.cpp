// Medidas do DSP 0.14.0. Não usa áudio da Pioneer.
#include "dsp.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

constexpr int kRate = 48000;

float rmsRange(const std::vector<float> &data, int start, int count) {
    double sum = 0.0;
    int used = 0;
    const int end = std::min(static_cast<int>(data.size()), start + count);
    for (int index = std::max(0, start); index < end; ++index) {
        sum += static_cast<double>(data[index]) * data[index];
        used += 1;
    }
    return used > 0 ? static_cast<float>(std::sqrt(sum / used)) : 0.f;
}

float maxStep(const std::vector<float> &data) {
    float peak = 0.f;
    for (size_t index = 1; index < data.size(); ++index) {
        peak = std::max(peak, std::fabs(data[index] - data[index - 1]));
    }
    return peak;
}

std::vector<float> tone(float freq, int frames, float amp) {
    std::vector<float> out(frames);
    for (int index = 0; index < frames; ++index) {
        out[index] = amp * std::sin(static_cast<float>(deckbridge::kTwoPi) * freq * index / kRate);
    }
    return out;
}

void svgFilters(const std::string &path, const std::vector<float> &knobs, const std::vector<float> &freqs,
                const std::vector<std::vector<float>> &db) {
    std::ofstream out(path);
    out << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"960\" height=\"540\" viewBox=\"0 0 960 540\">\n";
    out << "<rect width=\"960\" height=\"540\" fill=\"#121418\"/>\n";
    out << "<text x=\"24\" y=\"32\" fill=\"#f4f1ea\" font-family=\"sans-serif\" font-size=\"18\">Filtro TPT, resposta por posicao do knob</text>\n";
    const float left = 64.f, right = 920.f, top = 56.f, bottom = 470.f;
    const float minDb = -36.f, maxDb = 6.f;
    auto xOf = [&](float freq) {
        const float log0 = std::log10(40.f);
        const float log1 = std::log10(12000.f);
        return left + (std::log10(freq) - log0) / (log1 - log0) * (right - left);
    };
    auto yOf = [&](float value) {
        const float clamped = std::min(maxDb, std::max(minDb, value));
        return bottom - (clamped - minDb) / (maxDb - minDb) * (bottom - top);
    };
    for (int db = 0; db >= -30; db -= 6) {
        const float y = yOf(static_cast<float>(db));
        out << "<line x1=\"" << left << "\" y1=\"" << y << "\" x2=\"" << right << "\" y2=\"" << y
            << "\" stroke=\"#2a3140\"/>\n";
        out << "<text x=\"8\" y=\"" << (y + 4) << "\" fill=\"#8b93a7\" font-size=\"11\">" << db << "</text>\n";
    }
    const char *colors[] = {"#7eb6ff", "#f0c36a", "#e07a5f", "#8fbf8a", "#d080d0", "#f2efe6", "#6ec8c8"};
    for (size_t series = 0; series < knobs.size(); ++series) {
        out << "<polyline fill=\"none\" stroke=\"" << colors[series % 7] << "\" stroke-width=\"2\" points=\"";
        for (size_t index = 0; index < freqs.size(); ++index) {
            out << xOf(freqs[index]) << "," << yOf(db[series][index]) << " ";
        }
        out << "\"/>\n";
        const float labelY = 500.f - static_cast<float>(series) * 0.f;
        (void)labelY;
    }
    float labelX = 64.f;
    for (size_t series = 0; series < knobs.size(); ++series) {
        out << "<text x=\"" << labelX << "\" y=\"512\" fill=\"" << colors[series % 7]
            << "\" font-family=\"sans-serif\" font-size=\"13\">knob " << knobs[series] << "</text>\n";
        labelX += 120.f;
    }
    out << "</svg>\n";
}

void svgDecay(const std::string &path, const std::string &title, const std::vector<float> &seconds,
              const std::vector<float> &db) {
    std::ofstream out(path);
    out << "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"960\" height=\"420\" viewBox=\"0 0 960 420\">\n";
    out << "<rect width=\"960\" height=\"420\" fill=\"#121418\"/>\n";
    out << "<text x=\"24\" y=\"32\" fill=\"#f4f1ea\" font-family=\"sans-serif\" font-size=\"18\">" << title << "</text>\n";
    const float left = 64.f, right = 920.f, top = 56.f, bottom = 370.f;
    float minDb = 0.f, maxDb = -80.f;
    for (float value : db) {
        if (!std::isfinite(value)) continue;
        minDb = std::min(minDb, value);
        maxDb = std::max(maxDb, value);
    }
    if (maxDb - minDb < 6.f) {
        maxDb += 3.f;
        minDb -= 3.f;
    }
    auto xOf = [&](float sec) {
        const float last = seconds.empty() ? 1.f : seconds.back();
        return left + (sec / std::max(0.001f, last)) * (right - left);
    };
    auto yOf = [&](float value) { return bottom - (value - minDb) / (maxDb - minDb) * (bottom - top); };
    out << "<polyline fill=\"none\" stroke=\"#f0c36a\" stroke-width=\"2\" points=\"";
    for (size_t index = 0; index < seconds.size(); ++index) out << xOf(seconds[index]) << "," << yOf(db[index]) << " ";
    out << "\"/>\n</svg>\n";
}

}  // namespace

int main(int argc, char **argv) {
    const std::string dir = argc > 1 ? argv[1] : "/tmp/fx-0140";
    int fails = 0;
    auto check = [&](bool ok, const std::string &name) {
        std::cout << (ok ? "PASS " : "FAIL ") << name << "\n";
        if (!ok) fails += 1;
    };

    const int sweepFrames = kRate;
    std::vector<float> smooth;
    std::vector<float> harsh;
    deckbridge::ColorFilter color;
    color.set(0.42f, kRate);
    for (int index = 0; index < 2000; ++index) {
        float l = 0.4f;
        float r = l;
        color.process(l, r);
    }
    deckbridge::Biquad raw;
    raw.setLowpass(8000.f, static_cast<float>(kRate), 0.707f);
    for (int index = 0; index < 1000; ++index) raw.process(0.4f);
    for (int index = 0; index < sweepFrames; ++index) {
        const float knob = 0.42f - 0.30f * (static_cast<float>(index) / static_cast<float>(sweepFrames));
        color.set(knob, kRate);
        float l = 0.4f;
        float r = l;
        color.process(l, r);
        smooth.push_back(l);
        if (index == sweepFrames / 2) raw.setLowpass(180.f, static_cast<float>(kRate), 1.6f);
        harsh.push_back(raw.process(0.4f));
    }
    const float smoothStep = maxStep(smooth);
    const float harshStep = maxStep(harsh);
    std::cout << "CLICK_SMOOTH " << smoothStep << "\n";
    std::cout << "CLICK_HARSH " << harshStep << "\n";
    check(smoothStep < harshStep && smoothStep < 0.2f, "rampa suave tem menos descontinuidade que o salto");
    check(std::isfinite(smoothStep), "delta finito");

    const std::vector<float> knobs = {0.f, 0.18f, 0.35f, 0.5f, 0.66f, 0.84f, 1.f};
    const std::vector<float> freqs = {40.f, 80.f, 125.f, 250.f, 500.f, 1000.f, 2000.f, 4000.f, 8000.f, 12000.f};
    std::vector<std::vector<float>> db(knobs.size(), std::vector<float>(freqs.size(), 0.f));
    bool finiteResponse = true;
    for (size_t knobIndex = 0; knobIndex < knobs.size(); ++knobIndex) {
        for (size_t freqIndex = 0; freqIndex < freqs.size(); ++freqIndex) {
            deckbridge::ColorFilter filter;
            filter.set(knobs[knobIndex], kRate);
            const int frames = kRate / 5;
            const auto input = tone(freqs[freqIndex], frames, 0.2f);
            std::vector<float> out(frames);
            for (int index = 0; index < frames; ++index) {
                float l = input[index];
                float r = l;
                filter.process(l, r);
                out[index] = l;
                if (!std::isfinite(l)) finiteResponse = false;
            }
            const float inR = rmsRange(input, frames / 2, frames / 4);
            const float outR = rmsRange(out, frames / 2, frames / 4);
            const float ratio = outR / std::max(1e-8f, inR);
            db[knobIndex][freqIndex] = 20.f * std::log10(std::max(ratio, 1e-6f));
        }
    }
    check(finiteResponse, "resposta do filtro sem NaN");
    check(db[0][8] < db[0][1] - 6.f, "knob fechado a esquerda tira agudo");
    check(db[6][1] < db[6][8] - 6.f, "knob fechado a direita tira grave");
    check(std::fabs(db[3][4]) < 1.5f, "centro do knob passa o medio");
    svgFilters(dir + "/filtro-resposta.svg", knobs, freqs, db);

    deckbridge::BeatInsert reverb;
    reverb.configure(4, true, 0.72f, 120.f, 1.f, kRate);
    const int tailFrames = kRate * 6;
    std::vector<float> verb(tailFrames, 0.f);
    for (int index = 0; index < tailFrames; ++index) {
        float l = index == 0 ? 0.8f : 0.f;
        float r = l;
        reverb.process(l, r);
        verb[index] = l;
    }
    const int hop = kRate / 50;
    std::vector<float> verbSec;
    std::vector<float> verbDb;
    float peakDb = -120.f;
    const int skip = kRate / 20;
    for (int start = skip; start + hop < tailFrames; start += hop) {
        const float power = rmsRange(verb, start, hop);
        const float value = 20.f * std::log10(std::max(power, 1e-8f));
        verbSec.push_back(start / static_cast<float>(kRate));
        verbDb.push_back(value);
        peakDb = std::max(peakDb, value);
    }
    float rt60 = -1.f;
    for (size_t index = 0; index < verbDb.size(); ++index) {
        if (verbDb[index] < peakDb - 60.f) {
            rt60 = verbSec[index];
            break;
        }
    }
    std::cout << "RT60 " << rt60 << "\n";
    check(rt60 > 0.35f && rt60 < 4.5f, "RT60 da reverb fica numa faixa musical");
    svgDecay(dir + "/reverb-rt60.svg", "Reverb, envelope em dB", verbSec, verbDb);

    deckbridge::BeatInsert echo;
    echo.configure(1, true, 0.7f, 120.f, 1.f, kRate);
    const int echoFrames = kRate * 4;
    std::vector<float> echoes(echoFrames, 0.f);
    for (int index = 0; index < echoFrames; ++index) {
        float l = index == 0 ? 0.7f : 0.f;
        float r = l;
        echo.process(l, r);
        echoes[index] = l;
    }
    std::vector<float> echoSec;
    std::vector<float> echoDb;
    float prev = 1.f;
    bool falling = true;
    for (int repeat = 1; repeat <= 6; ++repeat) {
        const int at = repeat * 24000;
        const float peak = rmsRange(echoes, at - 40, 120);
        echoSec.push_back(repeat * 0.5f);
        echoDb.push_back(20.f * std::log10(std::max(peak, 1e-8f)));
        if (peak > prev * 1.05f) falling = false;
        prev = peak;
        std::cout << "ECHO_REPEAT " << repeat << " " << peak << "\n";
    }
    check(falling, "repeticoes do eco caem");
    svgDecay(dir + "/eco-queda.svg", "Eco, RMS de cada repeticao", echoSec, echoDb);

    auto hf = [](const std::vector<float> &data, int start) {
        double sum = 0.0;
        for (int index = start + 1; index < start + 400 && index < static_cast<int>(data.size()); ++index) {
            const double d = data[index] - data[index - 1];
            sum += d * d;
        }
        return std::sqrt(sum);
    };
    const double firstHf = hf(echoes, 24000);
    const double laterHf = hf(echoes, 24000 * 4);
    std::cout << "ECHO_HF " << firstHf << " " << laterHf << "\n";
    check(laterHf < firstHf, "repeticoes tardias ficam mais escuras");

    int nans = 0;
    uint32_t state = 0x9e3779b9u;
    for (int effect : {0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13}) {
        deckbridge::BeatInsert fx;
        fx.configure(effect, true, 0.6f, 120.f, 0.5f, kRate);
        for (int index = 0; index < kRate / 2; ++index) {
            state = state * 1664525u + 1013904223u;
            float l = ((state >> 8) & 255) / 255.f * 0.4f - 0.2f;
            float r = l * 0.8f;
            fx.process(l, r);
            if (!std::isfinite(l) || !std::isfinite(r) || std::fabs(l) > 1.f || std::fabs(r) > 1.f) nans += 1;
        }
        fx.configure(effect, false, 0.6f, 120.f, 0.5f, kRate);
        for (int index = 0; index < kRate / 4; ++index) {
            float l = 0.f;
            float r = 0.f;
            fx.process(l, r);
            if (!std::isfinite(l) || !std::isfinite(r)) nans += 1;
        }
    }
    std::cout << "NAN " << nans << "\n";
    check(nans == 0, "sem NaN e sem estouro de escala");

    deckbridge::MixerFx mix;
    mix.setSampleRate(kRate);
    mix.setBeat(1, true, 0.55f, 0, 124.f, 0.5f);
    const int block = 256;
    const int rounds = 300;
    const auto start = std::chrono::steady_clock::now();
    for (int round = 0; round < rounds; ++round) {
        for (int index = 0; index < block; ++index) {
            float l = 0.2f;
            float r = 0.15f;
            mix.processDeck(0, l, r);
        }
    }
    const auto elapsed = std::chrono::steady_clock::now() - start;
    const double us = std::chrono::duration<double, std::micro>(elapsed).count() / rounds;
    const double budget = 1e6 * block / static_cast<double>(kRate);
    std::cout << "CPU_US " << us << " BUDGET_US " << budget << "\n";
    check(us < budget * 0.5, "bloco de 256 amostras cabe na metade do tempo real");

    deckbridge::XPad roll;
    std::vector<int> hits(8, 0);
    for (int bar = 0; bar < 8; ++bar) {
        const float build = (bar + 0.5f) / 8.f;
        roll.set(2, 1.f, true, 120.f, kRate, build);
        for (int index = 0; index < 24000 * 4; ++index) {
            const int before = roll.env;
            float l = 0.f;
            float r = 0.f;
            roll.process(l, r);
            if (roll.env > before) hits[bar] += 1;
        }
    }
    bool accel = true;
    for (int bar = 1; bar < 8; ++bar) {
        std::cout << "BUILD_HITS " << bar << " " << hits[bar] << "\n";
        if (hits[bar] + 1 < hits[bar - 1]) accel = false;
    }
    check(hits[7] > hits[0] * 3, "o roll de bateria acelera");
    check(accel, "a densidade nao volta atras no gesto");

    std::cout << "FAILS " << fails << "\n";
    return fails == 0 ? 0 : 1;
}
