#!/usr/bin/env python3
"""Roteiro da mixagem de demonstração da tela do Pi (deckbridge_ui --script).

Cada gesto é a mensagem que a DDJ-400 manda, tirada do mapa oficial pelo nome do controle (nenhum byte digitado à mão).
Os tempos são compassos da grade da faixa do deck citado: o roteiro anda junto com a música. As linhas saem em
ordem de tempo; as rampas (faders, EQ, filtro) correm em paralelo com o resto.

    python3 pi/demo/mix_demo.py android/app/src/main/resources/ddj400-map.json > roteiro.txt
"""
import json
import sys

controls = json.load(open(sys.argv[1]))["controls"]


def find(key, group=None, desc=None, cc_msb=False):
    for c in controls:
        if c["key"] != key or "SHIFT" in c["description"]:
            continue
        if group and group not in c["group"]:
            continue
        if desc and desc not in c["description"]:
            continue
        if cc_msb and c["midino"] >= 32:
            continue
        return c["status"], c["midino"]
    raise SystemExit(f"controle ausente no mapa: {key} {group} {desc}")


def hx(*values):
    return " ".join(f"{v:02X}" for v in values)


lines = []


def say(text):
    lines.append(f"# {text}")


def press(when, key, group=None, desc=None):
    status, note = find(key, group, desc)
    lines.append(f"{when} {hx(status, note, 0x7F)}")
    lines.append(f"{when} {hx(status, note, 0x00)}")


def cc(when, key, value, group=None, desc=None, msb=True):
    status, note = find(key, group, desc, cc_msb=msb)
    lines.append(f"{when} {hx(status, note, value)}")


def ramp(deck, bar_from, bar_to, key, v0, v1, group=None, desc=None):
    status, note = find(key, group, desc, cc_msb=True)
    lines.append(f"ramp {deck} {bar_from} {bar_to} {status:02X} {note:02X} {v0} {v1}")


def pad(deck, bar, number, down):
    # Pads do SAMPLER (banda esquerda), que o RMX usa: pad 1–4 cenas, 5–8 X-PAD.
    status, note = find("PioneerDDJ400.samplerPadPressed", desc=f"PAD {number} (LEFT)")
    lines.append(f"bar {deck} {bar} {hx(status, note, 0x7F if down else 0x00)}")


D1, D2 = "[Channel1]", "[Channel2]"
say("Preparação: fader do deck 1 em cima, deck 2 fechado e sem grave, PLAY no deck 1.")
cc("at 0.3", "volume", 127, D1)
cc("at 0.3", "volume", 0, D2)
cc("at 0.3", "parameter1", 0, "[Channel2]_Effect1")
press("at 0.6", "play", D1)

say("Compassos 4–8 do deck 1: Beat FX ECHO no canal 1 (SELECT uma vez sai de Low Cut Echo para Echo).")
press("bar 1 3.5", "PioneerDDJ400.beatFxSelectPressed")
press("bar 1 3.6", "PioneerDDJ400.beatFxChannel", desc="CH1")
cc("bar 1 3.7", "PioneerDDJ400.beatFxLevelDepthRotate", 96)
press("bar 1 4", "PioneerDDJ400.beatFxOnOffPressed")
press("bar 1 8", "PioneerDDJ400.beatFxOnOffPressed")

say("Compassos 9–12: loop de 4 batidas (2 voltas), depois roll de 1 batida.")
press("bar 1 9", "beatloop_4_toggle", D1)
press("bar 1 11", "beatloop_4_toggle", D1)
press("bar 1 11.75", "beatloop_1_toggle", D1)
press("bar 1 12", "beatloop_1_toggle", D1)

say("Compasso 12: BROWSE desce para a Faixa 2 e LOAD no deck 2; SYNC iguala o tempo.")
cc("bar 1 12.25", "MoveVertical", 1, msb=False)
press("bar 1 12.75", "LoadSelectedTrack", D2)
press("bar 1 14", "PioneerDDJ400.syncPressed", D2)

say("Compasso 16: PLAY no deck 2 na cabeça do compasso e SYNC de novo (fase pela grade).")
press("bar 1 16", "play", D2)
press("bar 1 16.25", "PioneerDDJ400.syncPressed", D2)
ramp(1, 16.5, 20, "volume", 0, 127, D2)

say("Compasso 24: troca de grave em uma batida.")
ramp(1, 24, 24.25, "parameter1", 64, 0, "[Channel1]_Effect1")
ramp(1, 24, 24.25, "parameter1", 0, 64, "[Channel2]_Effect1")

say("Compassos 26–32: painel RMX; Echo UP segurado 4 compassos no deck 1 enquanto o fader dele desce; PLAY para.")
lines.append("panel 1 26 1")
pad(1, 28, 1, True)
ramp(1, 28, 32, "volume", 127, 0, D1)
pad(1, 32, 1, False)
press("bar 1 32.25", "play", D1)

say("Deck 2: RMX no canal 2 (CH SELECT), Noise UP de 4 compassos com o DRUM do X-PAD, solto no compasso do drop.")
press("bar 2 18", "PioneerDDJ400.beatFxChannel", desc="CH2")
pad(2, 20, 2, True)
pad(2, 20, 7, True)
pad(2, 20.05, 7, False)
pad(2, 24, 2, False)
pad(2, 24, 7, True)
pad(2, 24.05, 7, False)
lines.append("panel 2 25 0")

say("Compassos 26–30 do deck 2: filtro passa-altas abre e fecha; depois SPIRAL no master.")
ramp(2, 26, 28, "super1", 64, 110, D2)
ramp(2, 28, 29, "super1", 110, 64, D2)
press("bar 2 29.5", "PioneerDDJ400.beatFxSelectPressed")
press("bar 2 29.6", "PioneerDDJ400.beatFxSelectPressed")
press("bar 2 29.7", "PioneerDDJ400.beatFxChannel", desc="MASTER")
press("bar 2 30", "PioneerDDJ400.beatFxOnOffPressed")
press("bar 2 32", "PioneerDDJ400.beatFxOnOffPressed")
lines.append("end 2 36")

print("\n".join(lines))
