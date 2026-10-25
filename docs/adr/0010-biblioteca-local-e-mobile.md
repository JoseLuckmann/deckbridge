# ADR-0010 — Biblioteca local no aparelho, app de celular e exportação para o pendrive

**Data:** 2026-10-25  
**Estado:** aceita (decisão do dono, 2026-10-25). Emenda o item 7 do ADR-0009 ("pendrive novo, criado do zero, não entra agora").

## Contexto

O dono pediu três mudanças de direção para o Library Manager:

1. Ele deve **salvar a biblioteca localmente no aparelho**, não só no pendrive.
2. A parte de organizar passa a existir também no **celular** (referência principal: Samsung S25 Ultra), em **retrato e paisagem**: analisar, montar playlists, marcar faixas e outras coisas.
3. Depois, **exportar para um pendrive**.

Hoje o programa de desktop edita o pendrive no lugar, só se ele já tiver sido exportado pelo rekordbox. O item 7 do ADR-0009 deixou de fora o pendrive criado do zero porque não havia um export real para comparar.

## O que mudou nas evidências

Em 2026-10-25 achei exports **reais** do rekordbox (dados de teste do projeto rekordcrate, MPL-2.0, commit `68cd702`): um export vazio, um com duas faixas e uma biblioteca grande (720 páginas, 3.886 faixas). Eles ficam fora do repositório e só servem de conferência local. Com eles:

- As páginas que o gravador escreve saem **byte a byte iguais** às do rekordbox para as mesmas linhas (cores, colunas e as duas tabelas auxiliares), exceto número de página, ligação entre páginas e sequência.
- A biblioteca real grande é lida sem perder linhas, editada e relida, com o mesmo resultado nos leitores Kotlin, C++ do Pi e Kaitai.
- Vários defeitos da primeira versão apareceram e foram corrigidos (ver `desktop/README.md`).

Isso torna possível gerar um export do zero **com a mesma estrutura de um export real**. Continua sem haver um player nem o rekordbox lendo o resultado: isso segue `BLOCKED_HARDWARE`.

## Decisão

1. **A biblioteca local é um volume no formato do rekordbox** (`PIONEER/rekordbox/export.pdb`, `exportExt.pdb`, `PIONEER/USBANLZ/...`, `Contents/...`), numa pasta do aparelho:
   - celular: pasta própria do app (`getExternalFilesDir`, sem permissão extra);
   - computador: `~/.deckbridge/library`.
   Assim todo o núcleo que já existe (edição, ANLZ, backup, verificação) vale igual para a biblioteca local e para o pendrive.
2. **Criar do zero** passa a ser permitido, para a biblioteca local e para um pendrive vazio, com o desenho de um export real, **sempre marcado como "não testado em player"** até alguém testar. O item 7 do ADR-0009 fica revogado por este.
3. **O pendrive é destino de exportação** (e origem, ao "importar do pendrive"). Exportar faz o pendrive ficar com a biblioteca local: copia o áudio e os ANLZ que faltam e grava `export.pdb` e `exportExt.pdb`.
   - Antes de trocar um banco que já existe no pendrive, o banco antigo é copiado para o aparelho (com SHA-256).
   - Se o pendrive já tem uma biblioteca (feita no rekordbox, por exemplo), a tela avisa que exportar a **substitui** e oferece **importá-la antes** para o aparelho. Nada é mesclado às cegas.
   - O áudio nunca é apagado do pendrive.
4. **O app de celular é um app separado** do de performance (`os.deckbridge.manager`, não `os.deckbridge.android`). O de performance continua somente leitura (OD-23). O de celular escreve no pendrive só quando o usuário pede "Exportar", por um seletor de pasta do Android (SAF).
5. **Núcleo compartilhado:** um módulo `core/` (Kotlin JVM, sem tela) usado pelo desktop e pelo app Android, como build incluído do Gradle. O que depende da plataforma fica por trás de interfaces: decodificação de áudio (MediaCodec no Android; WAV e ffmpeg no desktop) e acesso ao armazenamento do pendrive (SAF no Android; arquivos no desktop).
6. **Layout do celular:** retrato (uma coluna, barra de abas embaixo, painéis em folha) e paisagem (duas colunas: lista e detalhe). Referência: S25 Ultra, 6,9", 1440×3120, cerca de 412×892 dp em retrato e 892×412 dp em paisagem, com a câmera em furo e as barras do sistema (tela de ponta a ponta).

## O que não está verificado

- Nenhum player (CDJ, XDJ, DDJ standalone) nem o rekordbox leu um banco gerado do zero ou gravado por este programa. `BLOCKED_HARDWARE`.
- O app Android não foi rodado num S25 Ultra, nem em emulador, nem com um pendrive real. A verificação possível aqui é: compilar o APK, rodar a interface no Robolectric nas dimensões do S25 Ultra (retrato e paisagem) e testar a lógica de exportação contra um armazenamento de arquivos.
- O acesso ao pendrive por SAF (DocumentFile) é lento e não tem renomeação atômica; a gravação do banco usa "renomear o antigo, renomear o novo, apagar o antigo", com volta atrás se algo falhar, mas isso só foi testado contra um provedor simulado.
- Os arquivos de ajustes do player (`MYSETTING.DAT`, `DEVSETTING.DAT` etc.) **não são gerados**: se o pendrive já os tem, ficam; se não tem, os players usam os ajustes de fábrica (não verificado).

## Alternativas descartadas

- **Pôr o gerenciador dentro do app de performance:** misturaria escrita no pendrive com o app que toca no palco e que tem a regra de nunca escrever. Descartada.
- **Banco próprio (SQLite) no aparelho e converter só ao exportar:** daria duas representações da mesma biblioteca e dois caminhos de erro. Guardar já no formato do rekordbox reaproveita tudo o que está testado.
- **Compose Multiplatform para compartilhar a tela entre desktop e celular:** ia exigir reestruturar os dois builds. O núcleo é compartilhado; as duas telas são escritas para o seu tipo de entrada (mouse e teclado; toque).
- **Mesclar automaticamente a biblioteca do pendrive com a local:** exige remapear identificadores de faixa, artista, álbum e playlist sem como validar contra um player. Descartada: o caminho é "importar para o aparelho, editar, exportar".
