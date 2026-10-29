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

## Achar a troca (0.24.0)

Pedido do dono (2026-10-29): o app analisar as faixas e achar uma mixagem boa como um DJ — olhando os breaks onde dá
para virar o grave, encaixando as duas, usando loop quando precisa — inclusive com músicas difíceis de encaixar.

**O que o app ouve (`TrackProfile`).** Uma vez por faixa, em segundo plano, preso à grade (primeira batida 1), compasso a
compasso: baixo (o grave que fica entre os bumbos: mediana do grave em janelas de 20 ms, para o ataque do bumbo não contar
como baixo), bateria (pulso do envelope de 5 ms no grave ou no agudo), melodia (médio perto do patamar e agudo presente)
e as frases (as mesmas da análise de frases). Fica em `perfis/` na pasta dos sets (fora do pendrive); muda o arquivo,
o BPM ou a primeira batida, mede de novo.

**Como acha (`MixFinder`).** Testa saídas (começos de frase e blocos de 8 na segunda metade da que sai), entradas
(começos de frase e blocos de 8 na primeira metade da que entra), durações de 8 a 64 compassos, o compasso em que o
grave vira (de 4 em 4) e loops, e pontua cada combinação pelo que um DJ procura e evita:

| Regra | Como pesa |
|---|---|
| Nunca dois baixos juntos, nem buraco sem baixo | cada compasso com baixo só de quem não devia ter tira ponto |
| Virar o grave onde a música pede | +3 onde o baixo da que entra começa (o drop) ou onde o da que sai some (break, outro) |
| Quem tem o grave segura a batida | perde ponto o compasso sem bateria de quem está com o grave |
| Duas melodias juntas só com tons perto | o peso do choque é 0,1 no mesmo tom, 0,25 vizinho, 1,2 longe |
| Sair e entrar em frase | +3 saída em começo de frase, +2 no Outro, +1,5 no break; +2 entrada em começo de frase |
| O drop da que entra cai quando a outra acaba | +5; −2 se o drop for queimado no meio da troca |
| Não sair cedo nem arrastar | perde ponto sair antes de 60% da faixa e acompanhar mais de 16 compassos depois do grave virar |
| Longo só quando dá para ouvir as duas batidas | bônus por duração × compassos com as duas baterias; andamentos longe pedem curto |
| Loop na que sai | só quando o fim dela é curto para a troca, só em trecho com bateria; custa um pouco por volta |
| Loop na que entra (4×2, 8×2, 4×4) | só quando faz o drop dela cair na hora certa |
| Quando nada casa longo | eco no começo da frase, corte seco no drop da que entra, ou filtro passa-alta em 16 compassos |

As quatro melhores (variadas: uma por estilo e duração parecida) aparecem no MIX em **ACHAR A TROCA**, com nota e o
porquê; tocar uma usa ela. A melhor já vira a troca automática do set quando as duas faixas foram ouvidas (as trocas que
o DJ ajustou ficam como estão). LOOP D1 / LOOP D2 e o estilo FILTRO também estão nos ajustes, e a onda marca as voltas
do loop com as batidas de cada volta.

**Por que assim.** As regras de saída e entrada em começo de frase, a troca do grave em limite de frase e o drop da que
entra no fim vêm de como DJs descrevem e fazem as trocas: Zehren, Alunno e Bientinesi (2020; *Computer Music Journal*
2022) acham pontos de entrada e saída pelos limites de estrutura e pelo que DJs dizem nas entrevistas; Vande Veire e De
Bie (2018) trocam o grave em limites de frase; Kim et al. (ISMIR 2020) mostram, em transições reais, trocas em pontos de
estrutura e longas. Casar trechos pela harmonia e pelo equilíbrio de espectro (não juntar dois trechos cheios no mesmo
lugar do espectro) é a ideia da "mashability" do AutoMashUpper (Davies et al. 2014). Os pesos são deste app, não dessas
fontes, e foram conferidos só com faixas sintéticas de estrutura conhecida (`MixFinderTest`).

