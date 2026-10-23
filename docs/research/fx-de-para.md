# De/para dos efeitos — 0.14.0, drum build 0.14.1

Fontes públicas, citadas pelo que o texto diz. Não há amostra, código nem áudio da Pioneer neste repositório. Onde o manual não publica um número, a célula diz isso e entra a medida deste motor.

- DDJ-400 operating instructions (EN): o knob LEVEL/DEPTH “adjusts the parameter for BEAT FX level and depth”; ON/OFF liga e desliga; SHIFT+ON/OFF desliga o Beat FX e acrescenta Release FX; BEAT ◁/▷ muda a fração; o knob FILTER, no centro, “the original sound outputs”. O manual não lista os nomes dos efeitos nem a curva molhado/seco.
- DJM-900NXS2 operating instructions (EN), tabela “Types of BEAT FX” e “Types of SOUND COLOR FX”. O manual do DJM-A9 descreve a mesma família (LEVEL/DEPTH, fração de tempo, cauda ao baixar o fader). Não há, nestes PDFs, equação de Q, constante de tempo do knob nem coeficientes.
- RMX-1000 operating instructions (EN), “Types of SCENE FX / ISOLATE FX / X-PAD FX / RELEASE FX”.

Medidas nossas: `tools/fx_measure.cpp`, 48 kHz. Gráficos em `docs/research/plots/`.

## O que mudou no som

No 0.13.1 cada efeito entrava no nível pedido, no mesmo sample, muitas vezes perto de 100% molhado. O filtro era biquad com corte linear no knob. Era isso que a primeira mix mostrava, e foi isso que soou agressivo.

No 0.14.0:

- Knob e parâmetros passam por um polo de 22–36 ms. Liga/desliga de efeito sem cauda cruza em cerca de 18 ms. O teste de degrau em DC (0,4) mediu salto máximo de 6e-8 na rampa do filtro novo e 0,299 no biquad que troca o corte no meio.
- Eco e reverb, ao desligar, deixam de receber sinal novo e a mistura da cauda cai (`0,99986` por sample no eco, cerca de 0,3 s até −20 dB). O teste antigo continua a exigir seco exato depois de 2 s: a cauda já morreu.
- Filtro (Color e Beat FX Filter): SVF no estilo TPT, Q 0,95, zona morta 0,47–0,53, corte exponencial, mistura mais fraca nos primeiros 20% do curso (sensação de prateleira). Esquerda fechada: 12 kHz fica pelo menos 6 dB abaixo de 80 Hz. Direita fechada: o inverso. Centro: 500 Hz a menos de 1,5 dB do seco.
- Eco: LEVEL mistura molhado e feedback. Molhado `0,10 + 0,58×nível` (teto cerca de 0,68). Feedback `0,16 + 0,50×nível`, teto 0,82, com passa-baixas perto de 6,2 kHz. A 70% de nível, as seis primeiras repetições caem de RMS 0,017 para 0,00044, e a energia aguda da 4.ª é menor que a da 1.ª (0,007 contra 0,100). Mudar a fração desliza o tempo em cerca de 50 ms (efeito de fita), em vez de esvaziar a linha.
- Reverb: quatro pentes com amortecimento, dois allpass e pré-atraso de `sr/48` (20 ms a 48 kHz). Comprimentos 1301, 1571, 1861 e 2113 samples, nossos. RT60 a nível 0,72: 0,65 s. Não é metálico no sentido do pente sem amortecimento; também não é uma sala medida da Pioneer.
- Flanger e phaser: LFO no tempo do BPM, feedback contido pelo nível.
- Roll / slip roll no deck: a leitura cruza cerca de 48 samples na emenda da fatia. O `renderRoll` dos testes continua a cópia seca, porque o teste pede o impulso intacto.
- Ruído: branco filtrado (SVF), nível baixo, corte a subir com o knob.
- Cena: a intensidade que o teste acompanha continua linear. O som usa `t^1,35` em corte, feedback e ruído. Ao soltar, espera o próximo downbeat e some em cerca de 18 ms.
- Drum build: sons sintetizados aqui (seno + ruído filtrado). Sem sample da Pioneer. Frações de tempo 1/4 → 1/8 → 1/16 → 1/32 de batida (16, 32, 64 e 128 ataques por compasso a 120). O gesto medido subiu 16 → 128 ataques. Pitch e volume sobem com o knob. Ao soltar, o último ataque acaba no downbeat seguinte.
- Isolador: Linkwitz-Riley de 24 dB/oitava (dois Butterworth em cascata) em 200 Hz e 2 kHz. Ganho 0–2 com polo de 20 ms. O teste de +6 dB em 80 Hz e o kill continuam a passar.

