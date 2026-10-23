# Vídeo de sessão: mix por mensagens MIDI da DDJ-400 (host)

Reproduz uma mixagem de duas faixas como se a DDJ-400 estivesse ligada: cada comando é uma mensagem MIDI (status, nota, valor) do mapa pinado do Mixxx, traduzida pela mesma `interpretPerformance` do app. Não é o tablet e não é a DDJ-400; ninguém ouviu o resultado aqui. A escuta continua `BLOCKED_HARDWARE`.

Peças:

1. `android/app/src/test/java/os/deckbridge/session/` — `SessionScript` (o roteiro), `SessionModel` (aplica as ações como o `DeckPage` e registra as operações resolvidas), `SessionOpsTest` (grava `ops.tsv` e confere a fase entre decks), `SessionFramesTest` (captura os quadros da tela real pela JVM, Robolectric, 1280x720).
2. `tools/ddj_session.cpp` — reexecuta o `ops.tsv` no motor C++ (`dsp.h`, `MixerFx`) e grava o áudio e os picos por canal.
3. ffmpeg junta os quadros (15 fps) e o áudio.

Passos (as faixas ficam fora do git; WAV 48 kHz estéreo para o áudio e WAV mono 24 kHz em `in/` para a análise do app):

```sh
export SESSION_DIR=/caminho/da/sessao       # contém in/01-*.wav e in/02-*.wav
cd android && ./gradlew testMultiDebugUnitTest --tests '*SessionOpsTest'
cd .. && g++ -std=c++17 -O2 -I android/app/src/main/cpp -o ddj_session tools/ddj_session.cpp
./ddj_session faixa1.wav faixa2.wav $SESSION_DIR/ops.tsv $SESSION_DIR/mix.wav
cd android && SESSION_FPS=15 ./gradlew testMultiDebugUnitTest --tests '*SessionFramesTest'
ffmpeg -framerate 15 -i $SESSION_DIR/frames/frame%05d.png -i $SESSION_DIR/mix.wav -vf format=yuv420p -r 30 -c:v libx264 -crf 22 -c:a aac -af volume=-3dB -shortest sessao.mp4
```

O que o roteiro mostra: hot cue e PLAY, filtro, EQ LOW em kill, SYNC (tempo e fase), troca de grave no breakdown, Beat FX Echo, painel RMX com Noise UP e o roll de bumbo+palma que cai no drop (salto de 128 batidas incluído), volta à primeira faixa pelo hot cue de breakdown.

Achados desta sessão:

- **Grade do analisador fora do bumbo.** Nas duas faixas de teste (house, 130 e 129 BPM) o BPM saiu certo, mas a primeira batida da grade caiu 222 ms e 123 ms fora do bumbo medido no áudio (o analisador escolheu um baixo em contratempo). O SYNC alinha pela grade, então a fase ficaria errada. O vídeo aplica o ajuste de grade que o app já tem (G−/G+, −222 ms e −123 ms), como um DJ faria ao preparar as faixas. Com a grade ajustada a fase entre os decks fica entre 0,0 e −0,5 ms (`checks.txt`). Corrigir a escolha de fase do analisador é item de backlog.
- **Crossfader:** a lei era linear (cada canal a 50% no centro). Passou a aditiva (os dois inteiros no centro), em `engine.cpp`, `masterGain` e no teste.
- Os auto cues do app (compassos 16, 32 e 168 da primeira faixa; 32 da segunda) caem em barras exatas.
