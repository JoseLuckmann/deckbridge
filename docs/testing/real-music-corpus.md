# Corpus de música real — 0.12.0 e 0.12.1

Medido em 22/10/2026 no JVM deste host. Não é o tablet. Não é a DDJ-400. A escuta continua `BLOCKED_HARDWARE`.

A seção de BPM, grade, tom, onda e mix abaixo de “Antes” é a passagem 0.12.0. A seção “Depois (0.12.1)” é a mesma pasta, com o detector e a paleta desta versão. Os prints de 0.12.0 ficam em `/opt/cursor/artifacts/prints/before-0.12.0/`. Os de 0.12.1 substituem os arquivos em `/opt/cursor/artifacts/prints/`.

Os nove arquivos originais ficam em `/opt/cursor/artifacts/real-corpus`. Não entram no git. `tools/fetch_real_corpus.py` baixa de novo e confere o SHA-256. A licença de cada item no Internet Archive é CC BY 3.0 (`http://creativecommons.org/licenses/by/3.0/`), sem NC, sem ND e sem SA. Dá para redistribuir com atribuição. O tamanho (o FLAC tem 27 MB; o WAV derivado, 42 MB) é o motivo de ficarem fora do repositório.

O AAC e o WAV desta pasta são transcodes feitos aqui com ffmpeg, a partir de faixas desta lista. Não são os arquivos publicados.

## Faixas

| Arquivo | Título | Artista | Álbum | Duração | Formato | Licença | SHA-256 |
|---|---|---|---|---|---|---|---|
| `01-chromosome-boutique.mp3` | Chromosome Boutique | Fature | Choice by Definition | 192,7 s | MP3 VBR | CC BY 3.0 | `82305c96e34512f62f064dd7c8658234810aacc604025ad4b5afb392da99638a` |
| `02-ecosystem.mp3` | Ecosystem | Fature | Choice by Definition | 216,9 s | MP3 VBR | CC BY 3.0 | `88c3e94f4b61769c9ade87b7bd0e90f4dd250a79fa048997310307253fd0c5bc` |
| `07-silver.mp3` | Silver | Fature | Choice by Definition | 240,1 s | MP3 VBR | CC BY 3.0 | `bdc6445a1c6fffd264cdc0af7253264f7f38d59528cae0a5b02fa54e37843d79` |
| `morning-original-mix.mp3` | Morning (Original Mix) | Miroslav Wilde | Ocean EP | 366,0 s | MP3 VBR | CC BY 3.0 | `38b3f83532a1364a699704316b985014c60506220b14c452d8b0d1318592eb53` |
| `ocean-original-mix.mp3` | Ocean (Original Mix) | Miroslav Wilde | Ocean EP | 362,0 s | MP3 VBR | CC BY 3.0 | `609b775f27417f340b5493a35ed98532d0fd53ac657e86db2c47fdd6f74c1074` |
| `wrist-watch.flac` | wrist watch | raymundo mendoza | translations ep | 322,1 s | FLAC | CC BY 3.0 | `9dc453fb7a780fde3ff98cfa7f8766753135bdf104d14805f3fa8666e9ca23b1` |
| `toodaze.mp3` | toodaze | raymundo mendoza | translations ep | 330,2 s | MP3 VBR | CC BY 3.0 | `124b76df2cf5df9c1b58eb75d45b711bb78919fa40607e7ffb214118a6e9b01b` |
| `caught-in-the-beat.mp3` | Caught In The Beat | Broke For Free | Slam Funk | 262,8 s | MP3 VBR | CC BY 3.0 | `9536238d78ae016c9d84e81e00e33d77929cec05d23dfbe6e4ce2066cedd8b6e` |
| `at-the-count.mp3` | At The Count | Broke For Free | Slam Funk | 246,7 s | MP3 VBR | CC BY 3.0 | `ff577b890df192acdfca4733967610e1c04a3c7636e100da4ee799b9ac4222d3` |

Atribuição: Fature (L.Bridge, 2010), Miroslav Wilde (selo kopoc), raymundo mendoza (unfoundsound) e Broke For Free. O comentário ID3 de Broke For Free cita a Free Music Archive e a mesma CC BY 3.0.

| Item | Página |
|---|---|
| FNet018 | https://archive.org/details/FNet018 |
| KPL033 | https://archive.org/details/KPL033MiroslavWilde-OceanEP |
| unfound53 | https://archive.org/details/unfound53 |
| Slam Funk | https://archive.org/details/Slam_Funk-7603 |

