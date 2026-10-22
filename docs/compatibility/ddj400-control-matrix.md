# Matriz de controles DDJ-400 — Mixxx 2.5.6

Extraída dos arquivos pinados. Não é captura da controladora.
A correlação com o aparelho real é a tarefa T009 e está `BLOCKED_HARDWARE`.

- Tag: `2.5.6`
- Commit: `3ebac449e7e5fe2a0186596657696e87ce8b0e56`
- XML sha256: `f3de2ecb02869f2aaf89101c138efa3c1f1b7357655ca20f705d2636ec4306ee`
- JS sha256: `87e1c696fe445d3c9816f04da9f3d0d5e774d0fc1c88bad699864bf13545a86d`
- Controles de entrada no XML: 201
- Saídas LED no XML: 112
- LEDs declarados em `PioneerDDJ400.lights`: 12
- Funções JS: 45

Bytes abaixo são os que estão no arquivo. Onde o arquivo não traz status ou nota, a célula fica vazia. Nada foi completado por semelhança.

## Entradas (XML `<control>`)

| Descrição | Grupo | Key | Status | Nota | Opções |
|---|---|---|---|---|---|
| BROWSE - rotate - Scroll tracklist/tree view | [Library] | MoveVertical | 0xB6 | 0x40 | SelectKnob |
| BROWSE - press - Move cursor between track list and tree view | [Library] | MoveFocusForward | 0x96 | 0x41 | Normal |
| BROWSE +SHIFT - press - Move cursor between track list and tree view | [Library] | MoveFocusBackward | 0x96 | 0x42 | Normal |
| LOAD (DECK1) - press - Load a Track into Deck 1 | [Channel1] | LoadSelectedTrack | 0x96 | 0x46 | Normal |
| LOAD (DECK2) - press - Load a Track into Deck 2 | [Channel2] | LoadSelectedTrack | 0x96 | 0x47 | Normal |
| Shift (DECK1) | [Channel1] | PioneerDDJ400.shiftPressed | 0x90 | 0x3F | Script-Binding |
| Shift (DECK2) | [Channel2] | PioneerDDJ400.shiftPressed | 0x91 | 0x3F | Script-Binding |
| PLAY/PAUSE (DECK1) - press - Play/Pause | [Channel1] | play | 0x90 | 0x0B | Normal |
| PLAY/PAUSE +SHIFT (DECK1) - press - Reverse playback in Slip Mode while held (Censor) | [Channel1] | reverseroll | 0x90 | 0x47 | Normal |
| PLAY/PAUSE (DECK2) - press - Play/Pause | [Channel2] | play | 0x91 | 0x0B | Normal |
| PLAY/PAUSE +SHIFT (DECK2) - press - Reverse playback in Slip Mode while held (Censor) | [Channel2] | reverseroll | 0x91 | 0x47 | Normal |
| CUE (DECK1) - press - Set/Call Cue, Back Cue | [Channel1] | cue_default | 0x90 | 0x0C | Normal |
| CUE +SHIFT (DECK1) - press - Jump to track start | [Channel1] | start_play | 0x90 | 0x48 | Normal |
| CUE (DECK2) - press - Set/Call Cue, Back Cue | [Channel2] | cue_default | 0x91 | 0x0C | Normal |
| CUE +SHIFT (DECK2) - press - Jump to track start | [Channel2] | start_play | 0x91 | 0x48 | Normal |
| JOG DIAL PLATTER Vinyl mode On (DECK1) - rotate - Scratch | [Channel1] | PioneerDDJ400.jogTurn | 0xB0 | 0x22 | Script-Binding |
| JOG DIAL PLATTER Vinyl mode Off (DECK1) - rotate - Pitch bend | [Channel1] | PioneerDDJ400.jogTurn | 0xB0 | 0x23 | Script-Binding |
| JOG DIAL PLATTER +SHIFT (DECK1) - rotate - Search (Fast Pitch bend) | [Channel1] | PioneerDDJ400.jogSearch | 0xB0 | 0x29 | Script-Binding |
| JOG DIAL PLATTER (DECK1) - touch - enable (on touch) / disable (on release) Scratching/Pitch
                    bend | [Channel1] | PioneerDDJ400.jogTouch | 0x90 | 0x36 | Script-Binding |
| JOG DIAL PLATTER +SHIFT (DECK1) - touch - enable (on touch) / disable (on release) highspeed
                    Pitch bend | [Channel1] | PioneerDDJ400.jogTouch | 0x90 | 0x67 | Script-Binding |
| JOG DIAL SIDE (DECK1) - rotate - Pitch bend | [Channel1] | PioneerDDJ400.jogTurn | 0xB0 | 0x21 | Script-Binding |
| JOG DIAL PLATTER Vinyl mode On (DECK2) - rotate - Scratch | [Channel2] | PioneerDDJ400.jogTurn | 0xB1 | 0x22 | Script-Binding |
| JOG DIAL PLATTER Vinyl mode Off (DECK2) - rotate - Pitch bend | [Channel2] | PioneerDDJ400.jogTurn | 0xB1 | 0x23 | Script-Binding |
| JOG DIAL PLATTER +SHIFT (DECK2) - rotate - Search (Fast Pitch bend) | [Channel2] | PioneerDDJ400.jogSearch | 0xB1 | 0x29 | Script-Binding |
| JOG DIAL PLATTER (DECK2) - touch - enable (on touch) / disable (on release) Scratching/Pitch
                    bend | [Channel2] | PioneerDDJ400.jogTouch | 0x91 | 0x36 | Script-Binding |
| JOG DIAL PLATTER +SHIFT (DECK2) - touch - enable (on touch) / disable (on release) highspeed
                    Pitch bend | [Channel2] | PioneerDDJ400.jogTouch | 0x91 | 0x67 | Script-Binding |
