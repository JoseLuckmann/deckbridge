# Checklist físico da Fase 1

O dono só tem a DDJ-400 e o Raspberry no fim do projeto. Até lá, nada abaixo está feito no aparelho. Simulação e testes neste computador não marcam estas linhas.

1. Instalar o serviço de arranque (T031) e fazer 5 boots frios. Anotar cada um. Sem o aparelho, fica `BLOCKED_HARDWARE`.
2. Confirmar na controladora os botões de troca de tela (T029). O mapa de computador está no código; a confirmação física não.
3. Ligar e tirar o pendrive e a DDJ com o programa aberto (T033). Guardar log. Sem teclado na apresentação.
4. Hash do pendrive antes e depois de carregar, cue e loop (T034). Montagem `ro`.
5. Master, fone e LEDs por 15 minutos na UI final (T035). Anotar o firmware. Se não der para ler, `UNKNOWN`.
6. Tirar o pendrive durante a música e provocar uma falha de áudio (T038). Não pode gravar no pendrive nem voltar a tocar sozinho.
7. Só então reunir o gate G1 (T039). Enquanto isso, `docs/benchmarks/G1-evidence.md` permanece aberto e bloqueado.
8. Na DDJ-400, com o painel RMX ligado na tela, confirmar que o modo de pad SAMPLER (notas 0x30–0x37) dispara Scene FX nos pads 1–4 e o X-Pad nos pads 5–8, que SHIFT+SAMPLER não foi tomado, que LEVEL/DEPTH muda a intensidade, e que o isolador da tela mata e abre as bandas. Confirmar também a sensibilidade do jog e o sentido do encoder BROWSE. Até esse teste, o mapeamento RMX, a sensibilidade do jog e o sentido do encoder ficam provisórios e `BLOCKED_HARDWARE`. Não marcar PASS a partir do computador, da JVM nem do APK.
9. Slip com a faixa em pausa, no scratch, com o hot cue premido e em reverse. O ponto de fundo continua a andar. Ao soltar, a cabeça volta a esse ponto. Até ouvir isto na DDJ-400, `BLOCKED_HARDWARE`.
10. No menu, WAVEFORM / PHASE METER. O medidor mostra a fase do compasso de cada deck em relação ao master. `BLOCKED_HARDWARE`.
11. No BROWSE, MATCHING lista faixas compatíveis em BPM e tom com o master. SUGGEST mostra a linha do motivo e não repete o que já esteve no ar. `BLOCKED_HARDWARE`.
12. A barra de frase e os cues automáticos (A no primeiro downbeat, B no drop, C no break, D no outro, ou o preset INTRO). Os cues ficam no app. O hash do USB antes e depois continua igual. `BLOCKED_HARDWARE`.
13. Beat jump de 64 batidas para a frente e para trás, na grade. `BLOCKED_HARDWARE`.
14. Auto gain ligado: o cartão mostra o trim em dB e o master não clipa. Desligado, o trim some. `BLOCKED_HARDWARE`.

Tentativa de montar uma imagem FAT neste ambiente, em 2026-10-21: `mkfs.vfat` criou o arquivo, e o `mount` respondeu `unknown filesystem type 'vfat'`. Não houve montagem. Os testes de montagem usam um runner falso.
