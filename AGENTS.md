# AGENTS.md — instruções obrigatórias ao agente implementador

## Missão

Implemente em incrementos verificáveis o DeckBridge OS, standalone para a DDJ-400, fundamentado em `specs/001-ddj400-standalone/` e em `STATUS.md`. O alvo 1 é o app Android no Galaxy Tab S6 Lite. O alvo 2 é o Raspberry Pi. Priorize a integridade da biblioteca musical e a estabilidade do áudio acima de features.

## Ordem normativa

1. Constituição (`.specify/memory/constitution.md`)
2. `spec.md` (o QUÊ e aceites)
3. `plan.md` (COMO)
4. `data-model.md` e `contracts/` (interfaces)
5. `tasks.md` (execução)
6. `research.md` (evidências e dúvidas; referências não substituem testes)

## Regras

- Não inventar resultados de testes, versões, compatibilidade, tempo de boot, consumo ou nomes de controles do Mixxx.
- No alvo 2, não começar com um fork próprio de todo o engine. Primeiro um vertical slice em Mixxx upstream estável e no mapping DDJ-400 existente. No alvo 1 o motor próprio já é o do app (OD-12, ainda provisória quanto à confirmação formal, e é o código que a 0.14.1 distribui).
- NÃO alterar firmware da DDJ-400; nenhuma necessidade de engenharia reversa de USB proprietária no MVP.
- **Nunca escrever no pendrive rekordbox** no MVP, nem permitir mount RW. Se algum parser abrir DB SQLite WAL, trabalhar em cópia isolada em disco local, não no pendrive.
  - Exceção única, por decisão do dono em 2026-10-25 (ADR-0009): o **Library Manager de desktop** (`desktop/`) pode escrever no pendrive, só por pedido explícito do usuário e com backup local, gravação em arquivo temporário e troca atômica, verificação e restauração. Nenhum outro programa (app Android, `deckbridge_pi`, `deckbridge_ui`, Mixxx) escreve no pendrive.
- Nunca copiar músicas protegidas ao repositório; fixtures sintéticas com licenças compatíveis, consentimento para testes de arquivos pessoais.
- Operações de alto custo (scans, inferência, exportação, DB, rede) não podem bloquear thread de áudio/UI crítica.
- Guardar cada decisão arquitetural significativa em ADR; citar justificativa e alternativa descartada.
- Versionar e fixar SHA/tag de dependências, construir a partir de artefatos verificáveis; nunca `curl|sh` em produção. Evitar dependências non-free.
- Não chamar recurso de pronto antes de demonstrar critérios de aceite com provas.
- Não mudar requisitos do P0/P1 por conveniência; abrir proposta com trade-offs.
- Em ausência de hardware, implementar em desktop/simulação e marcar testes HIL como `BLOCKED_HARDWARE`, nunca `PASS`.
- Perguntar ao proprietário somente decisões listadas em `spec.md` com status `OWNER_DECISION`, ou se uma restrição nova impossibilitar o trabalho; as defaults se aplicam na ausência de resposta.

## Execução por tarefas

- Iniciar na primeira tarefa desbloqueada de `tasks.md`.
- Para cada tarefa: criar branch `feat/<id>-descricao`, testes e documentação; registrar commit, evidências, status, riscos e próxima tarefa em `docs/runbooks/implementation-log.md` (criar o arquivo se ausente).
- Gate: testes unitários/integração automatizados; HIL para áudio/DDJ quando disponíveis; static analysis; sem segredos; revisão de mudanças em licenças.
- PR pequeno por componente; preservar funcionamento musical e rollback de versão.
- Tarefas paralelas só quando não editarem o mesmo subsistema ou ABI.
- Configurar CI em Linux x86_64; builds arm64 via runner nativo ou cross-compilation; hardware HIL dedicado depois.
- No final de cada fase, publicar matriz de aceites de `spec.md` com `PASS/FAIL/BLOCKED` e links para evidências.

## Definition of Done por item

Código compilável, lint e testes aplicáveis passando, documentação/configuração reproduzível, logs sem dados sensíveis, compatibilidade verificada quando hardware disponível, sem regressão de áudio, task marcada concluída com referência do commit.

## Prompt recomendado

> Leia AGENTS.md e os arquivos em specs/001-ddj400-standalone/. Não produza apenas um plano: execute as tarefas em ordem de dependência, começando por T001. Para cada entrega, registre evidências e bloqueios no implementation-log. Preserve a política read-only para USB rekordbox e não afirme testes de hardware que não executou. Trate melhorias inteligentes como posteriores aos gates de estabilidade musical.
