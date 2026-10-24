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

## Versão com loops e ajuste de grade pelo prato (23/10/2026)

- **Loop roll no deck 1 antes da entrada do deck 2:** SLIP ligado (toque), pads BEAT LOOP de 8, 4, 2 e 1 batida e CUE/LOOP CALL para 1/2 e 1/4, cada passo começando numa batida. Sai no instante em que o deck 2 entra e a faixa volta onde estaria (SLIP): fase entre decks preservada (−20 ms de propósito, ver abaixo, depois +2 ms). O mesmo roll em sentido de saída no fim (4, 2, 1, 1/2, 1/4).
- **Ajuste pelo prato:** o deck 2 entra 20 ms atrasado e com o fader de tempo 0,04% acima; o DJ empurra com quatro passos do prato (valor 65 = +5 ms cada) e depois acerta o fader. `checks.txt`: −20 ms na entrada, +1,6 ms depois dos empurrões, +2,2 ms estável.
- **Engine:** sair de um loop com SLIP ligado agora volta à posição sem loop (antes só voltava ao desligar o SLIP). Teste do áudio: o trecho do loop de 2 batidas se repete (correlação 0,89 contra 0,06 sem loop).
- **App:** o pitch bend do prato fora do modo vinil era permanente; agora é temporário (volta ao tempo do fader 150 ms depois do último passo).
- Limites: o prato no modo vinil (padrão do app) desloca a posição em passos de 5 ms por unidade; o SLIP é ligado pela tela (a DDJ-400 do mapa pinado não tem botão de SLIP). Só simulação.

## Set completo de seis faixas (23/10/2026)

Seis faixas de teste (as duas dos vídeos anteriores e quatro novas) tocadas do começo ao fim, sem saltar trechos, em dois decks que se alternam. O DJ é automático (`SetAgent`): cada transição é planejada com o estado real do modelo e executada por mensagens MIDI da DDJ-400 e toques na tela, sem SYNC.

Ordem: t5 (126 BPM, Gm), t4 (128, Gm), t6 (128, Bbm), t1 (130, Bbm), t2 (129, Abm), t3 (135, Gm). Duração: 26,3 min.

Cada transição, na ordem em que acontece:

1. BROWSE, giro do encoder e LOAD da próxima faixa no deck livre, hot cue 1 (primeira batida, quantizado).
2. O BPM da faixa nova é igualado ao que toca pelo fader de tempo (passa do ponto, volta e acerta), com o fader do canal embaixo, o EQ LOW fechado e o filtro em passa-altas.
3. O PLAY cai na linha de um compasso múltiplo de 8 da faixa que toca, corrigido pelo desvio medido entre os ataques reais e a grade (`tools/onset_offsets.py`), que é o "ouvido" do DJ. Nas transições 1 e 4 a entrada é 12 ms atrasada e dois passos do prato (5 ms cada) corrigem.
4. O fader do canal sobe devagar em dois compassos, o filtro abre em oito.
5. Troca de grave no meio da sobreposição (16 a 19 compassos, conforme a faixa), o deck novo vira master.
6. Um efeito diferente na saída de cada transição: filtro fechando e eco; roll de loop com slip (8, 4, 2, 1, 1/2, 1/4 de batida); RMX Noise UP com roll de bumbo e palma; eco de reverb e fader descendo; Color FX DUB ECHO.
7. O deck que saiu para ao fim da faixa; a faixa que ficou volta devagar ao tempo original em 16 compassos.

O final repete o roll com slip, mais o eco Spiral e o fader fechando. Cada faixa ganha o ganho automático do app (alvo −10 LUFS).

`checks.txt`: a fase entre os decks seis compassos depois de cada entrada ficou entre −2,0 e +0,1 ms (grade do analisador mais o desvio medido; não é medida independente do áudio).

Limites: o roteiro não olha para a estrutura musical das faixas (o analisador só marca intro e fim), só para a grade; o Color FX e o EQ são o modelo de estudo do app. Só simulação no host.

## Mixagem-show de duas faixas com SYNC (24/10/2026)

`SET_AGENT=mix` troca o DJ do set pelo `MixShowAgent` (base comum em `DjHands`): t1 (130 BPM) e t2 (129 BPM), 3,3 min, com tudo pela DDJ-400 e pela tela.

