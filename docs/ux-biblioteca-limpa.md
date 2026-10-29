# Biblioteca limpa: nomes, repetidas e menus no lugar (0.25.0)

Pedido do dono (2026-10-29): "subi algumas faixas duplicadas, mesma música e nomes diferentes, e outras faixas com nomes
diferentes, e ele não lidou em refinar o nome, artista etc."; e "esses recursos de menu não deveriam ficar dentro dos
meus menus específicos (por exemplo Analisar todas)? Ali só ter coisas que transcendem todo o app?"

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
| Menus no lugar | MENU só com o que vale para o app todo (Modo DJ, ajuda, tom, teste de áudio, cópias de segurança). Ferramentas da biblioteca no ⋯ do topo de MÚSICAS; "Transição" já estava no ⋯ do SET. | `LibraryTools`, `MenuTab` |

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
