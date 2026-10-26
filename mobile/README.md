# DeckBridge Biblioteca (celular)

App Android (`os.deckbridge.manager`) para organizar a biblioteca no celular e exportá-la para um pendrive, no formato do rekordbox. Referência: Samsung Galaxy S25 Ultra (1440×3120, ≈411×891 dp), em pé e deitado. Decisão e limites: `docs/adr/0010-biblioteca-local-e-mobile.md`.

## Design system

O mesmo do player (`StagePalette`): fundo preto, botões retos com texto azul-claro, ativo em azul-escuro com contorno azul, valores em laranja, tom compatível em verde, hot cue em grade 2×4, lista com disco, miniatura da onda e BPM monoespaçado, fileira de botões iguais embaixo (coluna de ícones quando deitado). `DesignSystemTest` impede cor à mão e formas arredondadas do Material. Tabela completa em `docs/ui-spec.md` §6.

## Princípio de tela

Curto e limpo: uma linha de topo, uma fileira de filtros, linhas de faixa compactas, detalhe em abas (MARCAR · CUES · INFO), ações raras no MENU e explicações só em confirmações.

## O que faz

- **Biblioteca no aparelho** (`getExternalFilesDir/biblioteca/dados`), mesmo formato de um pendrive; mudanças ficam numa fila (barra "Salvar / Desfazer / Descartar") e são gravadas com cópia de segurança; ao sair do app o que estiver pendente é salvo.
- **Importar** músicas do aparelho (seletor de documentos) com análise (BPM, tom, grade, ondas, cues sugeridos).
- **Marcar**: nota, cor, comentário, campos; seleção múltipla (segurar) para playlist, nota, cor, remover.
- **Playlists**: criar, renomear, apagar, reordenar (▲▼), tirar faixas.
- **Grade e cues**: onda com batidas, hot cues A–H e cues de memória na posição escolhida, grade regular por BPM e primeira batida.
- **Pendrive**: escolher a pasta raiz (SAF), ver se já há biblioteca, exportar (substitui, com backup local do banco antigo; nenhum áudio é apagado) e trazer de um pendrive (substitui a local, com backup).

O núcleo (`core/`) é o mesmo do programa de computador. Nenhum outro código escreve no pendrive além do exportador (ADR-0009/0010).

## Atalhos para o que se repete (rodada de UX 1)

- **Primeiros passos**: importar → analisar → marcar → playlists → exportar, com o próximo passo em um botão. Recolhido por padrão depois do primeiro passo; "Ocultar" some de vez.
- **Filtros rápidos com contagem**: Sem análise, Sem nota, Fora de playlists, Sem gênero, Sem artista, 4 ou 5 estrelas, Recentes. Respondem "o que ainda falta?".
- **Busca com filtros escritos**: `bpm:120-128`, `bpm:>124`, `tom:8A` (aceita Am e 8A), `nota:4`, `cor:verde`, `genero:house`, combináveis com palavras. O "?" na busca explica.
- **Salvar lista como playlist**: com busca ou filtro ativo, cria a playlist com o que está na tela.
- **Playlist alvo**: definida uma vez, cada faixa ganha um "+" de um toque (e "✓" para tirar); também na triagem e em "＋ ao alvo" na seleção.
- **Triagem**: uma faixa por vez, botões grandes de nota (avança sozinho), cor, adicionar ao alvo, ouvir; em pé e deitado.
- **Editar em lote**: vários campos de várias faixas; só o preenchido muda.
- **Arrumar tudo**: sugestões por regras (espaços, maiúsculas, nome do arquivo), todas visíveis e desmarcáveis; não é IA.
- **Analisar todas** as faixas sem análise de uma vez (em lotes de 6, cancelável).
- **Desfazer/Refazer** sempre à mão; cada ação rápida mostra um aviso com "DESFAZER".
- **Navegar entre faixas** com ‹ › nos detalhes, na ordem da lista atual.
- **Ordenar playlist** por BPM, nota ou ordem harmônica (roda Camelot + BPM).
- **Antes de exportar**: verificação com o botão que resolve (analisar todas, apagar playlists vazias); falta de áudio bloqueia.

## Planejar o set (rodada 7)

