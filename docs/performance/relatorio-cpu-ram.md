> Relatório da análise de 24/10/2026, feito numa cópia de trabalho a partir de ff80208. Os caminhos `scratchpad/…` eram arquivos temporários da sessão; os diffs que valeram estão nos commits seguintes, e o harness está em `tools/perf_harness.cpp`.

# DeckBridge OS — relatório de CPU e RAM (motor nativo + lado Kotlin)

Data: 2026-10-24. Branch analisada: `claude/cdj-3000-effects-mixing-8adh3b` @ `ff80208` (0.14.1), numa worktree
local (branch `perf-analysis`, nada commitado nem enviado). Nenhum hardware real (tablet, DDJ-400, Pi) foi usado:
**todo teste em aparelho é BLOCKED_HARDWARE**. Nenhum pendrive rekordbox foi tocado.

Convenções:
- **[MEDIDO]** = saída de um programa que rodei aqui (comando e arquivo citados).
- **[ESTIMATIVA]** = conta feita por mim com as hipóteses escritas ao lado. Não é medida.
- Host: Intel Xeon (Sapphire Rapids) 2,1 GHz, KVM, 4 vCPU, g++ 13.3 `-O2`. Os tempos de parede variam ±10–20 % entre
  rodadas (VM compartilhada); por isso também uso **contagem de instruções** (callgrind no x86, qemu+contador de blocos
  no ARMv6), que é determinística.
- Orçamento: bloco de 256 quadros a 48 kHz = 5333 µs; 1 quadro = 20 833 ns.

---

## 0. Resumo (o que importa)

1. **Não há FTZ/DAZ em lugar nenhum.** Com os decks parados e as caudas (eco/reverb/SEND) decaindo, o custo do
   callback **triplica** depois de ~30 s: 1,5 → 4,4 µs/quadro no x86 [MEDIDO, `perf_harness tail`]. Ligando FTZ só no
   callback: 3,9 → 0,75 µs/quadro (5×) [MEDIDO]. No ARM1176/VFP11 subnormal cai em código de suporte do kernel; lá o
   efeito tende a ser bem pior [ESTIMATIVA qualitativa, sem número]. Patch de 30 linhas, saída bit a bit igual em
   todas as cenas do dump.
2. **O callback aloca memória e faz trabalho pesado no 1º uso de vários estados** [MEDIDO, `perf_harness alloc`]:
   1º callback sem `setBeatFx` antes: 50 alocações / 3,5 MB, bloco de 1,98 ms no x86; 1º uso de RMX (SceneFx lazy):
   22 alocações / 1,4 MB, bloco de **4,37 ms no x86** (82 % do orçamento num Xeon); `drumBank().ensureModel` (se faltar
   um FLAC de bateria) gera 1,8–1,9 ms de síntese **dentro do áudio**. Além disso o callback usa `std::mutex`
   bloqueante (`mixMutex_`) dividido com setters que seguram o lock por 33–532 µs no x86 (decodificar FLAC de bateria,
   `ReleaseFx::trigger` 239 µs, troca de SEND 87 µs).
3. **Transcendentais por amostra com argumento constante.** No pior cenário há ~95 chamadas de libm por quadro
   (sinf 34, expf 24, powf 16, tanf 13, cosf 4, tanhf 2, logf 2) [MEDIDO, contagem de chamadas no callgrind]. A maioria
   tem o mesmo argumento amostra após amostra (coeficientes por taxa, knobs parados). Um memo por ponto de chamada é
   **bit a bit igual** e corta 25–27 % do tempo ocioso/típico [MEDIDO].
4. **Fdn (reverb de 8 linhas) é o ponto mais quente**: 191 ns/quadro sozinho, até 5 instâncias ativas no pior caso,
   8 `sinf` por amostra cada. Oscilador em quadratura + linhas em potência de 2: **73 ns** (2,6×) [MEDIDO], diferença
   de saída −58 dB RMS relativo (só pela troca do LFO).
5. **WSOLA (Master Tempo) concentra o custo numa amostra**: o grão novo (a cada 1024 quadros) custa 144–152 µs num
   quadro só, no x86 [MEDIDO]. Patch bit a bit igual (tabela de deslocamentos, sem teste de limite que nunca falha):
   **85 µs** [MEDIDO]. No Pi 1 o pico de um grão é [ESTIMATIVA] 1,3–3,4 ms por deck — os dois decks no mesmo bloco
   passam do orçamento de 5,3 ms. Precisa amortizar o grão em vários blocos.
6. **RAM: 127 MB por faixa de 6 min** (float L+R a 44,1 kHz; 15 876 000 quadros × 8 B) [MEDIDO no host: 2 decks =
   242 MiB de RSS]. Pico ao carregar ≈ 2–3× disso (cópia Java + nativa + faixa antiga). **O catálogo guarda a onda de
   detalhe de TODAS as faixas** do pendrive na heap (≈ 0,5 MB por faixa só de arrays [MEDIDO para a onda gerada pelo
   app]; 1000 faixas ≈ 0,5 GB [ESTIMATIVA]).
7. **UI: cada desenho de deck aloca ~0,17 MB (tira RGB) + 0,12 MB (grade da faixa inteira)** [MEDIDO no JVM do PC],
   a 12,5 Hz × 2 decks ≈ 7 MB/s de lixo [ESTIMATIVA a partir do medido], e a onda anda aos saltos de 80 ms.
8. **Pi 1 Model B**: com o motor atual, o pior caso precisa de ~27 800 instruções ARMv6 por quadro (1,33 G instr/s a
   48 kHz) [MEDIDO a contagem; ESTIMATIVA a taxa]. Um ARM1176 a 700 MHz entrega ~0,28–0,49 G instr/s neste tipo de
   código [ESTIMATIVA, IPC 0,4–0,7]. **Pior caso: 2,7–4,8× acima do possível; mesmo com todos os protótipos,
   1,9–3,3×.** Cena típica (2 decks, EQ, filtro): cabe com os protótipos (27–48 % da CPU) [ESTIMATIVA]. Ver §6.

