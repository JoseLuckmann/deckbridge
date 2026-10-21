# Simulação — plano, ainda sem ambiente montado

A DDJ-400 e o Raspberry só entram no fim do projeto. Esta trilha existe para testar o máximo possível antes disso. **Um resultado simulado não é prova no hardware** e não pode marcar tarefa HIL como `PASS` nem fechar G0 ou G1. A regra está em `src/deckbridge/evidence.py`.

Nesta rodada o ambiente em si não foi construído. Ficou o plano e o roteiro final.

| ID | O que será | Estado |
|---|---|---|
| T075 | Controladora virtual que fala MIDI pelo mapa pinado, via ALSA `snd-virmidi` ou equivalente | TODO |
| T076 | Dispositivo de áudio virtual de 4 canais: master 1–2, fone 3–4 | TODO |
| T077 | Rodar os perfis da OD-01 em emulação ARM (`arm64` e `armhf`, QEMU ou container) | TODO |
| T078 | Roteiro numerado para o hardware real, uma vez no fim | documento escrito; execução `BLOCKED_HARDWARE` |
| T079 | Regra: `SIMULATED` não promove tarefa HIL nem fecha gate | registrada no código e nos testes |

T077, quando existir, mostra que o programa roda naquela arquitetura. Não mostra que um Raspberry toca.
