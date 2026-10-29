# Biblioteca limpa: nomes, repetidas e menus no lugar (0.25.0)

Pedido do dono (2026-10-29): "subi algumas faixas duplicadas, mesma música e nomes diferentes, e outras faixas com nomes
diferentes, e ele não lidou em refinar o nome, artista etc."; e "esses recursos de menu não deveriam ficar dentro dos
meus menus específicos (por exemplo Analisar todas)? Ali só ter coisas que transcendem todo o app?"; e "a função de
marcar deveria ficar dentro da biblioteca de músicas mesmo, não precisa de um menu só dele".

## O que estava errado

- **O número na frente do nome era do próprio app.** A fila de importação copiava cada arquivo para o cache como
  `0003-<nome>` e lia título e artista do nome dessa cópia. Sem etiquetas, o título virava `0003-Duke_Dumont,_Purple_…`;
  com o nome "Artista - Título", o artista virava `0000-Adriatique`. Agora cada cópia fica numa pasta numerada, com o
  nome original.
- **Nada limpava o nome.** O nome do arquivo só preenchia campos vazios, e só com " - ". Sublinhados, número de faixa, tom
  e BPM na frente, "(Official Video)" e sites ficavam.
- **Repetidas só pelo nome exato.** Como cada cópia tinha um número diferente, a mesma música nunca era "a mesma"; e a
  mesma gravação com outro nome passava.
- **Arrumar nomes pelo MENU não abria nada.** A janela só existia dentro da aba MÚSICAS.

## O que faz agora

| | Como | Onde |
|---|---|---|
| Nome limpo já na importação | Título e artista pelas etiquetas e pelo nome do arquivo: sem número da fila, número de faixa (`07 - `, `A1 `), tom/BPM (`8A - 124 - `), `_` → espaço, "Artista - Título" separado, "Título - Extended Mix" → "Título (Extended Mix)", sem "(Official Video)", "[Free Download]", "(Lyrics)", sites, kbps, " - Copy", "(1)"; "ft." → "feat."; TUDO MAIÚSCULO → Título. Mantém Original Mix, Remix, Edit, Dub, VIP. "808 State" e "50 Cent" ficam. | `TrackNames`, `AudioIo.probe` |
| Arrumar as que já entraram | MÚSICAS → ⋯ → Arrumar nomes: antes → depois de cada uma, tudo marcado, nada muda sem Aplicar; Desfazer no aviso. | `Organizer` (regra "Título e artista arrumados") |
| A mesma música com outro nome | Impressão digital do áudio: não importa duas vezes (com áudio, duração até 3 s e BPM até 0,5 batendo), e MÚSICAS → ⋯ → Procurar repetidas mostra os grupos ("MESMO ÁUDIO" ou "MESMO NOME E DURAÇÃO"). | `AudioPrint`, `PrintStore`, `Duplicates` |
| Juntar sem perder nada | Fica a sugerida (melhor qualidade, em mais playlists, com nota, nome arrumado) ou a que você tocar; ela entra nas playlists das outras no lugar delas (a ordem do set fica), leva a nota, a cor, o gênero e o comentário, e as outras saem. Um Desfazer volta tudo. | `Duplicates.merge` |
| Menus no lugar | MENU só com o que vale para o app todo (Modo DJ, ajuda, tom, teste de áudio, cópias de segurança). Ferramentas da biblioteca no ⋯ do topo de MÚSICAS; "Transição" já estava no ⋯ do SET. MARCAR deixou de ser aba: é o primeiro botão da fileira de MÚSICAS ("★ MARCAR 4", só quando há sem nota); a barra de baixo fica com MÚSICAS, PLAYLISTS, PENDRIVE e MENU. | `LibraryTools`, `MenuTab`, `NavBar` |

## Por que assim

