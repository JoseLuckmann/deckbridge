# ADR-0005 — Rescan da fonte sem reiniciar o áudio

**Data:** 2026-10-21  
**Estado:** aceite para a fase de software. Não é prova no Mixxx em execução.

## Contexto

A T025 pede para a fonte USB aparecer num set que já está tocando, sem zerar o áudio. O ponto SP-003 perguntava se existe um controle Mixxx para disparar o rescan antes de qualquer patch em C++.

## O que foi lido no pin 2.5.6

Commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56`.

- Em `src/library/librarycontrol.cpp` deste commit não há controle `[Library],Rescan` nem o texto `Rescan`.
- Em `src/library/scanner/libraryscanner.h`, `LibraryScanner::scan()` é um slot do próprio processo (linha 37 desse arquivo na tag).
- Em `src/library/rekordbox/rekordboxfeature.h`, `refreshLibraryModels()` e `onRekordboxDevicesFound()` também são slots internos.

Não foi compilado o Mixxx e o binário não está nesta máquina. O comportamento em execução continua desconhecido.

## Decisão

Nesta fase o pedido de rescan só registra a intenção:

- não reinicia o áudio;
- não cita um controle que o fonte não publica (`mixxx_control` fica vazio);
- não aplica patch C++;
- `hardware_confirmed` fica falso.

O código está em `src/deckbridge/rescan.py`. Quando houver processo Mixxx de verdade, a ligação será um gancho interno, não um nome de controle inventado.

## Consequência

A T025 de software está limitada a esse aviso. Ver a fonte dentro do Mixxx, com música tocando, continua pendente de hardware e do binário.