**Limites.** O perfil não separa voz de synth nem sabe o tom de cada trecho (usa o tom da faixa); "melodia" é energia de
médio e agudo, não harmonia de verdade. Bateria com baixo contínuo mais alto que o bumbo pode ler como "sem bateria".
Ouvir uma faixa lê o áudio inteiro (uns 30 MB por faixa de 6 min, na thread de fundo, uma de cada vez); não foi medido
em aparelho (`BLOCKED_HARDWARE`). Nada disso foi testado com músicas reais do dono.

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
- Zehren, M., Alunno, M., Bientinesi, P. (2020). *Automatic Detection of Cue Points for DJ Mixing.* arXiv:2007.08411.
  https://arxiv.org/abs/2007.08411 ; e (2022) *Computer Music Journal* 46(3).
- Davies, M. E. P., Hamel, P., Yoshii, K., Goto, M. (2014). *AutoMashUpper: Automatic Creation of Multi-Song Music
  Mashups.* IEEE/ACM Trans. Audio, Speech, and Language Processing 22(12), 1726–1737.

## A tela MIX para validar uma virada (0.27.0)

Pedido do dono (2026-10-29): "essa tela tem um desafio grande de UX: precisa ser fácil e prática e trazer todas as
informações que o DJ precisa ao validar uma transição; hoje está caótica".

O que estava caótico: dois cartões grandes de deck repetiam o que as ondas mostram (REMAIN, TEMPO, MT, tom) e tomavam um
quarto da tela; não havia resumo para julgar a troca; a aba VIRADA juntava mixer, encaixe, passo e lembrete numa rolagem
só; e para validar o set era preciso fechar e abrir cada troca.

Como ficou (uma pergunta por região — "esta troca está boa?" — e o resto sob demanda):

| Região | O que mostra |
|---|---|
| Topo | VIRADA n/N, quem sai → quem entra (cor do deck 1 e do deck 2), ◀ ▶ para a anterior e a seguinte sem fechar o MIX. |
| Resumo | Estilo e duração; tom (verde combina, laranja não); quanto a que entra estica; a nota da troca achada ouvindo as faixas; onde a troca acontece em cada faixa (sai no OUTRO, entra na INTRO), onde o grave vira, onde cai o drop, os loops; o aviso do set, se houver; e o veredito ✓ BOA / ↻ REFAZER (fica no plano; o ensaio e o "Pronto pro show?" usam). |
| Decks | Uma linha fina por deck (BPM no master e quanto estica, tom, quanto falta) e as ondas com as frases embaixo (INTRO, SOBE, DROP, BREAK, OUTRO, em cores) e o traço verde onde o grave vira. |
| Abas | TROCA (o passo de agora e o próximo, por que esta troca, o lembrete) · MIXER (sua mão, o mixer, o encaixe) · AJUSTAR (as opções achadas, estilo, compassos, grave, loops, corrigir a análise; MAIS ▾ abre pontos e cues). |
| Embaixo | PLAY e os saltos de compasso, sempre ao alcance do polegar. |

Por quê: mostrar o essencial e esconder o resto até ser pedido (Nielsen, heurística 8, design minimalista; divulgação
progressiva, Nielsen 2006); o estado e o veredito sempre à vista (heurística 1); agrupar por tarefa, cada moldura uma
coisa (região comum, Palmer 1992); e o fluxo de validar em sequência sem sair da tela (custo de troca de contexto,
Monsell 2003, *Task switching*, Trends in Cognitive Sciences 7(3)).


## MIX mais simples: abre no mixer, topo de uma linha, mais efeitos (0.31.0)

Pedido do dono (2026-10-29): "deveria começar na tela de mixer e não na de guia de virada"; "aquela caixa de texto que
mostra que tá no automático não precisa existir"; "pense em outro layout, bem mais simplificado"; "adicione outros
efeitos além do eco"; e, sobre o resumo em cima, "polui muito a tela".