URLs diretas estão em `tools/fetch_real_corpus.py`.

Transcodes deste app, não publicados:

| Arquivo | Origem | Bytes | SHA-256 |
|---|---|---|---|
| `01-chromosome-boutique.m4a` | o MP3 de Chromosome Boutique | 4 749 164 | `325c04bf4a7cab766ea6b14f527051d4cbb67348337605770f6831efdadb8199` |
| `07-silver.wav` | o MP3 de Silver | 42 336 184 | `e9f3f127abe3c660a302c6a60eafbf5cd8b65bb935b11f648b66211e92833f49` |

O ID3 de Morning e de Ocean traz o título `tmp`. O título usado aqui é o do nome do arquivo no item. O ID3 de Chromosome Boutique, Ecosystem e Silver traz `TKEY` (`B m`, `D#m`, `E m`). Nenhuma faixa traz BPM no ID3. O metadado do item também não publica BPM. Não houve conferência manual batendo o tempo.

## Exportação nossa

`/opt/cursor/artifacts/real-music-usb` foi escrito por este app (`ourMusicVolume`). Não é uma exportação do rekordbox. O relatório relido dessa pasta diz `simulada: false`, `origem: export deste app`, 9 faixas, playlists Sets e Real, 27 hot cues, 18 memory cues, 9 loops de memory, e as nove faixas com PWV3, PWV4, PWV5, PWV6, PWV7 e PWAV. `DEVSETTING.DAT` pede RGB e playhead ao centro. O áudio dentro do volume é um clipe WAV mono de 1 s, não a faixa inteira: o PDB aponta para a análise; o arquivo longo fica no corpus.

Os cues saem da heurística de estrutura deste app (intro, build, drop, break), não de um ponto marcado por uma pessoa.

## Decodificação

Medido com ffmpeg neste host. O `MediaCodec` do app aceita MP3, FLAC, AAC e M4A, e não rodou aqui: não há aparelho Android neste processo.

| Arquivo | Saída ffmpeg | Tempo |
|---|---|---|
| `01-chromosome-boutique.mp3` | 0 | 359 ms |
| `wrist-watch.flac` | 0 | 196 ms |
| `01-chromosome-boutique.m4a` | 0 | 302 ms |
| `07-silver.wav` | 0 | 386 ms até f32le estéreo |

`decodeWav` no Silver: 154 ms, 10 584 000 frames. Correlação do canal esquerdo nos primeiros 5 s contra o ffmpeg: 1,00000. A passagem mono em 22 050 Hz já estava em disco, então o tempo do ffmpeg dessa conversão não foi medido de novo. O `decodeWav` desse mono, por faixa, ficou entre 10 ms e 52 ms.

## Antes (0.12.0) — BPM, grade e tom

O detector dessa passagem: envelope de fluxo de onset de banda cheia, refinamento parabólico do lag, e croma por Goertzel com o perfil de Krumhansl–Schmuckler. A confiança é a nota interna dele, não um tap humano.

| Faixa | BPM | Confiança | Erro mediano onset–grade | Tom detectado | Tom no ID3 |
|---|---|---|---|---|---|
| Chromosome Boutique | 139,94 | 1,12 | 105,7 ms | Am (0,96) | Bm |
| Ecosystem | 129,37 | 1,11 | 114,0 ms | Fm (0,96) | Ebm (`D#m`) |
| Silver | 141,83 | 1,17 | 104,1 ms | Bm (0,93) | Em |
| Morning | 117,92 | 2,36 | 105,6 ms | Bm (0,90) | ausente |
| Ocean | 117,90 | 2,06 | 117,5 ms | Abm (0,89) | ausente |
| wrist watch | 120,28 | 2,68 | 109,7 ms | Ab (0,92) | ausente |
| toodaze | 120,24 | 4,74 | 103,9 ms | Abm (0,89) | ausente |
| Caught In The Beat | 143,23 | 1,21 | 102,5 ms | Fm (0,96) | ausente |
| At The Count | 152,23 | 1,56 | 93,4 ms | A (0,96) | ausente |

Nas três faixas com `TKEY`, o tom detectado não bate com o metadado. Sem BPM publicado, não há erro de BPM contra uma ficha. A faixa pedida era 118–140. Morning e Ocean ficaram em 117,9. Silver em 141,8. Caught In The Beat em 143,2. At The Count em 152,2.