Resultado agregado dos protótipos (todos juntos, `all-prototypes.diff`), mesmas cenas:

| cena | x86 instr/quadro antes → depois [MEDIDO] | ARMv6 instr/quadro antes → depois [MEDIDO] | x86 p50 bloco 256, antes → depois [MEDIDO] |
|---|---|---|---|
| ocioso (stream aberto, sem faixa) | 2 295 → 1 040 (−55 %) | 2 443 → 1 038 (−58 %) | 38,7 → 16,1 µs |
| típica (2 decks, EQ, filtro, CUE) | 3 769 → 2 481 (−34 %) | 4 236 → 2 798 (−34 %) | 70–72 → 37 µs |
| pior Beat FX (SHIMMER + FX FREQ + SEND reverb + 2×keylock + Color + comp + iso + preview) | 17 475 → 13 113 (−25 %) | 21 581 → 14 039 (−35 %) | 335 → 206 µs |
| pior RMX (cena 2 SPIRAL UP + drum build + iso gate + resto) | 22 243 → 17 515 (−21 %) | 27 803 → 19 138 (−31 %) | 475–488 → 332 µs |

Testes com todos os protótipos: `fx_rmx_test` 86 PASS/0 FAIL, `console_test` 40/0, `engine_host_test all` 50/0
(inclui `blocks` bit a bit entre tamanhos de bloco, fuzz e 4 threads), `mt_test sintetico` mesmas métricas de
afinação/pureza [MEDIDO, `tests.sh cur all`]. O binário compilado para ARMv6/VFPv2 deu **saída bit a bit igual** à do
x86 nas 30 M amostras do dump [MEDIDO, qemu-arm `-cpu cortex-a7`; ver §2].

---

## 1. Perfil de CPU do callback (x86, -O2)

### 1.1 Ferramentas que escrevi

- `tools/perf_harness.cpp` (na worktree; cópia em `scratchpad/perf_harness.cpp`): inclui o `engine.cpp` real com os
  stubs de `tools/host_stubs`, abre o stream falso (`FakeDevice`, 4 canais, 48 kHz) e chama `onAudioReady` em blocos
  de 256. Modos: `scenes`, `modules`, `denormal`, `tail`, `hop`, `alloc`, `mem`, `loaddrum`, `drummodel`, `dump`
  (saída crua de 46 cenas para comparar bit a bit), `idle|typical|worstbeat|worst` (para callgrind/qemu).
  Faixas sintéticas de 120 s a 44,1 kHz (o deck reamostra para 48 kHz).
  Build: `g++ -std=c++17 -O2 -pthread -I tools/host_stubs -I android/app/src/main/cpp -I $J/include -I $J/include/linux -o ph tools/perf_harness.cpp`.
- `scratchpad/cmpf.cpp` (compara dumps), `cg.sh`/`cgall.sh` (callgrind só dentro de `processAudio`),
  `tbcount.cpp`/`armcount.sh`/`armall.sh` (contagem de instruções ARM no qemu), `tests.sh`.
- `tools/cpu_bench.cpp` (já existia) mede só o `MixerFx` sem decks, sem WSOLA, sem SEND/preview/limiter: média 313–354 µs
  por bloco [MEDIDO], o que subestima o motor inteiro. O harness novo cobre o callback real.

### 1.2 Cenas do motor inteiro, código original [MEDIDO, `ph_base scenes`, 2 rodadas]

| cena | ns/quadro (média) | % do orçamento | p50 bloco | p99 bloco | instr x86/quadro |
|---|---|---|---|---|---|
| ocioso | 158–194 | 0,8–0,9 % | 38,7 µs | 66–106 µs | 2 295 |
| 2 faixas paradas | 159–185 | 0,8–0,9 % | 38,5 µs | 83 µs | — |
| típica | 307–328 | 1,5–1,6 % | 71–72 µs | 134–224 µs | 3 769 |
| pior Beat FX 15 SHIMMER | 1 629–1 671 | 7,8–8,0 % | 332–335 µs | 836–1 195 µs | 17 475 |
| pior Beat FX 7 PHASER | 1 465–1 467 | 7,0 % | 295 µs | 741 µs | — |
| pior Beat FX 13 FILTER | 1 450–1 498 | 7,0–7,2 % | 289–292 µs | 733–906 µs | — |
| pior RMX | 2 120–2 213 | 10,2–10,6 % | 475–483 µs | 900–1 125 µs | 22 243 |

Os máximos isolados (até 15 ms numa rodada) são ruído da VM; não os uso para conclusões.
**Ocioso já custa 2 300 instruções por quadro sem nenhuma faixa**: 5 `Isolator` com `expf` por amostra, o Beat FX
desligado rodando o eco inteiro (para a cauda), `ColorFilter` FILTER com alisadores, limiters.

### 1.3 Microbenchmarks por módulo, código original [MEDIDO, `modules_O2.txt`; ns por quadro estéreo, melhor de 5]