CPU: um bloco de 256 samples do eco no deck ficou em 12,6 µs. O orçamento a 48 kHz é 5333 µs. NaN no passeio dos 12 inserts: 0.

## Beat FX

Fração no nosso app: 1/16, 1/8, 1/4, 1/2, 3/4, 1/1, 2/1, 4/1, 8/1. No DJM-900NXS2 o tempo de eco/roll vai de 1/16 a 16 tempos; flanger, phaser e o filtro rítmico vão até 64. Não copiamos o teto de 16/64: o seletor da DDJ-400 nesta app para em 8/1.

| Efeito | O que o manual publica | 0.13.1 | 0.14.0 | O que ainda falta |
| --- | --- | --- | --- | --- |
| Low Cut Echo | A DDJ-400 não nomeia. Não está na tabela do DJM-900NXS2 que li. | Eco com passa-altas, nível ≈ molhado, sem deslize. | Eco com passa-altas nas repetições (cerca de 160 Hz–2,8 kHz conforme o nível), feedback escurecido, cauda. | Sem frase oficial para copiar o equilíbrio. |
| Echo | DJM: várias repetições que atenuam; LEVEL/DEPTH é o balanço entre original e eco; baixar o fader deixa a cauda. Tempo 1/16–16, ou 1–4000 ms. | Molhado alto, feedback sem filtro, corte instantâneo da mistura. | Molhado e feedback sobem juntos; repetições mais escuras; cauda depois de off; tempo desliza. | A curva exata do knob não está no manual. Não há X-PAD de tempo. |
| Ping Pong | DJM: atrasos diferentes à esquerda e à direita, com fade-out se o sinal some. LEVEL/DEPTH é o balanço. | Troca de canais, sem escurecer, sem cauda longa. | O mesmo gesto, com filtro no retorno e cauda. | O manual não publica o desvio esquerda/direita. O nosso usa metade do tempo num lado. |
| Spiral | DJM e DJM-450: reverberação; ao mudar o tempo, o pitch muda junto. LEVEL/DEPTH “sets feedback, and the balance”. Fade-out de 1 tempo com o sinal cortado. | Atraso modulado por LFO, sem o pitch amarrado à mudança de tempo. | Feedback e mistura seguem o nível; o atraso ainda é um LFO, não o gesto de mudar o tempo. | Não é o spiral do manual. Falta o pitch subir quando a fração muda. |
| Reverb | DJM: balanço original/efeito. BEAT e TIME de 1–100%. X-PAD mexe no corte. Baixar o fader deixa cauda. | Quatro pentes pouco amortecidos. | FDN curto com pré-atraso e amortecimento. RT60 0,65 s a 72% de nível. | Sem o parâmetro 1–100% nem o X-PAD de corte. Não medimos RT60 de um DJM. |
| Trans | DJM: corta no tempo da fração. LEVEL/DEPTH ajusta duty e balanço. | Gate duro, duty `0,18+0,62×nível`. | O mesmo duty (o teste exige), com envoltória de cerca de 4 ms para não estalar. | Sem balanço seco/gate separado do duty. |
| Filter (Beat) | DJM: o corte anda no ciclo da fração (1/16–64). Todo à esquerda = original; à direita o efeito aumenta. | Biquad manual, corte linear, sem ciclo. | SVF manual, igual à família do Color FX. Não oscila no tempo. | Não é o filtro rítmico do DJM. É um filtro de knob. |
| Flanger | DJM: um ciclo no tempo da fração (1/16–64). Todo à esquerda = original. | LFO livre no atraso curto, feedback fixo em escala. | LFO no BPM, atraso fracionário, feedback `0,08+0,42×nível`. | Sem o X-PAD que desafina o ciclo. |
| Phaser | DJM: ciclo 1/16–64. Todo à esquerda = original. | Quatro allpass com corte recalculado em salto. | A frequência do LFO desliza; feedback pequeno. | Quatro estágios nossos, não a contagem do aparelho (não publicada). |
| Pitch | DJM: LEVEL/DEPTH, BEAT, TIME e X-PAD todos mexem no pitch, de −50% a 100%. Todo à esquerda volta ao original. | Atraso modulado, ±7 semitons em torno do centro do knob. | ±5 semitons, mistura que cresce ao sair do centro. | Não é um pitch shifter de grão. A faixa −50–100% não está mapeada. |
| Slip Roll | DJM: grava ao ligar e repete na fração. Se o tempo muda, grava de novo. O som por baixo continua (slip). LEVEL/DEPTH é o balanço original/roll. | O deck enrola a cabeça e esconde a posição. Sem crossfade. Nível não mistura o seco: o roll é o transporte. | Crossfade de 48 samples na emenda. O slip continua. | O nível ainda não faz o balanço seco/roll do manual. |
| Roll | DJM: igual, sem o slip. LEVEL/DEPTH é o balanço. | Enrolamento seco da cabeça. | Crossfade na emenda. | O mesmo buraco do balanço. |
| Helix | DJM: grava ao subir o LEVEL a partir da esquerda; todo à direita fixa o som; mudar o tempo altera o pitch do que foi gravado. | Atraso modulado em estéreo, não uma amostra fixa. | Feedback escurecido. Continua a ser um atraso. | Não grava nem “trava” no máximo. Não é o helix. |
| Mobius | Fora da tabela do DJM-900NXS2 que li. Nome da lista interna, vinda do rekordbox. | Dois atrasos cruzados. | Os mesmos atrasos, com escurecimento e cauda. | Sem documento de parâmetro para fechar o de/para. |
| Vinyl Brake | DJM: a velocidade cai até parar. LEVEL/DEPTH manda na velocidade; todo à esquerda volta ao original. Tempo 1/16–16 ou 10–4000 ms. | A velocidade cai em linha até zero ao longo da fração, sem knob de profundidade. | Igual. O teste exige a queda. | O knob não devolve a velocidade se estiver à esquerda. |