O mesmo detector, num clique sintético de 120 BPM, mantém o erro da grade em até 30 ms (`TrackAnalysisTest`). Nestas faixas o erro mediano fica entre 93 e 118 ms, perto de um quarto de batida. O onset de banda cheia não trava no kick. Isso é limite deste detector, não um número de escuta.

Estrutura (segundos). O intro saiu em 0,0 nas nove.

| Faixa | Build | Drop | Break | Análise | Onda | Heap antes → depois |
|---|---|---|---|---|---|---|
| Chromosome Boutique | 26,0 | 128,0 | 176,0 | 176 ms | 190 ms | 74 → 131 MB |
| Ecosystem | 106,0 | 156,0 | 184,0 | 130 ms | 177 ms | 79 → 142 MB |
| Silver | 50,0 | 98,0 | 150,0 | 122 ms | 177 ms | 86 → 177 MB |
| Morning | 16,0 | 108,0 | 166,0 | 227 ms | 344 ms | 286 → 132 MB |
| Ocean | 130,0 | 316,0 | 316,0 | 186 ms | 223 ms | 241 → 426 MB |
| wrist watch | 32,0 | 152,0 | 222,0 | 167 ms | 193 ms | 111 → 210 MB |
| toodaze | 64,0 | 208,0 | 246,0 | 135 ms | 201 ms | 112 → 231 MB |
| Caught In The Beat | 10,0 | 94,0 | 182,0 | 138 ms | 159 ms | 96 → 233 MB |
| At The Count | 12,0 | 88,0 | 172,0 | 125 ms | 151 ms | 89 → 213 MB |

O heap é o uso do JVM (`totalMemory - freeMemory`), não o RSS e não o tablet. O coletor corre entre faixas: Morning desce de 286 para 132 MB. Ocean é o pico, 426 MB. A análise em si fica entre 122 e 227 ms; a onda, entre 151 e 344 ms.

Em Ocean o break e o drop caem no mesmo segundo (316). A heurística não separou os dois. Os prints usam as quatro primeiras faixas, em que drop e break divergem.

## Antes (0.12.0) — onda, loop e mix

Prints JVM (Roborazzi), copiados para `/opt/cursor/artifacts/prints/before-0.12.0/` antes de serem substituídos:

- `real-chromosome-boutique-drop.png` e `real-chromosome-boutique-break.png`
- `real-ecosystem-drop.png` e `real-ecosystem-break.png`
- `real-silver-drop.png` e `real-silver-break.png`
- `real-morning-drop.png` e `real-morning-break.png`
- `real-browse.png`

A cor dessa passagem mistura as bandas (grave puxa laranja e também o verde, agudo entra no vermelho e no verde). O corte era 180 Hz e 4 kHz, de primeira ordem, e a altura era o máximo das três bandas. Um pico global servia as três. Nas faixas reais a onda ficava quase toda verde. Não é uma leitura de um export do rekordbox.

Mix offline de 120 s, Chromosome Boutique (a partir de 116 s, 139,94 BPM) com Ecosystem (a partir de 148 s, 129,37 BPM), eco alinhado à batida e fade de volume. Motor C++ hospedeiro, 48 kHz.

| Medida | Valor |
|---|---|
| Pico | 0,62856 |
| NaN | 0 |
| Eco (amostras) | 20 580 |
| Batida (amostras) | 20 580,11 |
| Energia da descontinuidade, seca | 0,00020585 |
| Energia da descontinuidade, com crossfade | 0,00058152 |
| Início do loop de 4 batidas | 116,0 s |

O crossfade linear de 48 amostras piorou o salto neste ponto: a fronteira seca já era pequena e a energia molhada ficou maior.

## Depois (0.12.1) — grade

O erro de cerca de um quarto de batida não era o atraso do LAME (576 amostras são ~13 ms e deslocam o arquivo inteiro), nem o centro do hop (~2,5 ms), nem o atraso de grupo do filtro. O onset de banda cheia dispara na batida e no contratempo. A mediana contra uma grade de semínima, com metade dos picos no contratempo, cai perto de batida/4. Um seguidor que alongava o período a cada snap fazia a fase andar a batida inteira: a mediana com sinal ficava perto de zero e o erro absoluto ficava nos dois lados.

A grade desta versão escolhe o BPM (90 a 180) que põe mais batidas a menos de 18 ms de um pico forte da banda grave (Butterworth de fase zero, 35–180 Hz, máximo local acima do percentil 84). O período fica fixo. Se há um pico a menos de 20 ms, o instante publicado é esse pico; o relógio não o segue. A cobertura é a fração das batidas que tinham esse pico. A mediana e o máximo abaixo são o resíduo depois desse encaixe, por isso ficam em décimas de milissegundo. Não é o erro de cada batida da faixa.

