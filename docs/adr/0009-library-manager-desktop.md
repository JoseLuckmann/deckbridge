# ADR-0009 — Library Manager de desktop: único programa que escreve no pendrive

**Data:** 2026-10-25  
**Estado:** aceita (decisão do dono, 2026-10-25). Emenda o ADR-0002 e a OD-23 só para o programa de desktop.

## Contexto

O dono pediu uma versão do programa para o computador, separada da tela de performance, para criar playlists, pré-processar músicas e organizar a biblioteca à mão. Mais tarde isso ganha automação (classificar, pontuar, renomear, preencher metadados). Ele precisa **escrever no pendrive**, no padrão da Pioneer.

O ADR-0002 e o AGENTS.md proíbem escrita no pendrive rekordbox. O próprio ADR-0002 já previa a saída: "projeto separado de writeback com backup, controle transacional, validação em USB descartável e opt-in explícito". Este ADR é esse projeto.

## Decisão

1. Existe um programa novo, o **Library Manager** (`desktop/`). Ele é o único que pode escrever num pendrive rekordbox.
2. **Performance continua somente leitura.** O app Android, o `deckbridge_pi`, o `deckbridge_ui` e o Mixxx não ganham nenhum caminho de escrita. A regra do AGENTS.md vale para eles sem mudança.
3. O Library Manager só escreve depois de um pedido explícito do usuário ("Aplicar ao pendrive"). Abrir, navegar e editar no programa não toca no pendrive: as edições ficam numa fila de mudanças pendentes, em memória.
4. Salvaguardas obrigatórias em toda gravação:
   - **Backup local antes de escrever:** cópia de `export.pdb`, `exportExt.pdb` e dos ANLZ afetados em `~/.deckbridge/backups/<volume>/<data-hora>/`, com SHA-256. Fora do pendrive, para sobreviver a um pendrive danificado.
   - **Escrever em arquivo temporário e trocar:** nunca editar `export.pdb` no lugar. O novo banco é montado em memória e gravado ao lado como `.deckbridge-novo`; depois relido e conferido; só então vira `export.pdb` por renomeação.
   - **Verificação antes da troca:** o banco novo tem de ser lido pelo nosso parser, ter as mesmas faixas que o plano diz, e toda linha que não foi editada tem de continuar idêntica byte a byte.
   - **Restaurar:** a tela lista os backups e desfaz com um clique.
   - **Recusas:** volume sem `PIONEER/rekordbox/export.pdb`, volume montado somente leitura, banco que o parser não entende por completo, ou arquivo de outro programa em uso. Nesses casos não grava e diz o motivo.
5. **Preservar, não regenerar.** O gravador lê as linhas do banco como bytes. Só as linhas editadas são reescritas; tabelas que não entendemos (colunas, cores, histórico, etc.) e campos de faixa que não editamos passam como estão. Isso evita perder o que o rekordbox escreveu e que não está documentado.
6. Sem rede, sem telemetria. O programa roda offline.
7. Pendrive novo, criado do zero (sem export do rekordbox), **não entra agora.** Um export sem referência real arrisca ficar incompatível, e a tabela de colunas dos players não está documentada o bastante. Fica para quando houver um export real para comparar.

## O que não está verificado

- Nenhum player (CDJ, XDJ, DDJ em modo standalone) nem o rekordbox leu um banco escrito por este programa. Fica `BLOCKED_HARDWARE`. Até alguém testar num player e no rekordbox, a regra é: **use primeiro um pendrive de teste ou uma cópia**, e o programa oferece "Gravar numa cópia" para isso.
- O gravador foi validado contra o nosso leitor Kotlin, o leitor C++ do Pi e um terceiro parser gerado da especificação aberta do formato (Kaitai, `rekordbox_pdb.ksy`, Crate Digger, commit `7c9d535`). São três leitores que podem errar do mesmo jeito em campos que nenhum conhece.

## Alternativas descartadas

- **Gerar o banco do zero a partir do modelo (como nos fixtures de teste):** simples, mas perde tudo que o rekordbox escreveu e que não entendemos. Descartada para pendrives reais. Continua nos fixtures.
- **Editar `export.pdb` no lugar, página por página:** menor escrita, mas um erro no meio deixa o banco pela metade. Descartada pela troca atômica.
- **Fazer o app Android escrever:** o Android é quem monta o pendrive, o app roda no palco e a regra da performance é não arriscar a biblioteca. Descartada.
- **Interface em SDL2 como no Pi:** a tela do Pi foi medida para 512 MB de RAM. O Library Manager roda num computador, é feito de tabelas, campos de texto e árvores, e reaproveita direto o Kotlin dos leitores e do analisador. Escolhido Compose Desktop (mesmo Kotlin do app, mesmo visual).

## Consequências

- O `desktop/` é um build Gradle separado, que compila os mesmos fontes de `android/app/src/main/java/os/deckbridge/logic/` (puro JVM, sem import de Android). Não toca no build do Android.
- A automação futura (classificar, pontuar, renomear, preencher metadados) entra como mais uma fonte de mudanças pendentes, passando pela mesma fila, revisão e gravação segura. Nada grava sem o "Aplicar".
- Escrever o ANLZ (cue points, grade, ondas das faixas importadas) e copiar áudio para o pendrive passam pelas mesmas salvaguardas, em etapa posterior.