| módulo | ns | | módulo | ns |
|---|---|---|---|---|
| Deck::read direta (pitch 1,02) | 21,1 | | SceneFx cena 0 | 185,9 |
| Deck::read Master Tempo (WSOLA) | 162,2 | | SceneFx cena 2 SPIRAL UP | 360,1 |
| Deck::read Key Shift +2 | 163,5 | | SceneFx cena 3 | 304,7 |
| ChannelComp knob 0,7 | 40,3 | | SceneFx cena 4 | 262,9 |
| Isolator/EQ fora de unity (16 biquads) | 48,4 | | SceneFx cenas 5/6/7 | 77–94 |
| Isolator/EQ unity | 13,6 | | XPad drum build (grade, risers, tanque) | 445,7 |
| Color SPACE | 256,5 | | SendReturn SHORT/LONG DELAY | 72–73 |
| Color DUB ECHO / SWEEP / NOISE / CRUSH / FILTER | 88 / 45 / 59 / 60 / 52 | | SendReturn DUB ECHO | 100,1 |
| Beat FX LOW CUT ECHO / ECHO / PING PONG | 74 / 51 / 52 | | SendReturn REVERB | 233,4 |
| Beat FX SPIRAL | 108,3 | | **Fdn (8 linhas) sozinho** | **191,4** |
| Beat FX REVERB | 200,2 | | GrainShift (1 canal) | 36,0 |
| Beat FX PHASER (8 setAllpass/amostra) | 117,5 | | BandSplit3 (1 canal; FX FREQ usa 4) | 20,9 |
| Beat FX PITCH / HELIX / FILTER | 92 / 70 / 85 | | PeakLimiter | 12,4 |
| **Beat FX SHIMMER** | **258,9** | | PreviewVoice::read | 9,8 |
| Beat FX TRANS/ROLL/BRAKE | 13–22 | | Svf::set (tanf) + process | 16,7 (process só: 8,0) |

### 1.4 Onde vai o tempo no pior caso [MEDIDO, callgrind `cg.worst`, só `processAudio`, 4 s]

`Fdn::process` 20 % (+3,5 % de `sinf` inlinado por cmath), `Deck::read` (WSOLA inlinado) 14 %, `Isolator::process`
12 % (5 instâncias, 80 biquads por quadro com EQ+iso+master fora de unity), `powf` 4,4 %, `readPerformance` 4,3 %,
`XPad::mixShots` 4 %, `tanf` 3,7 %, `expf` 3 %, `sinf` 4,7 %.

### 1.5 Pontos quentes e achados por categoria

- **Transcendentais por amostra** (114 chamadas de `std::exp/pow/tan/cos/sin/log10/tanh/sqrt` em `dsp.h`):
  - Constantes por taxa recalculadas a cada amostra: `Isolator::process` (`exp`), `ChannelComp` (3 `exp`),
    `MotorGain::next`, `ScratchFollower::step` (`exp` em **double**), `SceneFx::dark`, `ReleaseFx`, `XPad` (vários),
    `ColorFilter::spaceFx`/`dubFx` (2–4 `exp` cada), `SendReturn` (hp), `BeatInsert::reverbPair` (hp) e `glide`.
  - Coeficientes de filtro refeitos por amostra com o knob parado: `Svf::set` (`tanf`) no LOW CUT ECHO, no FILTER do
    Color, nos filtros das cenas RMX; `mapFilterKnob` (3 `powf`); `Biquad::setAllpass` (cos+sin ×8 no PHASER);
    `XPad::process` modo 3 (`setHighpass` por amostra); curvas `pow(x, 1.35)` etc. das cenas.
  - Variáveis de verdade (LFO, rampa do drum build, envelope do COMP com `log10f`+`powf`): não caem com memo; pedem
    taxa de controle (a cada 16–32 amostras, contada por um relógio global para manter a equivalência entre tamanhos de
    bloco) — **não** prototipado.
- **Denormais**: nenhum `FTZ`/`DAZ`/`FPSCR`/`FPCR` no código. Há `flushDenorm` em alguns estados (Svf, Biquad, lp do
  Fdn), mas **não nas linhas de atraso** (DelayLine, linhas do Fdn, difusores). Resultado no §0, item 1.
- **double onde float bastaria**: posição do deck em double é correta (faixa de 15 min > 2²⁴ quadros) — manter. Já o
  WSOLA acumula `dot`/`energy` em double (512 termos × 118 deslocamentos por grão) e `ScratchFollower` usa `exp` double
  por amostra. No ARMv6/VFP11 double custa mais que float [ESTIMATIVA qualitativa].
- **Divisão inteira em laço interno**: `% size` por leitura/escrita em `DelayLine`, `Fdn` (3 por linha por amostra),
  `GrainShift`. Trocados por comparação no P1 (bit a bit igual). No ARMv6 não há instrução de divisão inteira (vira
  chamada `__aeabi_idivmod`) [fato da ISA ARMv6].
- **Ramos**: o laço por quadro passa por ~20 módulos com `switch`/`if` por amostra (efeito, cena, modo); não há
  chamadas virtuais no caminho de áudio (só `onAudioReady`). O custo maior é falta de processamento em bloco (ver P9).
- **Alocações no callback** [MEDIDO, operador `new` contado só durante `onAudioReady`]:

  | situação | alocações | bytes | pior bloco x86 |
  |---|---|---|---|
  | 1º callback sem `setBeatFx` antes (`MixerFx::setSampleRate` prepara SEND, Color×2, master iso; `BeatInsert::prepare` lazy) | 50 | 3 511 696 | 1 978 µs (bloco 0) |
  | cena típica (BeatInsert lazy) | 14 | 885 156 | 1 891 µs |
  | 1º uso de RMX (`SceneFx::prepare` só é chamado dentro de `process`) | 22 | 1 430 372 | **4 373 µs** (bloco 0) |
  | Beat FX já configurado | 0 | 0 | — |

  E ainda: `XPad::fire` → `drumBank().ensureModel` gera kick/clap/snare/hats dentro do áudio se faltar um FLAC:
  1 770–1 907 µs [MEDIDO, 3 rodadas]. O app carrega os 5 FLAC no início (`MainActivity`), então isso só acontece se a
  leitura do asset falhar.
