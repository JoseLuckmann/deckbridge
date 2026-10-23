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

- **Grade do analisador fora do bumbo.** Nas duas faixas de teste (house, 130 e 129 BPM) o BPM saiu certo, mas a primeira batida da grade caiu 222 ms e 123 ms fora do bumbo medido no áudio (o analisador escolheu um baixo em contratempo). O SYNC alinha pela grade, então a fase ficaria errada. O vídeo aplica o ajuste de grade que o app já tem (G−/G+, −222 ms e −123 ms), como um DJ faria ao preparar as faixas. Com a grade ajustada a fase entre os decks fica entre 0,0 e −0,5 ms (`checks.txt`). **Corrigido em 23/10/2026** (ver abaixo).
- **Crossfader:** a lei era linear (cada canal a 50% no centro). Passou a aditiva (os dois inteiros no centro), em `engine.cpp`, `masterGain` e no teste.
- Os auto cues do app (compassos 16, 32 e 168 da primeira faixa; 32 da segunda) caem em barras exatas.

## Versão sem SYNC (23/10/2026)

O roteiro agora começa com os dois decks vazios: BROWSE, giro do encoder, LOAD do deck 1 (nota 70) e do deck 2 (nota 71), hot cue quantizado e PLAY. O SYNC não é usado: o BPM do deck 2 é igualado pelo fader de tempo (MSB/LSB, status 176+deck, notas 0 e 32, passando do ponto e voltando) e o PLAY do deck 2 cai na linha do compasso do deck 1 (8 compassos de solo).

`checks.txt` (fase entre decks, medida contra o bumbo real): −2,5 ms na entrada, 0,0 ms depois do salto de hot cue, +0,1 ms no retorno à primeira faixa. Entre 44 s e 63 s o deck 1 está pausado (a fase listada ali não vale). Só simulação e áudio do motor C++; escuta no tablet segue `BLOCKED_HARDWARE`.

## Fase da grade corrigida no analisador (23/10/2026)

`refineGridPhase` (`TrackAnalysis.kt`) corrige a fase quando o pico grave mais forte é um baixo no contratempo. O corpo do bumbo (100 a 250 Hz) tem o máximo de energia por fase logo depois do ataque e o mínimo no contratempo; o ataque vem do fluxo logarítmico de 1 a 5 kHz, a fase só é trocada se tiver também corpo grave (40 a 150 Hz) e difere mais de 15 ms da anterior, e a mediana do início do ataque (25% do pico, envelope de 1 ms) fecha o ajuste fino. A grade passa a ser regular, sem recolar nos picos do fluxo.

Medido nas duas faixas de teste (sem nenhum ajuste manual de grade): primeira batida 1 ms e 0 ms do início do bumbo (antes 222 ms e 123 ms fora), e a fase entre os decks no vídeo sem SYNC fica entre −0,5 e −1,0 ms. As origens do bumbo vêm de uma medição independente (limiar de 25% do ataque na faixa de 1 a 5 kHz). O vídeo `sessao-sem-sync.mp4` ainda foi gerado com o ajuste manual antigo (−222/−123 ms); o roteiro agora roda sem ele.

Limites: heurística validada em duas faixas house reais e num teste sintético (`gridLocksToTheKickWhenAnOffbeatBassIsLouder`, que falha sem a correção). Gêneros sem bumbo marcado (ambient, breakbeat) caem no caminho antigo, porque o contraste do corpo é pequeno. Não há corpus de grade com verdade de terreno para medir a taxa de acerto geral: marcado como risco.
