# Menos memória de cabeça: organizar a biblioteca e montar sets

O problema, nas palavras do dono: músicas novas entram, as velhas precisam continuar, as boas não podem se perder, e
decidir onde cada uma entra (na biblioteca e depois nos sets) é trabalho mental pesado. Aqui está o que a pesquisa sobre
coleções pessoais, organização de informação e decisão diz, e o que mudou no app por causa disso.

Só entram fontes primárias ou registros confiáveis delas; onde li só o resumo, está dito. Nenhuma destas mudanças foi
medida com DJs reais: as fontes justificam a direção, e o uso no aparelho é o que confirma.

## O que a pesquisa diz

| Fonte | Achado | O que muda para o DJ |
|---|---|---|
| Malone, T. W. (1983), *How do people organize their desks? Implications for the design of office information systems*, ACM TOIS 1(1) 99–112 | Pessoas mantêm **arquivos** (classificados) e **pilhas** (ainda sem classe). Classificar na hora é difícil, por isso a pilha existe. Ela também tem a função de **lembrar**: o que está à vista é lembrado. Recomenda classificação múltipla e adiada, e lembretes | A faixa nova não precisa de destino na hora. Precisa de um lugar para esperar e de algo que lembre das boas |
| Whittaker, S. & Sidner, C. (1996), *Email overload: exploring personal information management of email*, CHI '96, 276–283 | A caixa de entrada vira lista de pendências. Pastas mal definidas ("failed folders") falham, e o que é arquivado some da vista e é esquecido | A caixa de entrada deve ser explícita e esvaziável, e o que foi arquivado precisa voltar à vista |
| Cunningham, Jones & Jones (2004), *Organizing digital music for use: an examination of personal music collections*, ISMIR 2004 | Entrevistas: as pessoas organizam música pelo **uso** (ocasião, humor) mais do que por metadados | Playlists de uso (o set de sexta) valem mais que gênero; a faixa precisa dizer onde já foi usada |
| Lingel, J. (2013), *"When organizing becomes an extension of your brain": DJs, music libraries and information practices*, iConference 2013 (IDEALS hdl 2142/38390; li o resumo) | Entrevistas com 13 DJs: a organização é uma extensão da memória do DJ, e o crescimento da coleção é o obstáculo | O app deve guardar o que o DJ hoje guarda de cabeça |
| Bergman, Beyth-Marom & Nachmias (2003), *The user-subjective approach to personal information management systems*, JASIST 54(9) 872–878; Bergman et al. (2008), *Improved search engines and navigation preference in personal information management*, ACM TOIS 26(4) | As pessoas preferem **navegar** até o item (56–68% das buscas) a buscá-lo (4–15%), mesmo com busca boa. Organizar pelo contexto subjetivo do usuário (o projeto em que usou) ajuda a reencontrar | Da faixa para os sets onde ela está, e de volta: navegação de um toque, sem busca |
| Jones, W. & Teevan, J. (orgs.) (2007), *Personal Information Management*, Univ. of Washington Press; Jones, Bruce & Dumais, *Keeping found things found*, Information Research 10(1) paper 207 | Reencontrar é a meta de toda organização pessoal; o difícil é lembrar **onde** se pôs | "Onde está" na própria faixa |
| Risko, E. F. & Gilbert, S. J. (2016), *Cognitive offloading*, Trends in Cognitive Sciences 20(9) 676–688 | Pôr a memória fora da cabeça (anotar, deixar no ambiente) libera a mente e é escolhido quando o custo de lembrar é alto | Lembrete escrito na troca e mostrado na hora |
| Masicampo, E. J. & Baumeister, R. F. (2011), *Consider it done! Plan making can eliminate the cognitive effects of unfulfilled goals*, J. Pers. Soc. Psych. 101(4) 667–683 (li o resumo) | Meta inacabada fica "ativa" e atrapalha (efeito Zeigarnik). Fazer um plano específico desliga esse custo | Escrever "o que fazer na troca" tira essa troca da cabeça |
| Gollwitzer, P. M. (1999), *Implementation intentions: strong effects of simple plans*, American Psychologist 54(7) 493–503 | Planos "quando X, faço Y" disparam a ação sozinhos quando a situação aparece (efeito médio a grande em meta-análise posterior) | O lembrete é escrito como "quando o vocal entrar, tiro o grave" e aparece ao vivo, antes da troca |
| Scheibehenne, Greifeneder & Todd (2010), *Can there ever be too many options? A meta-analytic review of choice overload*, J. Consumer Research 37(3) 409–425 | Na média, o efeito de opções demais é quase nulo, mas varia muito. Pesa quando faltam preferências claras e critérios para comparar | Poucas sugestões **com o critério escrito ao lado**, não uma lista maior |
| Nielsen, J. (1994), heurística "reconhecer em vez de lembrar" | Mostrar o que é preciso para decidir, em vez de exigir que o usuário lembre | Cada sugestão diz tom, BPM, nota e uso em sets |
| Ward, M. K., Goodman, J. K. & Irwin, J. R. (2014), *The same old song: the power of familiarity in music choice*, Marketing Letters 25(1) 1–11 | Familiaridade prediz a escolha de música tanto quanto o gosto, mais ainda sob carga mental. As pessoas dizem querer novidade e escolhem o conhecido | Sem ajuda, o DJ tende a tocar as mesmas. O app lembra as boas que nunca entraram em set |

