# Biblioteca rekordbox e dois decks

Isto continua a prova A0. O app agora também lê uma exportação rekordbox em somente leitura e mistura dois decks. Ouvir no tablet e na DDJ-400 segue `BLOCKED_HARDWARE`.

## O que a tela faz

- Diagnóstico: o mesmo da prova A0, mais um botão **MIDI simulado**. Esse botão não é a controladora. Ele só mostra o nome que o mapa oficial dá ao Play do deck 1.
- Biblioteca: pede a pasta do pendrive (Storage Access Framework), guarda permissão de **leitura** e procura `PIONEER/rekordbox/export.pdb`. Se o pendrive já foi autorizado, a chegada USB tenta reler sozinha.
- O app não pede permissão de escrita e não grava no volume.
- Pendrive simulado: biblioteca gerada no app (duas faixas, playlists, hot cues). A tela diz SIMULAÇÃO.
- Decks: play, cue, pitch, volume, crossfader, sync por BPM, hot cues e loop do rekordbox em ciclo. A pré-escuta vai para os canais 3–4, em estéreo. Se o aparelho abrir menos de 4 canais, o fone **não** é jogado no master.
- O pitch da DDJ-400 junta MSB e LSB (14 bits), como o script oficial: centro em `0x2000`, faixas 6%, 10%, 16% e 25%. A faixa inicial desta versão é 10%. O slider da tela continua linear, de metade ao dobro.
- WAV e MP3 em estéreo. A forma de onda curta vem do ANLZ quando o arquivo tem a seção `PWAV`.

## Instalar

1. Copie `deckbridge-debug.apk` para o tablet.
2. Permita instalar de origem desconhecida.
3. Abra DeckBridge. A primeira tela é o diagnóstico.
4. Na Biblioteca, toque em **Autorizar pendrive** e escolha a raiz do USB exportado pelo rekordbox.
5. Abra a playlist e toque em Deck A ou Deck B.
6. Para o teste de canais, use o diagnóstico (tom no master, depois tom no fone) e compartilhe o relatório. O player não substitui esse teste.

## O que ainda não é prova

- Emulador, MIDI simulado e pendrive simulado não fecham A014 nem T009.
- Os prints em `/opt/cursor/artifacts/prints/` são renderização na JVM (Roborazzi, Robolectric, gráficos nativos). Não são o tablet.
- O emulador desta máquina continua sem print fiel: o KVM acusou `kvm_spurious_fault` na rodada anterior.
- OneLibrary só abre se a cópia local aceitar a chave pública. O arquivo sintético não abre.
- O prato (jog) é lido nos testes e ainda não move o áudio.
- OD-04 segue aberta.
