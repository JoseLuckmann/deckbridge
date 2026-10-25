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
| Faixas | buscar, ordenar por qualquer coluna, selecionar uma ou várias (Ctrl/Shift), arrastar para uma playlist |
| Metadados | título, artista, álbum, gênero, tom, selo, ano, nº da faixa, nota (0–5 estrelas) e comentário; em várias faixas de uma vez, só os campos mexidos mudam |
| Playlists | criar playlist e pasta, renomear, excluir, mover, adicionar (botão ou arrastando), remover, reordenar (botões ou arrastando) |
| Cues e grade | editor com a onda, a grade e os cues: hot cues A–H com nome e cor, cues de memória, loops, BPM, primeira batida, ÷2 / ×2, ±10 ms |
| Importar áudio | escolher arquivos ou pastas, ou soltá-los na janela: o áudio é copiado para o pendrive, analisado (BPM, tom, grade, ondas, cues sugeridos) e entra no banco |
| Renomear arquivos | por padrão (`{artista}/{album}/{artista} - {titulo}` e outros), com prévia; muda o áudio, o banco e o caminho dentro do ANLZ |
| Remover da biblioteca | tira da coleção, playlists, histórico e tags; o áudio e o ANLZ ficam |
| Organizar | sugestões por regras (espaços, maiúsculas, nome do arquivo → artista/título, duplicatas, campos vazios, sem análise) e análise sob demanda de faixas já no pendrive |
| Segurança | fila de mudanças, desfazer a última, descartar tudo, revisar antes de gravar, backups com restauração, compactar o banco |

BPM e grade são editados no editor de cues e grade (reescrevem o ANLZ junto com o banco). Duração, taxa e caminhos são só leitura.

O assistente **Organizar não usa inteligência artificial**: são regras fixas, cada sugestão mostra o antes e o depois, nada é aplicado sozinho e o que remove faixas vem desmarcado. Classificar por gênero, pontuar e preencher metadados com modelo de aprendizado **não existem**: não há dados rotulados nem um modelo cuja acurácia eu pudesse medir (a do analisador de tom e tempo está publicada na OD-24 e é modesta).

## Como a gravação protege o pendrive

1. Editar não toca no pendrive. As mudanças ficam na fila e a tela mostra o resultado delas.
2. "Aplicar" monta tudo em memória. No banco, só as tabelas editadas são reescritas, em páginas novas no fim do arquivo; as outras ficam idênticas, byte a byte. Linhas e campos que o programa não entende passam como o rekordbox os escreveu. No ANLZ, só as seções de grade e de cues são trocadas; ondas e frases ficam intactas, e as entradas de cue que não mudaram voltam com os mesmos bytes.
3. Tudo é conferido antes de qualquer gravação: o banco novo (relido pelo leitor cru e pelo do app, linhas não editadas idênticas, nenhuma página antiga alterada, nenhuma referência quebrada nova), cada arquivo de análise (ida e volta idêntica), cada cópia e renomeação contra o disco, o espaço livre e os nomes (acentos exigem locale UTF-8 no Linux).
4. Cópia local, com SHA-256 e fora do pendrive, em `~/.deckbridge/backups/<pendrive>/<data>/`: o `export.pdb`, cada arquivo que vai ser substituído, e o registro do que foi criado ou renomeado.
5. Ordem de execução: renomeações, cópias de áudio, arquivos de análise, e o `export.pdb` por último. Cada arquivo vai primeiro para um temporário ao lado, é relido e conferido e só então ocupa o lugar. **Se qualquer passo falhar, tudo é desfeito em ordem inversa** (testado com uma falha no meio).
6. A gravação recusa se o `export.pdb` mudou desde que foi aberto, se o volume está montado somente leitura, se o banco não é lido por inteiro, se o destino de uma cópia já existe ou se não há nada a gravar.
7. "Backups" lista as cópias e restaura. A restauração devolve os bancos e os ANLZ, desfaz as renomeações e remove os arquivos de análise que a gravação criou. **Áudio importado nunca é apagado**: continua no pendrive e a tela avisa. Antes de restaurar, o estado de agora dos bancos também é guardado.
8. "Gravar numa cópia…" aplica as mudanças numa cópia do pendrive, sem tocar no aberto. Use para testar num player antes de gravar no pendrive de verdade.
9. "Compactar o banco" (em Backups) reescreve o `export.pdb` só com as páginas em uso (as gravações deixam páginas antigas), sem mudar nenhuma linha, com conferência própria e backup.

