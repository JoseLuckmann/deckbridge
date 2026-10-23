# Cobertura do leitor de club face à DeckBridge

Pesquisa de 22 de outubro de 2026. O texto descreve o que as fontes públicas dizem do leitor profissional de dois canais que a DeckBridge usa como referência de ecrã, e o que faz sentido num tablet Android com a DDJ-400.

A DeckBridge não copia o ecrã, o nome nem o logótipo desse leitor. Os nomes de função abaixo são os das fontes, para a comparação. No ecrã da app os rótulos continuam genéricos (KEY, SYNC, SLIP, EMERG, HOT CUE, FILTRO).

Quatro classes:

1. Já está na DeckBridge.
2. Dá para fazer em software, com controlo no ecrã.
3. Dá para mapear num controlo que a DDJ-400 já envia. O nome é o do XML pinado do Mixxx (`docs/compatibility/ddj400-control-matrix.md`). Não foi inventado nenhum byte.
4. Precisa de hardware ou de rede que este projeto não tem.

Esforço: S pequeno, M médio, L grande. A escuta continua `BLOCKED_HARDWARE`.

## Contagem

A tabela tem 45 linhas. Uma linha pode citar duas classes; a contagem abaixo usa a classe principal, e a classe 3 é um subconjunto da 1 (não soma por cima).

| Classe | Linhas | Feitas nesta 0.10.0 | Recomendação curta |
|---|---|---|---|
| 1 já estava | 16 | o realce de tom passou a aceitar Camelot e nota musical no mesmo sítio | manter |
| 2 no ecrã | 14 | 9 na 0.10.0; na 0.12.0 entraram ordenar, Active Loop, reverse, grelha e contagem; na 0.13.0, MATCHING, SUGGEST, slip alargado, medidor de fase, beat jump 64 e leitura de PSSI | o que resta de ecrã é sobretudo o que a classe 4 exige hardware ou rede |
| 3 mapa DDJ-400 | 6, já dentro das 16 | 0 novas | não roubar o BEAT SYNC para o Key Sync |
| 4 fora de alcance | 11 | 0 | não prometer Pro DJ Link, nuvem, Wi-Fi nem o ecrã do prato |
| mista (1 e 2, ou 1 e 4) | 4: Active Loop, Tag List, modo do prato, On Air | 0 | o resto de cada uma está na própria linha |

As funções de rede e de nuvem contam como uma linha cada, mesmo quando o firmware acrescenta um serviço. Busca por voz e quatro decks não estão nesta tabela: o dono adiou os dois em 2026-10-22 e não são funções deste leitor.

## O que entrou na 0.10.0

Classe 2, esforço S, no ecrã do deck ativo:

- Key Shift de um semitom, com reset.
- Key Sync para o vizinho de menor deslocação entre os seis tons do manual (mesmo, dominante, subdominante, relativo e os relativos desses dois). É a regra do manual, com a nossa tabela de notas. Não é o algoritmo proprietário.
- Filtro da lista por janela de BPM e por tom compatível. A pesquisa já filtrava título, artista, BPM e tom exato.
- Contagem de batidas até ao próximo hot cue.
- Contagem de compassos até ao fim de um bloco de 16. O bloco é o índice do compasso. Não é análise de frase.
- Toque na visão geral para saltar (agulha). O manual limita isto à pausa ou ao topo do prato em modo vinyl. Aqui o toque vale também a tocar.
- Slip: guarda o ponto e, ao soltar, volta a ele e larga o loop. Não faz slip de pausa, scratch, hot cue nem a alavanca de reverse.
- Emergency loop: se faltam menos de 8 batidas e não há loop, arma um loop de 4 batidas até ao fim. O botão EMERG faz o mesmo de imediato, ou um loop de 4 no ponto atual se ainda falta faixa. O manual diz que não se mistura durante esse loop e que carregar outra faixa o cancela. Aqui o resto dos controlos continua.
- Tempo com duas casas (`+4.02%`). O fader da DDJ-400 continua de 14 bits. Não há detent de 0,02%.
- Banco de hot cue A–H na memória do app. O banco A é o da análise. Os outros não vêm de um ficheiro de banco e não são gravados no USB.

A capa (tabela de artwork e ficheiros em `PIONEER/Artwork`) é leitura de biblioteca, não uma função do leitor. Está em `android/FORMATO.md`.

## Tabela