| JOG DIAL SIDE (DECK2) - rotate - Pitch bend | [Channel2] | PioneerDDJ400.jogTurn | 0xB1 | 0x21 | Script-Binding |
| TEMPO (DECK1) - fader - Tempo control LSB | [Channel1] | PioneerDDJ400.tempoSliderLSB | 0xB0 | 0x20 | script-binding |
| TEMPO (DECK1) - fader - Tempo control MSB | [Channel1] | PioneerDDJ400.tempoSliderMSB | 0xB0 | 0x00 | script-binding |
| TEMPO (DECK2) - fader - Tempo control LSB | [Channel2] | PioneerDDJ400.tempoSliderLSB | 0xB1 | 0x20 | script-binding |
| TEMPO (DECK2) - fader - Tempo control MSB | [Channel2] | PioneerDDJ400.tempoSliderMSB | 0xB1 | 0x00 | script-binding |
| BEAT SYNC (DECK1) - press - Beat Sync to Master deck | [Channel1] | PioneerDDJ400.syncPressed | 0x90 | 0x58 | Script-Binding |
| BEAT SYNC LONG PRESS (DECK1) - press - Set as Master deck | [Channel1] | PioneerDDJ400.syncLongPressed | 0x90 | 0x5C | Script-Binding |
| BEAT SYNC +SHIFT (DECK1) - press - change Tempo range | [Channel1] | PioneerDDJ400.cycleTempoRange | 0x90 | 0x60 | Script-Binding |
| BEAT SYNC (DECK2) - press - Beat Sync to Master deck | [Channel2] | PioneerDDJ400.syncPressed | 0x91 | 0x58 | Script-Binding |
| BEAT SYNC LONG PRESS (DECK2) - press - Set as Master deck | [Channel2] | PioneerDDJ400.syncLongPressed | 0x91 | 0x5C | Script-Binding |
| BEAT SYNC +SHIFT (DECK2) - press - change Tempo range | [Channel2] | PioneerDDJ400.cycleTempoRange | 0x91 | 0x60 | Script-Binding |
| LOOP IN/4 BEAT (DECK1) - press - Set loop in | [Channel1] | loop_in | 0x90 | 0x10 | Normal |
| LOOP IN/4 BEAT (DECK2) - press - Set loop in | [Channel2] | loop_in | 0x91 | 0x10 | Normal |
| LOOP OUT (DECK1) - press - Set loop out | [Channel1] | loop_out | 0x90 | 0x11 | Normal |
| LOOP OUT (DECK2) - press - Set loop out | [Channel2] | loop_out | 0x91 | 0x11 | Normal |
| RELOOP/EXIT (DECK1) - press - (loop off) Reloop, (loop on) Loop exit | [Channel1] | reloop_toggle | 0x90 | 0x4D | Normal |
| RELOOP/EXIT (DECK2) - press - (loop off) Reloop, (loop on) Loop exit | [Channel2] | reloop_toggle | 0x91 | 0x4D | Normal |
| RELOOP/EXIT +SHIFT (DECK1) - press - Reloop and stop | [Channel1] | reloop_andstop | 0x90 | 0x50 | Normal |
| RELOOP/EXIT +SHIFT (DECK2) - press - Reloop and stop | [Channel2] | reloop_andstop | 0x91 | 0x50 | Normal |
| SHIFT + LOOP IN (DECK1) - Loop in adjust (using jog wheel) | [Channel1] | PioneerDDJ400.toggleLoopAdjustIn | 0x90 | 0x4C | Script-Binding |
| SHIFT + LOOP IN (DECK2) - Loop in adjust (using jog wheel) | [Channel2] | PioneerDDJ400.toggleLoopAdjustIn | 0x91 | 0x4C | Script-Binding |
| SHIFT + LOOP OUT (DECK1) - Loop out adjust (using jog wheel) | [Channel1] | PioneerDDJ400.toggleLoopAdjustOut | 0x90 | 0x4E | Script-Binding |
| SHIFT + LOOP OUT (DECK2) - Loop out adjust (using jog wheel) | [Channel2] | PioneerDDJ400.toggleLoopAdjustOut | 0x91 | 0x4E | Script-Binding |
| CUE/LOOP CALL LEFT (DECK1) - press - half active loop | [Channel1] | PioneerDDJ400.cueLoopCallLeft | 0x90 | 0x51 | Script-Binding |
| CUE/LOOP CALL LEFT + SHIFT (DECK1) - press - quick jump back | [Channel1] | PioneerDDJ400.quickJumpBack | 0x90 | 0x3E | Script-Binding |
| CUE/LOOP CALL LEFT (DECK2) - press - half active loop | [Channel2] | PioneerDDJ400.cueLoopCallLeft | 0x91 | 0x51 | Script-Binding |
| CUE/LOOP CALL LEFT + SHIFT (DECK2) - press - quick jump back | [Channel2] | PioneerDDJ400.quickJumpBack | 0x91 | 0x3E | Script-Binding |
| CUE/LOOP CALL LEFT (DECK1) - press - double active loop | [Channel1] | PioneerDDJ400.cueLoopCallRight | 0x90 | 0x53 | Script-Binding |
| CUE/LOOP CALL RIGHT + SHIFT (DECK1) - press - quick jump forwards | [Channel1] | PioneerDDJ400.quickJumpForward | 0x90 | 0x3D | Script-Binding |
| CUE/LOOP CALL LEFT (DECK2) - press - double active loop | [Channel2] | PioneerDDJ400.cueLoopCallRight | 0x91 | 0x53 | Script-Binding |
| CUE/LOOP CALL RIGHT + SHIFT (DECK2) - press - quick jump forwards | [Channel2] | PioneerDDJ400.quickJumpForward | 0x91 | 0x3D | Script-Binding |
| CROSSFADER - slider | [Master] | crossfader | 0xB6 | 0x1F | fourteen-bit-msb |
| CROSSFADER - slider | [Master] | crossfader | 0xB6 | 0x3F | fourteen-bit-lsb |
| CHANNELFADER - slider | [Channel1] | volume | 0xB0 | 0x33 | fourteen-bit-lsb |
| CHANNELFADER - slider | [Channel1] | volume | 0xB0 | 0x13 | fourteen-bit-msb |
| CHANNELFADER - slider | [Channel2] | volume | 0xB1 | 0x33 | fourteen-bit-lsb |
| CHANNELFADER - slider | [Channel2] | volume | 0xB1 | 0x13 | fourteen-bit-msb |
| Shift + Left CUE - Toggle quantize on/off (Deck1) | [Channel1] | PioneerDDJ400.toggleQuantize | 0x90 | 0x68 | Script-Binding |
| Shift + Right CUE - Toggle quantize on/off (Deck2) | [Channel2] | PioneerDDJ400.toggleQuantize | 0x91 | 0x68 | Script-Binding |
| TRIM - rotate | [Channel1] | pregain | 0xB0 | 0x24 | fourteen-bit-lsb |
| TRIM - rotate | [Channel1] | pregain | 0xB0 | 0x04 | fourteen-bit-msb |
| TRIM - rotate | [Channel2] | pregain | 0xB1 | 0x24 | fourteen-bit-lsb |
| TRIM - rotate | [Channel2] | pregain | 0xB1 | 0x04 | fourteen-bit-msb |
| EQ HI - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter3 | 0xB0 | 0x27 | fourteen-bit-lsb |
| EQ MID - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter2 | 0xB0 | 0x2B | fourteen-bit-lsb |
| EQ LOW - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter1 | 0xB0 | 0x2F | fourteen-bit-lsb |
| EQ HI - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter3 | 0xB0 | 0x07 | fourteen-bit-msb |
| EQ MID - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter2 | 0xB0 | 0x0B | fourteen-bit-msb |
| EQ LOW - rotate | [EqualizerRack1_[Channel1]_Effect1] | parameter1 | 0xB0 | 0x0F | fourteen-bit-msb |
| EQ HI - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter3 | 0xB1 | 0x27 | fourteen-bit-lsb |
| EQ MID - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter2 | 0xB1 | 0x2B | fourteen-bit-lsb |
| EQ LOW - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter1 | 0xB1 | 0x2F | fourteen-bit-lsb |
| EQ HI - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter3 | 0xB1 | 0x07 | fourteen-bit-msb |
| EQ MID - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter2 | 0xB1 | 0x0B | fourteen-bit-msb |
| EQ LOW - rotate | [EqualizerRack1_[Channel2]_Effect1] | parameter1 | 0xB1 | 0x0F | fourteen-bit-msb |
| CUE Channel - press - toggle Headphone Cue | [Channel1] | pfl | 0x90 | 0x54 | normal |
| CUE Channel - press - toggle Headphone Cue | [Channel2] | pfl | 0x91 | 0x54 | normal |
| HEADPHONES MIXING - rotate - Monitor Balance | [Master] | headMix | 0xB6 | 0x2C | fourteen-bit-lsb |
| HEADPHONES MIXING - rotate - Monitor Balance | [Master] | headMix | 0xB6 | 0x0C | fourteen-bit-msb |
| HEADPHONES LEVEL - rotate - Headphone gain | [Master] | headGain | 0xB6 | 0x2D | fourteen-bit-lsb |
| HEADPHONES LEVEL - rotate - Headphone gain | [Master] | headGain | 0xB6 | 0x0D | fourteen-bit-msb |
| FILTER CH1 - rotate - Filter Effect Knob | [QuickEffectRack1_[Channel1]] | super1 | 0xB6 | 0x17 | fourteen-bit-msb |
| FILTER CH1 - rotate - Filter Effect Knob | [QuickEffectRack1_[Channel1]] | super1 | 0xB6 | 0x37 | fourteen-bit-lsb |
| FILTER CH2 - rotate - Filter Effect Knob | [QuickEffectRack1_[Channel2]] | super1 | 0xB6 | 0x18 | fourteen-bit-msb |
| FILTER CH2 - rotate - Filter Effect Knob | [QuickEffectRack1_[Channel2]] | super1 | 0xB6 | 0x38 | fourteen-bit-lsb |
| BEAT LEFT - press - toggle focused Effect leftward | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxLeftPressed | 0x94 | 0x4A | Script-Binding |
| BEAT RIGHT - press - toggle focused Effect rightward | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxRightPressed | 0x94 | 0x4B | Script-Binding |
| BEAT FX SELECT - press - load next effect entry for focused Effect | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxSelectPressed | 0x94 | 0x63 | Script-Binding |
| BEAT FX SELECT +SHIFT - press - load previous effect entry for focused Effect | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxSelectShiftPressed | 0x94 | 0x64 | Script-Binding |
| BEAT FX CH SELECT CH1 - slide - Select FX on DECK 1 | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxChannel | 0x94 | 0x10 | Script-Binding |
| BEAT FX CH SELECT CH2 - slide - Select FX on DECK 2 | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxChannel | 0x94 | 0x11 | Script-Binding |
| BEAT FX CH SELECT MASTER - slide - Select FX on Master | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxChannel | 0x94 | 0x14 | Script-Binding |
| BEAT FX LEVEL/DEPTH - rotate (MSB) - Adjust FX Level (mix) and BEAT FX Depth (meta) in the
                    selected slot | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxLevelDepthRotate | 0xB4 | 0x02 | Script-Binding |