## Importar MP3, FLAC e AAC

WAV PCM (16, 24, 32 bits ou float) é lido pelo próprio programa. Os outros formatos precisam do `ffmpeg` e do `ffprobe` do sistema (não são embutidos nem distribuídos aqui; são chamados como programas separados, com argumentos em lista, sem shell). Sem eles a tela diz o que falta. As etiquetas do arquivo (título, artista, álbum, gênero, ano, nº) preenchem os campos; sem etiqueta, vale o nome `Artista - Título`.

As **etiquetas dentro dos arquivos de áudio (ID3 e semelhantes) não são alteradas**. Os metadados editados aqui são os do banco do rekordbox, que é o que os players leem.

## Verificação (25/10/2026)

Desktop: 6 testes (interface). O núcleo, com 75 testes, vive em `core/` (ADR-0010). No PC (Linux x86_64), 0 falhas, 0 ignorados:

| Teste | O que prova |
|---|---|
| `RawPdbTest` (6) | o leitor cru vê o mesmo que o do app; regravar sem mudar não muda nada; regravar cada tabela mantém linhas e as outras tabelas no lugar; tabela grande divide em páginas; `compact()` preserva as linhas |
| `EditorTest` (9) | edição de faixa só toca nessa faixa; nomes sem uso saem; valores inválidos são recusados sem efeito parcial; playlists; pasta recusa faixa e ciclo; 60 sequências aleatórias de 25 edições passam no `Verifier` |
| `SafeWriterTest` (9) | gravar não cria nem apaga outro arquivo; backup confere; restaurar devolve os bytes; volume alterado por outro programa é recusado; backup corrompido não é restaurado; `Verifier` pega página, linha e arquivo adulterados; 6 gravações seguidas |
| `AnlzTest` (5) | ler e regravar todos os ANLZ dá bytes idênticos; editar cues muda só PCOB/PCO2 e reaproveita entradas; a grade nova é lida pelo app e pelo Kaitai; o conjunto completo de uma faixa analisada é lido pelo app e pelo Kaitai |
| `FileEditsTest` (8) + `EncodingCheckTest` (1) | cues e grade no pendrive e de volta; pedidos inválidos recusados; renomear move o áudio, atualiza banco e ANLZ, e restaurar desfaz; nomes ruins recusados; remover faixa limpa playlists (posições contíguas) e nunca apaga áudio; **falha no meio desfaz tudo**; importar copia o áudio, cria o ANLZ e acrescenta a faixa (e restaurar mantém o áudio); MP3 sem ffmpeg avisa; acentos sem UTF-8 são recusados antes de gravar |
| `OrganizerTest` (8) | cada regra mostra antes e depois; o nome do arquivo só preenche campo vazio; duplicatas nunca vêm marcadas; padrões de nome geram caminhos seguros sem colisão e se aplicam num volume; a análise sob demanda não sobrescreve sem pedir e não toca no ANLZ antigo |
| `ManagerModelTest` (7), `UiLogicTest` (5) | fila, desfazer, aplicar e restaurar; arrastar para playlist e reordenar por metade da linha; tempos dos cues; importação em segundo plano de uma pasta |
| `AudioIoTest` (3) | seis formatos de WAV (16/24/32 bits, float, mono e estéreo, 22 a 96 kHz) viram o mesmo seno na taxa de análise; a duração vem do cabeçalho; MP3, FLAC, M4A e OGG passam pelo ffmpeg com etiquetas, duração e andamento corretos |
| `KaitaiCrossCheckTest` (1) | um parser gerado da especificação aberta (Kaitai, Crate Digger `7c9d535`) lê o banco gravado e vê o plano |
| `PiCrossCheckTest` (2) | o leitor C++ do Pi vê 25 bancos editados ao acaso e, depois de uma sessão de cues, grade, importação e reanálise, cada batida e cada cue gravados |
| `ScreenshotTest` (1) | renderiza as telas sem janela (`SHOTS_DIR=pasta`); capturas em `docs/ui/desktop-*.png` |

