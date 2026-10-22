# Biblioteca rekordbox e dois decks

Isto continua a prova A0. O app agora também lê uma exportação rekordbox em somente leitura e mistura dois decks. Ouvir no tablet e na DDJ-400 segue `BLOCKED_HARDWARE`.

## O que a tela faz

- Diagnóstico: o mesmo da prova A0, mais um botão **MIDI simulado**. Esse botão não é a controladora. Ele só mostra o nome que o mapa oficial dá ao Play do deck 1.
- Biblioteca: pede a pasta do pendrive (Storage Access Framework), guarda permissão de **leitura** e procura `PIONEER/rekordbox/export.pdb`. Se o pendrive já foi autorizado, a chegada USB tenta reler sozinha.
- O app não pede permissão de escrita e não grava no volume.
- Pendrive simulado: biblioteca gerada no app (duas faixas, playlists, hot cues). A tela diz SIMULAÇÃO.
- Decks: play, cue, pitch, volume, crossfader, sync por BPM, hot cues e loop do rekordbox em ciclo. A pré-escuta vai para os canais 3–4, em estéreo. Se o aparelho abrir menos de 4 canais, o fone **não** é jogado no master.
- O pitch da DDJ-400 junta MSB e LSB (14 bits), como o script oficial: centro em `0x2000`, faixas 6%, 10%, 16% e 25% (na tela, ±6, ±10, ±16 e WIDE). A faixa inicial desta versão é 10%. O fader TEMPO da tela usa a mesma conta, não uma reta de 0,5 a 2.
- WAV e MP3 em estéreo. A forma de onda curta vem do ANLZ quando o arquivo tem a seção `PWAV`. As três cores da onda grande são um desenho em cima desses bytes e dos beats. Não é uma análise espectral medida.
- A tela abre no player, com os dois decks lado a lado. SOURCE ou BROWSE abre a biblioteca por cima. LOAD DECK 1 e LOAD DECK 2 carregam a faixa e voltam ao player. O mixer físico da DDJ-400 continua mandando volume e crossfader; a tela não mostra mais a coluna de faders.

## Instalar

1. No tablet, copie `deckbridge-arm64.apk` (só arm64, versão 0.9.0). O `deckbridge-debug.apk` traz as três ABIs e serve ao emulador.
2. Permita instalar de origem desconhecida.
3. Abra DeckBridge. A primeira tela é o diagnóstico. ESTADO no player volta para ela.
4. No player, toque em BROWSE. AUTORIZAR pede a raiz do USB exportado pelo rekordbox, só leitura.
5. Abra a playlist e toque em LOAD DECK 1 ou LOAD DECK 2.
6. Para o teste de canais, use o diagnóstico (tom no master, depois tom no fone) e compartilhe o relatório. O player não substitui esse teste.

## O que ainda não é prova

- Emulador, MIDI simulado e pendrive simulado não fecham A014 nem T009.
- Os prints em `/opt/cursor/artifacts/prints/` são renderização na JVM (Roborazzi, Robolectric, gráficos nativos). Não são o tablet.
- O emulador desta máquina continua sem print fiel: o KVM acusou `kvm_spurious_fault` na rodada anterior.
- OneLibrary só abre se a cópia local aceitar a chave pública. O arquivo sintético não abre.
- O prato (jog) empurra o áudio por uma escala provisória de segundos. Não é a curva medida da DDJ-400. A escuta continua `BLOCKED_HARDWARE`.
- O mixer da rota 3 processa a lista de Beat FX no motor (Low Cut Echo, Echo, Ping Pong, Spiral, Reverb, Trans, Flanger, Phaser, Pitch, Slip Roll, Roll, Helix, Mobius, Filter, Vinyl Brake) e o filtro do knob FILTER por canal. Spiral, Helix, Mobius, Pitch e o reverb são aproximações originais, não o algoritmo comercial. O knob FILTER é o único Sound Color FX deste mapa: não há seletor de seis efeitos. A escuta não foi feita.
- RMX (Scene FX, X-Pad, isolador e release) também entra no motor, sincronizado ao BPM. O mapeamento MIDI é provisório: com o painel RMX ligado, os pads de SAMPLER (notas 0x30–0x37) disparam a cena e o X-Pad. SHIFT+SAMPLER não é tomado. O isolador é na tela. Ver o item 8 do checklist físico.
- Master Tempo no menu é um deslocador de tom por duas linhas de atraso. Tenta devolver a afinação quando o tempo muda. Não é o algoritmo comercial.
- A faixa de toque no player faz salto de batida e loop sem a controladora. O mesmo caminho existe no mapa oficial de BEAT JUMP e BEAT LOOP.
- A onda colorida prefere, quando o ANLZ traz, PWV7 (três bandas), depois PWV5 ou PWV3 (cor). Essa leitura é deste projeto e só foi exercida em fixtures nossas. Se essas secções faltam, cai na PWB3 deste projeto (grave, médio e agudo por coluna, cada banda no próprio pico) e, por fim, no PWAV mono. Um export real ainda não foi lido. A barra de frase debaixo da visão geral é simulada pelo número do compasso.
- O diagnóstico mostra o relatório das variantes sintéticas (páginas, 5.000 faixas, PWV, cues, tag desconhecida, fuzz, ANLZ ausente). Um erro de parse vira aviso. Não derruba o processo.
- HISTORY regista a faixa no ar (a tocar, fader do canal acima, durante o tempo do menu) com hora, deck, BPM e tom, numa base SQLite local. CSV e M3U8 saem para um documento que o utilizador escolhe. O M3U8 aponta para `deckbridge://set/…`.
- REC grava o master, com os efeitos, em WAV na pasta do app. O anel entre a thread de áudio e a thread que escreve não aloca na thread de áudio no teste hospedeiro. AAC opcional usa o codificador do sistema. O LAME não está no APK. Abaixo de 64 MB livres a gravação não começa.
- O botão KEY liga o mesmo deslocador de tom do menu. Não há nota de key lock no mapa oficial da DDJ-400. O resultado ouvido continua `BLOCKED_HARDWARE`.
- O zoom da forma de onda fica na tela. Beat esquerda/direita continuam sendo Beat FX. O sentido do encoder BROWSE não foi medido na controladora.
- OD-04 segue aberta.
