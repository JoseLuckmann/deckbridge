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
