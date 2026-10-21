# Checklist físico da Fase 1

O dono só tem a DDJ-400 e o Raspberry no fim do projeto. Até lá, nada abaixo está feito no aparelho. Simulação e testes neste computador não marcam estas linhas.

1. Instalar o serviço de arranque (T031) e fazer 5 boots frios. Anotar cada um. Sem o aparelho, fica `BLOCKED_HARDWARE`.
2. Confirmar na controladora os botões de troca de tela (T029). O mapa de computador está no código; a confirmação física não.
3. Ligar e tirar o pendrive e a DDJ com o programa aberto (T033). Guardar log. Sem teclado na apresentação.
4. Hash do pendrive antes e depois de carregar, cue e loop (T034). Montagem `ro`.
5. Master, fone e LEDs por 15 minutos na UI final (T035). Anotar o firmware. Se não der para ler, `UNKNOWN`.
6. Tirar o pendrive durante a música e provocar uma falha de áudio (T038). Não pode gravar no pendrive nem voltar a tocar sozinho.
7. Só então reunir o gate G1 (T039). Enquanto isso, `docs/benchmarks/G1-evidence.md` permanece aberto e bloqueado.

Tentativa de montar uma imagem FAT neste ambiente, em 2026-10-21: `mkfs.vfat` criou o arquivo, e o `mount` respondeu `unknown filesystem type 'vfat'`. Não houve montagem. Os testes de montagem usam um runner falso.
