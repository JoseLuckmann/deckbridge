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

## Tom — 0.13.1

O detetor que estava no app lia Goertzel até cerca de 1 kHz e um EDMA esparso, que não é o perfil publicado. No mesmo corte de 58, isso dava MIREX 0,476. A escolha dos parâmetros usou só o dev: dentro de cada família, os ids ordenados, o índice 2, 5, 8… é holdout; família de uma faixa também vai para o holdout. São 42 no dev e 16 no holdout. O extra (190 prévias, `tools/fetch_key_extra.py`, `manifest-key-extra.tsv`) não entrou na escolha.

Cada alavanca foi medida no dev e revertida quando não subia o MIREX. Ficou um CQT de 12 bandas por oitava, com janela de cerca de 67 ciclos, de 32,7 Hz a 5 kHz, salto de 4096 amostras, logaritmo da magnitude, peso 1,7 abaixo de 180 Hz, e correlação de Pearson com o EDMA publicado (Faraldo e outros). Núcleos em float. Um seno puro continua a sair como maior. Foram medidos e não ficaram: HPCP com picos e harmónicos 4 e 8, branqueamento, separação harmónico/percussivo, gate de croma, EDMM, bgate, Shaath, Krumhansl, Temperley, viés para menor e busca de afinação de ±30 cents. O Q baixo (uma janela da largura de um semitom) também não separava um seno de 440 Hz.

| Corte | n | MIREX | exato |
|---|---:|---:|---:|
| 0.13.0, as 58 | 58 | 0,476 | 0,397 |
| 0.13.1, dev | 42 | 0,636 | 0,524 |
| 0.13.1, holdout | 16 | 0,456 | 0,312 |
| 0.13.1, as 58 | 58 | 0,586 | 0,466 |
| 0.13.1, extra | 190 | 0,519 | 0,437 |
| 0.13.1, 58 mais extra | 248 | 0,535 | 0,444 |

Nas 58, o Camelot compatível passou de 0,552 para 0,759. O tempo não mudou: Acc1 0,655 e Acc2 0,724. A Essentia KeyExtractor EDMA, na medição já feita destas 58, fica em 0,581 / 0,466. O librosa CQT com o mesmo EDMA e Pearson, refeito nesta passagem, deu 0,591 / 0,483. O alvo de igualar a Essentia cumpre-se neste corte de 58 (0,586). Não se cumpre no holdout nem nas 190 faixas novas. O 0,65 não apareceu.

| Família | n | MIREX 0.13.0 | MIREX 0.13.1 | exato 0.13.1 |
|---|---:|---:|---:|---:|
| house | 10 | 0,660 | 0,630 | 0,500 |
| techno | 7 | 0,257 | 0,471 | 0,429 |
| trance | 8 | 0,563 | 0,575 | 0,375 |
| drum-and-bass | 10 | 0,350 | 0,620 | 0,600 |
| dubstep | 5 | 0,800 | 0,560 | 0,400 |
| electronica | 7 | 0,314 | 0,786 | 0,714 |
| glitch-hop | 5 | 0,400 | 0,620 | 0,400 |

Erros nas 58. Quinta, relativo e paralelo usam o mesmo critério do peso MIREX.

| | exato | quinta | relativo | paralelo | outro |
|---|---:|---:|---:|---:|---:|
| 0.13.0 | 23 | 7 | 1 | 4 | 23 |
| 0.13.1 | 27 | 9 | 7 | 2 | 13 |

Techno e drum and bass deixam de ser o buraco do corte pequeno: o techno sobe de 0,257 para 0,471, o drum and bass de 0,350 para 0,620. House desce de 0,660 para 0,630 e dubstep de 0,800 para 0,560. No extra, o techno volta a ser fraco (0,363 em 30) e o drum and bass fica em 0,536 em 28. O balde «outro» desce de 23 para 13 nas 58, mas ainda são 60 em 190 no extra. Os paralelos são 2 nas 58 e 20 no extra.

No host, a análise completa (tempo, tom, frases e LUFS) mediu 283 ms por prévia de cerca de 2 min. A estimativa para o Tab S6 Lite, a 2–4 vezes este núcleo, fica entre cerca de 0,6 s e 1,1 s. Não foi medido no tablet. O alvo de cerca de 2 s, nessa estimativa, cabe.

Chromosome Boutique, `TKEY` Bm: o 0.13.1 lê F e 154,20 BPM. Não é Bm. O 0.12.1 lia Bm e o 0.13.0 lia Am. Ecosystem, `TKEY` Ebm: lê Cm e 122,00 BPM (o 0.13.0 lia Fm). Silver, `TKEY` Em: lê D e 141,00 BPM (o 0.12.1 lia Bm, uma quinta). Caught In The Beat continua 160,00 e Fm, sem `TKEY`.

O piso do `GroundTruthTest` nas 58 passa a MIREX ≥ 0,56. O extra, quando as prévias estão no disco, pede MIREX ≥ 0,48. Não travam 0,58 nem 0,65.

## O que fica aberto

- Acc1 0,655 e Acc2 0,724 continuam abaixo de 0,85 e 0,95. O MIREX 0,586 nas 58 fica abaixo de 0,65 e de 0,70.
- O 0,586 não se repete fora do corte em que o dev foi afinado: holdout 0,456 (16 faixas) e extra 0,519 (190).
- O erro que resta no tempo é de métrica (2/3, 3/4, 3/2), não de oitava. O pente do kick não vê a semínima quando o kick não cai nela, como em vários drum and bass.
- Chromosome Boutique, Ecosystem e Silver não batem o `TKEY` (F, Cm e D).
- Não há hip-hop com BPM publicado neste corpus; glitch-hop é o substituto, e o Acc1 dele é 0,400.
- O áudio não foi ouvido. HIL `BLOCKED_HARDWARE`. A estimativa de tempo no tablet não é uma medição do aparelho.
