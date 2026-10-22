# RMX — reuso provisório

A matriz `ddj400-control-matrix.md` é o extrato do XML pinado e não leva texto fora desse extrato. Este arquivo é a leitura provisória do app. Não é um modo do XML. Sem a controladora, fica `BLOCKED_HARDWARE`. O item 8 de `docs/hil/checklist-fase1.md` é o teste.

O XML pinado não traz o botão de modo dos pads como entrada MIDI. O aparelho troca localmente quais notas os pads emitem. Esta camada não altera hot cue (notas 0x00–0x07), beat jump (0x20–0x27) nem beat loop (0x60–0x67).

Enquanto o painel RMX está ligado na tela, as notas já listadas de SAMPLER (`PioneerDDJ400.samplerPadPressed`, status 0x97 no deck 1 e 0x99 no deck 2, notas 0x30–0x37) passam a ser RMX:

| Pad | Nota | Gesto |
|---|---|---|
| 1–4 | 0x30–0x33 | segurar escolhe a Scene FX e sobe a intensidade; soltar deixa a cauda |
| 5–8 | 0x34–0x37 | liga ou desliga o X-Pad: Roll, Trans, Drum, Hi-pass |

Com o painel RMX aberto, `PioneerDDJ400.samplerPadShiftPressed` (status 0x98 / 0x9A, as mesmas notas) escolhe o drum build. Neste app essas notas não ejetam faixa.

| Shift+pad | Nota | Gesto |
|---|---|---|
| 1–4 | 0x30–0x33 | som: kick, clap, snare, kick+clap. Liga o Drum |
| 5–7 | 0x34–0x36 | comprimento: 4, 8 ou 16 compassos |
| 8 | 0x37 | liga ou desliga o silêncio do último meio tempo |

Com o painel ligado, controles que já existem no XML mudam de papel:

- BEAT FX LEVEL/DEPTH (status 0xB4, nota 0x02) define a intensidade da cena, de 0 a 1. Com o X-Pad Drum ligado, o mesmo knob é o quanto o roll sobe de volume, filtro e, se a tela pedir, de afinação.
- Beat esquerda e Beat direita escolhem o comprimento: 1, 2, 4, 8 ou 16 compassos.
- CH SELECT (notas 0x10, 0x11 e 0x14 no status 0x94) escolhe canal 1, 2 ou master.

O isolador de três bandas fica na tela. O XML não muda o CC de EQ quando SHIFT está premido, então os knobs de EQ não viram isolador.
