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
