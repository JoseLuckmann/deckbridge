# NOTICE

Data da auditoria: 2026-10-21. Nenhum parecer jurídico.

## Código deste repositório

Arquivos em `src/` e `tests/` são nossos. Licença provisória: GPL-2.0-or-later, para poder conviver com o Mixxx. A intenção comercial continua em aberto na OD-06. Ver `LICENSE`.

## Copiado para cá

| Arquivo | Origem | Licença |
|---|---|---|
| `third_party/mixxx/2.5.6/res/controllers/Pioneer-DDJ-400.midi.xml` | mixxxdj/mixxx tag 2.5.6, commit `3ebac449e7e5fe2a0186596657696e87ce8b0e56` | GPL-2.0-or-later, conforme o preâmbulo do `LICENSE` dessa tag |
| `third_party/mixxx/2.5.6/res/controllers/Pioneer-DDJ-400-script.js` | o mesmo commit | a mesma |

| `android/app/src/main/cpp/third_party/dr_flac.h` (v0.13.4) | mackron/dr_libs, commit `b4264c5c1f61c1d460aced4cf4d8a8dec197ca48` | domínio público (Unlicense) ou MIT-0, à escolha; ver o fim do arquivo |
| `android/app/src/main/cpp/third_party/dr_mp3.h` (v0.7.4) | o mesmo commit do dr_libs | a mesma |

Autores no cabeçalho dos dois arquivos de mapping: Warker, nschloe, dj3730, jusko, tiesjan. dr_flac e dr_mp3: David Reid. O SHA-256 do `dr_flac.h` daqui (`96b3dfca…3c9e`) é o do arquivo nesse commit. A entrada dele só foi escrita em 2026-10-25, junto com a do dr_mp3; antes ele entrou sem registro.

## Ligado no Pi (ADR-0007), sem copiar código

| Biblioteca | Como | Licença |
|---|---|---|
| alsa-lib (libasound) | ligada dinamicamente pelo programa do Pi; vem do sistema | LGPL-2.1-or-later |
| SDL2 e SDL2_ttf | ligadas dinamicamente pela tela nativa do Pi (ADR-0008); vêm do sistema | zlib |
| fonte DejaVu | lida do sistema (`fonts-dejavu-core`), não empacotada | licença Bitstream Vera / DejaVu (livre) |

O LAME e o `flac` de linha de comando só geram arquivos de teste neste PC. Não entram em nenhum binário.

## Consultado e não copiado

| Projeto | Licença vista em 2026-10-21 | Código neste repo |
|---|---|---|
| mixxxdj/mixxx 2.5.6 | GPL-2.0-or-later no preâmbulo do `LICENSE` da tag. O detector do GitHub devolve `NOASSERTION` porque o arquivo junta outros avisos. | só os dois arquivos de mapping |
| ghztomash/StandaloneMixxx | a API de licença do GitHub não achou `LICENSE`. A raiz do repo também não mostra esse arquivo. | nenhum; não copiar até haver licença |
| usr-ein/TriMixxx | GPL-3.0 no branch padrão | nenhum |
| nauticsoftware/NauticMixxx | GPL-3.0 no branch padrão | nenhum |
| dylanljones/pyrekordbox | MIT no branch padrão | nenhum |
| xsco/libdjinterop | o arquivo `LICENSE` do branch `main` é a LGPL versão 3. A frase "or later" do spec não foi revalidada arquivo a arquivo. | nenhum |
| fayaaz/mixxx-pi-gen | BSD-3-Clause no branch padrão | nenhum |
| skins do Mixxx | o `LICENSE` do Mixxx diz que cada skin declara a licença no próprio `skin.xml`, inclusive Creative Commons | nenhuma skin copiada |
| codecs (MAD, FFmpeg e outros) | dependem de como o Mixxx é compilado | nenhum codec copiado; auditoria arquivo a arquivo não feita |

ASIO e o runtime da Microsoft aparecem no `LICENSE` do Mixxx como peças de Windows. Não entram numa build Linux.

O LAME (LGPL) não é empacotado. A gravação WAV é código deste repositório. O AAC opcional, na versão 0.9.0, pede o codificador AAC do Android (`MediaCodec`), que não é distribuído dentro do APK. OD-06 continua aberta; por isso o LGPL do LAME não foi combinado com a licença provisória.

Fixtures em `fixtures/audio/` são tom senoidal gerado aqui, dedicado a CC0-1.0. Não são músicas de terceiros.

Pioneer, DDJ, rekordbox e AlphaTheta são marcas de terceiros. O nome DeckBridge não pede endosso delas.