## O que mudou

| Mudança | Base | Onde | Prova |
|---|---|---|---|
| **Novas**: filtro de caixa de entrada (sem nota e fora das playlists), logo depois de "Todas". A triagem (MARCAR) começa por elas e a caixa esvazia quando o DJ decide | Malone (pilhas, classificação adiada); Whittaker & Sidner (caixa explícita) | núcleo `Keep.isNew`, `Smart.triageQueue`; chip no celular e no computador | `KeepTest`, `KeepFlowTest` (captura `mobile-49`), `StudioTest` ("Novas 6") |
| **Esquecidas**: as de 4 ou 5 estrelas, há 30 dias ou mais na biblioteca e em nenhum set | Malone (função de lembrar); Ward et al. (puxão do conhecido); Whittaker & Sidner (arquivado some) | núcleo `Keep.isForgotten` (usa a data de entrada do banco, `dateAdded`) | `KeepTest` |
| **Nos sets**: na faixa, os sets em que ela está, com um toque para abrir, e quando entrou na biblioteca | Bergman (navegar em vez de buscar; contexto do usuário); Jones & Teevan (reencontrar); Cunningham et al. (organizar pelo uso) | `TrackPlaces` (studio), na aba MARCAR do celular e no inspetor do computador | `KeepFlowTest` (`mobile-50`), `StudioTest` (`desktop-nos-sets`) |
| **Por que esta**: cada sugestão de "Pôr na linha" diz tom (igual, vizinho, longe), diferença de BPM, nota e "nunca em set"/"em N sets" | Scheibehenne et al. (critério claro); Nielsen (reconhecer); Ward et al. (lembrar as não usadas na hora da escolha) | `Keep.why`, `SetDesk.why`, `AddToLineDialog` | `KeepTest`, `KeepFlowTest` (`mobile-51`) |
| **Lembrete da troca**: uma frase por troca, escrita no MIX, guardada no plano (fora do pendrive), na cola do set e no AO VIVO, antes da troca | Risko & Gilbert; Masicampo & Baumeister; Gollwitzer | `Transition.note`, `NotePanel`, `SetDesk.sheet`, `LiveBar` | `KeepTest`, `KeepFlowTest` (`mobile-52`) |
| **MIX no celular sem corte**: PLAY fixo embaixo; no meio, abas VIRADA (mixer, passo, lembrete) e AJUSTES (compassos, estilo, ritmo, grave, marcar); escurece a borda com "▾" quando ainda rola | Pedido do dono (os ajustes ficavam cortados embaixo); Hoober 2013 e Fitts (PLAY na zona do polegar, já em `ux-referencias.md`); Cowan 2001 (menos blocos à vista) | `MixScreen` | `SetFlowTest` (`mobile-17`, `mobile-53`, `mobile-40`) |

O que conta como set: uma playlist aberta pelo SET (o app guarda isso na pasta dos sets, fora do pendrive), mais as
linhas e as vertentes dela.

## O que fica para depois (não feito)

- Medir com DJs: quantas faixas ficam paradas em "Novas" por semana, quantas "Esquecidas" voltam a um set.
- Classificação por momento do set (abertura, pico, fechamento), um atributo subjetivo à moda de Bergman. O banco do
  rekordbox não tem campo para isso no export; precisaria de um arquivo à parte.
- Esquecidas pela data do último set em que a faixa entrou (hoje é "em nenhum set").