- **Topo de uma linha:** fechar, "1/4", A → B, o veredito como ícones (✓ boa, ↻ refazer) e ◀ ▶. Os fatos (estilo e
  compassos, tons, BPM, nota, onde sai/entra pelas frases, grave, drop, loops, aviso) foram para a aba TROCA. As linhas
  DECK 1/DECK 2 saíram (repetiam os nomes do topo); as ondas ficaram mais altas.
- **Abas MIXER · TROCA · AJUSTAR, abrindo no MIXER.** MIXER: a fileira FX e o mixer; GUARDAR/DESFAZER só aparecem quando
  a mão mexeu (e VOLTAR AO AUTOMÁTICO quando há uma mixagem sua guardada) — no automático não aparece aviso. O encaixe do
  CH 2 foi para AJUSTAR.
- **Transporte:** ◀ 1 · PLAY · 1 ▶ · LOOP (os saltos de 4 compassos ficam no teclado do computador).
- **Efeitos (FX):** ECHO, REVERB, FLANGER e ROLL, os nomes dos Beat FX da DDJ-400. Um efeito por troca, nos dois canais
  (como a unidade de Beat FX da controladora atribuída a um canal); o botão FX de cada canal liga e desliga e grava como os
  outros controles. ECHO e REVERB deixam rastro depois que o canal fecha (bons para sair); FLANGER e ROLL mexem no som só
  enquanto ligados. REVERB: Schroeder/Moorer (4 pentes amortecidos + 2 passa-tudo, medidas do Freeverb); FLANGER: atraso
  de 1–7 ms que vai e volta uma vez por compasso, com realimentação; ROLL: grava meia batida ao ligar e repete. Ficam no
  `MixDsp` do núcleo, o mesmo da prévia no celular e no computador (o motor C++ do modo DJ não roda na Biblioteca).

### Mixer do celular refeito e o FX como o da DDJ-400 (0.31.1)

Retorno do dono: a primeira versão do mixer ficou ruim (botões pequenos soltos em painéis vazios), e o FX deveria ter um
knob de nível, "igual no modelo da DDJ-400 que a gente fez".

- **Mixer do celular:** cada canal é uma coluna que ocupa a altura — nome, botão FX grande (44 dp), HI, MID, LOW e FILTER
  como botões grandes empilhados (o arco cresce até a altura que recebe, nome embaixo) e o fader ao lado. Sumiu a coluna do
  meio (era só um desenho do crossfader, sem função). No computador o mixer segue na altura fixa de antes.
- **Painel FX:** os quatro efeitos em grade 2×2 e o knob **LEVEL** ao lado, como a unidade de Beat FX da controladora.
  LEVEL vale na hora na prévia e grava na troca ao soltar o dedo (`Transition.fxLevel`; 0,8 é o ponto de antes). Em cada
  efeito: ECHO, o tanto de repetição e de retorno (`EchoSpec`); REVERB, o retorno; FLANGER e ROLL, quanto do efeito entra.
  O botão de cada canal agora só diz "FX" (o efeito está no painel).
- **Um jeito de mexer em botões:** `knobGestures` é o mesmo arrasto e a mesma acessibilidade para EQ, FILTER e LEVEL.

### O encaixe volta à tela do mixer, com jog (0.31.2)

Pedido do dono: o encaixe fica na tela do mixer, "simulando o jogger", com a interface de antes. O painel ENCAIXE DO CH 2
(−1 BAT, ◀ 5 ms, valor, 5 ms ▶, +1 BAT, SYNC FINO, GRADE D2, ZERAR) saiu de AJUSTAR e fica embaixo do mixer. O centro, onde
aparece o valor, é o jog: arrastar para a direita adianta a que entra e para a esquerda freia (1 ms a cada 3 dp, até ±250
ms); vale na hora na prévia que toca e só grava no plano quando o dedo solta (`SetDesk.jog`).