- **Locks**: `processAudio` faz `std::lock_guard<std::mutex> lock(mixMutex_)` pelo bloco inteiro; ~40 setters JNI
  usam o mesmo mutex. Tempo com o lock seguro, no x86 [MEDIDO]: `loadDrum` 90–532 µs por voz (decodifica FLAC dentro
  do lock), `setRmx` com release 239 µs (`ReleaseFx::trigger` faz `assign`+`clear` de 2×96 000 floats), troca de SEND
  87 µs, troca de Beat FX 52 µs, 1º `setBeatFx` 33 µs. Na thread de UI (prioridade normal) segurando o lock, a thread
  de áudio espera: inversão de prioridade. Em ARMv6 os mesmos setters custariam [ESTIMATIVA, fator 15–40×] 0,5–21 ms.
  `glitches_` é contado pelo callback, então seria visível no `GlitchWatch` do app.
- **Atômicos de 64 bits** (`record.h`: `frames`, `dropped`): com `-march=armv6` o link falha (`__atomic_load_8`); com
  `-march=armv6kz` usa `ldrexd`. Em ARMv6 puro iriam para `libatomic` com lock → não lock-free no callback [MEDIDO o
  erro de link; consequência é leitura do código].
- **Assimetria no insert** (achado de correção, não de desempenho): `BeatInsert::process` faz `sanitize()` (corte duro
  em ±1) no canal que recebe o insert, mesmo com o efeito desligado; o outro canal não é cortado. Com TRIM +12 dB o
  canal do insert pode cortar antes do fader. Vale um teste/decisão à parte.
- **Perfil de capacidade não usado**: `classifyCapability` calcula `heavyEffects`/`waveforms`, mas só vai para o
  relatório de diagnóstico; nada desliga efeitos nem a onda em aparelho fraco.

---

## 2. ARMv6 (Pi 1) — compilação cruzada e estimativa

Feito [MEDIDO]:
- `apt-get install g++-arm-linux-gnueabihf qemu-user` (pacotes Ubuntu 24.04 assinados; sem `curl|sh`).
- `arm-linux-gnueabihf-g++ -O2 -marm -march=armv6kz -mfpu=vfp -mfloat-abi=hard -static` compila o harness inteiro
  (`-march=armv6` exige `-latomic`, ver §1.5).
- `qemu-arm -cpu arm1176` **não roda** o binário: SIGILL em `_start`, porque a libc/crt do Ubuntu armhf é ARMv7
  Thumb-2. Para rodar no Pi 1 é preciso sysroot ARMv6 (Raspberry Pi OS 32 bit / Raspbian), não disponível aqui.
- Com `qemu-arm -cpu cortex-a7` (nosso código é ARMv6/VFPv2; só a libc é ARMv7) o dump das 46 cenas sai **bit a bit
  igual ao do x86** (30 123 008 amostras, 0 diferentes) — correção do código ARMv6/VFPv2, sem FMA. O tempo do qemu
  (5 min 29 s) não diz nada sobre o Pi.
- Contagem de instruções ARM executadas por quadro (qemu `-d in_asm,exec,nochain` + `tbcount`, que separa ARM/Thumb
  pelos símbolos `$a/$t`; diferença entre 41 e 1 bloco ÷ 10 240 quadros): tabela do §0. libm é a do Ubuntu (Thumb-2);
  a do Raspbian é ARM e pode diferir um pouco na contagem.

Estimativa para o Pi 1 Model B [ESTIMATIVA — hipóteses]:
- CPU: ARM1176JZF-S 700 MHz, emissão simples, sem NEON, VFP11. Hipótese de IPC para este código escalar em float com
  muitos loads dependentes: **0,4–0,7** → 280–490 M instr/s. Não há medição neste repositório que confirme; serve
  para ordem de grandeza.
- Pressupõe saída a 48 kHz; a 44,1 kHz tudo cai 8 %.
- Sobra para kernel, ALSA/USB, MIDI e interface: nenhuma (a conta usa 100 % da CPU para o áudio).

| cena | instr ARM/quadro | M instr/s a 48 kHz | fração de um Pi 1 (IPC 0,7 … 0,4) |
|---|---|---|---|
| ocioso, original | 2 443 | 117 | 24 % … 42 % |
| ocioso, protótipos | 1 038 | 50 | 10 % … 18 % |
| típica, original | 4 236 | 203 | 41 % … 73 % |
| típica, protótipos | 2 798 | 134 | 27 % … 48 % |
| pior Beat FX, protótipos | 14 039 | 674 | 138 % … 241 % |
| pior RMX, original | 27 803 | 1 335 | 272 % … 477 % |
| pior RMX, protótipos | 19 138 | 919 | 188 % … 328 % |

Fator de escala de tempo x86 → Pi 1 [ESTIMATIVA]: este Xeon roda ~2 295 instr/quadro do ocioso em ~157 ns
(≈ 14,6 G instr/s efetivos, medido); o Pi 1 a 280–490 M instr/s dá **~30–50× mais lento** por instrução, e ainda há
~10–25 % mais instruções no ARM (tabela do §0). Logo o pico do grão WSOLA (85 µs no x86 com P5) vira **~3–5 ms por
deck** no Pi 1 [ESTIMATIVA] — dois decks no mesmo bloco estouram o orçamento mesmo sem efeitos.

Para o Galaxy Tab S6 Lite (Exynos 9611: 4× Cortex-A73 2,3 GHz + 4× A53) [ESTIMATIVA, não medido, sem aparelho]:
o pior caso medido aqui (6,7 % de um núcleo Xeon com os protótipos, 10,6 % sem) deve ficar em ordem de 15–40 % de um
A73, com folga; o risco real no tablet são os picos (alocações, lock, grão WSOLA, denormais), não a média.
**BLOCKED_HARDWARE** até medir com `glitches_`/systrace no aparelho.