- **Impressão digital como a de Haitsma e Kalker (2002).** Energia em 33 faixas de 300 a 2000 Hz; cada bit é o sinal da
  diferença entre faixas vizinhas menos a do quadro anterior; quadros de ~186 ms a cada ~11,6 ms. Resiste a recodificar,
  mudar o volume e silêncio na frente; outra música muda metade dos bits. Diferente deles, só os bits **confiáveis**
  contam (fora do silêncio do quadro e com a diferença entre as maiores), a mesma ideia da decodificação por
  confiabilidade do próprio trabalho. Nos testes: a mesma gravação recodificada, com 3 s de silêncio e outro volume, dá
  ~6% de bits diferentes; outras músicas não batem; cliques sintéticos no mesmo andamento também não (poucos bits
  confiáveis: "não dá para dizer" não junta).
- **Versões não são repetidas.** Radio Edit e Extended têm áudio e duração diferentes; só se comparam faixas com
  duração até 8 s diferente, e pular na importação (sem perguntar) pede tudo batendo.
- **Regras visíveis e reversíveis.** Cada mudança de nome aparece antes → depois e se desfaz (Nielsen, heurísticas 1 e
  3); nada é consultado na internet.
- **Ações perto do objeto.** O que mexe na biblioteca fica na aba da biblioteca (princípio de proximidade e "mapeamento"
  de Norman, *The Design of Everyday Things*); o menu geral fica curto e estável.

## Limites

- Nomes: regras para os formatos comuns; um nome sem separador ("Artista Título") não tem como ser separado com
  segurança e fica como título. Não há consulta a base de dados de música (Discogs, MusicBrainz).
- Repetidas: procurar ouve o áudio das faixas que têm outra de duração parecida (uma decodificação por faixa, guardada
  em `digitais/`); o tempo no aparelho não foi medido (`BLOCKED_HARDWARE`). Os arquivos de áudio das que saem continuam na
  pasta da biblioteca do aparelho.
- Não testado com as músicas do dono; os testes usam áudio sintético e os nomes do print enviado.

## Fontes

- Haitsma, J., Kalker, T. (2002). *A Highly Robust Audio Fingerprinting System.* ISMIR 2002.
- Norman, D. (2013). *The Design of Everyday Things* (ed. revista). Basic Books.
- Nielsen, J. (1994). *10 Usability Heuristics for User Interface Design.* Nielsen Norman Group.

## Nomes sem separador e o nome completo à vista (0.27.0)

Relato do dono (2026-10-29): algumas faixas "ele não identificou bem" ("Duke Dumont, Purple Disco …", "Jan Blomqvist The
Space In B…", sem artista) e "não consigo ver o nome da música ou artista completo em lugar nenhum; ele sempre tranca os
nomes e não tem uma maneira de ver o nome do ficheiro".

- **Sem separador não há regra segura.** "Rebuke Along Came Polly" pode ser "Rebuke – Along Came Polly" ou "Rebuke Along
  – Came Polly"; chutar erra. Então: (1) se o começo do título é um artista que a biblioteca já conhece (o campo artista
  das outras faixas, e cada nome das listas "A, B & C"), Arrumar nomes sugere a divisão (`TrackNames.splitByKnown`);
  (2) na faixa aberta, SEPARAR ARTISTA E TÍTULO mostra as palavras do título como botões: um toque na última palavra
  do artista, aparece como fica, APLICAR (`TrackNames.splitAtWord`). Cada artista separado à mão passa a ser conhecido e
  ajuda nas próximas.
- **Nome completo.** A lista mostra o título em até duas linhas; o topo da faixa aberta mostra título e artista inteiros;
  a aba INFO mostra título, artista, álbum e o arquivo da biblioteca (caminho inteiro), selecionáveis para copiar.
- Limite: o nome original do arquivo de antes da importação não é guardado; o arquivo na biblioteca leva o nome do
  título da importação.

### Os nomes de arquivo do dono (vistos no gerenciador de arquivos)

Padrão "Artista1,_Artista2_Título_Extended_Mix.wav" (WAV sem etiquetas): o último artista da lista encosta no título.

