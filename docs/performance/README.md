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
