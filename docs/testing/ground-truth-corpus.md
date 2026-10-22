# Corpus com BPM e tom publicados — 0.12.2

Medido em 22/10/2026 no JVM deste host. Não é o tablet. Não é a DDJ-400. A escuta continua `BLOCKED_HARDWARE`. O áudio foi decodificado com ffmpeg para WAV mono 22050 Hz e lido por `decodeWavMono`. O APK, no aparelho, usa `decodeTrackPcm` (WAV direto, MP3/FLAC/AAC/M4A pelo MediaCodec) e depois o mesmo `analyzeTrack`. O MediaCodec não rodou neste host.

## O que entra e o que fica de fora

86 prévias de 2 minutos do GiantSteps (Knees e outros, ISMIR 2015), em `/opt/cursor/artifacts/ground-truth`. Não entram no git.

| Peça | Origem | Licença |
|---|---|---|
| Tempo | [giantsteps-tempo-dataset](https://github.com/GiantSteps/giantsteps-tempo-dataset), `annotations_v2/tempo/*.LOFI.bpm` | CC BY-SA 4.0, segundo o espelho do mirdata |
| Tom | [giantsteps-key-dataset](https://github.com/GiantSteps/giantsteps-key-dataset), `annotations/key/*.LOFI.key` | CC BY-SA 4.0, segundo o espelho do mirdata |
| Género | `annotations/genre/*.LOFI.genre` nos mesmos repositórios | a mesma |
| Áudio | `https://www.cp.jku.at/datasets/giantsteps/backup/{id}.LOFI.mp3` | prévia Beatport redistribuída pela JKU para investigação. Não é faixa CC. O MD5 do repositório é conferido |

`tools/fetch_ground_truth.py` clona nada sozinho: os dois repositórios já têm de estar em `/tmp/giantsteps-tempo-dataset` e `/tmp/giantsteps-key-dataset`. O script baixa o MP3, confere o MD5, converte para mono e escreve `manifest.json` e `manifest.tsv`.

O GiantSteps não tem hip-hop direto. O substituto usado é glitch-hop. House junta house, deep-house, tech-house, electro-house e progressive-house. Trance inclui psy-trance. Techno inclui minimal e hardcore-hard-techno.

Das 86 faixas, 58 têm BPM publicado e 58 têm tom publicado. Não são as mesmas 58: umas só têm tempo, outras só têm tom. A medição de tempo usa as 58 com BPM. A de tom usa as 58 com tom.

Acc1: `|previsto − ficha| / ficha ≤ 4%`. Acc2: o mesmo, ou o previsto a um fator 2, 1/2, 3 ou 1/3. Erro de oitava: o fator é 2 ou 1/2 e o Acc1 falha. O peso MIREX do tom já estava no app: 1 certo, 0,5 quinta no mesmo modo, 0,3 relativo, 0,2 paralelo, 0 o resto. Camelot compatível usa `keysCompatible` (mesmo código, ou números a uma casa, com volta).

## Tempo — antes e depois

Antes é o escolhedor da 0.12.1: a autocorrelação era calculada e ignorada; o BPM era o inteiro de 90 a 180 cuja grelha caía em mais picos graves. Esse critério prefere 3/4 e 3/2 quando há picos entre as batidas. Por isso a oitava era rara (6,9%) e o erro de 3/4, que o Acc2 não perdoa, era o grosso das falhas.

Depois é o que o APK 0.12.2 corre: pente no envelope do kick (35–180 Hz) vezes a autocorrelação, vezes um prior largo (gaussiana em oitavas, centro 124, desvio 1,2), na faixa 70–180, com um ajuste local de ±0,8 e um refino de no máximo 3 BPM preso aos kicks.

| | n | Acc1 | Acc2 | oitava |
|---|---:|---:|---:|---:|
| Antes (cobertura do kick) | 58 | 0,603 | 0,672 | 0,069 |
| Depois (pente × autocorrelação) | 58 | 0,655 | 0,724 | 0,069 |

| Família | n | Acc1 antes | Acc1 depois | Acc2 antes | Acc2 depois |
|---|---:|---:|---:|---:|---:|
| house | 10 | 0,800 | 1,000 | 0,800 | 1,000 |
| techno | 7 | 1,000 | 1,000 | 1,000 | 1,000 |
| trance | 8 | 0,875 | 1,000 | 0,875 | 1,000 |
| drum-and-bass | 10 | 0,500 | 0,400 | 0,600 | 0,600 |
| dubstep | 5 | 0,400 | 0,400 | 0,400 | 0,400 |
| electronica | 7 | 0,143 | 0,429 | 0,429 | 0,714 |
| glitch-hop | 5 | 0,200 | 0,400 | 0,400 | 0,400 |
| chill-out | 2 | 1,000 | 0,000 | 1,000 | 0,000 |
| dj-tools | 2 | 0,500 | 0,500 | 0,500 | 0,500 |
| hard-dance | 1 | 0,000 | 0,000 | 0,000 | 0,000 |
| pop-rock | 1 | 1,000 | 1,000 | 1,000 | 1,000 |

House, techno e trance fecham. Drum and bass, dubstep, glitch-hop e electronica continuam a falhar, quase sempre num fator 2/3, 3/4 ou 3/2, que não conta como Acc2. O prior ajuda a house e puxa 174 BPM para perto de 120 quando o pente do kick não tem um pico claro na semínima. Alargar a busca para 60–200, para apanhar fichas de 66 e 197, foi medido no mesmo código e descartado: o Acc1 caiu para 0,517 e a house deixou de fechar.

O alvo pedido era Acc1 ≥ 85% e Acc2 ≥ 95%. Não foi atingido. O teste `GroundTruthTest` trava o piso que este detetor segura: Acc1 ≥ 0,60, Acc2 ≥ 0,68, MIREX ≥ 0,42. Não trava o alvo.

## Tom — antes e depois

O parser de “G minor” / “Gb minor” foi corrigido antes das duas colunas (antes virava “Gmor” e o MIREX ia a zero por um erro de nome). As duas colunas medem o detetor.

| | n | MIREX | exato | Camelot |
|---|---:|---:|---:|---:|
| Antes | 58 | 0,457 | 0,379 | 0,517 |
| Depois | 58 | 0,476 | 0,397 | 0,552 |

O depois usa os frames altos da faixa inteira (até cerca de 220), afinação de −30 a +30 cents em passos de 10, e escolhe, pela margem, entre EDMA, Krumhansl e Temperley. Trocar a margem pela correlação de Pearson baixou o MIREX para 0,469 e foi revertido.

| Família | n | MIREX antes | MIREX depois | exato depois |
|---|---:|---:|---:|---:|
| house | 10 | 0,610 | 0,660 | 0,600 |
| techno | 7 | 0,314 | 0,257 | 0,143 |
| trance | 8 | 0,563 | 0,563 | 0,375 |
| drum-and-bass | 10 | 0,450 | 0,350 | 0,300 |
| dubstep | 5 | 0,700 | 0,800 | 0,800 |
| electronica | 7 | 0,386 | 0,314 | 0,286 |
| glitch-hop | 5 | 0,200 | 0,400 | 0,400 |

O alvo 0,70 não foi atingido.

## Réguas fora do APK

Não entram no binário.

| Régua | Tempo Acc1 | Tempo Acc2 | Tom MIREX | Tom exato |
|---|---:|---:|---:|---:|
| Este app, depois | 0,655 | 0,724 | 0,476 | 0,397 |
| librosa 1.0 `beat_track` | 0,448 | 0,552 | — | — |
| librosa `feature.tempo`, prior em 120, desvio 1,0 | 0,431 | 0,483 | — | — |
| librosa `feature.tempo`, prior em 140, desvio 1,0 | 0,603 | 0,707 | — | — |
| librosa chroma CQT + EDMA (Pearson) | — | — | 0,593 | 0,483 |
| librosa chroma CQT + Krumhansl | — | — | 0,571 | 0,466 |
| librosa chroma CQT + Temperley | — | — | 0,407 | 0,293 |
| Essentia 2.1b6 `RhythmExtractor2013` multifeature | 0,569 | 0,810 | — | — |
| Essentia 2.1b6 `KeyExtractor` perfil EDMA | — | — | 0,581 | 0,466 |

A Essentia acerta mais oitavas no Acc2 e erra mais o Acc1 (oitava 0,241). O teto de tom destas réguas, neste corte de 58 faixas, fica perto de 0,59. O 0,70 não aparece nem na Essentia nem no CQT do librosa.

## Chromosome Boutique e Caught In The Beat

Não há BPM escrito pelo artista.

Chromosome Boutique (Fature, Choice by Definition, CC BY 3.0, [FNet018](https://archive.org/details/FNet018)): o ID3 tem `TKEY` `B m` e não tem `TBPM`. A descrição do item não publica BPM. O JSON de Essentia que o Internet Archive gravou em 2014 (`01.Chromosome_Boutique_esslow.json.gz`, Essentia 2.1-beta1) diz BPM 80,7, com segundo pico do histograma em 120, e o tom A# maior. O intervalo mediano das batidas desse JSON corresponde a cerca de 108 BPM, não a 80,7. A Essentia 2.1b6 neste wav mono diz 81,0 e F maior. O app 0.12.0 lia 139,9. O 0.12.1, pela cobertura do kick, lia 162, perto do dobro de 81. O 0.12.2 lê 154,2 e o tom Am. O 140 da dúvida é a leitura antiga deste app, não uma ficha. 154, 162 e 81 não fecham entre si.

Caught In The Beat (Broke For Free, Slam Funk, CC BY 3.0, [Slam Funk](https://archive.org/details/Slam_Funk-7603)): o ID3 não tem `TBPM`. A página do item, a Free Music Archive, o Bandcamp e o MusicBrainz (`5222ddbe-1e73-49d5-b0f8-b7d2d60e6d01`) não publicam BPM. O 0.12.0 lia 143,2. O 0.12.1 lia 120. 120 é 3/4 de 160: a cobertura do kick preferiu a subdivisão. O 0.12.2 lê 160,0 e Fm. A Essentia 2.1b6 no mesmo wav lê 161,6 e C menor. O tempo de 0.12.2 e o da Essentia concordam perto de 160. O tom não concorda, e não há `TKEY`.

## Pasta sem export

Se `loadRekordbox` devolve `E_RB_EXPORT_NOT_FOUND` e a pasta tem áudio solto, o app corre `loadPlainFolder` fora da thread de UI. A nota começa em “A analisar n/total” e termina em “Pasta de áudio, sem export do rekordbox”. O cache é `cacheDir/plain-analysis/0122-{sha256}.txt` e guarda BPM, tom, batidas e a miniatura RGB. A segunda abertura não volta a decodificar. A onda ao vivo também tem o detalhe RGB; o cache restaurado repõe a miniatura, que é o que o browser desenha.

Prints, neste host, com quatro prévias GiantSteps renomeadas (House, Techno, Trance, Drum And Bass):

- `/opt/cursor/artifacts/prints/plain-browse-progress.png` — “A analisar 0/4”, BPM e tom ainda em “—”
- `/opt/cursor/artifacts/prints/plain-browse.png` — BPM e tom preenchidos e miniatura RGB. Nesta passagem: House 128,0 Gm (ficha 127, G minor), Techno 120,0 Cm (121, C minor), Trance 140,0 Dm (142, D minor), Drum And Bass 174,0 Gm (174, G minor)

É uma renderização Robolectric. Não é o tablet.

## O que fica aberto

- Acc1 0,655, Acc2 0,724 e MIREX 0,476 ficam abaixo de 0,85, 0,95 e 0,70.
- O erro que resta no tempo é de métrica (2/3, 3/4, 3/2), não de oitava. O pente do kick não vê a semínima quando o kick não cai nela, como em vários drum and bass.
- O tom deste app é Goertzel por oitavas, não um HPCP. A régua com CQT também não chega a 0,70 neste corte.
- O tom de Chromosome Boutique deixou de coincidir com o `TKEY` (Bm no 0.12.1, Am no 0.12.2).
- Não há hip-hop com BPM publicado neste corpus; glitch-hop é o substituto, e o Acc1 dele é 0,400.
- O áudio não foi ouvido. HIL `BLOCKED_HARDWARE`.
