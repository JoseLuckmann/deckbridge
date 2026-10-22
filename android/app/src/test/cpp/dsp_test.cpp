#include "../../main/cpp/dsp.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

int failures = 0;

void expect(bool ok, const std::string &name) {
    if (ok) {
        std::printf("PASS %s\n", name.c_str());
        return;
    }
    std::printf("FAIL %s\n", name.c_str());
    failures += 1;
}

std::vector<float> sine(int frames, float freq, float amp, int rate, float phase = 0.f) {
    std::vector<float> out(frames);
    for (int index = 0; index < frames; ++index) {
        const float t = phase + static_cast<float>(deckbridge::kTwoPi) * freq * index / static_cast<float>(rate);
        out[index] = amp * std::sin(t);
    }
    return out;
}

std::vector<float> renderBeat(int effect, float level, const std::vector<float> &dry, float beats = 1.f) {
    deckbridge::BeatInsert fx;
    fx.configure(effect, true, level, 120.f, beats, 48000);
    std::vector<float> out(dry.size());
    for (size_t index = 0; index < dry.size(); ++index) {
        float left = dry[index];
        float right = dry[index] * 0.5f;
        fx.process(left, right);
        out[index] = left;
    }
    return out;
}

float windowRms(const std::vector<float> &data, int start, int count) {
    if (count <= 0 || start >= static_cast<int>(data.size())) return 0.f;
    const int end = std::min(static_cast<int>(data.size()), start + count);
    double sum = 0.0;
    int used = 0;
    for (int index = std::max(0, start); index < end; ++index) {
        sum += static_cast<double>(data[index]) * data[index];
        used += 1;
    }
    if (used == 0) return 0.f;
    return static_cast<float>(std::sqrt(sum / used));
}

}  // namespace

