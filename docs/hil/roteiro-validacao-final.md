# Roteiro de validação final no hardware

**Quando:** uma vez, no fim, com a DDJ-400 e o Raspberry na mão.  
**Estado da execução:** `BLOCKED_HARDWARE`.  
**Regra:** simulação, desktop e emulação não substituem esta lista. O que não for medido fica `UNKNOWN`. Não preencher no chute e não usar a palavra comprovado antes destes passos.

1. Ligar o aparelho na fonte correta dele. Anotar modelo e fonte. Se não medir, `UNKNOWN`.
2. Conectar a DDJ-400 no USB. Anotar o firmware. Se não der para ler, `UNKNOWN`.
3. Ligar as caixas no master e o fone na pré-escuta.
4. Rodar `python3 -m deckbridge.hil_collect`. O que o script não enxergar permanece `UNKNOWN`.
5. Instalar exatamente o Mixxx 2.5.6 do pin em `third_party/mixxx/2.5.6/PIN.json`.
6. Comparar a captura MIDI (`aseqdump` ou equivalente) com `docs/compatibility/ddj400-control-matrix.md`. Registrar diferença. Não inventar byte.
7. Confirmar o master nos canais 1–2 e o fone nos canais 3–4, com sinais diferentes. Se não separar, parar.
8. Tocar 15 minutos com MP3 e WAV de teste. Anotar x-runs, temperatura e subtensão. O que não for medido fica `UNKNOWN`.
9. No pendrive exportado pelo rekordbox: hash antes, montar somente leitura, usar, hash depois. Os hashes precisam ser iguais. Se aparecer `rw`, não oferecer a fonte.
10. Tirar o pendrive no meio do uso. Não pode surgir arquivo novo nele. Se ainda der para ler, hash de novo.
11. Desligar pelo procedimento seguro. Nada volta a tocar sozinho depois de uma falha.
12. Esta lista é a prova no hardware. Resultado de simulação não fecha G0 nem G1.
13. Gravação: com REC ligado, o ficheiro WAV na pasta do app é o master (a mistura, com os efeitos). Conferir o cronómetro e o medidor de entrada. Se o espaço livre estiver abaixo de 64 MB, a gravação não começa e a faixa avisa. O AAC, se estiver ligado no menu, é o codificador do sistema (MediaCodec), não o LAME. Abrir o ficheiro depois. Não pode aparecer ficheiro novo no USB do rekordbox.
14. Histórico: uma faixa no ar (fader do canal acima e a tocar pelo tempo escolhido no menu) entra em HISTORY com hora, deck, BPM e tom. Exportar CSV e M3U8 para uma pasta escolhida pelo utilizador. O USB do rekordbox não é gravado. O caminho no M3U8 é `deckbridge://set/…`, nunca `CONTENTS/` nem `PIONEER/`.
15. RMX na controladora: o item 8 de `docs/hil/checklist-fase1.md` e `docs/compatibility/ddj400-rmx.md`. Confirmar os pads, o isolador na tela e o release. Se o byte divergir do mapa provisório, anotar o byte lido. Não inventar nome de controlo.
16. Ouvir, um a um, os Beat FX da lista e cada Scene FX, o X-Pad, o isolador e o release (brake, echo out, backspin). Anotar o que não soa como o esperado. Spiral, Helix, Mobius, Pitch, o reverb e o RMX são aproximações originais. Não marcar este passo a partir da JVM, do binário hospedeiro nem do APK.
17. Capa: numa exportação real, a arte aparece no cartão, na lista e no painel de info. Sem ficheiro, fica o ♪. O hash do USB antes e depois continua igual. A miniatura fica na pasta de cache do app.
18. Key Shift, Key Sync, SLIP, EMERG e o toque na visão geral. O tom muda de semitom com o master tempo aproximado. O SLIP volta ao ponto em que estava. O EMERG perto do fim faz um loop de 4 batidas. Não marcar pela JVM.
19. Filtro de BPM e tom na lista, contagem até o próximo cue, e o banco de hot cue na memória do app. O banco não é gravado no USB.
20. Cor da onda no pendrive real: RGB, 3 BANDAS e AZUL, com a tag que o export trouxer (PWV5/PWV4, PWV7/PWV6, PWV3/PWAV). Se “Usar configuração do pendrive” estiver ligado e existir `DEVSETTING.DAT` com um byte da lista documentada, a tela abre nesse modo. O hash do USB antes e depois continua igual. Não marcar pela JVM nem pelo pendrive simulado.
21. Na 0.12.0, no pendrive real: tocar o cabeçalho da lista (título, BPM, tom, tempo) e ver a ordem inverter. Com um loop de memória, a faixa só entra no loop quando a cabeça chega ao início. REV inverte a reprodução na tela. G−, G+, S−, S+ e G0 deslocam ou esticam a grade só na memória do app. O hash do USB antes e depois continua igual. A contagem até o cue inclui hot cue e memory cue. Não marcar pela JVM. O trecho de mix em `/opt/cursor/artifacts/` também não fecha este passo.
22. Slip: pausar, fazer scratch, premir um hot cue e inverter, com SLIP ligado. O ponto escondido continua. Ao soltar, a reprodução volta a esse ponto. A DDJ-400 não tem alavanca de reverse; o REV da tela é o gesto. Não marcar pela JVM nem pelo teste C++ do relógio.
23. PHASE METER no lugar da onda, MATCHING e SUGGEST no BROWSE, e beat jump ±64. A linha do SUGGEST tem de ser legível (tom, BPM, energia). Não marcar pela JVM.
24. Barra de frase numa faixa com PSSI e numa pasta sem export. Cues automáticos no app, não no USB. Auto gain com o trim no cartão, sem clipar o master. O hash do USB antes e depois continua igual. Não marcar pela JVM.
25. **Primeiro passo com a controladora (bring-up).** Abrir o diagnóstico e mexer em cada controle da DDJ-400 sem SHIFT, até o painel "Bring-up da controladora" mostrar 121/121. Uma mensagem em "Fora do mapa" quer dizer que o byte real difere do mapa do Mixxx 2.5.6. Nesse caso, tocar o controle na lista de "Ainda não vistos" e mexer nele para remapear. Anexar o relatório (botão Relatório), que leva o checklist e o remapeamento. Não marcar pela JVM nem pelo MIDI simulado.
26. Prato: scratch com o topo tocado, backspin manual (girar rápido e soltar) e bend pela borda. Anotar se a escala do prato (720 passos por volta no mapa, `jogSensitivity` 1,0) parece certa. Se não parecer, ajustar a sensibilidade no menu e anotar o valor. O motor do deck não pode estalar ao parar nem ao voltar.
27. Curvas do fader de canal e do crossfader, COMP, isolador do master, SEND (SHORT/LONG DELAY, DUB ECHO, REVERB) e FX FREQUENCY. Ouvir cada um no master e conferir que nenhum clipa com o master no máximo. Não marcar pela JVM nem pelo binário hospedeiro.
28. Touch Preview: tocar a onda pequena de uma faixa da lista. Os 30 s têm de sair só no fone (canais 3–4), nunca no master.
29. Exportar o histórico escolhendo o próprio pendrive do rekordbox como destino. O app tem de recusar e avisar. Hash do USB antes e depois: o arquivo vazio que o seletor cria é removido e mais nada muda. Anotar se o hash ficou idêntico.
30. Tirar e religar o cabo da DDJ-400 no meio de uma faixa. A música continua; o MIDI e os LEDs voltam sozinhos. Repetir com o áudio da controladora como saída e anotar o que acontece com o som (o motor tenta reabrir 6 vezes).
