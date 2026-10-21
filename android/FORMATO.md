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
