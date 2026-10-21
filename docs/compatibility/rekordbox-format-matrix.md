# Matriz semântica rekordbox — leitura do Mixxx 2.5.6

**Fonte lida:** `src/library/rekordbox/rekordboxfeature.cpp` no commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56` (tag `2.5.6`).  
**O que isto não é:** importação executada. Nenhum `export.pdb` real foi aberto. A tarefa T016, que compara um pendrive de verdade, está `BLOCKED_HARDWARE`.

Números de linha referem-se a esse arquivo nessa revisão. Se o upstream mover as linhas, vale o commit, não o número.

## Onde o Mixxx procura a biblioteca

| Dado no USB | O que o código faz | Linha |
|---|---|---|
| `PIONEER/rekordbox/export.pdb` | caminho fixo da base lida | 44 |
| tabelas de keys, genres, artists, albums, playlist entries, tracks, playlist tree, history | percorre as páginas do PDB | 512–519 |
| caminho da análise no registro da faixa | monta o caminho do ANLZ | 378 |
| arquivo irmão com sufixo `EXT` | se existir, separa grade e cues | 1270–1277 |

BPM gravado no PDB: `tempo / 100` (linha 371). Playlist interna usa o separador `-->` (linha 45).

## ANLZ — o que entra e o que o leitor ignora

`readAnalyze` (linha 873) trata três seções. As outras, inclusive waveforms (`WAVE_*`) e estrutura da música, caem no `default` e não são aplicadas.

| Seção | Quando `ignoreCues` é falso | Quando `ignoreCues` é verdadeiro |
|---|---|---|
| `BEAT_GRID` | ignorada | vira batidas do Mixxx, subversão `Rekordbox USB drive` |
| `CUES` | lida | ignorada |
| `CUES_2` | lida, com comentário e cor | ignorada |

Chamada (linhas 1272–1277):

- Se existe `ANLZ....EXT`: o `.DAT` é lido só para a grade (`ignoreCues=true`) e o `.EXT` é lido para os cues (`ignoreCues=false`). O comentário no código diz que a grade só parece correta no arquivo legado.
- Se não existe `.EXT`: o `.DAT` é lido com `ignoreCues=false`. Nesse ramo a grade é pulada.

Isso é o que o fonte faz. Não foi conferido num pendrive.

## Cues e loops — significado no código

Posição: o tempo inteiro do ANLZ menos `timingOffset`, com piso 1, vezes a taxa de amostragem em kHz. Vira `FramePos`.

| Entrada rekordbox | Destino no código do Mixxx |
|---|---|
| hot cue em `CUE_LIST_TYPE_HOT_CUES` | `setHotCue` no índice `hot_cue() - 1`, fim inválido, tipo hot cue |
| memory cue | entra numa lista; o primeiro da lista em ordem de tempo, se não tiver fim, vira o Main Cue |
| memory cue seguinte, ou loop | `lastHotCueIndex` aumenta e `setHotCue` usa esse índice |
| loop (`CUE_ENTRY_TYPE_LOOP`) | tem posição final; `setHotCue` pede tipo Loop quando o fim é válido |

`CUES_2` repete a mesma regra e ainda copia comentário e cor. A cor do hot cue estendido vem dos bytes vermelho, verde e azul. A cor de memory cue/loop vem de `color_id`.

Índices de cor no fonte: Pink=1, Red=2, Orange=3, Yellow=4, Green=5, Aqua=6, Blue=7, Purple=8. O valor de Red no fonte está escrito `0xF870900` (linha 59). Foi copiado como está, sem correção.

O comentário nas linhas 1080–1081 fala do comportamento antigo do Mixxx 2.3 (tratar loop como hot cue). O código de `setHotCue` (linhas 853–856), na 2.5.6, pede `CueType::Loop` quando há fim. Qual dos dois a tela mostra ao tocar **não foi executado**.

## Deslocamento de tempo do MP3

Só entra se o arquivo termina em `.mp3` (linha 1227). O valor depende de flags de compilação:

| Flag no fonte | Casos e offsets escritos |
|---|---|
| `__COREAUDIO__` | A=12, B=13, C=26, D=50; `.m4a` ganha 48 à parte |
| `__MAD__` | A e D = 26 |
| `__FFMPEG__` | D = 26 |

WAV não passa por esse `if`. Como o Mixxx desta fase **não foi compilado aqui**, o offset da nossa build é `UNKNOWN`.

## Prioridade de áudio (OD-03, 21/10/2026)

MP3 e WAV são a prioridade de testes. O deslocamento acima só é questão de MP3. FLAC e AIFF não foram o foco desta leitura.

## O que falta para tratar isto como fato de produto

- Pendrive exportado no rekordbox (T014), com a versão que o proprietário ainda não informou (OD-04).
- Importar no Mixxx pinado e comparar cues, loops e grade (T016), sem escrever no pendrive.
- Até lá, esta matriz é mapa do fonte, com estado `BLOCKED_HARDWARE` para a prova.