| Função | Fonte | Classe | Esforço | Estado | Recomendação |
|---|---|---|---|---|---|
| Beat Sync de BPM | Manual, Beat Sync | 1 e 3: `PioneerDDJ400.syncPressed` (0x90/0x58 e 0x91/0x58). Pressão longa: `syncLongPressed` (0x5C) define o master | — | feito | Não usar este botão para Key Sync |
| Master Tempo | Manual, Master Tempo | 1 | — | WSOLA (janela de 2048, salto de 1024, busca de ±220 amostras), lendo direto da faixa, sem latência; o deslocador antigo de dois atrasos não segurava a afinação (19,5 Hz no lugar de 220 Hz no teste isolado) | Não vender como o algoritmo comercial; ouvir no hardware |
| Quantize e valor de batida | Manual, Quantize; atalho QUANTIZE BEAT VALUE | 1 | — | feito no ecrã | Manter |
| Beat Jump | Manual: o salto usa o valor de BEAT JUMP BEAT VALUE | 1 | — | 1, 2, 4, 8, 16, 32 e, desde a 0.13.0, 64 no ecrã. O manual não fixa a lista; o valor é um ajuste | Manter |
| Loop in / out | Manual; mapa `loop_in` 0x10, `loop_out` 0x11 | 1 e 3 | — | feito | Manter |
| Loop de 4 e de 8 | Manual, botões 4 BEAT e 8 BEAT | 1 e 3: pads de beat loop `beatloop_4_toggle` (nota 0x64) e `beatloop_8_toggle` (0x65) em 0x97/0x99 | — | feito | Manter |
| Metade e dobro do loop | Manual | 1 e 3: `PioneerDDJ400.cueLoopCallLeft` / `cueLoopCallRight` | — | feito | Manter |
| Reloop / exit | Manual | 1 e 3: `reloop_toggle` 0x4D; shift `reloop_andstop` 0x50 | — | feito | Manter |
| Active Loop | Manual: o loop gravado começa quando a cabeça chega lá | 1 parcial, resto 2 | M | na 0.12.0 o loop da memória arma na carga e só entra quando a cabeça cruza o início | Ouvir no hardware |
| Emergency Loop | Manual, Emergency Loop | 2 | S | feito, com as diferenças acima | Ouvir no hardware |
| Gravar loop no USB | Manual, MEMORY | 4 | — | a leitura de memory cue já existe. Escrever o USB está fora (ADR-0002) | Não gravar o pendrive |
| Slip | Manual, Slip | 2 | S / M | na 0.13.0 o regresso ao ponto também cobre pausa, scratch, hot cue premido e reverse no ecrã. A DDJ-400 não tem alavanca de slip reverse | Ouvir no hardware |
| Slip Reverse | Manual: alavanca DIRECTION em SLIP REV | 4 | — | a DDJ-400 não tem essa alavanca | Não simular a alavanca |
| Key Shift | Manual: toque em KEY SHIFT, um semitom, RESET | 2 | S | feito | Manter no ecrã |
| Key Sync | Manual: o menor salto entre os seis tons vizinhos do master | 2 | S | feito como essa regra, com a nossa tabela | Não remapear o BEAT SYNC |
| Hot Cue A–H | Manual, Hot Cues | 1 e 3: pads de hot cue | — | feito | Manter |
| Hot Cue Bank | Manual: carrega um banco feito no rekordbox; o leitor não edita o banco | 2 | M | na 0.13.0 o banco do export é lido, só leitura. A troca A–H na memória do app continua. O USB não é escrito | Ouvir no hardware |
| Hot Cue Auto Load | Atalho HOT CUE AUTO LOAD | 1 | — | preferência no menu | Manter |
| Track Filter | Manual: só na biblioteca; não vale em TAG LIST, SEARCH, MATCHING, HOT CUE BANK nem FOLDER | 2 | S | janela de BPM e tom compatível na lista. Não é o ecrã inteiro de edição | Chega para já |
| Search | Manual, SEARCH | 1 | — | título, artista, BPM e tom exato | Manter |
| Tag List | Manual. Converter em playlist grava o dispositivo | 1 a lista da sessão; 4 a gravação | — | etiquetas da sessão. O USB não é destino | Manter |
| Ordenar a lista ao tocar no título | Manual, track list | 2 | S | na 0.12.0 o cabeçalho ordena título, BPM, tom e tempo, e inverte no segundo toque | Manter |
| Categoria MATCHING | O manual cita a categoria ao excluir o Track Filter | 2 | S | na 0.13.0 há lista MATCHING e SUGGEST. Não é o ecrã inteiro do leitor | Ouvir no hardware |
| Tom no ecrã e realce de vizinhos | Manual, indicador Key; realce nosso a partir da roda | 1 | — | feito. `Am` e `8A` contam como o mesmo tom | Manter |
| Beat countdown | Manual, ecrã de onda: compassos e batidas até ao cue gravado mais próximo | 2 | S | na 0.12.0 a contagem usa o hot cue ou o memory cue, o que estiver mais perto à frente | Manter |
| Frase / PSSI | A análise pública do ANLZ descreve frases. O manual deste leitor descreve o beat countdown, não um ecrã PSSI. O anúncio de 2020 fala de dados de frase para iluminação | 2 | L | na 0.13.0 a tag PSSI do `.EXT` é lida (máscara publicada). 16 de 26 fronteiras anotadas caem a 1 compasso. Não é iluminação de palco | Ouvir no hardware |
| Cor da onda BLUE / RGB / 3BAND | Manual, atalho WAVEFORM COLOR. BLUE é o valor de fábrica | 1 | — | as três estão no menu. Na 0.11.0 a cor lida o export (PWV5/PWV4, PWV7/PWV6, PWV3/PWAV). Sem a tag, o app analisa o WAV | Manter |
| Zoom | Manual, modo Zoom | 1 | — | feito | Manter |
| Playhead à esquerda ou ao centro | Atalho WAVEFORM CURRENT POSITION | 1 | — | o menu move o playhead. Se `DEVSETTING.DAT` trouxer o byte documentado, pode ser o padrão | Manter |
| Touch Preview | Manual: ouvir antes de carregar, com mesa compatível e Pro DJ Link | 4 | L | não feito | Não simular a mesa |
| Toque na onda geral (agulha) | Manual: durante a pausa, ou com o topo do prato premido em vinyl | 2 | S | feito, também durante a reprodução | Dizer a diferença no roteiro |
| Ecrã do prato (arte, slip, sync, ponto, vinyl, cue, master) | Manual, Jog display | 4 | — | o prato físico não existe. A arte foi para o cartão do deck | Não desenhar um prato falso como se fosse o LCD |
| Vinyl / CDJ | Manual | 1 o modo; 4 o tato | — | o menu tem o modo. Os segundos do jog continuam provisórios | Medir no hardware |
| Ajuste de velocidade do vinyl | Manual, TOUCH & RELEASE / TOUCH / RELEASE | 4 | — | não há esse mecanismo | Deixar |
| Reverse | Manual | 2 | M | na 0.12.0 o botão REV inverte o passo no motor. Não há alavanca equivalente confirmada no mapa da DDJ-400. Slip reverse continua fora | Ouvir no hardware |
| Faixas de tempo ±6 / ±10 / ±16 / WIDE | Manual: unidades 0,02%, 0,05%, 0,05% e 0,5%. WIDE até ±100% | 1 as quatro faixas da DDJ-400 (6, 10, 16, WIDE); 2 o texto fino | S | as faixas já existiam. O texto passou a duas casas | Não fingir detent de 0,02% no fader |
| Pro DJ Link, até 6 leitores, iluminação, Link Info, cópia de ajustes | Manual, PRO DJ LINK. Firmware 2.03 cita o limite de 6. Firmware 3.11 acrescenta Link Info. Firmware 3.22 acrescenta o RMX-IGNITE | 4 | L | fora | Não prometer |
| CloudDirectPlay, Google Drive, StreamingDirectPlay, Cloud Analysis, rating, Information Jump | Firmware 2.00, 3.00, 3.11 e 3.16 | 4 | L | fora. Exige conta, rede e, no rating, escrita | Não prometer |
| Wi-Fi e Ethernet | Manual: LAN e até 4 computadores por Wi-Fi | 4 | — | o tablet pode ter rádio; a app não fala esse protocolo | Fora |
| Stagehand | Firmware 2.04 | 4 | — | app externa | Fora |
| MIDI/HID do leitor a comandar um computador | Manual, Using a DJ application | 4 | — | a DeckBridge é o programa que recebe a DDJ-400. Não se apresenta como esse leitor | Fora |
| On Air ligado a uma mesa na rede | Manual, ON AIR DISPLAY via PRO DJ LINK | 4 o link; 1 o ON AIR local pelo fader | — | o badge local existe | Manter o local |
| My Settings gravados no USB | Manual | 4 | — | escrever o USB está fora | Fora |
| Ajuste de grelha | Manual, Grid Adjust | 2 | M | na 0.12.0 G−, G+, S−, S+ e G0 deslocam ou esticam a grade na memória do app. O USB não é escrito | Ouvir no hardware |
| Fase em vez da onda | Atalho WAVEFORM / PHASE METER | 2 | M | na 0.13.0 o menu troca WAVEFORM e PHASE METER | Ouvir no hardware |

