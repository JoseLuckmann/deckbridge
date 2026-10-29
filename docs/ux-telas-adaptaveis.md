# Telas que servem em qualquer aparelho (modo DJ)

Relato do dono (2026-10-29): "faça o modo de renderizar melhor em qualquer tela; no meu Samsung S25 Ultra fica horrível".

## O que estava errado

O modo DJ foi desenhado para o Tab S6 Lite deitado e os prints de teste eram em mdpi (px = dp: 2000×1200, 1280×720,
1024×600 "dp"). Nenhum mostrava um celular deitado, que tem **metade da altura**: o S25 Ultra tem 891×412 dp no padrão
(832×384 com o "zoom de tela" maior), contra 1333×800 dp do Tab S6 Lite. As capturas novas (`PhoneScreenShots.kt`)
mostraram, no S25 Ultra deitado:

- as ondas (o que o DJ mais olha) com ~45 dp cada: o topo (aviso + navegação, 68 dp), os hot cues (78 dp) e os cartões
  (150 dp) comiam o resto;
- a coluna de info dos decks cortada no meio do tom ("KEY Am" pela metade);
- o painel do Beat FX cortado embaixo, sem como chegar ao resto;
- o MIXER com os números cortados ("0…", "T…", "FA…") e o rótulo do crossfader por cima do medidor;
- o RMX com os nomes cortados ("ECH…", "BAC…", "PIT…") e o ganho do isolador quebrando em duas linhas;
- as barras de status e de navegação do Android tirando mais ~10–15% da altura (o modo DJ não era tela cheia);
- em pé (412 dp de largura) dois decks não cabem: ondas de 200 dp, relógios virando "0…".

## O que faz agora

- **Arranjo apertado** quando a altura é menor que 520 dp (`TIGHT_HEIGHT`, `PlayerScreen.kt`): topo numa linha só (navegação,
  REC, relógio e INFO; 34 dp), sem a coluna de info dos decks (tom no cartão, compassos na onda), hot cues sem rótulo
  (60 dp), cartões de 136 dp sem a régua de minutos, faixa do Beat FX mais estreita. As ondas passam de ~45 para ~90 dp
  cada no S25 Ultra.
- O painel do Beat FX/RMX/STATUS **rola** quando não cabe (antes cortava).
- **MIXER em abas** (CANAIS, MASTER, OPÇÕES) abaixo de 520 dp de altura ou 1000 dp de largura; números e rótulos menores
  nos canais, master num arranjo baixo (isolador, Send, fones e medidor lado a lado) e crossfader com rótulo e valor
  numa linha.
- **RMX**: abaixo de 1250 dp de largura a última linha (RELEASE, ROLL, CH) vira duas; o isolador encolhe e o ganho não quebra.
- **Tela cheia de palco**: o modo DJ esconde as barras do sistema (um deslize da borda mostra por uns segundos) e voltam a
  esconder ao recuperar o foco. O conteúdo vai até o recorte da câmera, e só o lado do recorte ganha folga
  (`WindowInsets.displayCutout`).
- **Sempre deitado** (`sensorLandscape`): em pé não existe layout de dois decks que caiba em 412 dp; o modo Biblioteca
  continua girando livre.
- O Tab S6 Lite (1333×800 dp) não muda: as capturas antes/depois são iguais.

## Por que assim

- A onda é a informação principal num player de club (o CDJ-3000 dá a maior faixa da tela a ela); o que sai no arranjo
  apertado já aparece em outro lugar (tom no cartão, compassos na onda, banco de hot cue no ···).
- Esconder as barras do sistema é o que apps de performance fazem em palco (o modo imersivo "sticky" do Android é para
  isso: conteúdo de tela cheia em que o usuário ainda chega às barras com um gesto).
- Mixer em abas em vez de encolher tudo: números de 13 sp em barras de 26 dp não cabem; com uma aba por vez cada barra
  tem ~44 dp e o valor inteiro.

## Limites (`BLOCKED_HARDWARE`)

- Nada disto foi visto num S25 Ultra de verdade: as capturas são do Robolectric (sem barras do sistema e sem recorte de
  câmera). O tamanho do recorte, o gesto para mostrar as barras e a leitura sob luz de pista ficam para o aparelho.
- O "zoom de tela" menor (975×450 dp) não tem captura; fica entre o padrão e o tablet e cai no arranjo apertado.
