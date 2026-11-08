# Referências de design aplicadas

As escolhas visuais do app (o que é cor, movimento, forma e peso na tela) e de onde vêm. Como em
`docs/ux-referencias.md`: fonte, o que diz, o que mudou e a prova. Onde só li resumo ou cobertura de imprensa, está dito.

## Base que já existia

O app segue o design system do player (`StagePalette`: fundo preto, painéis quase pretos, azul para o que se toca,
laranja para atenção, verde para "combina"), por ser usado em cabine escura e para parecer o equipamento que o DJ já
conhece. `DesignSystemTest` impede cor escrita à mão fora do tema. Ver `docs/ui-spec.md`.

## Rodada de design (2026-10-27)

| Conceito e fonte | O que diz | O que mudou | Evidência |
|---|---|---|---|
| Cor nunca é o único sinal — WCAG 2.x, critério 1.4.1; Birch, J. (2012), *Worldwide prevalence of red-green color deficiency*, J. Opt. Soc. Am. A 29(3) 313–320 | Perto de 8% dos homens de origem europeia (e 0,4% das mulheres) não distinguem bem verde de vermelho e laranja; a informação não pode depender só da cor | "Combina / não combina" ganhou símbolo junto da cor: ✓ e ! (`FitMark`) nas sugestões, na lista do set e na pool; "✓" no tom compatível da biblioteca; "!" no tom do deck 2 quando não combina; fio pontilhado (não só laranja) na troca com aviso; ⚠ já existia nas pílulas | `DesignSystemTest`, capturas |
| Primeira impressão — Reinecke, Yeh, Miratrix, Mardiko, Zhao, Liu & Gajos (2013), *Predicting users' first impressions of website aesthetics with a quantification of perceived visual complexity and colorfulness*, CHI '13 | Colorido e complexidade visual são o que mais pesa no que se acha da tela em 500 ms (548 pessoas, 450 sites); o melhor fica em níveis moderados | `tools/visual_audit.py` mede colorido (Hasler & Süsstrunk 2003) e densidade de bordas nas capturas. Resultado: biblioteca 15,8 / 5%, quadro 13,4 / 4,5%, MIX 51 / 11% (instrumento, esperado), faixa 66,8 / 5,7% (a mais colorida, pelas oito cores cheias). Na faixa e na triagem, as cores não escolhidas ficaram apagadas (55%) e a escolhida cheia: a faixa caiu para 59,9 e a escolha salta | tabela abaixo |
| Movimento do Material 3 — Material Design 3, *Motion: easing and duration* (m3.material.io) | Curva "emphasized" (cubic-bezier 0,2 0 0 1): sai rápido e assenta devagar; durações médias de 250–400 ms para mudanças de lugar | Deslize dos cartões do quadro com a curva emphasized em 300 ms; fios aparecem depois, em 150 ms (etapas) | `Theme.kt` (`Emphasized`) |
| Ação principal que se acha de relance — pesquisa do Material 3 Expressive (Google, 2025: 46 estudos, mais de 18 mil participantes; li só a cobertura da imprensa, que cita elementos-chave achados até 4× mais rápido) | Tamanho, forma e cor diferentes para o que mais importa na tela fazem achá-lo mais depressa, em todas as idades | PLAY do MIX maior que os outros botões da fileira (56 dp de altura, o dobro da largura) e na cor própria do play | captura `mobile-53` |

### Medida visual das telas (`python3 -I tools/visual_audit.py docs/ui/*.png`)

| Tela | Colorido | Bordas |
|---|---|---|
| Biblioteca (`mobile-01`) | 15,8 | 5,2% |
| Novas (`mobile-49`) | 9,7 | 1,9% |
| Quadro do set (`mobile-41`) | 13,4 | 4,5% |
| MIX, virada (`mobile-17`) | 51,0 | 11,4% |
| MIX, ajustes (`mobile-53`) | 49,3 | 12,8% |
| Faixa (`mobile-50`), antes / depois | 66,8 / 59,9 | 5,7% / 5,6% |
| Fim da triagem (`mobile-48`) | 17,1 | 1,9% |
| Computador, biblioteca com inspetor | 20,5 | 12,1% |
| Computador, quadro | 7,9 | 4,4% |

Os números servem para comparar telas e versões, não como nota absoluta: o estudo mediu sites, não instrumentos.

Não verificado com pessoas: a direção vem das fontes; o uso real é o que confirma.