## Color FX

A DDJ-400 só tem o knob FILTER por canal. O manual: no centro sai o som original. Não há seletor de seis cores.

O DJM-900NXS2 tem SOUND COLOR FX à parte (SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER), com knob COLOR e PARAMETER. Não está neste mapa de controladora. Ruído, pitch e dub eco continuam no Beat FX e nas cenas, não num seletor fingido.

| Knob | Manual | 0.13.1 | 0.14.0 |
| --- | --- | --- | --- |
| FILTER | Centro = original. O DJM, no COLOR de FILTER, desce o passa-baixas à esquerda e sobe o passa-altas à direita. PARAMETER aumenta a ressonância. A DDJ-400 não publica frequências nem Q. | Biquad, zona ±0,02, corte linear (passa-baixas cerca de 120–2 kHz, passa-altas 180–6 kHz), Q 0,707, salto de coeficiente. | SVF, Q 0,95 fixo, zona 0,47–0,53, exponencial (passa-baixas de cerca de 11 kHz até 95 Hz; passa-altas de cerca de 45 Hz até 8,5 kHz), mistura em curva. |

Lacuna: a ressonância não tem knob. O Q 0,95 é escolha nossa para um pico leve, não uma medida de um DJM.

## RMX-1000

O aparelho tem dez cenas. Build Up: BPF ECHO, ECHO, NOISE, SPIRAL UP, REVERB UP. Break Down: HPF ECHO, LPF ECHO, CRUSH ECHO, SPIRAL DOWN, REVERB DOWN. O knob grande, todo à esquerda, é o som original; à direita o efeito aumenta. Dois sub-parâmetros. A nossa lista tem oito nomes e um knob só. O macro `t^1,35` junta o que lá são três knobs.