1. LOAD de t1 no deck 1 e PLAY; filtro varrendo nos compassos 4–6 e ECHO no 8.
2. Loop roll no deck 1 (compassos 12–16): pads de 8, 4, 2, 1 batida e CUE/LOOP CALL para 1/2 e 1/4, com SLIP.
3. LOAD de t2 no deck 2. **Scratch** no deck 2 nos compassos 20–24 de t1: toque no topo do prato (vinil), giros para frente e para trás. Nos compassos 22–24 entram **cortes de crossfader** a cada meia batida.
4. **SYNC** no deck 2 (129 → 130 BPM) no compasso 28 e PLAY no compasso 32 de t1. Fader e filtro abrem, o grave troca no compasso 16 de t2 e o deck 2 vira master.
5. **Buildup com RMX** no deck 1: Noise UP segurado por 8 compassos (24–32 de t2) e roll de bumbo no último. **BACKSPIN** (release FX do RMX) na meia batida antes do compasso 32 de t2. O deck 1 para e o fader fecha.
6. O backspin cai no breakdown de t2. Um loop roll com SLIP (compassos 48–52) solta no drop. Depois vêm 16 compassos de drop com eco.
7. **Backspin manual pelo prato** no compasso 72: a mão toca o topo, gira para trás com força (0,15 s, ~9x em reverso) e solta. A faixa volta uns 4 compassos pela inércia e freia; PLAY para o deck e o fader fecha.

Para o scratch, o motor ganhou `ScratchFollower` (`dsp.h`). Com o topo do prato tocado (nota 54), a cabeça segue o prato: cada passo de giro move 5 ms e a resposta é suavizada em ~10 ms. Ao soltar com o prato girando, a faixa segue pela inércia: freia 5x por segundo até a velocidade normal (ou zero, com o deck parado) e parte de 0 a 1x em ~0,12 s. É o backspin manual: um giro de 9x para trás leva ~1,8 s para parar. Agarrar o prato de novo para o giro na hora. Durante o giro solto, os giros do prato sem toque não empurram a faixa. Antes, o prato no modo vinil só empurrava a posição.

`checks.txt`: fase entre os decks seis compassos depois da entrada com SYNC: +0,0 ms.

Erros corrigidos no caminho:
- `ddj_session` tratava "loop d -1 -1" como um loop em posições negativas, e o deck calava depois de um loop roll. O set de seis faixas não foi afetado (sem silêncio fora do começo e do fim).
- A onda ampliada ficava plana quando a pasta vinha do cache, porque o cache não guarda o detalhe de 150 colunas/s. O app agora refaz o detalhe a partir do PCM decodificado para tocar, na carga do deck e fora da thread da tela.

Limites:
- Só simulação no host, sem audição na DDJ-400.
- O scratch usa passos fixos de 5 ms por unidade de giro, sem medir a velocidade real do prato da DDJ-400 (`BLOCKED_HARDWARE`).

## DDJ-400 virtual embaixo da tela (24/10/2026)

A DDJ-400 desenhada (`DdjPanel`) é uma segunda tela, renderizada à parte: janela própria de 1280 × 480 com quadros em `frames-ddj/`. A tela do app continua com o layout oficial, em 1280 × 720, sem nada a mais. Só o vídeo junta as duas (`ffmpeg ... vstack`): o app em cima, a DDJ embaixo. O painel junta duas fontes:

- **Controles físicos (`DdjSurface`):** faders, botões giratórios, ângulo e toque do prato, botões e pads apertados e modo de pad. Saem só dos bytes MIDI que a "mão" manda, decodificados com as notas do mapa pinado do Mixxx 2.5.6, sem passar pelo app.
- **LEDs (PLAY, CUE, SYNC, CUE de canal, pads, VU, Beat FX ON):** vêm de `ledTargets`, os mesmos bytes que o app mandaria à controladora (regra do DeckPage, refeita em `SetModel.leds`).

Uma linha embaixo mostra as três últimas mensagens MIDI (hora, bytes e descrição do mapa).

A diferença entre as duas telas é o que se valida. Exemplos:
- Depois do SYNC, o fader de tempo físico continua onde estava, enquanto o app mostra +0,78%. É o comportamento esperado: o DDJ-400 não tem fader motorizado.
- O modo de pad é do próprio hardware; o mapa não tem os botões de modo. O painel mostra o modo pela faixa de notas que os pads mandam.

Limites:
- O desenho segue a disposição da controladora, mas as medidas não são as do desenho técnico da Pioneer.
- MASTER LEVEL não aparece: não manda MIDI no mapa pinado.
- O ângulo do prato usa 720 passos por volta, a conta do script oficial (`scratchEnable 720`), não medido no prato real.

Erro corrigido junto: os quadros liam os picos de `mix.wav.levels.tsv` da pasta do set de seis faixas. No vídeo de mixagem anterior, os medidores da tela mostravam o áudio do set, não o da mixagem. Agora `SET_LEVELS` aponta os picos do áudio deste roteiro.

`docs/ui/ddj-virtual-rmx.png`: o buildup do RMX, com o deck 1 em SAMPLER, os pads 2 (Noise UP segurado) e 7 (roll de bateria) acesos e o LOW do deck 1 fechado.