| BEAT FX ON/OFF - press - Toggle FX in the selected Slot | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxOnOffPressed | 0x94 | 0x47 | Script-Binding |
| BEAT FX ON/OFF +SHIFT - press - Disable all enabled Beat FX Slots | [EffectRack1_EffectUnit1] | PioneerDDJ400.beatFxOnOffShiftPressed | 0x94 | 0x43 | Script-Binding |
| PAD 1 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_1_activate | 0x97 | 0x00 | normal |
| PAD 1 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_1_clear | 0x98 | 0x00 | normal |
| PAD 1 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_1_activate | 0x99 | 0x00 | normal |
| PAD 1 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_1_clear | 0x9A | 0x00 | normal |
| PAD 2 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_2_activate | 0x97 | 0x01 | normal |
| PAD 2 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_2_clear | 0x98 | 0x01 | normal |
| PAD 2 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_2_activate | 0x99 | 0x01 | normal |
| PAD 2 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_2_clear | 0x9A | 0x01 | normal |
| PAD 3 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_3_activate | 0x97 | 0x02 | normal |
| PAD 3 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_3_clear | 0x98 | 0x02 | normal |
| PAD 3 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_3_activate | 0x99 | 0x02 | normal |
| PAD 3 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_3_clear | 0x9A | 0x02 | normal |
| PAD 4 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_4_activate | 0x97 | 0x03 | normal |
| PAD 4 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_4_clear | 0x98 | 0x03 | normal |
| PAD 4 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_4_activate | 0x99 | 0x03 | normal |
| PAD 4 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_4_clear | 0x9A | 0x03 | normal |
| PAD 5(DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_5_activate | 0x97 | 0x04 | normal |
| PAD 5 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_5_clear | 0x98 | 0x04 | normal |
| PAD 5 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_5_activate | 0x99 | 0x04 | normal |
| PAD 5 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_5_clear | 0x9A | 0x04 | normal |
| PAD 6 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_6_activate | 0x97 | 0x05 | normal |
| PAD 6 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_6_clear | 0x98 | 0x05 | normal |
| PAD 6 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_6_activate | 0x99 | 0x05 | normal |
| PAD 6 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_6_clear | 0x9A | 0x05 | normal |
| PAD 7 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_7_activate | 0x97 | 0x06 | normal |
| PAD 7 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_7_clear | 0x98 | 0x06 | normal |
| PAD 7 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_7_activate | 0x99 | 0x06 | normal |
| PAD 7 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_7_clear | 0x9A | 0x06 | normal |
| PAD 8 (DECK1) HOT CUE MODE - press - set hotcue | [Channel1] | hotcue_8_activate | 0x97 | 0x07 | normal |
| PAD 8 +SHIFT (DECK1) HOT CUE MODE - press - delete hotcue | [Channel1] | hotcue_8_clear | 0x98 | 0x07 | normal |
| PAD 8 (DECK2) HOT CUE MODE - press - set hotcue | [Channel2] | hotcue_8_activate | 0x99 | 0x07 | normal |
| PAD 8 +SHIFT (DECK2) HOT CUE MODE - press - delete hotcue | [Channel2] | hotcue_8_clear | 0x9A | 0x07 | normal |
| PAD 1 (DECK1) BEAT LOOP MODE - press - 1/4 Beatloop | [Channel1] | beatloop_0.25_toggle | 0x97 | 0x60 | Normal |
| PAD 1 (DECK2) BEAT LOOP MODE - press - 1/4 Beatloop | [Channel2] | beatloop_0.25_toggle | 0x99 | 0x60 | Normal |
| PAD 2 (DECK1) BEAT LOOP MODE - press - 1/2 Beatloop | [Channel1] | beatloop_0.5_toggle | 0x97 | 0x61 | Normal |
| PAD 2 (DECK2) BEAT LOOP MODE - press - 1/2 Beatloop | [Channel2] | beatloop_0.5_toggle | 0x99 | 0x61 | Normal |
| PAD 3 (DECK1) BEAT LOOP MODE - press - 1/1 Beatloop | [Channel1] | beatloop_1_toggle | 0x97 | 0x62 | Normal |
| PAD 3 (DECK2) BEAT LOOP MODE - press - 1/1 Beatloop | [Channel2] | beatloop_1_toggle | 0x99 | 0x62 | Normal |
| PAD 4 (DECK1) BEAT LOOP MODE - press - 2 Beatloop | [Channel1] | beatloop_2_toggle | 0x97 | 0x63 | Normal |
| PAD 4 (DECK2) BEAT LOOP MODE - press - 2 Beatloop | [Channel2] | beatloop_2_toggle | 0x99 | 0x63 | Normal |
| PAD 5 (DECK1) BEAT LOOP MODE - press - 4 Beatloop | [Channel1] | beatloop_4_toggle | 0x97 | 0x64 | Normal |
| PAD 5 (DECK2) BEAT LOOP MODE - press - 4 Beatloop | [Channel2] | beatloop_4_toggle | 0x99 | 0x64 | Normal |
| PAD 6 (DECK1) BEAT LOOP MODE - press - 8 Beatloop | [Channel1] | beatloop_8_toggle | 0x97 | 0x65 | Normal |
| PAD 6 (DECK2) BEAT LOOP MODE - press - 8 Beatloop | [Channel2] | beatloop_8_toggle | 0x99 | 0x65 | Normal |
| PAD 7 (DECK1) BEAT LOOP MODE - press - 16 Beatloop | [Channel1] | beatloop_16_toggle | 0x97 | 0x66 | Normal |
| PAD 7 (DECK2) BEAT LOOP MODE - press - 16 Beatloop | [Channel2] | beatloop_16_toggle | 0x99 | 0x66 | Normal |
| PAD 8 (DECK1) BEAT LOOP MODE - press - 32 Beatloop | [Channel1] | beatloop_32_toggle | 0x97 | 0x67 | Normal |
| PAD 8 (DECK2) BEAT LOOP MODE - press - 32 Beatloop | [Channel2] | beatloop_32_toggle | 0x99 | 0x67 | Normal |
| PAD 1 (DECK1) BEAT JUMP MODE - press - Jump 1 Beat backwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x20 | Script-Binding |
| PAD 1 (DECK2) BEAT JUMP MODE - press - Jump 1 Beat backwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x20 | Script-Binding |
| PAD 2 (DECK1) BEAT JUMP MODE - press - Jump 1 Beat forwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x21 | Script-Binding |
| PAD 2 (DECK2) BEAT JUMP MODE - press - Jump 1 Beat forwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x21 | Script-Binding |
| PAD 3 (DECK1) BEAT JUMP MODE - press - Jump 2 Beats backwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x22 | Script-Binding |
| PAD 3 (DECK2) BEAT JUMP MODE - press - Jump 2 Beats backwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x22 | Script-Binding |
| PAD 4 (DECK1) BEAT JUMP MODE - press - Jump 2 Beats forwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x23 | Script-Binding |
| PAD 4 (DECK2) BEAT JUMP MODE - press - Jump 2 Beats forwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x23 | Script-Binding |
| PAD 5 (DECK1) BEAT JUMP MODE - press - Jump 4 Beats backwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x24 | Script-Binding |
| PAD 5 (DECK2) BEAT JUMP MODE - press - Jump 4 Beats backwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x24 | Script-Binding |
| PAD 6 (DECK1) BEAT JUMP MODE - press - Jump 4 Beats forwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x25 | Script-Binding |
| PAD 6 (DECK2) BEAT JUMP MODE - press - Jump 4 Beats forwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x25 | Script-Binding |
| PAD 7 (DECK1) BEAT JUMP MODE - press - Jump 8 Beats backwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x26 | Script-Binding |
| PAD 7 (DECK2) BEAT JUMP MODE - press - Jump 8 Beats backwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x26 | Script-Binding |
| PAD 8 (DECK1) BEAT JUMP MODE - press - Jump 8 Beats forwards | [Channel1] | PioneerDDJ400.beatjumpPadPressed | 0x97 | 0x27 | Script-Binding |
| PAD 8 (DECK2) BEAT JUMP MODE - press - Jump 8 Beats forwards | [Channel2] | PioneerDDJ400.beatjumpPadPressed | 0x99 | 0x27 | Script-Binding |
| PAD 7 (DECK1) +SHift BEAT JUMP MODE - press - decrease Beatjump by a factor of 16 | [Channel1] | PioneerDDJ400.decreaseBeatjumpSizes | 0x98 | 0x26 | Script-Binding |
| PAD 7 (DECK2) +Shift BEAT JUMP MODE - press - decrease Beatjump by a factor of 16 | [Channel2] | PioneerDDJ400.decreaseBeatjumpSizes | 0x9A | 0x26 | Script-Binding |
| PAD 8 (DECK1) +SHift BEAT JUMP MODE - press - increase Beatjump by a factor of 16 | [Channel1] | PioneerDDJ400.increaseBeatjumpSizes | 0x98 | 0x27 | Script-Binding |
| PAD 8 (DECK2) +Shift BEAT JUMP MODE - press - increase Beatjump by a factor of 16 | [Channel2] | PioneerDDJ400.increaseBeatjumpSizes | 0x9A | 0x27 | Script-Binding |
| PAD 1 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler1] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x30 | Script-Binding |
| PAD 1 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler1] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x30 | Script-Binding |
| PAD 2 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler2] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x31 | Script-Binding |
| PAD 2 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler2] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x31 | Script-Binding |
| PAD 3 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler3] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x32 | Script-Binding |
| PAD 3 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler3] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x32 | Script-Binding |
| PAD 4 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler4] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x33 | Script-Binding |
| PAD 4 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler4] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x33 | Script-Binding |
| PAD 5 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler5] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x34 | Script-Binding |
| PAD 5 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler5] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x34 | Script-Binding |
| PAD 6 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler6] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x35 | Script-Binding |
| PAD 6 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler6] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x35 | Script-Binding |
| PAD 7 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler7] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x36 | Script-Binding |
| PAD 7 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler7] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x36 | Script-Binding |
| PAD 8 (LEFT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler8] | PioneerDDJ400.samplerPadPressed | 0x97 | 0x37 | Script-Binding |
| PAD 8 (LEFT)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler8] | PioneerDDJ400.samplerPadShiftPressed | 0x98 | 0x37 | Script-Binding |
| PAD 1 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler9] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x30 | Script-Binding |
| PAD 1 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler9] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x30 | Script-Binding |
| PAD 2 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler10] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x31 | Script-Binding |
| PAD 2 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler10] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x31 | Script-Binding |
| PAD 3 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler11] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x32 | Script-Binding |
| PAD 3 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler11] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x32 | Script-Binding |
| PAD 4 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler12] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x33 | Script-Binding |
| PAD 4 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler12] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x33 | Script-Binding |
| PAD 5 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler13] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x34 | Script-Binding |
| PAD 5 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler13] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x34 | Script-Binding |
| PAD 6 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler14] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x35 | Script-Binding |
| PAD 6 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler14] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x35 | Script-Binding |
| PAD 7 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler15] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x36 | Script-Binding |
| PAD 7 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler15] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x36 | Script-Binding |
| PAD 8 (RIGHT) SAMPLE MODE - press - Play Sample or Load Track | [Sampler16] | PioneerDDJ400.samplerPadPressed | 0x99 | 0x37 | Script-Binding |
| PAD 8 (Right)+SHIFT SAMPLE MODE - press - Stop Playback or Eject Track | [Sampler16] | PioneerDDJ400.samplerPadShiftPressed | 0x9A | 0x37 | Script-Binding |

