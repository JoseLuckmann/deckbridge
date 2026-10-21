# O que fazer com o aparelho na mão — Fase 0

Isto ainda não foi feito. Siga na ordem. Não escreva no pendrive das músicas.

1. Use a fonte do próprio Raspberry. Não pegue uma fonte qualquer.
2. Ligue a DDJ-400 num cabo USB-B, com fones na saída de fone e caixas na saída master.
3. Se tiver tela, qualquer resolução serve. A referência do projeto é 7 polegadas, 800x480 ou 1024x600.
4. No aparelho, com o código deste repositório:

```bash
python3 -m deckbridge.hil_collect --out hil-aparelho.json
```

5. Confira no JSON: modelo, RAM e firmware da DDJ. O que o script não conseguir ler fica `UNKNOWN`. Não preencha no chute.
6. A firmware da DDJ não sai sozinha desse comando. Anote a versão mostrada no utilitário oficial da Pioneer/AlphaTheta, num computador, se você tiver esse número. Se não tiver, deixe `UNKNOWN`.
7. Instale o Mixxx 2.5.6 como em `docs/setup/arm64-pipeline.md`. Abra o programa, escolha a DDJ-400 como som e como controle, carregue `fixtures/audio/tone-440hz-1s-44100.mp3` num deck e `fixtures/audio/tone-440hz-1s-48000.wav` no outro.
8. Ouça se o master sai nas caixas e o cue sai no fone. Quinze minutos com os dois tocando é a prova do marco G0. Grave a saída de:

```bash
python3 -m deckbridge.benchmark --duration 900 --interval 5 --out bench-aparelho.json
```

9. Não marque a prova como feita se o som falhou, se a fonte esquentou demais ou se você não ouviu o fone separado das caixas.
10. O pendrive preparado no rekordbox é outro passo (tarefa T014). O diretório `fixtures/usb-audio-only/` é só um tom de teste, não uma biblioteca rekordbox.
