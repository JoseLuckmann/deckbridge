# Formato rekordbox — o que entrou no app

O leitor de `export.pdb` e de ANLZ (`.DAT` / `.EXT`) foi escrito neste repositório, em Kotlin, a partir da descrição pública do formato.

Não foi copiado código destas bibliotecas. A licença de cada uma foi lida em 21/10/2026:

| Projeto | Licença vista | Uso aqui |
|---|---|---|
| Crate Digger (Deep Symmetry), inclusive o arquivo Kaitai | EPL-2.0 OR MPL-2.0 OR LGPL-3.0-only | não copiado |
| rekordcrate | MPL-2.0 | não copiado |
| pyrekordbox | MIT | não copiado |

A fixture `export.pdb` e os ANLZ são sintéticos, gerados pelo próprio app em `SyntheticRekordbox`. Não há música comercial. Os WAV são cliques gerados aqui.

`exportExt.pdb` é o mesmo contêiner DeviceSQL, com as tabelas públicas de My Tags: tipo 3 (tag ou categoria) e tipo 4 (ligação faixa–tag). O nome perto fica no byte `0x1D`; o nome longe, quando o subtipo tem o bit `0x04`, fica no offset de 16 bits em `0x1E`. A descrição usada é a análise pública do formato. O código do Crate Digger e do rekordcrate não foi copiado.

`exportLibrary.db` (OneLibrary / Device Library Plus) é SQLCipher. A documentação pública do pyrekordbox (MIT) diz que a chave é a mesma em toda exportação e não depende de licença nem de máquina. O app copia o arquivo para o cache, abre essa cópia só para leitura com essa chave e apaga a cópia. Não grava WAL no pendrive. Não usa a chave do `master.db`. Se a cópia não abrir, a tela diz isso. O arquivo sintético deste repositório não é uma base SQLCipher, então o teste dele continua sem leitura. Um `exportLibrary.db` real ainda não foi aberto aqui.

OD-04 (versão do rekordbox do dono) continua aberta. Um pendrive real ainda não foi lido. Isso fica `BLOCKED_HARDWARE`.

## Capa (0.10.0)

A leitura segue a descrição pública em <https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/exports.html>. Não foi copiado código nem o esquema Kaitai.

- A tabela de tipo `0x0d` guarda a capa. Cada linha começa com o id (`u32` little-endian) e, logo a seguir, a string DeviceSQL com o caminho. Não é um offset para o heap.
- Na faixa, `artwork_id` está nos bytes `0x1c`–`0x1f`. Zero significa sem capa.
- O caminho aponta para a imagem de 80×80. Um irmão com `_m` antes da extensão é a de 240×240, quando existe. O leitor prefere o `_m` se o ficheiro estiver no volume.
- A exportação real costuma ser JPEG. A biblioteca sintética deste repositório grava PNG gerado aqui, no caminho `PIONEER/Artwork/`. Não há arte de terceiros.
- A miniatura (80 px) fica no cache do app. O volume só é lido. Sem bytes, ou se a imagem não abrir, o ecrã mostra o ♪ que já era o desenho de fallback.

## Onda colorida (0.11.0)

A leitura é deste projeto, a partir da descrição pública. Não foi copiado código de parser. As fixtures são geradas aqui. Um export real ainda não passou por este leitor.

Fontes citadas no código (`WaveColor.kt`):

- ANLZ: <https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html>
- Pedido da onda (PWV4, seis bytes por coluna): <https://djl-analysis.deepsymmetry.org/djl-analysis/track_metadata.html>
- Cor e posição do playhead em `DEVSETTING.DAT`: <https://docs.rs/rekordcrate/latest/rekordcrate/setting/enum.WaveformColor.html> e `WaveformCurrentPosition` no mesmo módulo
- `MYSETTING*.DAT` não lista a cor: <https://pyrekordbox.readthedocs.io/en/latest/formats/mysetting.html>

Cabeçalho comum da tag: fourcc, `len_header` u32 big-endian, `len_tag` u32 big-endian. O corpo que este leitor usa começa em tag+12.

