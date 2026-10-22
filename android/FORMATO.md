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

## Onda colorida (0.9.0)

A leitura de PWV5, PWV3 e PWV7 é deste projeto, a partir da descrição pública. Não foi copiado código de parser. Só foi exercida nas fixtures geradas aqui. Um export real ainda não passou por este leitor.

No corpo da secção, os dois formatos começam com um `u32` big-endian (tamanho dos dados) e um `u32` big-endian `0x10000`. O leitor salta esses oito bytes.

- PWV5 e PWV3: entradas `u16` big-endian. Os 5 bits baixos são a altura 0–31, escalada para 0–255. Os 3 bits seguintes escolhem a banda: 0–1 agudo, 2–3 médio, 4–5 grave mais metade no médio, 6–7 grave.
- PWV7: bytes intercalados grave, médio, agudo. O tamanho dos dados é múltiplo de 3.

Quando os dois existem, a ordem de preferência da onda grande é PWV7, depois PWV5, depois PWV3, depois a secção PWB3 deste projeto, depois o PWAV mono. PWB3 continua a ser uma secção nossa: um export real não a traz. A tag desconhecida, com comprimento válido, é ignorada. `.DAT` ausente gera aviso; o leitor ainda tenta `.EXT` e `.2EX`. Página de 2048, 4096 ou 8192 bytes é aceite. Um buffer que não é PDB devolve erro em vez de derrubar o processo.

O corpus de variantes (páginas, títulos longos e unicode, arte ausente, ANLZ ausente, `.EXT`/`.2EX`, PWV3/PWV5/PWV7, cues com e sem comentário e cor, memory cue e loop, playlists, linha apagada, caminhos com caracteres fora do ASCII, 5.000 faixas e fuzz) é gerado por `usbVariantReport()`. “Estilo rekordbox 5/6/7” aqui significa quais tags estão presentes, não um campo de versão lido de um export daqueles programas. Os caminhos exFAT/FAT32 são cadeias dentro do PDB, não um disco montado.

## Gravação

O WAV estéreo de 16 bits é escrito por este projeto. O LAME não entra no APK: a licença do repositório é GPL-2.0-or-later provisória, o OD-06 continua aberto, e não se vincula o LGPL do LAME enquanto isso não estiver fechado. O AAC opcional usa o `MediaCodec` do Android (AAC-LC, contentor MPEG-4). A gravação e o histórico ficam na pasta do app ou numa pasta que o utilizador escolhe. O USB do rekordbox não é destino.
