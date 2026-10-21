# Biblioteca rekordbox e dois decks

Isto continua a prova A0. O app agora também lê uma exportação rekordbox em somente leitura e mistura dois decks. Ouvir no tablet e na DDJ-400 segue `BLOCKED_HARDWARE`.

## O que a tela faz

- Diagnóstico: o mesmo da prova A0, mais um botão **MIDI simulado**. Esse botão não é a controladora. Ele só mostra o nome que o mapa oficial dá ao Play do deck 1.
- Biblioteca: pede a pasta do pendrive (Storage Access Framework), guarda permissão de **leitura** e procura `PIONEER/rekordbox/export.pdb`. Se o pendrive já foi autorizado, a chegada USB tenta reler sozinha.
- O app não pede permissão de escrita e não grava no volume.
- Pendrive simulado: biblioteca gerada no app (duas faixas, playlists, hot cues). A tela diz SIMULAÇÃO.
- Decks: play, cue, pitch, volume, crossfader, sync por BPM e hot cues. A pré-escuta vai para os canais 3–4. Se o aparelho abrir menos de 4 canais, o fone **não** é jogado no master.
- WAV e MP3. A forma de onda curta vem do ANLZ quando o arquivo tem a seção `PWAV`.

## Instalar

1. Copie `deckbridge-debug.apk` para o tablet.
2. Permita instalar de origem desconhecida.
3. Abra DeckBridge. A primeira tela é o diagnóstico.
4. Na Biblioteca, toque em **Autorizar pendrive** e escolha a raiz do USB exportado pelo rekordbox.
5. Abra a playlist e toque em Deck A ou Deck B.
6. Para o teste de canais, use o diagnóstico (tom no master, depois tom no fone) e compartilhe o relatório. O player não substitui esse teste.

## O que ainda não é prova

- Emulador, MIDI simulado e pendrive simulado não fecham A014 nem T009.
- Nesta máquina o KVM falhou ao abrir o emulador (`kvm_spurious_fault`). Com a CPU em software, a interface do Android ficou em "Application Not Responding" e não deu para gravar a tela do app. Não há print.
- OneLibrary (`exportLibrary.db`) não é lida.
- Loops do rekordbox aparecem na análise e não tocam em ciclo.
- A curva real do pitch da DDJ-400 não foi medida. O slider da tela é linear, de metade ao dobro do tempo.
- OD-04 segue aberta.