| Faixa | BPM 0.12.0 | BPM 0.12.1 | Mediana kick | Máx. kick | Cobertura | Mediana banda cheia (métrica antiga) |
|---|---|---|---|---|---|---|
| Chromosome Boutique | 139,94 | 162,00 | 0,2 ms | 0,5 ms | 57% | 94,2 ms |
| Ecosystem | 129,37 | 122,00 | 0,2 ms | 0,5 ms | 63% | 124,3 ms |
| Silver | 141,83 | 141,00 | 0,2 ms | 0,5 ms | 72% | 100,6 ms |
| Morning | 117,92 | 118,00 | 0,2 ms | 0,5 ms | 74% | 105,1 ms |
| Ocean | 117,90 | 118,00 | 0,3 ms | 0,5 ms | 83% | 99,3 ms |
| wrist watch | 120,28 | 120,00 | 0,2 ms | 0,5 ms | 84% | 114,5 ms |
| toodaze | 120,24 | 120,00 | 0,3 ms | 0,5 ms | 93% | 70,0 ms |
| Caught In The Beat | 143,23 | 120,00 | 0,3 ms | 0,5 ms | 62% | 112,2 ms |
| At The Count | 152,23 | 152,00 | 0,2 ms | 0,5 ms | 77% | 89,4 ms |

Morning, Ocean e At The Count ficam no mesmo tempo da 0.12.0 (118, 118 e 152). Silver volta a 141. Ecosystem passa a 122. Chromosome passa a 162, que é o tempo em que mais batidas caem num pico grave; 1,5 × 108 ≈ 162. Caught In The Beat passa de 143 para 120: a autocorrelação sem tecto apontava ~160, e a cobertura do kick prefere 120. A mediana de banda cheia continua entre 70 e 124 ms. Essa coluna existe para a métrica antiga não desaparecer.

`tools/measure_reference.py` usa librosa 1.0 (licença ISC) só como régua neste host. Não entra no APK. Vários tempos do librosa saem uma oitava ou uma relação 3:2 abaixo do kick (Chromosome 107,67, Caught 107,67, Silver 143,55, Ecosystem 123,05, Morning e Ocean 117,45, Count 152,00). Não é a verdade da faixa.

## Depois (0.12.1) — tom

O croma continua Goertzel, oitavas 2–6, com mais peso nas graves, nos primeiros 80 quadros altos, e um teste de afinação de −20, 0 e +20 cents. O perfil é o EDMA ou o de Krumhansl, o que tiver mais margem. O peso MIREX usado aqui: 1 tom exato, 0,5 quinta do mesmo modo, 0,3 relativo, 0,2 paralelo, 0 no resto. A média nas três faixas com `TKEY` passa de 0,17 para 0,50.

| Faixa | TKEY | 0.12.0 | 0.12.1 | MIREX | librosa (Krumhansl / EDMA) |
|---|---|---|---|---|---|
| Chromosome Boutique | Bm | Am | Bm | 1,00 | Dm / F |
| Ecosystem | Ebm | Fm | Fm | 0,00 | Fm / Fm |
| Silver | Em | Bm | Bm | 0,50 | Bm / D |

Ecosystem continua Fm. O librosa, nos dois perfis, também diz Fm. O tag diz Ebm. Não forcei o tag contra o áudio. Silver é uma quinta acima do tag e coincide com o Krumhansl do librosa; o EDMA do librosa diz D. O CQT do librosa em Chromosome não coincide com o Goertzel deste app nem com o tag. A afinação medida não é uma rotação de 200 cents.

Nas outras seis, sem `TKEY`: Morning F#m, Ocean Bm, wrist watch F#m, toodaze Am, Caught In The Beat Fm, At The Count Am. O librosa diz, na mesma ordem, F#m, Cm, Gm, Gm, Fm, Dbm. Não há ficha para fechar esses seis.

## Depois (0.12.1) — cor RGB

Cada coluna é soma. R é a energia grave, G a média, B a aguda, depois de um Butterworth de fase zero em 200 Hz e 2,2 kHz. Cada banda divide por um percentil dela na faixa inteira (grave p58, médio p94, agudo p55), e só então recebe peso (grave 1,15, médio 0,88, agudo 1,20) e gama 1,25. O percentil alto no médio impede que a banda larga pinte a faixa de verde. O chão é 12% da referência mais alta, para um resíduo quase vazio não normalizar a 255. A altura é o RMS das três, percentil 72, gama 1,15. A quantização é a do PWV5: 3 bits por canal.