int main() {
    const double beat = deckbridge::samplesPerBeat(120.0, 48000.0);
    expect(std::abs(beat - 24000.0) < 1e-6, "beat length at 120 bpm");

    const double jumped = deckbridge::beatJumpSample(1000.0, 1.0, 120.0, 48000.0, -1.0, -1.0, 48000.0 * 32.0, true);
    expect(std::abs(jumped - 24000.0) < 1e-6, "jump +1 lands on the next grid sample");
    const double back = deckbridge::beatJumpSample(24000.0, -4.0, 120.0, 48000.0, -1.0, -1.0, 48000.0 * 32.0, true);
    expect(std::abs(back - 0.0) < 1e-6, "jump -4 from beat 1 lands on zero");
    const double paused = deckbridge::beatJumpSample(1000.0, 2.0, 120.0, 48000.0, -1.0, -1.0, 48000.0 * 32.0, true);
    expect(std::abs(paused - 48000.0) < 1e-6, "paused jump uses the same grid");
    const double wrapped = deckbridge::beatJumpSample(72000.0, 1.0, 120.0, 48000.0, 48000.0, 96000.0, 48000.0 * 32.0, true);
    expect(std::abs(wrapped - 48000.0) < 1e-6, "jump wraps inside the active loop");

    deckbridge::LoopState loop;
    const bool on = deckbridge::toggleBeatLoop(loop, 48000.0, 4.0, 120.0, 48000.0);
    expect(on && std::abs((loop.end - loop.start) - 96000.0) < 1e-6, "4-beat loop is 96000 samples");
    deckbridge::scaleLoop(loop, 0.5);
    expect(std::abs((loop.end - loop.start) - 48000.0) < 1e-6, "halve loop");
    deckbridge::scaleLoop(loop, 2.0);
    expect(std::abs((loop.end - loop.start) - 96000.0) < 1e-6, "double loop");

    std::vector<float> step(4000);
    for (int index = 0; index < 4000; ++index) step[index] = (index % 2000 < 1000) ? -0.8f : 0.8f;
    float previous = deckbridge::readLooped(step.data(), 4000, 1960.0, 0.0, 2000.0, deckbridge::kFadeSamples);
    float maxDelta = 0.f;
    for (int index = 1961; index < 2048; ++index) {
        const double pos = index < 2000 ? static_cast<double>(index) : static_cast<double>(index - 2000);
        const float sample = deckbridge::readLooped(step.data(), 4000, pos, 0.0, 2000.0, deckbridge::kFadeSamples);
        maxDelta = std::max(maxDelta, std::abs(sample - previous));
        previous = sample;
    }
    expect(maxDelta < 0.25f, "loop crossfade keeps the boundary step small");

    const auto dry = sine(36000, 440.f, 0.25f, 48000);
    const char *names[] = {"Low Cut Echo", "Echo", "Ping Pong", "Spiral", "Reverb", "Trans", "Flanger", "Phaser",
                           "Pitch", "Helix", "Mobius", "Filter"};
    const int ids[] = {0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13};
    for (int index = 0; index < 12; ++index) {
        const float level = ids[index] == 13 ? 0.05f : (ids[index] == 8 ? 0.95f : 0.8f);
        const auto wet = renderBeat(ids[index], level, dry, ids[index] == 5 ? 1.f : 0.5f);
        expect(deckbridge::finiteBuffer(wet), std::string(names[index]) + " finite and inside [-1,1]");
        expect(deckbridge::diffRms(dry, wet) > 0.003f, std::string(names[index]) + " differs from dry");
    }

    std::vector<float> impulse(30000, 0.f);
    impulse[0] = 0.6f;
    const auto echo = renderBeat(1, 0.9f, impulse, 1.f);
    int peakAt = 0;
    float peak = 0.f;
    for (int index = 2000; index < static_cast<int>(echo.size()); ++index) {
        if (std::abs(echo[index]) > peak) {
            peak = std::abs(echo[index]);
            peakAt = index;
        }
    }
    expect(std::abs(peakAt - 24000) < 800, "echo peak sits on the beat");

    deckbridge::BeatInsert trans;
    trans.configure(5, true, 0.85f, 120.f, 1.f, 48000);
    std::vector<float> gated(24000);
    for (int index = 0; index < 24000; ++index) {
        float left = 0.4f;
        float right = 0.4f;
        trans.process(left, right);
        gated[index] = left;
    }
    expect(windowRms(gated, 0, 800) > 0.2f, "trans opens on the beat");
    expect(windowRms(gated, 18000, 800) < 0.05f, "trans is shut later in the beat");

    deckbridge::BeatInsert tail;
    tail.configure(1, true, 0.8f, 120.f, 1.f, 48000);
    for (int index = 0; index < 24000; ++index) {
        float left = 0.2f;
        float right = 0.2f;
        tail.process(left, right);
    }
    tail.configure(1, false, 0.8f, 120.f, 1.f, 48000);
    for (int index = 0; index < 96000; ++index) {
        float left = 0.2f;
        float right = 0.2f;
        tail.process(left, right);
    }
    float left = 0.2f;
    float right = 0.2f;
    tail.process(left, right);
    expect(std::abs(left - 0.2f) < 1e-3f && std::abs(right - 0.2f) < 1e-3f, "echo tail returns to dry");

    std::vector<float> rollIn(24000, 0.f);
    rollIn[0] = 1.f;
    std::vector<float> rollOut(24000);
    deckbridge::renderRoll(rollIn.data(), 24000, 6000, rollOut.data());
    expect(std::abs(rollOut[0] - 1.f) < 1e-6f && std::abs(rollOut[6000] - 1.f) < 1e-6f && std::abs(rollOut[12000] - 1.f) < 1e-6f,
           "roll repeats the slice on the beat grid");
    expect(std::abs(rollOut[3000]) < 1e-6f, "roll is quiet between hits");

    deckbridge::SlipClock slip;
    slip.engage(0.0, 6000.0, true);
    for (int index = 0; index < 24000; ++index) slip.advance(1.0);
    expect(slip.audible < 6000.0 && std::abs(slip.hidden - 24000.0) < 1e-6, "slip roll hides the moving playhead");
    slip.release();
    expect(std::abs(slip.audible - 24000.0) < 1e-6, "slip release jumps to the hidden playhead");

    const char *scenes[] = {"Echo build-up", "Noise sweep up", "Spiral up", "Reverb up", "Reverb down", "Echo LPF down",
                            "Tape stop", "Dub echo"};
    for (int sceneId = 0; sceneId < 8; ++sceneId) {
        deckbridge::SceneFx scene;
        scene.scene = sceneId;
        scene.bars = 1.f;
        scene.bpm = 120.f;
        scene.sampleRate = 48000;
        scene.setCeiling(1.f);
        scene.prepare();
        scene.setHold(true);
        float previous = -1.f;
        bool mono = true;
        std::vector<float> wet;
        const int ramp = static_cast<int>(beat * 4.f);
        for (int index = 0; index < ramp; ++index) {
            float l = dry[index % dry.size()];
            float r = l;
            scene.process(l, r);
            if (!(scene.intensity + 1e-5f >= previous)) mono = false;
            previous = scene.intensity;
            if (index > ramp / 2) wet.push_back(l);
        }
        expect(mono && scene.intensity > 0.9f, std::string(scenes[sceneId]) + " intensity rises");
        std::vector<float> drySlice(wet.size());
        for (size_t index = 0; index < wet.size(); ++index) drySlice[index] = dry[(ramp / 2 + static_cast<int>(index)) % dry.size()];
        expect(deckbridge::finiteBuffer(wet) && deckbridge::diffRms(drySlice, wet) > 0.002f, std::string(scenes[sceneId]) + " differs, finite");
        scene.setHold(false);
        const int tailSamples = static_cast<int>(beat * 4.f) + 8;
        for (int index = 0; index < tailSamples; ++index) {
            float l = 0.2f;
            float r = 0.2f;
            scene.process(l, r);
        }
        float l = 0.2f;
        float r = 0.2f;
        scene.process(l, r);
        expect(std::abs(l - 0.2f) < 1e-3f && scene.intensity <= 0.001f, std::string(scenes[sceneId]) + " release returns to dry");
    }

    deckbridge::XPad drums;
    drums.set(2, 1.f, true, 120.f, 48000);
    std::vector<float> hits(48000, 0.f);
    for (int index = 0; index < 48000; ++index) {
        float l = 0.f;
        float r = 0.f;
        drums.process(l, r);
        hits[index] = l;
    }
    expect(deckbridge::finiteBuffer(hits), "drums finite");
    expect(windowRms(hits, 0, 700) > windowRms(hits, 4000, 700) * 2.f, "first drum hit is on the beat");
    expect(windowRms(hits, 24000, 700) > windowRms(hits, 16000, 700) * 2.f, "second drum hit is one beat later");

    deckbridge::XPad gate;
    gate.set(1, 1.f, true, 120.f, 48000);
    std::vector<float> chopped(24000);
    for (int index = 0; index < 24000; ++index) {
        float l = 0.3f;
        float r = 0.3f;
        gate.process(l, r);
        chopped[index] = l;
    }
    expect(windowRms(chopped, 100, 400) > 0.15f && windowRms(chopped, 14000, 400) < 0.05f, "x-pad gate opens on the beat");

    deckbridge::Isolator iso;
    iso.prepare(48000);
    iso.set(2.f, 0.f, 0.f, false);
    const auto lowSine = sine(16000, 80.f, 0.2f, 48000);
    std::vector<float> boosted(lowSine.size());
    for (size_t index = 0; index < lowSine.size(); ++index) {
        float l = lowSine[index];
        float r = lowSine[index];
        iso.process(l, r);
        boosted[index] = l;
    }
    expect(deckbridge::finiteBuffer(boosted), "isolator finite");
    expect(windowRms(boosted, 8000, 4000) > windowRms(lowSine, 8000, 4000) * 1.3f, "low band +6 dB is louder");

    deckbridge::Isolator killed;
    killed.prepare(48000);
    killed.set(0.f, 0.f, 0.f, false);
    float late = 1.f;
    for (int index = 0; index < 16000; ++index) {
        float l = 0.4f;
        float r = 0.4f;
        killed.process(l, r);
        late = std::abs(l);
    }
    expect(late < 0.02f, "full kill falls quiet after the release");

    deckbridge::Isolator gateBand;
    gateBand.prepare(48000);
    gateBand.set(1.f, 1.f, 1.f, true);
    std::vector<float> choppedBand(8000);
    for (int index = 0; index < 8000; ++index) {
        float l = 0.3f;
        float r = 0.3f;
        gateBand.process(l, r);
        choppedBand[index] = l;
    }
    expect(deckbridge::diffRms(std::vector<float>(8000, 0.3f), choppedBand) > 0.01f, "isolator gate changes the signal");

    deckbridge::ReleaseFx brake;
    brake.trigger(0, 120.f, 48000);
    float previousMul = 2.f;
    bool down = true;
    const int span = static_cast<int>(beat * 4);
    for (int index = 0; index < span; ++index) {
        float l = 0.2f;
        float r = 0.2f;
        const float mul = brake.pitchMul();
        if (mul > previousMul + 1e-4f) down = false;
        previousMul = mul;
        brake.process(l, r);
    }
    expect(down && brake.pitchMul() < 0.05f, "vinyl brake pitch falls to a stop");

    deckbridge::ReleaseFx echoOut;
    echoOut.trigger(1, 120.f, 48000);
    std::vector<float> echoTail;
    for (int index = 0; index < span; ++index) {
        float l = index < 2000 ? 0.4f : 0.f;
        float r = l;
        echoOut.process(l, r);
        if (index > 1000) echoTail.push_back(l);
    }
    expect(deckbridge::finiteBuffer(echoTail) && deckbridge::diffRms(std::vector<float>(echoTail.size(), 0.f), echoTail) > 0.002f,
           "echo-out differs and stays finite");

    deckbridge::ReleaseFx spin;
    spin.trigger(2, 120.f, 48000);
    bool wentNegative = false;
    for (int index = 0; index < span / 2; ++index) {
        float l = 0.2f;
        float r = 0.2f;
        if (spin.pitchMul() < -0.2f) wentNegative = true;
        spin.process(l, r);
        if (!std::isfinite(l)) wentNegative = false;
    }
    expect(wentNegative, "backspin pitch goes backwards");

    expect(std::abs(deckbridge::directedStep(1.0, 1.0, 48000, 48000, false) - 1.0) < 1e-9, "forward step is one sample");
    expect(std::abs(deckbridge::directedStep(1.0, 1.0, 48000, 48000, true) + 1.0) < 1e-9, "reverse step is minus one sample");
    const double reversedWrap = deckbridge::wrapReverseLoop(-10.0, 100.0, 200.0, true);
    expect(reversedWrap > 100.0 && reversedWrap < 200.0, "reverse loop wraps before the start");
    expect(std::abs(deckbridge::wrapReverseLoop(50.0, 100.0, 200.0, false) - 50.0) < 1e-9,
           "playing forward before the loop does not jump in");
    std::vector<float> seam = sine(8000, 440.f, 0.6f, 48000);
    const double seamStart = 1000.0;
    const double seamEnd = 5000.0;
    const float dryJump = seam[4999] - seam[1000];
    const float wet = deckbridge::readLooped(seam.data(), 8000, seamEnd - 1.0, seamStart, seamEnd, deckbridge::kFadeSamples);
    const float wetJump = wet - seam[1000];
    expect(wetJump * wetJump < dryJump * dryJump, "loop crossfade lowers the boundary jump");

    if (failures != 0) {
        std::printf("%d FAILED\n", failures);
        return 1;
    }
    std::printf("ALL PASS\n");
    return 0;
}