| Nossa cena | O mais próximo no manual | Sub-parâmetros oficiais que não temos | 0.14.0 |
| --- | --- | --- | --- |
| Echo UP | ECHO ou BPF ECHO. ECHO: o knob grande é o balanço original/eco; sub 1 é o tempo; sub 2 é o corte. | Tempo e corte separados. | Passa-altas a subir, feedback a subir, um pouco de ruído no fim. Solta no downbeat. |
| Noise UP | NOISE: o knob grande é o corte do ruído; sub 1 é o volume; sub 2 é modulação. | Volume separado do corte. | Ruído branco num passa-banda que sobe, volume musical (cerca de 0,05–0,20), não um jato. |
| Spiral UP | SPIRAL UP: o knob grande é o tempo do spiral; sub 1 é o eco; sub 2 é quanto o pitch sobe. | Três knobs. | Atraso que encolhe e feedback que sobe. O pitch não segue o manual. |
| Reverb UP | REVERB UP: grau da reverb; sub 2 é o corta-altas. | Dois knobs de “grau”. | Passa-altas mais reverb amortecida. |
| Reverb DOWN | REVERB DOWN: o sub 2 é o corta-baixas. | Igual. | Passa-baixas a fechar mais reverb. |
| Echo DOWN | LPF ECHO ou HPF ECHO. O knob grande é o corte; o eco repete na fração e atenua. | Tempo e modulação. | Passa-baixas a fechar, eco a realimentar, escurecido. |
| Tape STOP | Não é cena. Parece o Release FX VINYL BRAKE: a velocidade cai até parar. | Alavanca, não knob de cena. | Passa-baixas e `pitchMul = 1 − intensidade`. |
| Dub ECHO | Não está nas dez cenas. O DUB ECHO é Sound Color FX do DJM: eco no médio ou no agudo, feedback à direita. | Knob COLOR + PARAMETER. | Eco mais longo com passa-baixas no retorno. |

Release FX do RMX, na alavanca: VINYL BRAKE, ECHO, BACK SPIN. Ao soltar, as cenas saem e o original continua. O nosso tem os três, no tempo que os testes já fixaram (freio em 4 tempos, backspin em 2). O eco de release agora escurece as repetições. Não há alavanca física na DDJ-400: são botões no ecrã, e o SHIFT+ON/OFF do Beat FX continua a ser o gesto da controladora para “desliga e solta”.

### X-PAD

O manual: ROLL grava o que entra quando se toca o pad e repete na fração que a posição marca. KICK, SNARE, CLAP e HI HAT disparam fontes internas. Quantize alinha ao tempo. NUDGE adianta ou atrasa. O nível do pad, com ROLL, é o balanço entre a faixa e a amostra. Banco e samples de SD são do remixbox: não usamos isso.

O nosso X-PAD não é uma superfície. São quatro modos (Roll, Trans, Drum, Hi-pass) e a fração 1/16–1/1.

Drum build na 0.14.0, mapeado na DDJ-400: com o painel RMX aberto, o pad Drum (modo 2) liga o gesto; o knob BEAT FX LEVEL/DEPTH deixa de mandar na intensidade da cena e passa a mandar na densidade e no pitch. No ecrã aparece a fração. Isso é leitura nossa do roll a acelerar, não uma tabela do manual (o manual mostra 1/1, 1/2, 1/4 e 1/8 no roll do isolador, e one-shots no X-PAD). Os ataques dessa versão eram sintetizados aqui.