## Saídas LED (XML `<output>`)

| Grupo | Key | Status | Nota | On | Mínimo |
|---|---|---|---|---|---|
| [Channel1] | play_indicator | 0x90 | 0x0B | 0x7F | 0.5 |
| [Channel2] | play_indicator | 0x91 | 0x0B | 0x7F | 0.5 |
| [Channel1] | play_indicator | 0x90 | 0x47 | 0x7F | 0.5 |
| [Channel2] | play_indicator | 0x91 | 0x47 | 0x7F | 0.5 |
| [Channel1] | cue_indicator | 0x90 | 0x0C | 0x7F | 0.5 |
| [Channel2] | cue_indicator | 0x91 | 0x0C | 0x7F | 0.5 |
| [Channel1] | cue_indicator | 0x90 | 0x47 | 0x7F | 0.5 |
| [Channel2] | cue_indicator | 0x91 | 0x47 | 0x7F | 0.5 |
| [Channel1] | sync_enabled | 0x90 | 0x58 | 0x7F | 0.5 |
| [Channel2] | sync_enabled | 0x91 | 0x58 | 0x7F | 0.5 |
| [Channel1] | pfl | 0x90 | 0x54 | 0x7F | 0.5 |
| [Channel2] | pfl | 0x91 | 0x54 | 0x7F | 0.5 |
| [Channel1] | hotcue_1_enabled | 0x97 | 0x00 | 0x7F | 0.5 |
| [Channel2] | hotcue_1_enabled | 0x99 | 0x00 | 0x7F | 0.5 |
| [Channel1] | hotcue_2_enabled | 0x97 | 0x01 | 0x7F | 0.5 |
| [Channel2] | hotcue_2_enabled | 0x99 | 0x01 | 0x7F | 0.5 |
| [Channel1] | hotcue_3_enabled | 0x97 | 0x02 | 0x7F | 0.5 |
| [Channel2] | hotcue_3_enabled | 0x99 | 0x02 | 0x7F | 0.5 |
| [Channel1] | hotcue_4_enabled | 0x97 | 0x03 | 0x7F | 0.5 |
| [Channel2] | hotcue_4_enabled | 0x99 | 0x03 | 0x7F | 0.5 |
| [Channel1] | hotcue_5_enabled | 0x97 | 0x04 | 0x7F | 0.5 |
| [Channel2] | hotcue_5_enabled | 0x99 | 0x04 | 0x7F | 0.5 |
| [Channel1] | hotcue_6_enabled | 0x97 | 0x05 | 0x7F | 0.5 |
| [Channel2] | hotcue_6_enabled | 0x99 | 0x05 | 0x7F | 0.5 |
| [Channel1] | hotcue_7_enabled | 0x97 | 0x06 | 0x7F | 0.5 |
| [Channel2] | hotcue_7_enabled | 0x99 | 0x06 | 0x7F | 0.5 |
| [Channel1] | hotcue_8_enabled | 0x97 | 0x07 | 0x7F | 0.5 |
| [Channel2] | hotcue_8_enabled | 0x99 | 0x07 | 0x7F | 0.5 |
| [Channel1] | hotcue_1_enabled | 0x98 | 0x00 | 0x7F | 0.5 |
| [Channel2] | hotcue_1_enabled | 0x9A | 0x00 | 0x7F | 0.5 |
| [Channel1] | hotcue_2_enabled | 0x98 | 0x01 | 0x7F | 0.5 |
| [Channel2] | hotcue_2_enabled | 0x9A | 0x01 | 0x7F | 0.5 |
| [Channel1] | hotcue_3_enabled | 0x98 | 0x02 | 0x7F | 0.5 |
| [Channel2] | hotcue_3_enabled | 0x9A | 0x02 | 0x7F | 0.5 |
| [Channel1] | hotcue_4_enabled | 0x98 | 0x03 | 0x7F | 0.5 |
| [Channel2] | hotcue_4_enabled | 0x9A | 0x03 | 0x7F | 0.5 |
| [Channel1] | hotcue_5_enabled | 0x98 | 0x04 | 0x7F | 0.5 |
| [Channel2] | hotcue_5_enabled | 0x9A | 0x04 | 0x7F | 0.5 |
| [Channel1] | hotcue_6_enabled | 0x98 | 0x05 | 0x7F | 0.5 |
| [Channel2] | hotcue_6_enabled | 0x9A | 0x05 | 0x7F | 0.5 |
| [Channel1] | hotcue_7_enabled | 0x98 | 0x06 | 0x7F | 0.5 |
| [Channel2] | hotcue_7_enabled | 0x9A | 0x06 | 0x7F | 0.5 |
| [Channel1] | hotcue_8_enabled | 0x98 | 0x07 | 0x7F | 0.5 |
| [Channel2] | hotcue_8_enabled | 0x9A | 0x07 | 0x7F | 0.5 |
| [Channel1] | beatloop_0.25_enabled | 0x97 | 0x60 | 0x7F | 0.5 |
| [Channel2] | beatloop_0.25_enabled | 0x99 | 0x60 | 0x7F | 0.5 |
| [Channel1] | beatloop_0.5_enabled | 0x97 | 0x61 | 0x7F | 0.5 |
| [Channel2] | beatloop_0.5_enabled | 0x99 | 0x61 | 0x7F | 0.5 |
| [Channel1] | beatloop_1_enabled | 0x97 | 0x62 | 0x7F | 0.5 |
| [Channel2] | beatloop_1_enabled | 0x99 | 0x62 | 0x7F | 0.5 |
| [Channel1] | beatloop_2_enabled | 0x97 | 0x63 | 0x7F | 0.5 |
| [Channel2] | beatloop_2_enabled | 0x99 | 0x63 | 0x7F | 0.5 |
| [Channel1] | beatloop_4_enabled | 0x97 | 0x64 | 0x7F | 0.5 |
| [Channel2] | beatloop_4_enabled | 0x99 | 0x64 | 0x7F | 0.5 |
| [Channel1] | beatloop_8_enabled | 0x97 | 0x65 | 0x7F | 0.5 |
| [Channel2] | beatloop_8_enabled | 0x99 | 0x65 | 0x7F | 0.5 |
| [Channel1] | beatloop_16_enabled | 0x97 | 0x66 | 0x7F | 0.5 |
| [Channel2] | beatloop_16_enabled | 0x99 | 0x66 | 0x7F | 0.5 |
| [Channel1] | beatloop_32_enabled | 0x97 | 0x67 | 0x7F | 0.5 |
| [Channel2] | beatloop_32_enabled | 0x99 | 0x67 | 0x7F | 0.5 |
| [Channel1] | beatloop_0.25_enabled | 0x98 | 0x60 | 0x7F | 0.5 |
| [Channel2] | beatloop_0.25_enabled | 0x9A | 0x60 | 0x7F | 0.5 |
| [Channel1] | beatloop_0.5_enabled | 0x98 | 0x61 | 0x7F | 0.5 |
| [Channel2] | beatloop_0.5_enabled | 0x9A | 0x61 | 0x7F | 0.5 |
| [Channel1] | beatloop_1_enabled | 0x98 | 0x62 | 0x7F | 0.5 |
| [Channel2] | beatloop_1_enabled | 0x9A | 0x62 | 0x7F | 0.5 |
| [Channel1] | beatloop_2_enabled | 0x98 | 0x63 | 0x7F | 0.5 |
| [Channel2] | beatloop_2_enabled | 0x9A | 0x63 | 0x7F | 0.5 |
| [Channel1] | beatloop_4_enabled | 0x98 | 0x64 | 0x7F | 0.5 |
| [Channel2] | beatloop_4_enabled | 0x9A | 0x64 | 0x7F | 0.5 |
| [Channel1] | beatloop_8_enabled | 0x98 | 0x65 | 0x7F | 0.5 |
| [Channel2] | beatloop_8_enabled | 0x9A | 0x65 | 0x7F | 0.5 |
| [Channel1] | beatloop_16_enabled | 0x98 | 0x66 | 0x7F | 0.5 |
| [Channel2] | beatloop_16_enabled | 0x9A | 0x66 | 0x7F | 0.5 |
| [Channel1] | beatloop_32_enabled | 0x98 | 0x67 | 0x7F | 0.5 |
| [Channel2] | beatloop_32_enabled | 0x9A | 0x67 | 0x7F | 0.5 |
| [Channel1] | track_loaded | 0x98 | 0x26 | 0x7F | 0.5 |
| [Channel1] | track_loaded | 0x98 | 0x27 | 0x7F | 0.5 |
| [Channel2] | track_loaded | 0x9A | 0x26 | 0x7F | 0.5 |
| [Channel2] | track_loaded | 0x9A | 0x27 | 0x7F | 0.5 |
| [Sampler1] | track_loaded | 0x97 | 0x30 | 0x7F | 0.5 |
| [Sampler2] | track_loaded | 0x97 | 0x31 | 0x7F | 0.5 |
| [Sampler3] | track_loaded | 0x97 | 0x32 | 0x7F | 0.5 |
| [Sampler4] | track_loaded | 0x97 | 0x33 | 0x7F | 0.5 |
| [Sampler5] | track_loaded | 0x97 | 0x34 | 0x7F | 0.5 |
| [Sampler6] | track_loaded | 0x97 | 0x35 | 0x7F | 0.5 |
| [Sampler7] | track_loaded | 0x97 | 0x36 | 0x7F | 0.5 |
| [Sampler8] | track_loaded | 0x97 | 0x37 | 0x7F | 0.5 |
| [Sampler9] | track_loaded | 0x99 | 0x30 | 0x7F | 0.5 |
| [Sampler10] | track_loaded | 0x99 | 0x31 | 0x7F | 0.5 |
| [Sampler11] | track_loaded | 0x99 | 0x32 | 0x7F | 0.5 |
| [Sampler12] | track_loaded | 0x99 | 0x33 | 0x7F | 0.5 |
| [Sampler13] | track_loaded | 0x99 | 0x34 | 0x7F | 0.5 |
| [Sampler14] | track_loaded | 0x99 | 0x35 | 0x7F | 0.5 |
| [Sampler15] | track_loaded | 0x99 | 0x36 | 0x7F | 0.5 |
| [Sampler16] | track_loaded | 0x99 | 0x37 | 0x7F | 0.5 |
| [Sampler1] | track_loaded | 0x98 | 0x30 | 0x7F | 0.5 |
| [Sampler2] | track_loaded | 0x98 | 0x31 | 0x7F | 0.5 |
| [Sampler3] | track_loaded | 0x98 | 0x32 | 0x7F | 0.5 |
| [Sampler4] | track_loaded | 0x98 | 0x33 | 0x7F | 0.5 |
| [Sampler5] | track_loaded | 0x98 | 0x34 | 0x7F | 0.5 |
| [Sampler6] | track_loaded | 0x98 | 0x35 | 0x7F | 0.5 |
| [Sampler7] | track_loaded | 0x98 | 0x36 | 0x7F | 0.5 |
| [Sampler8] | track_loaded | 0x98 | 0x37 | 0x7F | 0.5 |
| [Sampler9] | track_loaded | 0x9A | 0x30 | 0x7F | 0.5 |
| [Sampler10] | track_loaded | 0x9A | 0x31 | 0x7F | 0.5 |
| [Sampler11] | track_loaded | 0x9A | 0x32 | 0x7F | 0.5 |
| [Sampler12] | track_loaded | 0x9A | 0x33 | 0x7F | 0.5 |
| [Sampler13] | track_loaded | 0x9A | 0x34 | 0x7F | 0.5 |
| [Sampler14] | track_loaded | 0x9A | 0x35 | 0x7F | 0.5 |
| [Sampler15] | track_loaded | 0x9A | 0x36 | 0x7F | 0.5 |
| [Sampler16] | track_loaded | 0x9A | 0x37 | 0x7F | 0.5 |