---

## 3. Memória

### 3.1 Como a faixa fica na RAM (leitura do código + medida no host)

Fluxo de carga (`DeckPage.loadTrack` → `AudioDecode.decodeTrackAudio` → `NativeEngine.loadDeck`):
1. `source.read(path)` → arquivo comprimido inteiro num `ByteArray` (MP3 320 kbps de 6 min ≈ 14,4 MB
   [ESTIMATIVA pela taxa]); para MP3/FLAC/AAC ainda é escrito num arquivo temporário em `cacheDir` para o `MediaExtractor`.
2. `PcmAccumulator`: dois `FloatArray` (L, R) na taxa do arquivo, capacidade = duração estimada + 0,5 s; cresce
   1,25× com cópia se a estimativa errar.
3. `nativeLoadDeckStereo`: `GetFloatArrayRegion` para dois `std::vector<float>` novos (fora do lock, bom).
4. `installDeck` troca os vetores; a faixa antiga é liberada depois da troca (pico: antiga + nova).
5. Se faltar onda de detalhe, `wavesFromPcm(left)` roda sobre o canal esquerdo.
6. O `DecodedPcm` Java fica vivo até o fim da corrotina e depois espera o GC.

Faixa de 6 min a 44,1 kHz [conta exata]: 15 876 000 quadros × 2 canais × 4 B = **127,0 MB** (121,1 MiB) no nativo, e
o mesmo no Java até o GC. Host [MEDIDO, `ph_cur mem`]: motor sem faixa 6,6 MB RSS; +10 callbacks (preparos lazy)
10,2 MB; 2 faixas de 6 min carregadas: **252,4 MB** RSS; pico durante a carga com a cópia "Java" viva: 373,5 MB.

Pico no tablet ao carregar o deck 2 com o deck 1 tocando [ESTIMATIVA, soma das cópias acima]:
deck 1 (127) + faixa antiga do deck 2 (127) + nova nativa (127) + Java L/R (127 + folga) + bytes comprimidos (14)
≈ **520–550 MB**, mais 1,25× do `PcmAccumulator` se a duração estimada vier curta. `MAX_TRACK_MINUTES = 15` → até
~318 MB por faixa a 44,1 kHz (o comentário do código fala em 330 MB a 48 kHz).

### 3.2 Buffers fixos do motor [conta a partir do código; total conferido com RSS]

| item | tamanho |
|---|---|
| `sizeof(ToneEngine)` [MEDIDO] | 1,29 MB (Wsola 47 KB por deck, GrainShift 16 KB cada, etc.) |
| BeatInsert: linhas 2×96 000 + pre + Fdn + 6 GrainShift | ≈ 0,96 MB |
| SceneFx: 2×96 000 + Fdn + 2 GrainShift | ≈ 0,88 MB |
| ReleaseFx: 2×96 000 | 0,77 MB |
| ColorFilter ×2: Fdn + dub 2×96 000 cada | ≈ 1,7 MB |
| SendReturn: 2×96 008 + Fdn | ≈ 0,85 MB |
| XPad (tanque + Haas) + DrumBank (~81 k amostras) | ≈ 0,4 MB |
| PreviewVoice: 30 s estéreo float a 44,1 kHz | 10,6 MB (+ a cópia Java do trecho) |

Total de efeitos ≈ 5,6 MB [ESTIMATIVA por conta; o salto medido de 6,6 → 10,2 MB cobre a parte preparada no início].
Para o tablet é pouco; para o Pi 1 (512 MB divididos com a GPU) a pré-escuta de 30 s em float é cara.

### 3.3 Catálogo e análise (Kotlin)

- `loadRekordbox` monta `analyses = tracks.associate { analysisFor(...) }`: lê `.DAT/.EXT/.2EX` de **todas** as faixas
  ao montar o pendrive e guarda as ondas de detalhe (PWV5 cor, PWV7 3 bandas, PWV3 azul) + grade + cues + frases de
  cada uma. Se a faixa não tem onda, `fillMissingWave` **decodifica o WAV** dela na montagem.
  A onda que o próprio app gera para 6 min ocupa ~537 KB em arrays [MEDIDO, `kbench`: 54 000 colunas de detalhe];
  as do rekordbox são da mesma ordem (150 colunas/s). 1 000 faixas ≈ 0,5 GB de heap [ESTIMATIVA] — acima do
  `largeHeap` típico de um tablet de 4 GB e impossível no Pi.
- `analyzeTrack` (pasta sem rekordbox) leva **6,6–8,8 s por faixa de 6 min no JVM do PC** [MEDIDO]; roda numa
  `Thread` separada (não bloqueia a UI). No Pi 1 seriam minutos por faixa [ESTIMATIVA, fator 30–50×].
  `wavesFromPcm` 6 min: 468–588 ms [MEDIDO]. `decodeTrackPcm` cria ainda um mono do tamanho da faixa (63 MB).
- `PcmAccumulator.append` amostra a amostra: 71–93 ms para 6 min no JVM; a versão em bloco com `asShortBuffer().get`
  não foi mais rápida (105–145 ms) [MEDIDO]. **Não** recomendo mudar isso.

### 3.4 Propostas de memória

1. **int16 no motor** (−50 %: 63,5 MB por faixa de 6 min). Interpolação linear sobre int16 convertido na leitura
   (`x * (1/32768.f)`) é exata em relação ao PCM de origem quando o arquivo já é 16 bit (MP3/AAC decodificam para 16 bit
   no `MediaCodec` deste app: `PcmAccumulator` lê `short`) — então **não há perda nenhuma** para MP3/AAC/WAV 16; só
   FLAC/WAV 24 bit perderiam 8 bits (−96 dB, abaixo do ruído do DAC da DDJ-400 [ESTIMATIVA]). Scratch, loop, roll e
   WSOLA leem pela mesma `readInterp`: muda só a função de leitura. Bit a bit: igual para fontes 16 bit se a conversão
   for `s/32768.f` (a mesma do `PcmAccumulator`). Custo de CPU: uma conversão int→float por leitura (pequeno).
