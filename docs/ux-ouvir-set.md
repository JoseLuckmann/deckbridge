# Ouvir o set: o set inteiro, em segundo plano, com player na notificação (0.26.0)

Pedido do dono (2026-10-29): "quando estou ouvindo a mixagem ele deixa a minha tela apagar"; "ouvir o set completo
deveria jogar um player para segundo plano e permitir eu navegar no aplicativo e até sair do aplicativo, bloquear a
tela"; "um player bem legal na barra de notificações que permita saltar entre as viradas"; "quero um ouvir pro set todo:
vou ouvir as músicas com as transições".

## O que faz

| | Como | Onde |
|---|---|---|
| O set inteiro | OUVIR (na lista e no quadro) toca cada faixa do começo — ou de onde a virada anterior a deixou — até o ponto de saída, a virada planejada (com a mixagem do DJ, se houver) e segue. Feito na hora, 45 s à frente numa thread de fundo; não monta um arquivo de uma hora. | `SetStream`, `SetDesk.playWhole` |
| Só as viradas | O OUVIR antigo (as viradas em sequência, 8 s antes e depois) ficou no ⋯ do set: "Ouvir só as viradas". | `SetDesk.playAll` |
| Segundo plano | Sair do set, ir para outra aba, sair do app e bloquear a tela não param. Serviço de mídia em primeiro plano; tirar o fone pausa; ligação pausa. | `SetPlaybackService`, `Preview.playSetStream` |
| Notificação e tela de bloqueio | Sessão de mídia com capa desenhada por trecho ("FAIXA 4/9" com a seguinte; "VIRADA 3/8" com a que sai na cor do deck 1 e a que entra na do deck 2, e a linha do set), título, "Virada n/N · set", barra de tempo arrastável, anterior/próxima virada, pausar e parar. Botões dos fones também. | `SetPlaybackService.cover` |
| Saltar entre viradas | Próxima/anterior caem 10 s antes da virada, para ouvir a troca chegando. | `Preview.skip`, `LEAD_IN_MS` |
| Barra do player no app | Embaixo de qualquer aba: o que toca, Faixa/Virada n/N, tempo, ⏮ ⏯ ⏭ ✕; tocar no nome abre o set. | `SetPlayerBar` |
| Tela acesa | Ouvindo a prévia no MIX ou o ensaio, a tela não apaga. O set em segundo plano deixa apagar (é um player). | `keepAwake` |

## Achado

A thread que toca a prévia ao vivo (MIX, desde a 0.23) chamava `stop()` ao chegar ao fim — dentro de uma `Thread`,
isso é o `Thread.stop()` do Java, que lança `UnsupportedOperationException` na thread da tela. Agora chama o
`stop()` do Preview. Achado pelo teste do player do set.

## Limites

- Fora das viradas cada faixa toca no andamento dela; a virada toca no da que sai e a que entra volta ao dela quando a
  virada acaba: um degrau do tamanho da diferença de BPM (até ~6% no master automático).
- A posição da faixa seguinte vem da prévia montada da virada; pular para o meio de uma faixa usa a grade.
- Nada foi ouvido num aparelho (`BLOCKED_HARDWARE`): consumo de bateria, folga de 45 s em faixas MP3 longas
  (decodificação pelo MediaCodec), notificação nos skins (One UI) e Bluetooth não foram medidos. No Android 13+ a
  notificação pede licença na primeira vez; sem ela o set toca, mas os controles não aparecem na gaveta.
