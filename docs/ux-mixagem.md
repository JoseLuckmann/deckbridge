# Mixagem com a mão do DJ (tela MIX)

Pedido do dono (2026-10-28): poder ajustar o sync quando a música entra fora da grade; trocas automáticas menos
apressadas e menos simples (abrir médio e agudo de uma sem baixar a outra); poder interferir no meio da troca (efeito,
mais grave) e o app "aprender", gravando o que o DJ fez como a mixagem certa daquela transição daquele set; e, com o
grave cortado, subir pelo fader em vez de subir médio e agudo no botão.

## O que mudou

| Pedido | O que a tela faz agora | Onde |
|---|---|---|
| Sync à mão | ENCAIXE DO CH 2: ◀ 5 ms atrasa e 5 ms ▶ adianta a que entra (como frear ou empurrar o prato); ±1 BAT desloca uma batida inteira; ZERAR; o sync fino medido continua por baixo. Vale na hora, tocando. Teclado: `,` `.` `[` `]`. | `Transition.nudgeMs`, `beatShift`; `MixPreview.shiftMs` |
| Mexer no meio da troca | O mixer da tela é de mexer: HI, MID, LOW, FILTER (passa-baixa ↔ passa-alta), ECHO e o fader de cada canal. O que está na sua mão fica laranja; AUTO devolve o canal ao automático dali em diante. | `MixerPanel`, `SetDesk.setControl` |
| Aprender a mixagem certa | Tudo o que a mão faz enquanto toca é gravado ("● SUA MÃO, não guardada"). GUARDAR faz daquela a mixagem da troca neste set (toca assim no ensaio e no mix de teste do set); DESFAZER volta; AUTOMÁTICO tira a mão. LOOP repete a troca para ensaiar de novo, passada por passada. | `MixTake`, `TakeRecorder`, `MixPlayer` |
| As trocas novas no seu jeito | Com três mixagens guardadas, as trocas automáticas novas começam com o ponto do grave e o tempo de saída que você costuma usar ("As trocas novas já começam do seu jeito"). | `MixHabits` |
| Automático melhor | A que entra sobe pelo fader com o LOW fechado e HI/MID no meio-dia; a que sai abre espaço tirando agudo (até a metade) e um pouco de médio enquanto a outra sobe, e mais um pouco depois da troca; o grave passa num início de compasso; o fader da que sai nunca desce em menos de 4 compassos. | `MixRenderer.swapShape`, `levels` |
| Sentir se encaixa | A prévia agora é mixada ao vivo: estilo, ritmo, ponto do grave, sync e mão mudam o som na hora, sem montar de novo. Só duração, pontos e Master Tempo leem o áudio de novo. | `MixPlayer`, `StudioPreview.playStream` |

## Por que assim (referências)

- **Três bandas e fader por canal são a "linguagem" de uma transição.** Chen et al. (ICASSP 2022) geram transições
  aprendidas de mixes reais controlando exatamente isso — EQ e fader de cada faixa — e um ouvinte as julga perto das de
  DJs; Kim, Yang e Nam (DAFx 2022) estimam os ganhos de fader e EQ de mixes reais pelo mesmo modelo. Por isso a mixagem
  gravada guarda curvas desses controles (mais FILTER e ECHO, que a DDJ-400 tem) e o automático as escreve do mesmo jeito.
- **Troca de grave, frases e transições longas.** Vande Veire e De Bie (2018) montam mixes automáticos de drum and bass
  com troca de grave em limites de frase e aprovação de fãs do gênero; Kim et al. (ISMIR 2020), alinhando 20.765
  transições de 1.557 mixes reais, mostram que os DJs trocam em pontos de estrutura e com transições longas. Daí o
  grave num início de compasso e a saída mínima de 4 compassos.
- **O DJ ensina fazendo (programação por demonstração).** Cypher (org., 1993, *Watch What I Do*) e, em aprendizado de
  máquina com a pessoa no laço, Amershi et al. (2014, *Power to the People*) e Fiebrink et al. (Wekinator, 2011): quem
  sabe o resultado mostra, o sistema generaliza pouco e de forma visível, e a pessoa corrige. Aqui a generalização é
  pequena e explicada (ponto do grave e tempo de saída, mediana de pelo menos 3 mixagens), nunca uma caixa-preta.
- **Gravar a mão como automação "latch".** O controle fica onde a mão o deixou até o próximo gesto, como na automação
  das mesas e programas de áudio (modos *touch/latch*) e como um botão físico; uma passada nova só substitui o trecho que
  a mão tocou.
- **Passo do sync.** Dois ataques a mais de ~10–20 ms um do outro já se ouvem como dois (bumbo "galopando"): ver
  Hirsh (1959) e a revisão de Repp (2005) sobre sincronização. O passo de 5 ms acha o encaixe sem pular por cima dele.
- **Ver e desfazer.** O estado (automático, sua mixagem, sua mão não guardada) fica sempre à vista e tudo se desfaz
  (Nielsen, heurísticas 1 e 3); a cor laranja não é o único sinal — o rótulo muda junto (WCAG 1.4.1).

## Limites (honestos)

- Nada disso foi ouvido num aparelho nem com a DDJ-400 (`BLOCKED_HARDWARE`): a latência do toque ao som (buffer de ~80 ms
  pedido; o real depende do aparelho) e o consumo da mixagem ao vivo no celular não foram medidos.
- A prévia guarda as duas faixas em 16 bits na memória (~30 MB por troca de 32 compassos); ficam no máximo duas.
- O EQ da prévia não é o da DDJ-400 (filtros de 1 polo em ~200 Hz e ~2,5 kHz, sem reforço acima do meio-dia).
- "Aprender" é só ponto do grave e tempo de saída da troca de grave; o resto da mão vale só para a troca em que foi
  gravada.

## Fontes

- Chen, B.-Y., Hsu, W.-H., Liao, W.-H., Martínez Ramírez, M. A., Mitsufuji, Y., Yang, Y.-H. (2022). *Automatic DJ
  Transitions with Differentiable Audio Effects and Generative Adversarial Networks.* ICASSP 2022.
  https://arxiv.org/abs/2110.06525
- Kim, T., Yang, Y.-H., Nam, J. (2022). *Joint Estimation of Fader and Equalizer Gains of DJ Mixers Using Convex
  Optimization.* DAFx 2022. https://mac.kaist.ac.kr/pubs/KimYangNam-dafx2022.pdf
- Kim, T., Choi, M., Sacks, E., Yang, Y.-H., Nam, J. (2020). *A Computational Analysis of Real-World DJ Mixes Using
  Mix-To-Track Subsequence Alignment.* ISMIR 2020. https://program.ismir2020.net/static/final_papers/352.pdf
- Vande Veire, L., De Bie, T. (2018). *From raw audio to a seamless mix: creating an automated DJ system for Drum and
  Bass.* EURASIP J. Audio, Speech, and Music Processing, 13. https://doi.org/10.1186/s13636-018-0134-8
- Cypher, A. (org.) (1993). *Watch What I Do: Programming by Demonstration.* MIT Press.
- Amershi, S., Cakmak, M., Knox, W. B., Kulesza, T. (2014). *Power to the People: The Role of Humans in Interactive
  Machine Learning.* AI Magazine 35(4).
- Fiebrink, R., Cook, P. R., Trueman, D. (2011). *Human model evaluation in interactive supervised learning.* CHI 2011.
- Hirsh, I. J. (1959). *Auditory perception of temporal order.* JASA 31(6).
- Repp, B. H. (2005). *Sensorimotor synchronization: A review of the tapping literature.* Psychonomic Bulletin & Review 12(6).