2. **Não guardar a cópia Java**: decodificar direto para um buffer nativo (`ByteBuffer.allocateDirect` ou JNI
   incremental em blocos de 1 s) e soltar `bytes`/`DecodedPcm` antes de instalar. Pico −127 MB.
3. **Streaming / mmap para o Pi**: guardar o PCM decodificado num arquivo de cache em disco local (nunca no pendrive)
   e ler por `mmap` com read-ahead em thread separada; o callback só lê páginas já residentes (precisa de um anel de
   pré-carga ao redor da posição, hot cues e loops — mais complexo; o scratch exige alguns segundos residentes em volta
   da agulha). RAM residente por deck vira dezenas de MB.
4. **Catálogo preguiçoso**: no catálogo só grade + cues + *preview* (1 200 colunas); a onda de detalhe só da faixa
   carregada nos decks (LRU de 2–4 faixas). Sem decodificar WAV na montagem.
5. **Efeitos sob demanda**: hoje são preparados no 1º `setSampleRate` (Color×2, SEND) ou no 1º uso (Beat, cena,
   release). Para o Pi: alocar cada linha só para o efeito escolhido e liberar fora do áudio; uma linha de 96 000 cobre
   2 s a 48 kHz — as cenas usam 1 batida, pode ser 1 s.
6. **Pré-escuta**: 10 s em vez de 30 s no perfil baixo, ou tocar do mesmo cache mmap do item 3.
7. **LOD da onda**: guardar o detalhe em 2 níveis (150/s e 37,5/s) e desenhar do nível que dá ~1 coluna por pixel.

---

## 4. Kotlin / UI

Leitura do código (`DeckPage.kt`, `WaveCanvas.kt`, `WaveColor.kt`, `PlayerModel.kt`) + medida no JVM do PC
(`kbench/UiBench.kt`, compilado com o kotlinc do cache do Gradle; HotSpot, **não é o ART**):

- **Taxa de redesenho**: a posição vem por polling a cada 80 ms (`delay(80)`) e grava `faces` inteiro
  (`faces.mapIndexed { copy(...) }`) → o `DeckPage` recompõe e as duas ondas redesenham a **12,5 Hz**; a onda anda aos
  saltos (com zoom de 16 batidas em 1 000 px, ~21 px por salto [ESTIMATIVA pela geometria]). Outros laços: picos a
  20 Hz (aloca `FloatArray` e uma `List` por volta), LEDs a 20 Hz (monta `LedState` com sets/maps), rampa do RMX a 25 Hz.
- **Por desenho de deck** (medido no JVM, 1 000 px, janela de 7,5 s):
  `waveStrip` 3 bandas 37 µs e **68,8 KB** alocados; `waveStrip` RGB 53 µs e **166,7 KB** (5 arrays de 1 000 + caixas
  de `Integer` porque `waveStrip` recebe lambdas `(Int) -> Int` não inline e as cores RGB não cabem no cache de
  `Integer`); `gridLines` para 768 batidas 59 µs e **115 KB** (ordena, mapeia e filtra a faixa inteira a cada quadro);
  `applyGrid` com ajuste 33 µs e 27,8 KB. Soma ≈ 0,3 MB por deck por desenho → ~7 MB/s com 2 decks a 12,5 Hz
  [ESTIMATIVA a partir do medido]. Mais: `Path` novo por banda com 2×pixels pontos (3 por deck), `TextMeasurer.measure`
  de rótulos a cada quadro, `drawLine` por coluna no modo RGB (até 2 400 × 2 linhas).
- Nada de análise pesada na thread principal: decodificação e análise já rodam em `Dispatchers.Default`/`Thread`.

Propostas (sem protótipo; ganhos são estimativas):
1. `waveStrip` `inline` (remove as caixas) e `remember(beats, bpm, duration, gridEdit)` para `gridLines`/`applyGrid`,
   recortando só a janela visível por busca binária → de ~0,3 MB para ~20 KB por desenho.
2. **Onda pré-renderizada em ladrilhos** (`ImageBitmap` de, p. ex., 512 px por ladrilho, por nível de zoom), gerados
   fora da thread principal quando a faixa carrega; o quadro só faz `drawImage` com deslocamento + marcadores.
3. **Posição interpolada por quadro**: ler posição+taxa do motor (um `positionSeconds` com carimbo de tempo) e
   extrapolar dentro de `withFrameNanos` desenhando só o deslocamento dos ladrilhos; isolar a leitura de posição num
   `State` lido só dentro do `Canvas` (fase de desenho), sem recompor o `DeckPage`.
4. **Níveis de qualidade**, ligados ao `RuntimeProfile` que já existe: high = 60 Hz RGB; mid = 30 Hz 3 bandas;
   low/Pi = 15 Hz azul, sem rótulos de frase, LEDs a 10 Hz.

---

## 5. Patches priorizados

Diffs (aplicar em ordem; os scripts geram os mesmos diffs sobre `ff80208`):
- `scratchpad/p1-bitexact.diff` — P1 (memo + caches + módulos), sem o atalho do Isolator.
- `scratchpad/all-prototypes.diff` — tudo o que está na worktree: P1, P1b, P2, P3, P4, P5 (+ FTZ em `engine.cpp`).
- `scratchpad/wsola-fast-not-bitexact.diff` — P5b (não incluído no `all-prototypes`).
- Scripts: `patch_p1.py`, `patch_p2_fdn.py`, `patch_p2_sleep.py`, `patch_p2_fdnfast.py`, `patch_wsola.py exact|fast`.
- Harness: `tools/perf_harness.cpp` na worktree (cópia `scratchpad/perf_harness.cpp`).