O teste do Kaitai precisa de `KAITAI_PARSERS` (veja `tools/setup_kaitai.sh`); sem a variável, ele é ignorado e o relatório mostra isso. O do Pi precisa de g++.

## O que NÃO foi verificado

- **Nenhum player** (CDJ, XDJ, DDJ em modo standalone) **nem o rekordbox** leu um banco ou um ANLZ gravado por este programa. `BLOCKED_HARDWARE`. Até alguém testar, grave primeiro numa cópia.
- Os dados de teste são o pendrive sintético do app. Não havia um export real do rekordbox para comparar byte a byte. Os leitores (Kotlin, C++ do Pi e Kaitai) podem errar do mesmo jeito em campos que nenhum conhece.
- Convenções do formato assumidas, sem confirmação num export real:
  - o `entry_index` das playlists novas começa onde o banco já começa (0 ou 1);
  - o `index_shift` de artistas e álbuns só é recalculado se o próprio arquivo já o mostra como 0x20 por posição;
  - o campo `memory_count` das listas de cue recebe o número de cues;
  - o `.DAT` guarda os hot cues A–C e o `.EXT` guarda A–H;
  - os campos `order_first`/`order_last` das entradas de cue novas ficam em 0xFFFF;
  - o ANLZ de faixa importada leva `PPTH`, grade, cues, `PWAV`, e no `.EXT`/`.2EX` as ondas coloridas, mas **não leva** a análise de frases (`PSSI`) nem `PVBR`;
  - os ANLZ novos ficam em `PIONEER/USBANLZ/PDBM/<id>/ANLZ0000.*`, um caminho que o rekordbox não usa (os players seguem o caminho do banco).
- A interface foi vista só no render offscreen e numa janela real sob Xvfb (renderização por software). Não foi usada num monitor, com mouse e teclado de verdade, nem com um pendrive real. **Arrastar e soltar com o mouse e o arrastar de arquivos do gerenciador de arquivos para a janela não foram exercitados**: testei a lógica com posições simuladas e vi o desenho do estado de arrasto, mas o gesto real depende do sistema de janelas.
- MP3, FLAC, M4A e OGG foram testados com o ffmpeg desta máquina (arquivos gerados por ele a partir de um WAV sintético), não com músicas reais nem com outras versões do ffmpeg.

## Não existe

- Pendrive novo, criado do zero (sem export do rekordbox). Sem um export real para comparar, um banco gerado do zero pode ficar incompatível com os players (a tabela de colunas, por exemplo, não está documentada o bastante). O ADR-0009 mantém isso de fora.
- Classificar por gênero, pontuar e preencher metadados com modelo de aprendizado.
- Gravar etiquetas (ID3 etc.) dentro dos arquivos de áudio.
- Capa de álbum, análise de frases (`PSSI`) para faixas importadas, conversão de formato.


## Biblioteca local e pendrive (ADR-0010)

- "Abrir a biblioteca local" cria (se não existir) e abre `~/.deckbridge/library`, no mesmo formato de um pendrive do rekordbox. Os dois bancos nascem do gerador (`ExportFactory`), que reproduz byte a byte, nas tabelas fixas, o export vazio gerado pelo rekordbox (teste `ExportFactoryTest`, só com `REAL_EXPORT`).
- "Exportar para pendrive…": copia áudio e análises que faltam e grava o banco por último; backup local do banco antigo; relê e confere. "Trazer de pendrive…" substitui a biblioteca aberta pela do pendrive (backup antes) e só lê o pendrive.
- Juntar duas bibliotecas não existe: exportar e trazer substituem.
