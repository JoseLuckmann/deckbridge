# DeckBridge Library Manager (desktop)

Programa para o computador, separado da tela de performance. Serve para organizar a biblioteca do pendrive à mão: metadados, playlists e pastas. É o **único** programa do projeto que escreve num pendrive rekordbox (ADR-0009). O app Android, o Pi e o Mixxx continuam somente leitura.

A automação (classificar, pontuar, renomear, preencher metadados) é etapa futura. Ela entrará como mais uma fonte de mudanças na mesma fila, com a mesma revisão e a mesma gravação segura.

## Como rodar

```sh
cd desktop
./gradlew run                       # tela de boas-vindas com os pendrives encontrados
./gradlew run --args=/media/eu/PENDRIVE   # já abre um pendrive
./gradlew test                      # testes (ver abaixo o que cada um cobre)
```

Precisa de JDK 21. O pendrive tem de ter sido exportado pelo rekordbox (`PIONEER/rekordbox/export.pdb`). Pendrive vazio, criado do zero, não é suportado (ADR-0009, item 7).

## O que dá para fazer

| Área | Ações |
|---|---|
| Faixas | buscar, ordenar por qualquer coluna, selecionar uma ou várias (Ctrl/Shift) |
| Metadados | título, artista, álbum, gênero, tom, selo, ano, nº da faixa, nota (0–5 estrelas) e comentário; em várias faixas de uma vez, só os campos mexidos mudam |
| Playlists | criar playlist e pasta, renomear, excluir, mover (subir/descer), adicionar faixas, remover, reordenar |
| Segurança | fila de mudanças, desfazer a última, descartar tudo, revisar antes de gravar, backups e restauração |

Só leitura na tela: BPM, duração, taxa, caminho do arquivo e da análise. O BPM e a grade ficam para a etapa do editor de grade, porque o player usa a grade do ANLZ, que este programa ainda não escreve.

## Como a gravação protege o pendrive

1. Editar não toca no pendrive. As mudanças ficam na fila e a tela mostra o resultado delas.
2. "Aplicar" monta o banco novo em memória. Só as tabelas editadas são reescritas, em páginas novas no fim do arquivo. As outras ficam idênticas, byte a byte. Linhas e campos que o programa não entende passam como o rekordbox os escreveu.
3. O banco novo é conferido antes de qualquer gravação (`Verifier`): relido pelo leitor cru e pelo leitor do app, linhas não editadas idênticas, nenhuma página antiga alterada, nenhuma referência quebrada que não existia antes.
4. Cópia do `export.pdb` em `~/.deckbridge/backups/<pendrive>/<data>/`, com SHA-256, fora do pendrive.
5. O arquivo novo é gravado ao lado (`export.pdb.deckbridge-novo`), relido e trocado por renomeação atômica. Se algo falhar, o original volta.
6. A gravação recusa se o `export.pdb` mudou desde que foi aberto, se o volume está montado somente leitura, se o banco não é lido por inteiro ou se não há nada a gravar.
7. "Backups" lista as cópias e restaura. Antes de restaurar, o estado de agora também é guardado, então dá para desfazer a restauração.
8. "Gravar numa cópia…" aplica no `export.pdb` de outra pasta (uma cópia do pendrive), sem tocar no aberto. Use para testar num player antes de gravar no pendrive de verdade.

O arquivo cresce a cada gravação (páginas antigas ficam órfãs). `RawPdb.compact()` produz um arquivo enxuto; a tela ainda não oferece essa ação.

## Verificação (25/10/2026)

31 testes, 0 falhas, 0 ignorados, no PC (Linux x86_64):

| Teste | O que prova |
|---|---|
| `RawPdbTest` | o leitor cru vê o mesmo que o leitor do app; regravar sem mudar não muda nada; regravar cada tabela mantém todas as linhas e as outras tabelas no lugar; tabela grande divide em páginas e lê de volta; `compact()` preserva as linhas |
| `EditorTest` | edição de faixa só toca nessa faixa; nomes sem uso saem, nomes criados e abandonados também; valores inválidos são recusados sem deixar nada pela metade; playlists (criar, renomear, encher, reordenar, mover, excluir); pasta recusa faixa e ciclo; 60 sequências aleatórias de 25 edições passam no `Verifier` |
| `SafeWriterTest` | gravar não cria nem apaga outro arquivo, o backup confere, restaurar devolve os bytes originais, volume alterado por outro programa é recusado, backup corrompido não é restaurado, `Verifier` pega página antiga adulterada, linha adulterada e arquivo truncado, 6 gravações seguidas continuam válidas |
| `ManagerModelTest` | fila, desfazer, descartar, recusa sem efeito parcial, aplicar + reabrir + restaurar, busca, ordenação, seleção e escopo de playlist |
| `KaitaiCrossCheckTest` | um parser gerado da especificação aberta (Kaitai, Crate Digger `7c9d535`) lê o banco gravado e vê o mesmo que o plano, com acentos em UTF-16 |
| `PiCrossCheckTest` | o leitor C++ do Pi (`pi/library/rekordbox.h`) vê o mesmo em 25 bancos com edições aleatórias |
| `ScreenshotTest` | renderiza as telas sem janela (`SHOTS_DIR=pasta`); as capturas estão em `docs/ui/desktop-*.png` |

O teste do Kaitai precisa de `KAITAI_PARSERS` (veja `tools/setup_kaitai.sh`); sem a variável, ele é ignorado e o relatório mostra isso. O teste do Pi precisa de g++.

## O que NÃO foi verificado

- **Nenhum player** (CDJ, XDJ, DDJ em modo standalone) **nem o rekordbox** leu um banco gravado por este programa. `BLOCKED_HARDWARE`. Até alguém testar, grave primeiro numa cópia.
- Os dados de teste são o pendrive sintético do app. Não havia um export real do rekordbox para comparar byte a byte. Os três leitores (Kotlin, C++ e Kaitai) podem errar do mesmo jeito em campos que nenhum conhece.
- Duas convenções do formato foram assumidas, sem confirmação num export real: o `entry_index` das playlists novas começa onde o banco já começa (0 ou 1) e o `index_shift` de artistas e álbuns só é recalculado se o próprio arquivo já o mostra como 0x20 por posição.
- A interface foi vista só pelo render offscreen e numa janela real sob Xvfb (renderização por software). Não foi usada num monitor, com mouse e teclado de verdade, nem com um pendrive real.

## Ainda não existe (próximos passos)

Importar áudio para o pendrive com análise (BPM, tom, grade, ondas), escrever o ANLZ (pontos de cue, grade), editor de grade e de cues, renomear arquivos por padrão de nome, remover faixa da biblioteca, capa, compactar o banco pela tela, drag and drop, pendrive novo do zero e a automação.
