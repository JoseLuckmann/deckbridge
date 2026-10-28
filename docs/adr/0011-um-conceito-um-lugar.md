# ADR-0011 — Um conceito, um lugar: a lógica compartilhada mora no núcleo

**Estado:** aceita (pedido do dono, 2026-10-27: "cuidado com duplicações de efeitos, beatsync, faixas e coisas assim; o
ideal é sempre ter isso no core").

## Contexto

Há dois apps (ADR-0010): o de performance (`os.deckbridge.android`, toca com a DDJ-400, nunca escreve no pendrive) e o
de biblioteca (`os.deckbridge.manager`, celular e computador). A lógica comum (leitores do pendrive, análise, tom, sync,
cores) morava dentro do app de performance (`android/app/src/main/java/os/deckbridge/logic/`) e o núcleo `core/` a
compilava de lá. Com o tempo, a biblioteca ganhou versões próprias de conceitos que já existiam:

| Conceito | Antes | Problema |
|---|---|---|
| Tom (Camelot) | 4 versões: `Smart.camelot`/`harmonicDistance` (biblioteca), `camelotOf`/`camelotNumber`/`camelotDistance` (performance), `SetPlanner.shiftKey` | A da performance não entendia "G#m", "A#", "Gb", "C♯"; a mesma faixa podia "combinar" num app e não no outro |
| Nomes das partes (Intro, Drop, Outro…) | Texto escrito à mão em 6 arquivos | Um erro de digitação quebrava a montagem automática sem aviso |
| Eco (Beat FX) | Motor C++ (`dsp.h`, performance e Pi) e um eco próprio na prévia do MIX (Kotlin) | Sons e contas diferentes para o mesmo efeito |
| AO VIVO / histórico | O app de performance sabe o que tocou (histórico CSV/M3U); a biblioteca tinha um AO VIVO manual | Dois conceitos de "o que tocou" |

## Decisão

1. **A lógica compartilhada mora em `core/src/main/kotlin/os/deckbridge/logic/`.** *(Desde o ADR-0012 o app usa o núcleo como dependência, sem `srcDir`.)* O app de performance compila esses
   fontes (`sourceSets["main"].java.srcDir`), a biblioteca usa o núcleo como build incluído. Um conceito existe uma vez;
   quem precisa dele importa do núcleo.
2. **Tom:** uma tabela e um parser (`camelotOf`, aceitando sustenido, bemol, ♯/♭, "min"), uma distância
   (`camelotDistance`), um deslocamento por semitom (`camelotShift`). `Smart` e `SetPlanner` só delegam.
3. **Partes da faixa:** `Section` (INTRO, BUILD, DROP, BREAK, OUTRO; `Section.MIX`) é o único lugar dos nomes. Os nomes de
   frase do rekordbox (Verse, Chorus, Bridge…) continuam como o rekordbox os grava, por serem outro vocabulário.
4. **Eco:** a prévia do MIX usa as mesmas contas do ECHO do motor (`EchoSpec`: realimentação 0,16 + 0,5 × quantidade, no
   máximo 0,82; mistura 0,1 + 0,58 × quantidade; 1 tempo). `EchoSpecTest` lê o `dsp.h` e falha se um lado mudar sem o
   outro.
5. **AO VIVO da biblioteca:** sai da tela (`Features.liveMode = false`); o código fica, compilado e testado, para voltar
   quando os dois apps trocarem o plano do set e o histórico.

## O que ainda é duplicado, e por quê

- **A prévia do MIX inteira** (EQ por faixa, faders, troca de grave, eco) é um modelo simplificado em Kotlin; o motor de
  verdade é C++. O computador (JVM) e o app de biblioteca (sem NDK) não carregam o motor. **Próximo passo proposto:**
  um render offline do motor C++ exposto por JNI (o motor já compila no host para o Pi e para os testes), usado pela
  prévia nos dois lados. Custo: build nativo no app de biblioteca e no computador (por sistema operacional). Até lá, os
  efeitos da prévia seguem as contas do motor e têm teste de deriva (como o eco).
- **Sync:** o "sync fino" da prévia mede o atraso entre os bumbos para alinhar offline; o sync do app de performance
  (`syncPitch`, `beatPhase`) acerta pitch e fase ao vivo. Fazem coisas diferentes; ficam separados, cada um uma vez.
- **ANLZ:** o núcleo tem o editor que preserva os bytes do arquivo (para gravar); a leitura decodificada é a do pacote
  `logic` (a mesma do app de performance). Não é duplicação.

## Alternativa descartada

- **Fundir os dois apps num só:** misturaria a escrita no pendrive com o app que toca no palco e que nunca escreve
  (ADR-0010). Descartada; o que se junta é o código, não os apps.
- **Copiar a lógica para dentro de cada app:** é o que gerou as duplicações. Descartada.
