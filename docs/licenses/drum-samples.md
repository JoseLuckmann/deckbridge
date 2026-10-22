# One-shots do drum build — 0.14.1

Os arquivos em `android/app/src/main/assets/drums/` são o que o APK toca. Não há sample, preset nem áudio da Pioneer. O fallback, se um arquivo não abrir, é modelagem física no motor (`DrumBank::ensureModel`): bumbo com envelope de pitch 150→50 Hz, clique de ruído e saturação; palma com quatro rajadas de ruído passa-banda espaçadas cerca de 10 ms e uma cauda; caixa com tom mais ruído; chimbal com ruído passa-altas. A mix de demonstração usa os arquivos abaixo, não esse fallback.

Cada hit é mono, 48 kHz, FLAC 16 bit. O player lê com interpolação, velocidade por ataque, e um fade curto no fim do buffer para não estalar. A camada de palma e a cauda de sala foram feitas aqui, em cima das gravações.

## Bumbo, caixa e chimbal

Fonte: [Karoryfer Big Rusty Drums](https://github.com/sfzinstruments/karoryfer.big-rusty-drums), licença [CC0 1.0](https://github.com/sfzinstruments/karoryfer.big-rusty-drums/blob/main/LICENSE). São gravações de uma bateria, com várias velocidades e round-robins, em FLAC. Não é um pack “royalty-free” com cláusula extra.

| Arquivo nosso | Arquivo de origem | O que foi feito |
| --- | --- | --- |
| `kick.flac` | `Samples/kick_24/kick/kick/k_vl14_rr2.flac` | 44,1 kHz → 48 kHz, passa-altas a 28 Hz, um pouco do agudo que já estava no ataque, saturação leve, corte por volta de 300 ms com fade |
| `snare.flac` | `Samples/snare_14/center/top/sn_center_vl10_rr4.flac` e `Samples/snare_14/rimshot/top/sn_rims_vl6_rr1.flac` | centro mais rimshot mais baixo, corte por volta de 380 ms; depois um soft clip (drive 2,4, pico 0,92) para o corpo da caixa sentar perto do bumbo no roll |
| `hat-closed.flac` | `Samples/hihat_14/cl/cl/ht_cl_vl6_rr4.flac` | corte por volta de 130 ms |
| `hat-open.flac` | `Samples/hihat_14/open/oh/ht_open_vl6_rr1.flac` | corte por volta de 620 ms |

O bumbo de origem tem corpo perto de 44 Hz e o pico do ataque a cerca de 8 ms. É um bumbo acústico, não um seno. O corte e a saturação deixam ele mais curto para o roll, sem trocar o timbre por um oscilador.

## Palma

Não achei um WAV/FLAC de palma em CC0 para baixar sem conta. O Freesound exige login para o arquivo original. Usei a prévia oficial de alta qualidade (MP3 128 kbps) de três gravações CC0 e reamostrei para 48 kHz FLAC. A prévia é lossy: o ataque é o da gravação, com o teto desse MP3. Isso fica dito aqui para não parecer um master de 24 bit.

| Camada | Fonte | Licença |
| --- | --- | --- |
| corpo | [handclap](https://freesound.org/people/Frederik_Sunne/sounds/438968/) de Frederik_Sunne, id 438968 | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) |
| segunda pancada, cerca de 10 ms depois | [Clap 01.wav](https://freesound.org/people/totalcult/sounds/388546/) de totalcult, id 388546 | CC0 1.0 |
| terceira, cerca de 21 ms depois | [Layered Clap](https://freesound.org/people/aulix24/sounds/858298/) de aulix24, id 858298 | CC0 1.0 |

`clap.flac` soma as três e acrescenta três repetições curtas (13, 19 e 27 ms), filtradas, como sala pequena. O arquivo final dura cerca de 260 ms.

Prévia usada, não o WAV original:

- `https://cdn.freesound.org/previews/438/438968_2845145-hq.mp3`
- `https://cdn.freesound.org/previews/388/388546_14360-hq.mp3`
- `https://cdn.freesound.org/previews/858/858298_13691143-hq.mp3`

## O que não entrou

- PhaseForge “Snare & Clap Specialist”: o ZIP diz CC0 e, no mesmo `LICENSE.txt`, proíbe redistribuir os sons como biblioteca. Não usei.
- SFXMint: o site marca os arquivos como CC0 e descreve o catálogo como gerado ou sintetizado. Não usei no lugar de gravação.
- Zenodo 4958592 (29k drum samples): a licença do registro é CC BY 4.0, e o zip passa de 900 MB. Não usei.
- Qualquer pack só “royalty-free” com restrição de revenda, e qualquer sample de equipamento Pioneer.