## LEDs no script (`PioneerDDJ400.lights`)

Iguais ou não aos `<output>` do XML: comparação de arquivo, não de hardware.

| Nome | Status | Data1 |
|---|---|---|
| lights.beatFx | 0x94 | 0x47 |
| lights.shiftBeatFx | 0x94 | 0x43 |
| lights.deck1.vuMeter | 0xB0 | 0x02 |
| lights.deck1.playPause | 0x90 | 0x0B |
| lights.deck1.shiftPlayPause | 0x90 | 0x47 |
| lights.deck1.cue | 0x90 | 0x0C |
| lights.deck1.shiftCue | 0x90 | 0x48 |
| lights.deck2.vuMeter | 0xB0 | 0x02 |
| lights.deck2.playPause | 0x91 | 0x0B |
| lights.deck2.shiftPlayPause | 0x91 | 0x47 |
| lights.deck2.cue | 0x91 | 0x0C |
| lights.deck2.shiftCue | 0x91 | 0x48 |

## Funções JS

- `PioneerDDJ400.beatFxChannel`
- `PioneerDDJ400.beatFxLeftPressed`
- `PioneerDDJ400.beatFxLevelDepthRotate`
- `PioneerDDJ400.beatFxOnOffPressed`
- `PioneerDDJ400.beatFxOnOffShiftPressed`
- `PioneerDDJ400.beatFxRightPressed`
- `PioneerDDJ400.beatFxSelectPressed`
- `PioneerDDJ400.beatFxSelectShiftPressed`
- `PioneerDDJ400.beatjumpPadPressed`
- `PioneerDDJ400.changeFocusedEffectBy`
- `PioneerDDJ400.cueLoopCallLeft`
- `PioneerDDJ400.cueLoopCallRight`
- `PioneerDDJ400.cycleTempoRange`
- `PioneerDDJ400.decreaseBeatjumpSizes`
- `PioneerDDJ400.focusedFxGroup`
- `PioneerDDJ400.increaseBeatjumpSizes`
- `PioneerDDJ400.init`
- `PioneerDDJ400.jogSearch`
- `PioneerDDJ400.jogTouch`
- `PioneerDDJ400.jogTurn`
- `PioneerDDJ400.loopToggle`
- `PioneerDDJ400.quickJumpBack`
- `PioneerDDJ400.quickJumpForward`
- `PioneerDDJ400.samplerPadPressed`
- `PioneerDDJ400.samplerPadShiftPressed`
- `PioneerDDJ400.samplerPlayOutputCallbackFunction`
- `PioneerDDJ400.setLoopButtonLights`
- `PioneerDDJ400.setReloopLight`
- `PioneerDDJ400.shiftPressed`
- `PioneerDDJ400.shutdown`
- `PioneerDDJ400.startLoopLightsBlink`
- `PioneerDDJ400.startSamplerBlink`
- `PioneerDDJ400.stopLoopLightsBlink`
- `PioneerDDJ400.stopSamplerBlink`
- `PioneerDDJ400.syncLongPressed`
- `PioneerDDJ400.syncPressed`
- `PioneerDDJ400.tempoSliderLSB`
- `PioneerDDJ400.tempoSliderMSB`
- `PioneerDDJ400.toggleFxLight`
- `PioneerDDJ400.toggleLight`
- `PioneerDDJ400.toggleLoopAdjustIn`
- `PioneerDDJ400.toggleLoopAdjustOut`
- `PioneerDDJ400.toggleQuantize`
- `PioneerDDJ400.trackLoadedLED`
- `PioneerDDJ400.vuMeterUpdate`