- PWV5 (detalhe RGB, cerca de 150 colunas por segundo): em tag+12 `entryBytes` = 2, tag+16 a contagem, tag+20 um u32 (nas fixtures `0x00960305`), dados em tag+24. Cada entrada é um u16 big-endian: 3 bits R, 3 G, 3 B, 5 de altura, 2 bits baixos sem uso. A escala é `n * 255 / 7` e `n * 255 / 31`.
- PWV4 (prévia RGB, em geral 1200 colunas): `entryBytes` = 6, dados em tag+24. Os seis bytes são da mesma coluna. Os bytes 3, 4 e 5 são grave, médio e agudo. A altura é o máximo dos três. A cor mistura esses três bytes (grave puxa laranja, médio verde, agudo branco-azul). Não é o desenho de um parser de terceiros.
- PWV3 (detalhe azul, 150 colunas por segundo): um byte, brancura nos 3 bits altos e altura nos 5 baixos, dados em tag+24.
- PWV7 (detalhe de 3 bandas): três bytes por coluna, médio, agudo, grave, nessa ordem. Dados em tag+24.
- PWV6 (prévia de 3 bandas): o mesmo trio, dados em tag+20. Não há o u32 extra entre a contagem e os bytes.
- PWAV / PWV2: o leitor antigo continua no corpo (tamanho e depois os bytes). Altura nos 5 bits baixos. A prévia azul usa esses bits.
- PWVC entra no `.2EX` e não é desenhado.
- PWB3 continua a ser uma secção deste projeto. Um export real não a traz.

A onda grande, por modo: RGB usa PWV5 e, se faltar, PWV4. 3 BANDAS usa PWV7 e, se faltar, PWV6. AZUL usa PWV3 e, se faltar, PWAV. O cartão do deck usa a prévia do mesmo modo (PWV4, PWV6 ou PWAV). Se a tag daquele modo não está no volume, o app calcula três bandas a partir do WAV mono (passa-baixas em 180 Hz e 4000 Hz, ida e volta, duas e três passagens, um pico global) e o INFO mostra “analisado no app”. O volume não é escrito.

A preferência de cor não é lida de `MYSETTING.DAT`, `MYSETTING2.DAT` nem `DJMMYSETTING.DAT`: essas páginas públicas não documentam o byte. O app lê só `PIONEER/DEVSETTING.DAT` ou `PIONEER/rekordbox/DEVSETTING.DAT`. Cabeçalho de 104 bytes (96 de texto, little-endian). O byte de cor é o offset 104+10: `0x01` azul, `0x03` RGB, `0x04` 3 bandas. A posição do playhead é o offset 104+13: `0x01` centro (50% nesta tela), `0x02` esquerda (20%). Qualquer outro byte fica sem modo. O menu pode desligar “Usar configuração do pendrive”.

A tag desconhecida, com comprimento válido, é ignorada. `.DAT` ausente gera aviso; o leitor ainda tenta `.EXT` e `.2EX`. Página de 2048, 4096 ou 8192 bytes é aceite. Um buffer que não é PDB devolve erro em vez de derrubar o processo.

O corpus de variantes (páginas, títulos longos e unicode, arte ausente, ANLZ ausente, `.EXT`/`.2EX`, PWV3/PWV5/PWV7, cues com e sem comentário e cor, memory cue e loop, playlists, linha apagada, caminhos com caracteres fora do ASCII, 5.000 faixas e fuzz) é gerado por `usbVariantReport()`. “Estilo rekordbox 5/6/7” aqui significa quais tags estão presentes, não um campo de versão lido de um export daqueles programas. Os caminhos exFAT/FAT32 são cadeias dentro do PDB, não um disco montado.

## Export deste app (0.12.0)

`ourMusicVolume` grava um PDB e ANLZ nossos, com PWV4, PWV5, PWV6, PWV7, PWV3, PWAV, hot cues, memory cues e um loop, a partir da análise deste repositório. A pasta `/opt/cursor/artifacts/real-music-usb` é esse export. Não é uma cópia do rekordbox e não deve ser tratada como uma. O deslocamento e o estica da grade (G−, G+, S−, S+, G0) ficam na memória do app. O volume não recebe esses bytes.

`inspectText` e `tools/inspect_usb.py` leem uma pasta e imprimem faixas, playlists, cues, tags de onda, `DEVSETTING.DAT` e erros. Não criam arquivo dentro dela. O relatório do `--jvm` sai em `/tmp/deckbridge-inspect.txt`.

## Gravação

O WAV estéreo de 16 bits é escrito por este projeto. O LAME não entra no APK: a licença do repositório é GPL-2.0-or-later provisória, o OD-06 continua aberto, e não se vincula o LGPL do LAME enquanto isso não estiver fechado. O AAC opcional usa o `MediaCodec` do Android (AAC-LC, contentor MPEG-4). A gravação e o histórico ficam na pasta do app ou numa pasta que o utilizador escolhe. O USB do rekordbox não é destino.