A legenda no INFO e em Sobre é um diagrama desenhado neste app (três círculos em soma): vermelho grave, verde médio, azul agudo; amarelo é linha, ciano é voz, rosa é batida, branco é coro. Não é a imagem enviada.

Média de 81 colunas, já em 3 bits, na região da estrutura. A altura é 0–255.

| Faixa | Build | Drop | Break |
|---|---|---|---|
| Chromosome Boutique | 172, 143, 150 · h 209 | 255, 129, 240 · h 226 rosa | 207, 188, 255 · h 237 rosa-claro |
| Ecosystem | 126, 5, 21 · h 67 | 255, 139, 255 · h 250 rosa | 140, 101, 167 · h 133 |
| Silver | 36, 78, 240 · h 86 azul | 255, 138, 255 · h 246 rosa | 53, 40, 203 · h 69 azul |
| Morning | 0, 17, 2 · h 8 | 225, 122, 28 · h 215 laranja | 0, 33, 0 · h 10 |

Os pixels acesos das mesmas tiras confirmam o mesmo sítio: Chromosome, Ecosystem e Silver no drop são sobretudo rosa; o build de Silver é azul; o drop de Morning é vermelho e amarelo (grave com melodia, pouco agudo nessa janela). O break e o build de Morning, nos segundos da heurística, quase não têm altura. Há colunas só vermelhas, só verdes, só azuis e brancas nas quatro faixas. Não pintei de azul uma janela que não tem chimbal.

Os prints da tela, com essa paleta no PWV5, ficam em `/opt/cursor/artifacts/prints/`:

- drop, build e break de Chromosome Boutique, Ecosystem, Silver e Morning
- `real-browse.png`
- `info-art.png` (legenda no INFO)

## Depois (0.12.1) — emenda do loop

O crossfade passou a potência igual. Na última amostra da rampa o valor é exactamente o que entra no loop, para um cosseno de π/2 em vírgula flutuante não reabrir uma emenda fechada. O áudio que entra é o que está imediatamente antes do início, na mesma fracção de amostra.

No mix de 120 s (Chromosome Boutique a 162 BPM a partir de 116 s, Ecosystem a 122 BPM), a energia da emenda seca foi 0,00125770 e a molhada 0,00000000. O eco ficou em 17778 amostras contra 17777,78 da batida. Pico 0,66616. NaN 0. No teste C++ das nove faixas, loops de 1, 4 e 8 batidas e três fracções de início: 81 emendas, 0 piores que o seco.

O trecho de 45 s foi gerado de novo com esses BPM:

- `/opt/cursor/artifacts/real-mix-120s.wav`
- `/opt/cursor/artifacts/real-mix-excerpt.wav` (45 s)
- `/opt/cursor/artifacts/real-mix-excerpt.mp3`

O relatório bruto, com o carimbo `analise 0.12.1 rgb`, está em `/opt/cursor/artifacts/real-music-report.txt`.

## O que esta passagem não fecha

- Ninguém ouviu o mix nem as faixas neste host. Ouvir no tablet e na DDJ-400 continua `BLOCKED_HARDWARE`.
- O `MediaCodec` não decodificou nada aqui.
- A cobertura do kick fica entre 57% (Chromosome Boutique) e 93% (toodaze). As outras batidas não têm um pico grave a 20 ms. O máximo de 0,5 ms é o resíduo das que tinham.
- A mediana de banda cheia continua perto de 100 ms. Essa métrica conta o contratempo.
- Ecosystem fica Fm contra o `TKEY` Ebm. O detector externo concorda com Fm, não com o tag.
- Caught In The Beat ficou em 120. Uma leitura de chapéu, sem o kick, aponta perto de 160.
- O drop de Morning nesta janela é laranja (grave e médio), não rosa. O build de Silver é o exemplo azul.
- Ocean continua com break e drop no mesmo segundo (316).
- Ajuste de grade, reverse e Active Loop foram exercidos na JVM. Não foram ouvidos.
- O pendrive real do José ainda não chegou. `tools/inspect_usb.py PASTA` e `tools/inspect_usb.py PASTA --jvm` leem uma pasta sem gravar nela. O relatório JVM sai em `/tmp/deckbridge-inspect.txt`.