Na playlist, **SET** abre o planejador. Entre cada par de faixas aparece a transição: tom → tom (verde se igual ou
vizinho na roda), diferença de andamento, estilo, compassos e o ponto de saída.

- **▶** monta e toca a transição: 12 s antes, os compassos juntos, 12 s depois. A que entra toca no andamento da que sai,
  com a entrada caindo na batida da saída, com Master Tempo (o tom não muda).
- **MIX** abre o teste de mixagem, no layout do player: DECK 1 e DECK 2 com ondas coloridas rolando juntas, batidas,
  cues e o trecho junto marcado; mixer (HI/LOW/FADER e crossfader) acompanhando; PLAY e pulos de compasso; fileiras
  BARS, FX, SAÍDA D1, ENTRA D2 (com os cues como pads), MASTER TEMPO e AUTO. Cada ajuste monta a prévia de novo.
- **▶ OUVIR** no topo: todas as transições em sequência, para ouvir o set de ponta a ponta.
- **PRÓXIMA**: três faixas que combinam com a última (tom e BPM primeiro, depois nota). ▶ ouve, ＋ põe no fim.
- O plano fica em `sets/` ao lado da biblioteca do aparelho; o pendrive e o banco do rekordbox não mudam.

## Montar e testar sets (rodada 9)

- **MONTAR SET** (aba PLAYLISTS): de onde (biblioteca, ★4+ ou uma playlist), duração (30–120 min) e forma (Fluido,
  Subindo, Sobe e desce). Cria a playlist na ordem para mixar e abre o SET.
- No SET: **ORDENAR** (a primeira faixa fica), **⚠** com o que pode soar mal em cada troca (tom, salto de BPM, sem
  grade, saída sem espaço), **PONTE** (uma faixa que combina com as duas, posta no meio) e **ENSAIAR**: toca cada
  transição; ✓ BOA ou ✗ REFAZER passa para a próxima, e as marcas ficam no plano.

## Teste de áudio

MENU → APARELHO → **Teste de áudio**: lê 30 s de uma música da biblioteca pelo decodificador do Android e monta uma
transição, e mostra o formato e quanto tempo levou. É a conferência que só o aparelho faz (MP3/FLAC, velocidade).

## Ícone e integração com o Android

Ícone adaptativo e temático (disco nas cores do player), abertura preta, nome "Biblioteca DJ" no lançador. Segurar o ícone: Triagem, Importar, Exportar. "Compartilhar" áudio de outro app importa direto. Deslizar a linha para a direita põe a faixa na playlist alvo.

## Build e testes

```
cd mobile
LC_ALL=C.UTF-8 ./gradlew :app:testDebugUnitTest :app:assembleDebug   # APK em app/build/outputs/apk/debug
```

Testes (JVM/Robolectric, SDK 33): `DesignSystemTest` (4), `ScreenshotTest` (12 capturas em `docs/ui/mobile-*.png`), `UxFlowTest` (toques de verdade: "+" do alvo com DESFAZER, estrela na linha, triagem até o fim, filtro rápido), `SafStorageTest` (3: gravar/trocar arquivos, recusa de renomeação que devolve o antigo, exportar uma biblioteca inteira e trazê-la de volta por um provedor de documentos de brinquedo).

## Não verificado (`BLOCKED_HARDWARE`)

- Nada rodou no S25 Ultra: toque, rotação real, desempenho, memória.
- MP3/FLAC/AAC dependem do MediaCodec do aparelho (`MediaDecoder`); nos testes só WAV é decodificado.
- Pendrive USB real pelo seletor do Android (provedores reais podem se comportar diferente do de brinquedo, por exemplo ao renomear) e o espaço livre (o SAF não informa; uma falta de espaço aparece como erro de gravação, com o banco antigo intacto porque o banco é escrito por último).
- Nenhum player nem o rekordbox leu o que o app grava.
- A pré-escuta (`Preview`, MediaPlayer) e a triagem com áudio só valem em aparelho: nos testes não há codec.
- Se os atalhos realmente aceleram o uso é hipótese até alguém usar com a biblioteca real; os testes provam que funcionam, não que são a melhor ordem.
- Importar muitas faixas de uma vez mantém as análises na memória até gravar.
