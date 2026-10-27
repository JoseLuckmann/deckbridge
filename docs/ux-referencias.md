# Referências de UX aplicadas

Cada conceito tem a fonte (estudo ou diretriz), o que ele diz e o que mudou no app por causa dele. Só entram fontes
primárias ou diretrizes estabelecidas; onde só achei resumo secundário, está dito.

| Conceito e fonte | O que diz | O que mudou | Evidência |
|---|---|---|---|
| Lei de Fitts — Fitts, P. M. (1954), *The information capacity of the human motor system in controlling the amplitude of movement*, J. Exp. Psychology 47(6) 381–391 | O tempo para acertar um alvo cresce com a distância e cai com o tamanho | Conferido que todo controle tocável das telas principais tem área de toque ≥ 48 dp (o Compose estende a área dos pequenos) | `TouchTargetTest` (músicas, playlist, quadro, MIX, marcar) |
| Alvo para o polegar — Parhi, Karlson & Bederson (2006), *Target size study for one-handed thumb use on small touchscreen devices*, MobileHCI 2006, 203–210 | ~9,2 mm para toques isolados; abaixo disso os erros sobem | Mesmo teste (48 dp ≈ 9 mm na densidade de referência do Android) | idem |
| Material Design: alvo de 48 × 48 dp; WCAG 2.5.5 (44 px, AAA) e 2.5.8 (24 px, AA) | Mínimos de diretriz | idem | idem |
| Como as pessoas seguram o celular — Hoober, S. (2013), *How Do Users Really Hold Mobile Devices?*, UXmatters | Em 1.333 observações, 49% seguravam com uma mão (cifras de resumos secundários; o autor alerta que "zona do polegar" não é regra rígida) | No quadro do SET, OUVIR saiu do canto de cima e foi para a barra de baixo, ao lado de ENSAIAR | `SetBoardTest`, `StudioTest` ("OUVIR C" na barra) |
| Visibilidade do estado do sistema — Nielsen, J. (1994), *Enhancing the explanatory power of usability heuristics*, CHI '94, 152–158 | O sistema deve sempre dizer o que está acontecendo | O app grava sozinho; agora o topo diz "✓ salvo", "• alterado" ou "gravando…" | captura `mobile-01` |
| Limiar de Doherty — Doherty & Thadani (1982), IBM (cifra de 400 ms vinda de resumos secundários) | Resposta abaixo de ~400 ms mantém o ritmo de trabalho | Já aplicado na rodada de velocidade (quadro 3–14 ms no computador); o MIX abre na hora e monta a prévia em segundo plano com "MONTANDO…" | `SetBenchTest` |
| Gradiente de objetivo — Kivetz, Urminsky & Zheng (2006), *The goal-gradient hypothesis resurrected*, J. Marketing Research 43(1) 39–58 | O esforço acelera perto da meta; ver o progresso motiva | Na triagem, além de "4/10" e da barra, "faltam N" nas três últimas | `UxFlowTest` |
| Regra pico-fim — Kahneman, Fredrickson, Schreiber & Redelmeier (1993), *When more pain is preferred to less: adding a better end*, Psychological Science 4(6) 401–405 | A lembrança de uma experiência pesa o pico e o fim | Triagem termina numa tela de resumo (quantas passaram, com nota, com cor, na playlist alvo) com PRONTO; o ensaio termina num resumo com "REFAZER A PRIMEIRA" (abre o MIX da que ficou a refazer) | `UxFlowTest`, `SetBuildingTest`, captura `mobile-48` |
| Efeito estética-usabilidade — Kurosu & Kashimura (1995); Tractinsky, Katz & Ikar (2000), *What is beautiful is usable*, Interacting with Computers 13(2) 127–145 | A aparência influencia a usabilidade percebida (correlação; a causalidade é discutida) | Rodada de refino visual anterior (barra plana, tirinha de cor, filtros proporcionais) | `docs/ui/` |
| Memória de trabalho — Cowan, N. (2001), *The magical number 4 in short-term memory*, Behavioral and Brain Sciences 24 | ~3–5 blocos de cada vez | Barra de baixo com 5 áreas; o que é raro fica no ⋯ (já era assim; mantido) | — |
| Lei de Hick — Hick (1952) (só fonte secundária encontrada) | Mais opções iguais, decisão mais lenta | Mantido o "simples por padrão" do MIX (AJUSTAR abre o resto) | — |

Não verificado com pessoas: nenhuma destas mudanças foi medida com usuários reais (tempo de tarefa, erros). As fontes
justificam a direção; o teste no aparelho é o que confirma.
