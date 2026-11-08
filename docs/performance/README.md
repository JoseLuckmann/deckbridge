# Desempenho

Leitura completa: `relatorio-cpu-ram.md` (perfil por módulo, memória, UI, estimativa ARMv6 e viabilidade do Pi 1).

## Como medir

```bash
J=$(dirname $(dirname $(readlink -f $(which javac))))
g++ -std=c++17 -O2 -pthread -I tools/host_stubs -I android/app/src/main/cpp -I $J/include -I $J/include/linux \
    -o /tmp/perf_harness tools/perf_harness.cpp
/tmp/perf_harness scenes     # custo do callback inteiro por cena
/tmp/perf_harness dump x.f32 # 46 cenas gravadas, para comparar antes/depois bit a bit
/tmp/perf_harness tail       # caudas decaindo com os decks parados (denormais)
/tmp/perf_harness alloc      # alocações dentro do callback
```

## Rodada 1 (24/10): P0 a P5

FTZ no callback, memo de pow/exp, cache de coeficientes, linhas de atraso sem `%`, Fdn com LFO em quadratura e linhas
em potência de 2, Beat FX que dorme desligado, grão do WSOLA com tabela de deslocamentos.

Motor inteiro, bloco de 256, 4 canais, 48 kHz, x86 deste host (`perf_harness scenes`, uma rodada antes e uma depois):

| cena | antes ns/quadro | depois ns/quadro |
|---|---|---|
| ocioso (stream aberto, sem faixa) | 168,5 | 61,8 |
| 2 faixas carregadas, paradas | 166,5 | 60,5 |
| típica (2 decks, EQ, filtro, CUE) | 355,1 | 160,4 |
| pior Beat FX (SHIMMER + bandas) | 1747,7 | 1229,3 |
| pior Beat FX (PHASER + bandas) | 1512,1 | 1027,3 |
| pior Beat FX (FILTER + bandas) | 1512,9 | 992,5 |
| pior RMX (cena 2 + drum build) | 2216,2 | 1522,2 |

- Saída: as 30 123 008 amostras do dump diferem só pelo LFO novo do Fdn (P2): −58,4 dB RMS relativo, máximo 0,0065. O resto é bit a bit igual (medido pela análise com P2 isolado).
- Master Tempo (`mt_test sintetico`): mesmas métricas de afinação e pureza, custo de 0,90 % para 0,47 % de um núcleo.
- Testes: `fx_rmx_test` 87/0 (novo: Beat FX dorme e volta igual ao código anterior), `console_test` 40/0, `fx_measure` 13/0, `dsp_test` ALL PASS, `engine_host_test` 0 falhas, ASan+UBSan no fuzz 0 falhas, e o APK compila para arm64, armeabi-v7a e x86_64.
- Não medido no tablet nem num Pi (`BLOCKED_HARDWARE`). Os números do Pi no relatório são estimativas.

## Rodada 2 (24/10): nada pesado no áudio, metade da RAM

Medido neste host (`perf_harness alloc` e `perf_harness mem`):

| item | antes | depois |
|---|---|---|
| alocações no 1º callback | 50 (3,5 MB) | 0 |
| alocações no 1º uso do RMX | 22 (1,4 MB), bloco de 4,4–8,7 ms | 0 |
| lock do áudio: release (`setRmx`) | 159 µs | 1,2 µs |
| lock do áudio: troca de SEND | 80 µs | 14 µs |
| lock do áudio: troca de Beat FX | 26 µs | 4 µs |
| lock do áudio: carregar FLAC de bateria | 90–532 µs por voz | só a troca do vetor |
| RSS do motor com 2 faixas de 6 min | 254,6 MB | 133,5 MB |
| pico do motor ao carregar 2 faixas | 375,7 MB | 254,6 MB |

O que mudou:
- `MixerFx::prepareAll` em `start()`, antes de o stream tocar: Beat FX, cenas, release, X-PAD e o banco de bateria ficam prontos fora do callback.
- `DelayLine::clear()` agora é O(1): um contador de amostras escritas faz a parte velha ler zero, bit a bit igual a uma linha zerada. Antes o release zerava 2×96 000 floats com o lock do áudio seguro.
- O FLAC de bateria é decodificado fora do lock; dentro dele só a troca do vetor.
- Faixa do deck em int16. Para fonte de 16 bit é bit a bit igual (`dsp_test`). Fontes de 24 bit ou float perdem abaixo de −96 dBFS. O JNI converte em pedaços de 16 k quadros, sem cópia float do tamanho da faixa.
- Saída das 46 cenas do dump bit a bit igual à rodada 1, com o preparo antecipado e o clear O(1).
- Kotlin:
  - O catálogo do app (`loadRekordboxLight`) não guarda as ondas de detalhe. No volume sintético isso tira 1,26 MB de 26 faixas; a 0,5 MB por faixa de 6 min, uma biblioteca de 1000 faixas deixa de ocupar ~0,5 GB (estimativa).
  - O detalhe da faixa carregada é relido do ANLZ dela e fica só enquanto ela estiver num deck (`CatalogMemoryTest`).
  - O PCM decodificado em Java (127 MB numa faixa de 6 min) fica livre para o GC logo depois de ir para o motor, não no fim da carga.
  - `waveStrip` usa uma interface de `Int` primitivo: sem encaixotar cada coluna, ~0,17 MB a menos por desenho.
  - A grade da faixa é calculada uma vez por faixa ou ajuste (`remember`), não a cada quadro (~0,12 MB por desenho).
- Pré-escuta em int16: 30 s estéreo a 44,1 kHz ocupam 5,3 MB, não mais 10,6 MB.
- Com os dois decks parados, a tela não recompõe mais: a posição só é gravada quando muda. Antes o `DeckPage` inteiro recompunha 12,5 vezes por segundo.
- Os picos dos medidores e dos LEDs ficam num vetor fixo, sem alocar nem mudar estado do Compose 20 vezes por segundo.
- O TSan, rodando agora todos os testes do motor, achou duas corridas:
  1. No stub de teste (`FakeDevice`), que ficou atômico.
  2. Na thread de nova tentativa do motor, que era `detach()`. Ela agora fica guardada e o destrutor espera por ela. No app o motor é um singleton que nunca morre, então lá a corrida não aparecia; no teste, um motor novo nascia no endereço do antigo.
