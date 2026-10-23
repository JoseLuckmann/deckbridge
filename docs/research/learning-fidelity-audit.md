# Auditoria de fidelidade — DeckBridge como ferramenta de aprendizado

Decisão do dono, 23/10/2026: o foco do programa é ser muito parecido com o fluxo de um leitor de club (CDJ-3000), do mixer de referência (DJM-V10) e do RMX da Pioneer, para que quem só tem uma DDJ-400 e um tablet pratique como se fosse real e faça uma transição fácil para o equipamento maior. Isso eleva o DJ Trainer (P4, `spec.md` §3.5) de roadmap a eixo do produto. Os requisitos P0 e P1 não mudam (AGENTS.md).

Método: leitura do código (`android/app/src/main`), `docs/research/cdj3000-coverage.md`, `docs/research/fx-de-para.md` e o mapa pinado da DDJ-400. Nada aqui foi verificado em hardware nem contra um aparelho Pioneer. Onde o manual não publica um número, a linha diz isso.

Regra de leitura: "fiel" aqui é o **gesto e o fluxo** (o que o DJ faz, em que ordem, o que vê, o que ouve). Não é copiar tela, nome comercial ou logótipo.

## 1. O que a controladora já manda e o app ignora

Medido no código: `performanceAction` (`logic/MixMath.kt`) só traduz PLAY, CUE, HOT CUE, VOLUME, CROSSFADER e JOG. O mapa (`ddj400-map.json`) tem estes controles, sem ação no app nem no motor:

| Controle da DDJ-400 | Entradas no mapa | Estado |
| --- | --- | --- |
| EQ HI / MID / LOW por canal | 12 (`parameter3/2/1`) | **sem ação**. Não há EQ de canal no motor. |
| TRIM por canal | 4 (`pregain`) | **sem ação** (o trim existente é só o auto gain da análise) |
| HEADPHONES MIX e LEVEL | 4 (`headMix`, `headGain`) | **sem ação** |

Consequência para o aprendizado: o primeiro gesto de mixagem (troca de grave com EQ LOW, cortar o médio na entrada) não funciona. É o item P0 da lista abaixo.

## 2. Lacunas por equipamento de referência

### Leitor (CDJ-3000)

| Lacuna | Fluxo real que se treina | Esforço |
| --- | --- | --- |
| Vista de prato (jog) com estado | ver play/cue, slip, vinyl, sync e o ponto sem olhar a onda | M. Já decidido não desenhar um LCD falso (`cdj3000-coverage.md`); proposta: anel de posição neutro no cartão do deck. |
| Contagem de frase na onda | saber quantos compassos faltam para a próxima frase | S (dados PSSI já lidos) |
| Master Tempo, quantize, loop, slip, hot cue, key | gestos de performance | já existem; falta a escuta no hardware |

### Mixer (DJM-V10 como referência)

| Lacuna | Fluxo real | Esforço |
| --- | --- | --- |
| EQ de canal de 3 bandas com kill | troca de grave, corte de médio | M (DSP + MIDI + tela) |
| TRIM com indicador de nível por canal | ganho de entrada, evitar clipe | S |
| Medidores de canal e master | ver o nível antes do fader | S |
| Curva do crossfader e do fader | ajuste de curva | S |
| CUE/PFL com HEAD MIX e LEVEL | monitorar no fone e misturar | M (fone já roteado; faltam os dois knobs) |
| Seletor de Sound Color FX por canal | escolher SPACE, DUB ECHO, SWEEP, NOISE, CRUSH, FILTER | S (motor pronto na 0.15.0; falta a tela do deck) |
| EQ de 4 bandas e ISOLATOR do V10 | a DDJ-400 só tem 3 bandas | fora (não fingir bandas que a controladora não envia) |

### RMX

Ver `fx-de-para.md`. Para o aprendizado, o que mais pesa: cada cena com os dois sub-parâmetros do aparelho de referência, release FX (alavanca) e X-PAD como superfície, não como lista de botões.

### Camada de ensino (P4)

Nada existe hoje. Proposta mínima, tudo observável e desligável, sem automatizar o mixer (`spec.md` US-07):

1. **Modos** PERFORMANCE, GUIDED MIX e TRAINING, mutuamente exclusivos, com um toque para desligar.
2. **Dicas de contexto** com os nomes reais dos gestos ("hora de trocar o grave", "faltam 8 compassos para a quebra"), a partir de fase, frase e EQ.
3. **Exercícios** com pontuação reprodutível: beatmatch (medidor de fase), entrada na frase, troca de grave, build com release no drop.
4. **Guia de transição**: para cada controle da DDJ-400, onde está num CDJ-3000, no mixer de referência e no RMX, com os nomes reais das funções.

## 3. Ordem proposta

| Passo | Item | Por quê |
| --- | --- | --- |
| 1 | EQ de canal de 3 bandas + TRIM + HEAD MIX/LEVEL, com a tela mostrando o estado | é o gesto mais básico e hoje não existe |
| 2 | Medidores de canal/master e curva do crossfader | feedback visual que um mixer real dá |
| 3 | Seletor de Sound Color FX na tela do deck | motor pronto |
| 4 | Tela RMX/FX: alvos de toque de 48 dp, progresso do build, release visível | usabilidade do que já existe |
| 5 | Modo GUIDED MIX com dicas de frase e de troca de grave | primeiro valor de ensino |
| 6 | Exercícios com pontuação (beatmatch, frase, troca de grave, build) | fecha o ciclo de prática |
| 7 | Guia de transição por controle | ponte para o equipamento maior |

## 4. Riscos

- Fidelidade sem aparelho: as curvas de EQ, filtro e FX são nossas. Rotular como "modelo de estudo", não como equivalente.
- O trainer não pode competir por CPU com o áudio (`spec.md` §3.5). Medir antes de ligar por padrão.
- Marca: usar nomes de função, não logótipos nem o layout idêntico do LCD.
- Escuta e controle seguem `BLOCKED_HARDWARE` até o tablet com a DDJ-400.
