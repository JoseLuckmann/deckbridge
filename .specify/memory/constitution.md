# Constituição do DeckBridge OS

**Versão:** 1.1.0 | **Ratificação:** 2026-10-21 | **Última alteração:** 2026-10-22

A emenda 1.1.0 está em `docs/adr/0006-constituicao-1.1.0.md`. O alvo 1 é o app Android no Galaxy Tab S6 Lite. O Raspberry com Mixxx é o alvo 2. O USB do rekordbox continua somente leitura.

## I. Audio-first: estabilidade acima de funcionalidades

A thread de processamento em tempo real nunca faz I/O de disco, rede, parsing de USB, inferência, alocações imprevisíveis nem locks não limitados introduzidos pelo DeckBridge. Regressões de x-runs, descontinuidade ou travamento musical impedem release. Só dependências conhecidas/validadas no caminho crítico.

## II. Patrimônio musical é somente leitura por padrão

O USB do rekordbox é montado read-only no MVP. Não alterar `export.pdb`, `exportLibrary.db`, ANLZ ou arquivos de áudio. Estados modificados (cues e sessões) persistem localmente em espaço separado. Recursos de exportação bidirecional exigem especificação, opt-in, backup, validação offline e testes de corrupção; não entram nas releases iniciais.

## III. Prova antes de promessa

Toda claim de compatibilidade, desempenho e segurança precisa de teste reproduzível, hardware registrado e critérios objetivos. Documentação de terceiros não equivale a aceite local. Dados sintéticos e cenários falsos não são HIL.

## IV. Offline e controle físico primeiro

A DDJ-400 deve conseguir controlar o fluxo musical sem internet. No alvo 1 o host é o tablet: o set não depende de notebook, de nuvem nem de um segundo celular. Um companion (PWA ou telefone) continua opcional e nunca é necessário para iniciar ou continuar tocando. No alvo 2 a tela do aparelho faz o mesmo papel. Modos inteligentes não podem se sobrepor a controles manuais, salvo ativação explícita do DJ.

## V. Upstream-first e interfaces versionadas

No alvo 2, usar Mixxx 2.5.6 como referência e aproveitar o mapeamento DDJ-400 existente. Preferir skin, mapping e serviços desacoplados. Alterações nesse core exigem ADR, flag, teste de regressão e patch isolável; upstream quando possível. No alvo 1 o motor é o do app Android (Kotlin, Compose, C++ com Oboe). O mapa MIDI da DDJ-400 continua o XML pinado do Mixxx 2.5.6; não se copia áudio, código nem sample da Pioneer. Interfaces e schemas são versionados, migração compatível e rollback suportado.

## VI. Segurança e privacidade locais

Sem exposição de serviços em interfaces públicas por padrão; sem upload obrigatório de áudio, faixas, metadados ou telemetria; sem chaves embutidas ou execução arbitrária via API. API móvel é pareada/autenticada. USB e arquivos ANLZ/PDB são entradas não confiáveis: validar tamanho, paths, limites e fuzzing.

## VII. Releases reproduzíveis e recuperação

Pin SHA/tag upstream, checksum das imagens e arquivos, builds determinísticos sempre que factível, backup de dados locais e mecanismo de rollback. Boot e shutdown idempotentes; queda de energia deve permitir recuperação da configuração sem alterar USB externo.

## VIII. Desenvolvimento guiado por gates

Vertical slice funcional antes de abstrações. Cada marco produz testes, relatório de evidências e atualização do backlog; nenhuma tarefa pode ser declarada pronta sem DoD. Trabalho físico depende de execução com o proprietário.

## Política de governança

Mudanças desta constituição precisam de ADR e justificativa, incremento SemVer, atualização de templates e revalidação de aceites. Em conflitos, requisitos de segurança do áudio e integridade do USB têm precedência. O implementador deve interromper release com falha nesses princípios, ainda que a UI esteja funcional.