Verificação usada em todos: `perf_harness dump` antes/depois + `cmpf` (bit a bit ou erro RMS), `fx_rmx_test`,
`console_test`, `engine_host_test all` (o teste `blocks` compara blocos de 64 a 4096 entre si, então continua válido
mesmo quando a saída muda em relação à versão antiga), `mt_test sintetico` para o WSOLA.

| # | o quê | ganho | risco para o áudio | bit a bit vs. original | como verificar |
|---|---|---|---|---|---|
| **P0** | **FTZ/DAZ no callback** (`DenormalGuard` em `onAudioReady`: MXCSR no x86, FPCR.FZ no arm64, FPSCR FZ+DN no ARMv7/v6) | cauda com decks parados: 3,9 → 0,75 µs/quadro (5×) [MEDIDO]; no ARMv6 provavelmente bem maior [ESTIMATIVA] | baixo; só muda resultados na faixa subnormal (< 1,2e-38) | **igual** nas 46 cenas do dump | `perf_harness tail`, dump |
| **P1** | memo de pow/exp por ponto de chamada (`MathMemo`), cache de coeficientes de `Svf`/`Biquad`, sem `%` nas linhas de atraso e no Fdn, `exp` double do scratch fora do laço | ocioso −27 %, típica −25 %, pior −13–19 % de tempo [MEDIDO p50] | muito baixo (funções puras) | **igual** | dump, testes |
| **P1b** | `Isolator` "assentado": em unity com o alisamento parado, pula a conta (5 instâncias) | idem acima, incluso; unity 13,6 → 4,7 ns [MEDIDO] | muito baixo | **igual** | dump |
| **P2** | Fdn: LFO em quadratura (sem 8 `sinf`/amostra) | Fdn 191 → 147 ns [MEDIDO, com P1] | baixo: modulação ±6 amostras, o LFO original já tinha erro de frequência por acumular fase em float | **diferente**: −58,4 dB RMS relativo, máx. 0,0065 | dump/cmpf, ouvir reverb/SPACE/SHIMMER |
| **P3** | Fdn: linhas em potência de 2 + máscara, índice inteiro/fração, guarda NaN numa comparação | Fdn 147 → 73 ns; SPACE 257 → 104; SHIMMER 259 → 154; SEND REVERB 233 → 106 [MEDIDO] | baixo | igual ao P2 (0 diferenças P2 vs P3); +~40 KB por Fdn | dump |
| **P4** | Beat FX desligado e com cauda zerada "dorme" (saída = `sanitize(seco)`, como hoje); linhas zeradas aos poucos (64 amostras/quadro) | ocioso p50 28 → 14 µs, típica 53 → 38 µs [MEDIDO] | médio-baixo: ao religar depois de dormir a linha começa limpa (hoje tocaria um resto já < tailMix 1e-5) | **igual** no dump (saída igual enquanto dorme) | dump, `fx_rmx_test`, teste novo de religar |
| **P5** | WSOLA: tabela de deslocamentos e sem teste de limite (que nunca falha: índice máx. = 2·kSearch + ⌊1022·rate + 0,5⌋ < length) | pico do grão 152 → 85 µs; módulo 162 → 102 ns [MEDIDO] | muito baixo | **igual** | dump, `mt_test` |
| P5b | WSOLA: busca grossa com metade dos termos, acumulação float | pico 85 → 63 µs [MEDIDO]; média sem ganho claro | médio: outro alinhamento em 2,3 % das amostras (máx. 0,116) | diferente, −43,6 dB | `mt_test` (iguais), ouvir. **Não recomendo** agora |
| P6 | **Sem alocação nem preparo no callback**: preparar Beat/Scene/Release/Send/Color/XPad/DrumBank em `start()` (fora do lock) ou em thread de controle; `ensureModel` no carregamento | remove blocos de 2–4,4 ms no x86 [MEDIDO] | baixo | igual | `perf_harness alloc` → 0 alocações |
| P7 | **Tirar o mutex do callback**: fila SPSC de comandos (setters escrevem, o callback aplica no início do bloco); trabalho pesado (FLAC, `assign`, `clear`) preparado fora e trocado por ponteiro; `try_lock` como passo intermediário | elimina inversão de prioridade (33–532 µs de lock no x86) | médio (refatoração do contrato JNI); o teste `threads` e o fuzz cobrem | igual se a ordem dos comandos for preservada no bloco | `engine_host_test threads/fuzz`, TSAN |
| P8 | Amortizar o grão do WSOLA: buscar o próximo alinhamento ao longo dos 1 024 quadros do grão atual (a posição de análise do próximo grão é previsível pelo passo) | tira o pico de 85 µs (Pi: 3–5 ms [ESTIMATIVA]) | médio | diferente | `mt_test`, `hop` |
| P9 | Taxa de controle (a cada 16–32 amostras, relógio global) para coeficientes que mudam sempre: PHASER, FILTER do Beat FX, risers do drum build, rampas das cenas, envelope do COMP | pior caso RMX/PHASER [ESTIMATIVA −20–30 %, não medido] | baixo-médio (zipper inaudível a 1–3 kHz de controle) | diferente | testes + escuta |
| P10 | Processamento em bloco (cada módulo processa N quadros num laço apertado, L/R juntos) | [ESTIMATIVA] 1,5–3× no tablet (vetorização NEON); no Pi 1 só menos overhead | alto (refatoração grande) | diferente na ordem de arredondamento | tudo |
| P11 | Aplicar o `RuntimeProfile` (`heavyEffects=off` desliga SHIMMER/REVERB/SPACE/drum build/keylock no perfil baixo) | o pior caso deixa de existir no perfil baixo | baixo (requer decisão do dono: é mudança de feature, AGENTS.md pede proposta) | — | — |
| P12 | Memória: int16, sem cópia Java, catálogo preguiçoso (§3.4) | −127 MB por deck no pico, −64 MB por deck residente; catálogo de ~0,5 GB → MBs | baixo (int16: exato para fontes 16 bit) | int16: igual para fontes 16 bit | `perf_harness mem`, testes |
| P13 | UI (§4): `inline`/`remember`, ladrilhos, interpolação por quadro, tiers | ~0,3 MB → ~20 KB por desenho de deck [ESTIMATIVA] | sem risco ao áudio | — | Macrobenchmark/Perfetto no tablet (BLOCKED_HARDWARE) |

