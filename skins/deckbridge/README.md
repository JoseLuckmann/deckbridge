# DeckBridgeCompact

Este diretório descreve o layout da pele compacta. O Mixxx não foi aberto aqui e nenhum arquivo de skin do Mixxx foi carregado.

As telas desenhadas estão em `docs/ui/screenshots/`. São SVG gerados por `src/deckbridge/ui_layout.py`, nas referências 800x480 e 1024x600 e também em 1366x768. Há uma variante 800x480 sem waveform, para o perfil fraco da OD-01.

BPM, tempo e estado PLAY/PAUSE ficam nas duas decks. A waveform some quando o perfil pede `waveforms=off`. Isso não mede desempenho de áudio.