## O que não é função deste leitor

- **Sound Check.** Procurei o manual (edição DRI1586A) e o histórico de firmware até 3.22. Não há uma função com esse nome. Não foi acrescentada.
- **Stagelinq.** É um protocolo de outro fabricante. Não está nas fontes deste leitor.
- **Busca por voz e quatro decks.** Adiados pelo dono em 2026-10-22. Ver `specs/001-ddj400-standalone/spec.md` §1.4 e as tarefas A053 e A054.

## Firmware

O histórico público chama-se ver. 3.22 e a data dessa entrada é 15 de janeiro de 2026. O que muda o produto, além de correções:

- 3.22 (15 jan 2026): Pro DJ Link com o RMX-IGNITE. A nota diz que esta atualização não inclui OneLibrary.
- 3.20 (24 abr 2025): ligação Pro DJ Link com a DJM-2000NXS.
- 3.19 e 3.18 (dez 2024): ecrã, áudio e bloqueios em algumas unidades.
- 3.16 (10 out 2024): Pro DJ Link com a XDJ-AZ; CloudDirectPlay com Google Drive; Power Management a 20 minutos; rating editável no CloudDirectPlay.
- 3.14 (25 jul 2024): popup “Waiting” raro no BROWSE.
- 3.11 (3 out 2023): Cloud Analysis, Edit Rating, Information Jump, Link Info.
- 3.00 (27 jun 2023): StreamingDirectPlay para Beatport Streaming.
- 2.04: Stagehand; correção de BPM/tom no Track Filter.
- 2.03: correção do Beat Countdown; até 6 leitores na ligação.
- 2.02: capa por vezes em baixa resolução a partir de HFS+.
- 2.00 (30 nov 2021): CloudDirectPlay.