## RMX — reuso provisório (0.8.0)

O XML pinado não traz o botão de modo dos pads como entrada MIDI. O aparelho troca localmente quais notas os pads emitem. Esta camada não altera hot cue (notas 0x00–0x07), beat jump (0x20–0x27) nem beat loop (0x60–0x67).

Enquanto o painel RMX está ligado na tela, as notas já listadas de SAMPLER (`PioneerDDJ400.samplerPadPressed`, status 0x97 no deck 1 e 0x99 no deck 2, notas 0x30–0x37) passam a ser RMX:

| Pad | Nota | Gesto |
|---|---|---|
| 1–4 | 0x30–0x33 | segurar escolhe a Scene FX e sobe a intensidade; soltar deixa a cauda |
| 5–8 | 0x34–0x37 | liga ou desliga o X-Pad: Roll, Trans, Drum, Hi-pass |

`PioneerDDJ400.samplerPadShiftPressed` (status 0x98 / 0x9A, as mesmas notas) continua sem função de RMX, para não tomar o eject/stop do mapa.

Com o painel ligado, controles que já existem no XML mudam de papel:

- BEAT FX LEVEL/DEPTH (status 0xB4, nota 0x02) define a intensidade da cena, de 0 a 1.
- Beat esquerda e Beat direita escolhem o comprimento: 1, 2, 4, 8 ou 16 compassos.
- CH SELECT (notas 0x10, 0x11 e 0x14 no status 0x94) escolhe canal 1, 2 ou master.

O isolador de três bandas fica na tela. O XML não muda o CC de EQ quando SHIFT está premido, então os knobs de EQ não viram isolador.

Isto não é um modo do XML. É uma leitura provisória até o item 8 de `docs/hil/checklist-fase1.md`. Sem a controladora, fica `BLOCKED_HARDWARE`.