- O último artista termina onde o nome é um artista conhecido ("Duke Dumont, **Purple Disco Machine** | Something On My
  Mind") ou um nome que se repete depois de "feat" no próprio arquivo ("Praise Cats, **Andrea Love** | Shined On Me feat
  Andrea Love…") — `TrackNames.splitByCommaList`.
- O que é da mix vai para parênteses: "Extended Mix", "Radio Edit"…, o "(feat. Fulano)" e o "(Remixer … Remix)" quando o
  remixer é conhecido — `TrackNames.mixParens`.
- Quando nada disso decide ("Wolfram, Josh Ludlow YoYo Disco…"), o separar da faixa já vem marcado com o palpite de duas
  palavras para o último artista, para o DJ conferir.

### Vírgula no começo é lista de artistas (0.28.1)

Correção do dono (2026-10-29), vendo Arrumar nomes com "Lorenz Rhode, Purple Disco…" no título: "esses são os
artistas; música muito dificilmente vai ter vírgula no nome". Antes, sem artista conhecido (biblioteca nova, importação),
a lista ficava no título. Agora a vírgula antes do nome da mix sempre separa (`TrackNames.splitByCommaGuess`), na
importação, em Arrumar nomes e no palpite do SEPARAR:

- primeiro o que é certo: artista conhecido ou nome repetido depois de "feat" (como antes);
- senão, o último artista fica com até 3 palavras e acaba antes de uma palavra que abre título (at, the, on, my, you, o,
  de, el, la…: "Purple Disco Machine | **At** the Disko"); se não há, uma antes dela ("Purple Disco Machine | Something
  **On** My Mind"); senão 2 palavras, nome e sobrenome ("Josh Ludlow | YoYo Disco");
- o título fica com ao menos uma palavra antes do nome da mix; vírgula dentro de parênteses ou depois de "feat"/mix não
  conta.

É palpite: o DJ confere e corrige tocando nas palavras (SEPARAR ARTISTA E TÍTULO), e o artista corrigido passa a ser
conhecido. Erra em artistas de 1 ou 3+ palavras sem palavra de título por perto.

## Pontos de interesse como cues já na importação (0.28.0)

Pedido do dono (2026-10-29): "ele deveria rodar na importação já a análise que faz toda a camada de marcação dos pontos
interessantes da música e deixar marcado como cues (intro, drop, break e mais outros pontos de interesse)".

- **Na importação** (`ImportPrep` → `CuePlanner`): pelas frases da análise do app, uma memória em cada começo de seção
  — Intro (na primeira batida 1), Build, Drop, Break, Drop 2, Break 2…, Outro —, o **Fill** (o compasso em que o bumbo
  sai antes de um drop) e o **Fim** (a última batida 1). Os oito mais úteis viram hot cues A–H em ordem de tempo
  (prioridade: Intro, Drop, Break, Drop 2, Outro, Build, Break 2, Drop 3), com cor por tipo: Intro verde, Build
  amarelo, Drop vermelho, Break azul, Outro roxo. Tudo na batida 1 mais próxima. Antes eram até 5 hot cues e a Intro
  no começo do arquivo ficava de fora.
- **Nas que já estão na biblioteca**: MÚSICAS → ⋯ → Marcar pontos (cues). Só acrescenta: os cues do DJ ficam, os hot
  cues novos vão para as letras vagas (os mais importantes primeiro) e memórias novas só onde não há outra a menos de
  0,5 s. Em lotes, com cópia de segurança; um desfazer volta o lote da fila.
- **Na faixa**: as letras e a lista de cues mostram o nome do ponto e a cor do tipo.
- **Arrumar nomes**: cada mudança aparece como ARTISTA e TÍTULO, inteiros, e o "antes" embaixo (a frase antiga era
  cortada e parecia deixar o artista vazio).
- Limite: a qualidade dos pontos é a da análise de frases do app (conferida em faixas sintéticas e no corpus local, não
  nas músicas do dono); não acha voz nem riff.