Ordem sugerida: P0, P6, P1+P1b, P5, P3 (+P2), P4, P12 (int16 e cópia Java), P7, P13, P8, P9; P10/P11 depois de decisão.
Cada um num PR pequeno com o dump antes/depois anexado, como pede o AGENTS.md.

---

## 6. Viabilidade franca: Raspberry Pi 1 Model B

**Conflito com o AGENTS.md**: o AGENTS.md manda o alvo 2 (Raspberry Pi) começar por um *vertical slice* com o Mixxx
upstream estável e o mapping DDJ-400 existente, **não** com um motor próprio. Portar este motor para o Pi contraria a
ordem normativa e precisa de ADR e decisão do dono. O que segue é só a análise pedida.

- **CPU** [ESTIMATIVA, §2]: a cena típica (2 decks, EQ, filtro, CUE) cabe com os protótipos (27–48 % da CPU); o pior
  caso de efeitos não cabe nem com os protótipos (1,9–3,3× acima). Master Tempo nos dois decks já é pesado e o pico do
  grão (3–5 ms por deck) estoura blocos de 256 sem P8. Análise de BPM/tom no aparelho: minutos por faixa — no Pi 1 só
  dá para usar a análise do rekordbox (ANLZ) ou fazer a análise fora.
- **RAM**: 512 MB divididos com a GPU (com `gpu_mem=16–64` sobram ~448–496 MB para Linux). Duas faixas de 6 min em
  float = 254 MB + pico de carga → inviável sem int16 + carga sem cópia; com int16 = 127 MB para 2 decks, ainda
  apertado; o caminho realista é PCM em cache local + `mmap` (§3.4 item 3) e catálogo preguiçoso.
- **USB**: o Pi 1 B tem um único USB 2.0 do SoC atrás do hub/Ethernet LAN9512 — áudio da DDJ-400 (USB classe
  áudio), MIDI, pendrive e Ethernet dividem o mesmo barramento; o driver `dwc_otg` do Pi 1 é conhecido por consumir
  CPU com interrupções. Ler o pendrive enquanto toca disputa banda e CPU com o áudio isócrono [ESTIMATIVA, não medido
  aqui]. O pendrive rekordbox continua só leitura.
- **Tela 7"**: sem Android no Pi 1 (não há build oficial; e o Pi 1 não teria RAM/CPU para ele) → front-end Linux:
  framebuffer/DRM direto (SDL2 ou LVGL) em vez de X11/Wayland + Compose. A VideoCore IV pode acelerar 2D via
  OpenGL ES 2 (driver legado), mas o tempo de CPU para preparar a onda conta. A UI teria de ser reescrita (o Compose
  e o Kotlin não vão junto); o motor C++ porta com stubs de Oboe → ALSA.
- **Contraste** [ESTIMATIVA qualitativa]: Pi Zero 2 W e Pi 3 (Cortex-A53 quad 1–1,4 GHz, NEON, 512 MB/1 GB) e Pi 4
  (A72, até 8 GB) mudam o quadro: núcleos várias vezes mais rápidos, NEON, 4 núcleos (áudio num núcleo isolado,
  análise em outro). Zero 2 W ainda tem só 512 MB (o int16/mmap continua necessário) e um único USB OTG. Pi 4 é o único
  em que o Mixxx upstream costuma ser usado com folga; também é o alvo coerente com o AGENTS.md.

Conclusão: **Pi 1 Model B como sonho é viável só para um modo reduzido** (2 decks, sem Master Tempo ou com um deck só,
EQ/filtro, poucos efeitos leves, onda simples a 15 Hz, análise vinda do rekordbox, PCM por mmap) e com um front-end
Linux novo. O set completo de FX/RMX do 0.14.1 não cabe [ESTIMATIVA]. Para o alvo 2 o caminho do AGENTS.md (Mixxx
upstream num Pi 4/Pi 3) segue sendo o recomendado; as otimizações P0–P7 e P12 valem já para o tablet.

---

## 7. O que não foi feito / bloqueios

- Nenhuma medição em tablet, Pi ou DDJ-400: **BLOCKED_HARDWARE** (CPU real, xruns, latência, consumo, UI Compose/ART,
  USB compartilhado).
- O build Android não foi testado (sem Android SDK no container); os protótipos compilam no host e no
  `arm-linux-gnueabihf`, e o `DenormalGuard` para arm64 (`mrs/msr fpcr`) não foi compilado para arm64 aqui.
- Nada foi commitado; os protótipos ficam na worktree `/home/user/deckbridge/.claude/worktrees/agent-ab394912753d72a03`
  (branch local `perf-analysis`) e nos diffs do scratchpad. Antes de virar PR: ADR para P2/P3/P4/P7, entrada no
  `docs/runbooks/implementation-log.md` e o dump antes/depois.
