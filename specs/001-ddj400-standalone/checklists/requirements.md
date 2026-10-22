# Checklist de qualidade e completude da especificação

**Data:** 2026-10-21, revista em 2026-10-22 | **Escopo:** definição de requisitos (não implementação). O app 0.14.1 não fecha os itens que pedem hardware.
**Marcadores**: `[x]` = documentado, `[ ]` = pendente de validação ou execução; não confundir spec com produto concluído.

## Requisitos e definição de produto

- [x] Problema e contexto da DDJ-400 definidos.
- [x] Resultado final e experiência operacional offline descritos.
- [x] Escopos P0, P1, P2, P3, P4, P5 separados.
- [x] Fora de escopo e dependências explicitados.
- [x] Requisitos FR/NFR numerados e rastreáveis.
- [x] Histórias BDD de casos críticos.
- [x] Error handling, USB removido, DDJ ausente, firmware e energia cobertos.
- [x] Critérios G0–G4 com evidências e condições de bloqueio.
- [x] Decisões arquiteturais assumidas e OWNER_DECISION documentadas. OD-11 a OD-24 estão em `spec.md` §14. A constituição 1.1.0 separa alvo 1 (tablet) e alvo 2 (Pi).

## Integração e viabilidade

- [x] Mixxx stable 2.5.6 indicado, sem confundir com 2.7 dev.
- [x] Canais master 1–2 e fones 3–4 especificados com fonte.
- [x] DDJ-400 mapeamento XML/JS oficial indicado como baseline.
- [x] Diferença entre USB `export.pdb`, OneLibrary e `master.db` explicitada.
- [x] Segurança de USB RO garantida como requisito, inclusive caches/SQLite WAL.
- [x] Núcleo RT isolado de rede, parsing e IA.
- [x] Licenças e separação entre projetos de referência explicadas.
- [x] Pipeline de POC em Pi antigo sem compra nova definido.
- [x] OD-01 decidida em 21/10/2026: sem modelo único; HIL continua necessário em cada aparelho real antes de declarar suporte.
- [ ] DDJ firmware e mapeamentos reais capturados (T009).
- [ ] Export real da versão do rekordbox do usuário auditado (T014).
- [ ] Performance latência/x-runs medida na placa escolhida (T018/T049).

## Entrega agentic

- [x] `AGENTS.md` completo e com proibições normativas.
- [x] Constituição em `.specify/memory/constitution.md`.
- [x] `spec.md`, `plan.md`, `research.md`, `data-model.md`, `tasks.md`, `quickstart.md` criados.
- [x] API local futura definida em OpenAPI 3.1.
- [x] Tasks com deps, gates e evidências.
- [x] Plano de testes por unidade, integração, HIL, soak e segurança.
- [ ] Aplicação / firmware / scripts reais implementados (escopo da execução futura pelos agentes).
- [ ] Produção/release distribuível homologado no Pi e DDJ-400 (G2).

## Condição de aceite da especificação

Esta especificação é considerada pronta para delegação quando `README`, `AGENTS`, arquivos Speckit e API passam revisão de consistência e sintaxe, e decisões críticas do MVP têm defaults. **Gates HIL continuam pendentes** e não são falhas na especificação; são trabalhos que agentes e proprietário precisam executar em hardware real.