Nada disto foi implementado. São classe 4, salvo o Beat Countdown e o Track Filter, que o manual já descreve e que a 0.10.0 cobre no ecrã.

## Recomendações

Os cinco itens acima entraram no ecrã na 0.12.0. A escuta deles continua `BLOCKED_HARDWARE`.

Na 0.13.1 o tom da análise própria passou a um CQT com o EDMA publicado. Não é um gesto novo na controladora. Na 0.13.0 entraram, ainda sem escuta: MATCHING, SUGGEST, o medidor de fase no lugar da onda, slip com a pausa, o scratch, o hot cue premido e o reverse (o ponto escondido continua; ao soltar, a cabeça volta), beat jump de 64, leitura de PSSI e banco de hot cue do export em só leitura. A DDJ-400 não tem alavanca física de slip reverse. O gesto de reverse em slip está no ecrã. Busca por voz e quatro decks continuam por fazer.

Não fazer nesta linha: Pro DJ Link, CloudDirectPlay, StreamingDirectPlay, Wi-Fi, ecrã dentro do prato, gravar loops, grades ou bancos no USB.

## Fontes

- Manual: <https://downloads.support.alphatheta.com/manuals/dj-players/CDJ-3000/CDJ-3000_DRI1586A_manual.pdf>
- Página do produto: <https://www.pioneerdj.com/en/product/dj-players-turntables/cdj-3000/>
- Anúncio de 2020 (frase para iluminação, no texto da marca): <https://www.pioneerdj.com/en-gb/news/2020/cdj-3000-professional-dj-multi-player/>
- Histórico de firmware até 3.22: <https://downloads.support.alphatheta.com/firmwares/dj-players/CDJ-3000/CDJ-3000-Firmware-Change-History-Ver322-en.pdf>
- Firmware 3.22 no centro de ajuda (15 jan 2026): <https://support.alphatheta.com/en-us/articles/4404623755545>
- Notícia 3.14 (25 jul 2024): <https://www.pioneerdj.com/en/news/2024/cdj-3000-and-opus-quad-firmware-update/>
- Notícia 3.20 (24 abr 2025): <https://www.pioneerdj.com/en/news/2025/cdj-3000-firmware-update-320/>
- Tutorial de CloudDirectPlay (firmware 2.00, 30 nov 2021): <https://www.youtube.com/watch?v=Sy5z244e3bs>
- Capa no export, descrição pública (não foi copiado código): <https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/exports.html>
- Frases no ANLZ, descrição pública: <https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html>
- Mapa da DDJ-400 usado na classe 3: `docs/compatibility/ddj400-control-matrix.md`, gerado do XML pinado do Mixxx. Nenhum nome novo foi acrescentado a esse ficheiro.
