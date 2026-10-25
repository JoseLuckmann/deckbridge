// JNI da lógica de controle (controller.h) para o app Android e para os testes JVM (ControllerCore.kt).
// Vai dentro do libdeckbridge-engine.so do app e, nos testes, numa biblioteca de host feita pelo Gradle.
// O estado (montador MIDI, pitch de 14 bits) vive em objetos C++ apontados por um handle long.
#include "controller.h"
#include "leds.h"

#include <jni.h>

#include <cstring>

using namespace deckbridge::control;

namespace {

std::string str(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_os_deckbridge_logic_ControllerCore_newAssembler(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new MidiAssembler());
}

/** Monta mensagens dos bytes; escreve trios (status, data1, data2) em `out` e devolve quantas mensagens. */
JNIEXPORT jint JNICALL Java_os_deckbridge_logic_ControllerCore_assemblerPush(JNIEnv *env, jobject, jlong handle, jbyteArray bytes, jintArray out) {
    auto *assembler = reinterpret_cast<MidiAssembler *>(handle);
    if (assembler == nullptr || bytes == nullptr || out == nullptr) return 0;
    const jsize n = env->GetArrayLength(bytes);
    std::vector<uint8_t> data(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(data.data()));
    std::vector<MidiMessage> messages;
    assembler->push(data.data(), data.size(), messages);
    const jsize room = env->GetArrayLength(out) / 3;
    const jsize count = std::min<jsize>(room, static_cast<jsize>(messages.size()));
    std::vector<jint> flat(static_cast<size_t>(count) * 3);
    for (jsize i = 0; i < count; ++i) {
        flat[static_cast<size_t>(i) * 3] = messages[static_cast<size_t>(i)].status;
        flat[static_cast<size_t>(i) * 3 + 1] = messages[static_cast<size_t>(i)].data1;
        flat[static_cast<size_t>(i) * 3 + 2] = messages[static_cast<size_t>(i)].data2;
    }
    if (count > 0) env->SetIntArrayRegion(out, 0, count * 3, flat.data());
    return count;
}

JNIEXPORT jlong JNICALL Java_os_deckbridge_logic_ControllerCore_newTempo(JNIEnv *, jobject) {
    return reinterpret_cast<jlong>(new TempoAssembler());
}

JNIEXPORT jdouble JNICALL Java_os_deckbridge_logic_ControllerCore_tempoRange(JNIEnv *, jobject, jlong handle, jint deck) {
    auto *tempo = reinterpret_cast<TempoAssembler *>(handle);
    return tempo != nullptr ? tempo->rangeOf(deck) : kDefaultTempoRange;
}

JNIEXPORT void JNICALL Java_os_deckbridge_logic_ControllerCore_tempoSetRange(JNIEnv *, jobject, jlong handle, jint deck, jdouble value) {
    auto *tempo = reinterpret_cast<TempoAssembler *>(handle);
    if (tempo != nullptr) tempo->setRange(deck, value);
}

/**
 * mode 0: interpretPerformance (tempo, performance, superfície); 1: só o TempoAssembler; 2: só performanceAction;
 * 3: só surfaceAction. Devolve o ordinal do Kind (-1 = sem ação); out = deck, hotIndex, tem slider (0/1), slider.
 */
JNIEXPORT jint JNICALL Java_os_deckbridge_logic_ControllerCore_interpret(JNIEnv *env, jobject, jlong tempoHandle, jint mode, jint status, jint midino,
                                                                       jstring group, jstring key, jstring description, jboolean mapped, jint value,
                                                                       jdoubleArray out) {
    ControlHit hit;
    hit.status = status;
    hit.midino = midino;
    hit.group = str(env, group);
    hit.key = str(env, key);
    hit.description = str(env, description);
    hit.mapped = mapped == JNI_TRUE;
    auto *tempo = reinterpret_cast<TempoAssembler *>(tempoHandle);
    std::optional<Action> action;
    switch (mode) {
        case 1: if (tempo != nullptr) action = tempo->interpret(hit, value); break;
        case 2: action = performanceAction(hit, value); break;
        case 3: action = surfaceAction(hit, value); break;
        default: {
            TempoAssembler scratch;
            action = interpretPerformance(hit, value, tempo != nullptr ? *tempo : scratch);
            break;
        }
    }
    if (!action) return -1;
    const jdouble fields[4] = {static_cast<jdouble>(action->deck), static_cast<jdouble>(action->hotIndex), action->slider ? 1.0 : 0.0,
                               action->slider.value_or(0.0)};
    if (out != nullptr && env->GetArrayLength(out) >= 4) env->SetDoubleArrayRegion(out, 0, 4, fields);
    return static_cast<jint>(action->kind);
}

JNIEXPORT jdouble JNICALL Java_os_deckbridge_logic_ControllerCore_pitchFromTempo14(JNIEnv *, jobject, jint msb, jint lsb, jdouble range) {
    return pitchFromTempo14(msb, lsb, range);
}

JNIEXPORT jdouble JNICALL Java_os_deckbridge_logic_ControllerCore_nextTempoRange(JNIEnv *, jobject, jdouble current) {
    return nextTempoRange(current);
}

// ---------- LEDs ----------
// Estado em 16 inteiros: por deck carregado, tocando, sync, pfl, máscara de hot cues, beat loop, bits do pico (float);
// depois beat fx e máscara dos pads do SAMPLER.
static LedState ledState(JNIEnv *env, jintArray packed) {
    LedState state;
    jint v[16]{};
    if (packed != nullptr && env->GetArrayLength(packed) >= 16) env->GetIntArrayRegion(packed, 0, 16, v);
    for (int d = 0; d < 2; ++d) {
        const jint *p = v + d * 7;
        auto &led = state.decks[static_cast<size_t>(d)];
        led.loaded = p[0] != 0;
        led.playing = p[1] != 0;
        led.sync = p[2] != 0;
        led.pfl = p[3] != 0;
        led.hotCues = static_cast<uint8_t>(p[4]);
        led.beatLoop = p[5];
        std::memcpy(&led.peak, &p[6], sizeof(float));
    }
    state.beatFxOn = v[14] != 0;
    state.samplerPads = static_cast<uint8_t>(v[15]);
    return state;
}

JNIEXPORT jintArray JNICALL Java_os_deckbridge_logic_ControllerCore_ledTargets(JNIEnv *env, jobject, jintArray packed) {
    LedTargets targets;
    ledTargets(ledState(env, packed), targets);
    std::vector<jint> flat;
    for (const auto &[k, value] : targets.items()) {
        flat.push_back(k);
        flat.push_back(value);
    }
    jintArray out = env->NewIntArray(static_cast<jsize>(flat.size()));
    if (out != nullptr && !flat.empty()) env->SetIntArrayRegion(out, 0, static_cast<jsize>(flat.size()), flat.data());
    return out;
}

JNIEXPORT jlong JNICALL Java_os_deckbridge_logic_ControllerCore_newLedDiff(JNIEnv *, jobject) { return reinterpret_cast<jlong>(new LedDiff()); }

JNIEXPORT void JNICALL Java_os_deckbridge_logic_ControllerCore_ledResend(JNIEnv *, jobject, jlong handle) {
    if (auto *diff = reinterpret_cast<LedDiff *>(handle)) diff->resend();
}

JNIEXPORT jbyteArray JNICALL Java_os_deckbridge_logic_ControllerCore_ledMessages(JNIEnv *env, jobject, jlong handle, jintArray packed) {
    std::vector<uint8_t> bytes;
    if (auto *diff = reinterpret_cast<LedDiff *>(handle)) diff->messages(ledState(env, packed), bytes);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
    if (out != nullptr && !bytes.empty()) env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
    return out;
}

JNIEXPORT jint JNICALL Java_os_deckbridge_logic_ControllerCore_beatLoopIndex(JNIEnv *, jobject, jdouble beats) { return beatLoopIndex(beats); }

}  // extern "C"
