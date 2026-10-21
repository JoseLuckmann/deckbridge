# Formato rekordbox — o que entrou no app

O leitor de `export.pdb` e de ANLZ (`.DAT` / `.EXT`) foi escrito neste repositório, em Kotlin, a partir da descrição pública do formato.

Não foi copiado código destas bibliotecas. A licença de cada uma foi lida em 21/10/2026:

| Projeto | Licença vista | Uso aqui |
|---|---|---|
| Crate Digger (Deep Symmetry), inclusive o arquivo Kaitai | EPL-2.0 OR MPL-2.0 OR LGPL-3.0-only | não copiado |
| rekordcrate | MPL-2.0 | não copiado |
| pyrekordbox | MIT | não copiado |

A fixture `export.pdb` e os ANLZ são sintéticos, gerados pelo próprio app em `SyntheticRekordbox`. Não há música comercial. Os WAV são cliques gerados aqui.

`exportLibrary.db` (OneLibrary) só é detectado. Não é aberto, para não criar WAL/SHM e porque a base costuma estar cifrada. `exportExt.pdb` também só é anunciado, sem leitura das linhas.

OD-04 (versão do rekordbox do dono) continua aberta. Um pendrive real ainda não foi lido. Isso fica `BLOCKED_HARDWARE`.
