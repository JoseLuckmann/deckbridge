# Corpus completo de tempo e tom (23/10/2026)

Medido no host (JVM), não no tablet. Escuta: `BLOCKED_HARDWARE`.

## Corpus

`tools/fetch_corpus_v2.py` baixa todas as faixas do GiantSteps com BPM ou tom publicado: 661 com BPM, 604 com tom (anotações CC BY-SA 4.0; o áudio é prévia de 2 min da JKU, fora do git, em `/opt/cursor/artifacts/ground-truth-v2`). Divisão fixa: sha1 do id módulo 5 igual a 0 é **holdout** (132 de tempo e 123 de tom); o resto é treino. Só o treino entrou na escolha de parâmetros, no prior e nos pesos. O teste é `CorpusV2Test` (`CORPUS=1`; `CORPUS_KEY=0` pula o tom, que é a parte lenta).

Esta rodada pesa mais do que a de 58 faixas do `ground-truth-corpus.md`: o piso daquele corte não vale como comparação, porque o corte antigo não pôde ser reconstruído (as faixas dele eram a ordem do sistema de arquivos).

## Tempo

| | Acc1 holdout | Acc2 holdout | Acc1 tudo | Acc2 tudo |
|---|---:|---:|---:|---:|
| antes (pente do kick, prior em 124) | 0,629 | 0,742 | 0,654 | 0,766 |
| depois (envelope multibanda, pente de harmônicos e de subdivisões, prior do treino) | **0,894** | **0,947** | 0,868 | 0,955 |

Holdout por gênero depois: drum and bass 0,909 / 0,970 (n=33), dubstep 0,810 / 0,905 (n=21), house 1,000 (n=19), techno 0,933 / 1,000 (n=15), trance 1,000 (n=16). Eletrônica e quebradas têm n pequeno (8 e 6).

O que mudou (`TempoOnset.kt`): 40 bandas mel, fluxo logarítmico somado e média local de 0,5 s; autocorrelação com pente nos múltiplos 1, 2, 3 e 4 da batida (pesos 1; 0,6; 0,4; 0,3) mais meia batida (1,5) e um quarto de batida (0,5); prior multimodal de BPM (tabela de 70 a 190, tirada do treino, suavizada em 0,04 oitava, piso 0,2). O período é refinado na faixa inteira (`GridScorer`: melhor fase de uma grade regular sobre o envelope multibanda e o fluxo do bumbo, passo de 0,005 BPM), com o refino antigo pela inclinação dos picos do bumbo como segundo candidato.

Cuidado: o prior é a distribuição de BPM de EDM de loja (Beatport). Música fora do EDM pode precisar de outra tabela; o prior largo antigo era mais neutro. Acc1 dentro de 1%: 0,78 (a ficha é inteira).

## Tom

| | MIREX holdout | exato holdout | MIREX tudo | exato tudo |
|---|---:|---:|---:|---:|
| antes (CQT e perfil EDMA) | 0,472 | 0,374 | 0,499 | 0,401 |
| depois (picos espectrais e classificador linear) | **0,589** | **0,496** | 0,634 | 0,536 |

O que mudou (`KeyModel.kt`): STFT de 8192 a 22050 Hz, picos com interpolação parabólica em 3 compartimentos por semitom, afinação, cromagrama completo e grave (<260 Hz), 4 trechos por faixa com soma de log-probabilidades, e um classificador linear de pesos compartilhados entre as 12 tônicas (24 pesos por modo, treinados no treino). Pesos treinados só no treino: treinar em tudo daria um modelo melhor e acabaria com o holdout.

Custo: duas faixas de 5 min levam cerca de 10 s no total no host (antes 6 s). O app guarda o resultado em cache.

## Frases

Não melhoraram. As fronteiras de frase só têm anotação feita à mão a partir da própria curva de energia (`phrase-annotations.md`), o que não é verdade de terreno independente, e as nove faixas dessa anotação não estão neste ambiente. Sem corpus de estrutura, qualquer ajuste seria chute. Fica registrado como pendente até haver faixas com frases marcadas por uma pessoa.
