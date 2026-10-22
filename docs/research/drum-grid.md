# Grade do drum build — 0.14.1

O roll da 0.14.0 contava samples num relógio que começava em zero quando o efeito ligava. Esse relógio não era a fase do deck. A fração mudava no mesmo sample em que o knob passava o limiar, mesmo no meio da batida. Por isso o roll não sentava no kick.

Na 0.14.1 o ataque sai quando a fase musical do deck cruza o ponto da grade. A fase é

`beat = (posição_lida / taxa_da_fonte − origem) × BPM_da_grade / 60`.

- `posição_lida` é a posição da fonte do sample que acabou de sair, antes do passo. O passo já inclui o pitch do fader, o `rateMul` (brake, spiral da cena, backspin) e o sentido.
- `BPM_da_grade` é o BPM do arquivo, ou o BPM depois do stretch da grade na memória do app. O pitch não entra de novo nessa conta: ele já acelera a posição.
- `origem` é o downbeat. Se a análise tem batidas, é o tempo da primeira batida menos os tempos que faltam para o “1” do compasso. Shift da grade entra nessa conta. Sem batidas, a origem é zero.
- Sync só muda o pitch do deck. A fase continua a ser a desse deck. No master, a fase é a do deck master.
- O bloco de áudio não quantiza o ataque para o início do buffer. O loop do motor chama o agendador uma vez por sample.
- A latência do Oboe é a mesma do deck e do hit, porque os dois entram no mesmo buffer. Não há um segundo adiantamento: isso faria o roll chegar antes da faixa.

O teste `tools/drum_grid_test.cpp` mede o sample em que o impulso passa de um limiar e compara com o sample em que a fase cruza o ataque previsto. BPMs 120, 128, 140 e 174, pitch 1,00, 0,92 e 1,08, blocos de 1 e de 256 samples. O pior erro nesta máquina foi 0 samples (0 ms), abaixo de 1 ms. O gráfico está em `docs/research/plots/drum-onset.svg`.

O começo quantiza para a próxima semínima. 4 compassos são 1+1+1+1 (1/4, 1/8, 1/16 e 1/32, um compasso cada). 8 compassos são 2+2+3+1. 16 compassos são 4+4+7+1. O 1/32 é sempre o último compasso. A troca de subdivisão cai na fronteira do compasso, que também é fronteira de semínima. O último ataque é estritamente antes do downbeat final. Com o gap ligado, o último meio tempo fica sem ataque. Soltar o pad no meio do gesto espera a próxima semínima, em vez de cortar entre dois ataques.

O passa-altas do roll sobe de 40 Hz até cerca de 180 Hz e mistura no máximo 40% do sinal filtrado. O corpo do bumbo e da caixa fica na gravação; a subida só afina o grave.

O caminho antigo (relógio solto, seno e ruído) continua quando o build está em zero e o motor não está a seguir a grade. É o que `dsp_test` e o `BUILD_HITS` de `fx_measure` ainda medem. Com o Drum ligado na tela, o payload manda build acima de zero e o motor segue a grade.