Na 0.14.1 o Drum segue a fase do deck, não um relógio que começa em zero. A conta está em `docs/research/drum-grid.md`. O teste de onset (`tools/drum_grid_test.cpp`) mediu 0 ms de erro a 120, 128, 140 e 174 BPM, com pitch 0,92, 1,00 e 1,08, em blocos de 1 e de 256 samples. O gráfico é `docs/research/plots/drum-onset.svg`.

O gesto musical: 4, 8 ou 16 compassos. Em 8, são dois de semínima, dois de colcheia, três de semicolcheia e um de fusa. O 1/32 é o último compasso. A troca de subdivisão só na fronteira do compasso. O começo quantiza para a próxima semínima. O último ataque fica antes do downbeat do drop. Há opção de calar o último meio tempo e de subir a afinação cerca de 10% no fim. Volume sobe com o gesto. O passa-altas vai de 40 Hz a cerca de 180 Hz, misturado até 40%, para o sample continuar a soar como bateria.

Os hits são gravações CC0 em `android/app/src/main/assets/drums/` (bumbo, palma, caixa, chimbal fechado e aberto). Licenças e URLs em `docs/licenses/drum-samples.md`. Não há sample da Pioneer. Se um arquivo não abrir, o motor cai na modelagem física (bumbo 150→50 Hz com clique e saturação, palma de quatro rajadas, caixa de tom mais ruído). A tela e o Shift+SAMPLER escolhem kick, clap, snare ou kick+clap. Nos dois últimos trechos a palma ou a caixa entra por cima, e o chimbal marca o contratempo. O caminho sintetizado da 0.14.0 continua só quando o build está em zero, para os testes antigos.

### Isolate

O manual: LOW/MID/HI no centro não fazem efeito. Além do isolador há CUT/ADD, TRANS/ROLL e GATE/DRIVE, com comportamentos diferentes para cada lado do knob (kill do bumbo, atraso de 1/8, roll por banda, gate, drive). O nosso isolador é só o crossover de três bandas, ganho 0–2 (kill até cerca de +6 dB), mais um gate simples. Não há CUT/ADD, roll por banda nem drive. O crossover passou a Linkwitz-Riley de 24 dB/oitava, com o ganho a deslizar em 20 ms para não estalar.

## Gráficos

- `docs/research/plots/filtro-resposta.svg` — knob em 0, 0,18, 0,35, 0,50, 0,66, 0,84 e 1.
- `docs/research/plots/reverb-rt60.svg` — envelope depois dos primeiros 50 ms. RT60 0,65 s a nível 0,72.
- `docs/research/plots/eco-queda.svg` — RMS de cada repetição a nível 0,70.
- `docs/research/plots/drum-onset.svg` — erro de cada caso do teste de grade. O pior caso nesta máquina foi 0 ms. A linha de 1 ms é o limite pedido.

Estes gráficos são do binário hospedeiro. Não foram medidos no tablet nem numa mesa Pioneer.


## Atualização de 23/10/2026: o que a tabela acima ainda diz de menos

O código já tem, e `tools/fx_rmx_test.cpp` cobre: Spiral com o deslocador de grão no laço de feedback (as repetições sobem de tom), Helix que trava o som no laço com o knob todo à direita, Pitch por grão de −1 a +1 oitava (−50% a +100%) e o roll com o balanço original/roll (`rollMixFromLevel`: esquerda é o original, direita o roll, 0,5 dá 70% de roll). Continua faltando: o pitch do Spiral e do Helix mudando junto com o tempo da faixa (o aparelho de referência faz isso), a gravação do Helix ao subir o LEVEL e o X-PAD de corte do Reverb.
