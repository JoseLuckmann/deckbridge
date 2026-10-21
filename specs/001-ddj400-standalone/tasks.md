# Tasks 001 — backlog executável por agentes

**Versão:** 1.0.0 | **Formato:** IDs permanentes `T001...`, dependências explícitas, saída + evidência.  
**Estados permitidos:** `TODO`, `IN_PROGRESS`, `PASS`, `FAIL`, `BLOCKED_HARDWARE`, `BLOCKED_OWNER`, `DEFERRED`.  
**Status inicial deste pacote:** `TODO`. A partir de 21/10/2026 as tarefas de software da Fase 0 passam a ter estado próprio na linha e no `implementation-log`. Tarefa sem marca continua `TODO`.

## Regras de execução

1. Executar tarefas respeitando dependências; não fechar fase sem gate Gx.
2. Para cada tarefa gerar PR/commit, saída reproduzível e arquivos de evidência em `docs/benchmarks/` ou `docs/runbooks/implementation-log.md`.
3. **Nunca** marcar teste manual como PASS sem hardware ou logs reais; usar `BLOCKED_HARDWARE` e prosseguir em paralelo se a dependência permitir.
4. Tarefas `[P]` podem ser implementadas em paralelo quando o escopo de arquivos não se sobrepõe.
5. Trabalhos de Smart DJ, PWA e Trainer não são desbloqueados para release antes de G1/G2, embora spikes offline possam começar.
6. Aceite das tarefas menciona testes específicos por FR/NFR; os gates consolidados continuam obrigatórios.

## Fase 0 — bootstrap, trilha de provas e baseline

- [x] **T001** `[P]` Criar repo com branch policy, README, AGENTS e Spec Kit, licença provisional verificada, arquivos de configuração/CI. **Deps:** nenhuma. **Entregável:** skeleton em Git + branch. **Teste:** auditoria de árvore, CI kickoff. **Evidência:** commit hash, árvore de repo. **Estado:** `PASS` local em 21/10/2026. O workflow de CI está no repositório; a execução dele num runner remoto não foi observada.
- [x] **T002** `[P]` Fixar Mixxx `2.5.6` tag/SHA, registrar releases e política de forks na ADR existente `docs/adr/0001-mixxx-base.md` (o caminho `docs/adr/001-upstream-base.md` só redireciona). **Deps:** nenhuma. **Teste:** checksum da fonte observado; `git tag -v` não rodou porque a chave não foi importada. **Aceite:** ADR, sem beta em release. **Estado:** `PASS` em 21/10/2026, com a assinatura PGP ainda não verificada.
- [x] **T003** `[P]` Auditoria de licença por repositório (Mixxx, StandaloneMixxx, TriMixxx, Nautic, skins, pyrekordbox, libdjinterop, assets, codecs). **Deps:** T001. **Teste:** NOTICE/LICENSE/SBOM inicial. **Aceite:** sem código sem licença. **Estado:** `PASS` em 21/10/2026. StandaloneMixxx ficou `UNKNOWN` e não foi copiado. Codecs não foram auditados arquivo a arquivo porque não foram copiados.
- [x] **T004** `[P]` Criar harness de evidências: template HIL, coleta de `uname`, Pi model, RAM, energia, kernel, Mixxx, DDJ firmware, logs, ISO time. **Deps:** T001. **Teste:** dados ausentes se tornam `UNKNOWN`, nunca valores inventados. **Estado:** `PASS` do harness em 21/10/2026. A coleta no Raspberry continua na T005, `BLOCKED_HARDWARE`.
- [ ] **T005** `[HIL]` Inventariar Raspberry antigo com script read-only e DDJ-400 (firmware/model/power/cables). **Deps:** T004. **Aceite:** perfil H0 em JSON, foto de ligação opcional. **Se hardware ausente:** `BLOCKED_HARDWARE`.
- [ ] **T006** `[P]` Criar ambiente x86_64 com Mixxx estável, fixtures áudio de licença aberta e guia de setup; testar execução fora do Pi. **Deps:** T002. **Teste:** smoke desktop CI manual reproduzível. **Estado:** guia e smoke prontos em 21/10/2026. Execução do Mixxx `BLOCKED` neste ambiente: o binário não está instalado. Áudio não foi tocado.
- [ ] **T007** `[P]` Preparar pipeline arm64 baseado em Raspberry Pi OS; optar por Pi-gen/apt build após ADR. **Deps:** T002,T003. **Teste:** `.deb` ou release tarball arm64 com manifest e hashes. **Estado:** ADR-0004 e pacote `Architecture: all` com hash em 21/10/2026. Binário arm64 do Mixxx `BLOCKED_HARDWARE`. Pi-gen adiado para T041.
- [x] **T008** `[P]` Ler mapping oficial DDJ-400 da versão fixada, extrair tabela de controls, JS hooks e LEDs sem inventar CC/notes. **Deps:** T002. **Entrega:** `docs/compatibility/ddj400-control-matrix.md`. **Estado:** `PASS` da extração em 21/10/2026. A captura na controladora é a T009, `BLOCKED_HARDWARE`.
- [ ] **T009** `[HIL]` Capturar MIDI de DDJ-400 em hardware e correlacionar à matriz; investigar bug firmware slider. **Deps:** T005,T008. **Teste:** cada ação M0 tem evento correspondente ou exceção documentada.
- [ ] **T010** `[HIL]` Enumerar canais de saída áudio da DDJ-400 em Linux; distinguir números lógicos e indexes do backend. **Deps:** T005. **Teste:** reproduzir sinais separados 1–2/3–4.
- [ ] **T011** `[HIL]` Instalar Mixxx estável no Pi antigo e configurar mapping/ALSA com backup. **Deps:** T005,T007,T008. **Teste:** app inicia sem crash, controladora aparece.
- [ ] **T012** `[HIL]` Realizar teste master/cue + 2 decks e play/cue/sync/tempo/FX/fader/jog com sample test. **Deps:** T009,T010,T011. **Teste:** matriz 15min + registros áudio/log (G0).
- [x] **T013** `[P]` Criar fixtures sintéticas de MP3/WAV/FLAC/AIFF e metadados + imagens de USB permitidas; direitos/atribuição. **Deps:** T001. **Teste:** hashes reproduzíveis, sem músicas comerciais. **OD-03 (21/10/2026):** MP3 e WAV são a prioridade de testes e desempenho; FLAC e AIFF permanecem, sem serem o foco. **Estado:** `PASS` em 21/10/2026. A pasta não é exportação rekordbox.
- [ ] **T014** `[HIL]` Produzir USB teste no rekordbox, identificar export legacy/OneLibrary e gravar expected metadata (5 faixas, 3 playlists, cues, loops, beatgrids). **Deps:** T013 e OD-04. **Teste:** manter arquivos de teste privados quando tiverem mídia protegida.
- [x] **T015** `[P]` Analisar árvore do código de import RB da versão Mixxx fixada e os padrões ANLZ; criar matriz semântica de cues. **Deps:** T002,T013. **Entrega:** `docs/compatibility/rekordbox-format-matrix.md`. **Estado:** `PASS` da leitura do fonte em 21/10/2026. Importação num pendrive real continua `BLOCKED_HARDWARE` (T016).
- [ ] **T016** `[HIL]` Rodar import de USB real na build em Pi, comparar playlists/markers com expected, sem write. **Deps:** T011,T014,T015. **Teste:** hashes pré/pós; diff de cues/loops.
- [ ] **T017** `[P]` Benchmark de CPU/RAM/temperatura/x-runs em desktop e harness Pi (15min). **Deps:** T004,T006. **Teste:** relatórios sem extrapolações. **Estado:** harness pronto em 21/10/2026. Amostra de desktop registrada à parte, sem Mixxx e sem extrapolar. Raspberry e x-runs `BLOCKED_HARDWARE`.
- [ ] **T018** `[HIL]` Coletar benchmark no aparelho real que estiver ligado. **Deps:** T012,T017. **Teste:** gravação de dados em 15min. **OD-01 (21/10/2026):** o resultado alimenta o perfil de execução; não escolhe um modelo único de Raspberry.
- [ ] **T019** Consolidar **Gate G0**, riscos, backlog reordenado e blockers owner, com matriz FR-001...004. **Deps:** T009,T010,T012,T016,T018. **Resultado:** `G0-evidence.md` PASS/FAIL/BLOCKED, sem liberar G1 com FAIL grave.

## Fase 1 — produto mínimo DDJ-400 standalone

- [ ] **T020** `[P]` Criar `DeviceProfile` + `Config TOML` versionados e parser/validation. **Deps:** T001,T004. **Teste:** defaults, schema inválido, `usb.allow_write=true` recusado.
- [ ] **T021** `[P]` Criar adapter de detecção udev/udevmon em daemon não-root (processamento fora do udev worker). **Deps:** T020. **Teste:** simulação coldplug/hotplug e desconexão.
- [ ] **T022** Implementar Mount Guard `ro,nodev,nosuid,noexec`, mount root saneado, timeout e verificação de `findmnt`. **Deps:** T021. **Teste:** loopback FAT/exFAT, fake paths, USB read-only.
- [ ] **T023** `[P]` Implementar state machine de device/controller/audio/source, códigos de erro do plano. **Deps:** T020. **Teste:** transições e idempotência, property tests se possível.
- [ ] **T024** `[P]` Implementar Format Probe de legacy/OneLibrary/audio-only/unknown sem alterar USB, com recursos limitados. **Deps:** T022. **Teste:** PDB válido, arquivos truncados, huge paths, timeout.
- [ ] **T025** Integrar mount/source com descoberta/rescan USB Mixxx; resolver SP-003 antes de patch C++. **Deps:** T015,T022,T024. **Teste:** conectar USB em set ativo e visualizar fonte sem resetar áudio.
- [ ] **T026** `[P]` Definir skin compacta baseada em mecanismos Mixxx, wireframes de referência 800x480 e 1024x600, layout adaptável a outras resoluções (OD-02, 21/10/2026). **Deps:** T002. **Teste:** screenshots das 4 telas nessas duas referências e numa resolução diferente.
- [ ] **T027** Implementar `PERFORMANCE` skin com 2 decks, waveform, BPM, tempo, estado, cues e loops. **Deps:** T026. **Teste:** screenshots/visibilidade; sem uso de webview pesada.
- [ ] **T028** Implementar `BROWSE` e fluxo source/playlist/track/load para encoder e DDJ-400. **Deps:** T008,T025,T026. **Teste:** 100 faixas e navegação sem mouse.
- [ ] **T029** Mapear combinações de botões para alternar BROWSE/PERFORMANCE/STATUS, sem quebrar controles de performance. **Deps:** T008,T028. **Teste:** mapeamento explícito por estados + HIL.
- [ ] **T030** Implementar `STATUS` e mensagens E_DDJ/E_AUDIO/E_RB/E_USB legíveis, com i18n pt-BR/en-US. **Deps:** T023,T026. **Teste:** states sintéticos.
- [ ] **T031** Configurar autostart supervisionado (systemd + desktop/Sway) com usuário não-root, grace shutdown e fallback recovery. **Deps:** T020,T027. **Teste:** 5 cold boots HIL.
- [ ] **T032** Implementar diagnóstico mínimo do sistema (logs com privacy, temperatur/undervoltage, DDJ status e 4ch). **Deps:** T023,T031. **Teste:** eventos sintetizados, sem root shell injection.
- [ ] **T033** `[HIL]` Provar hotplug USB, DDJ reconnection e ausência de keyboard na apresentação. **Deps:** T025,T028,T029,T031. **Teste:** roteiro manual com vídeo/log.
- [ ] **T034** `[HIL]` Provar imutabilidade USB com hashes e variações de ações (load/cue/loop). **Deps:** T022,T025,T027. **Teste:** 100% hashes iguais, mount `ro`.
- [ ] **T035** `[HIL]` Provar master/cue/LEDs e todas as ações da matriz M0 por 15min na UI final. **Deps:** T027,T029,T033. **Teste:** report de DDJ-400 por firmware.
- [ ] **T036** `[P]` Testar parsers com fuzz limitado e inputs maliciosos/Unicode/path traversal; confinamento para processo parser auxiliar. **Deps:** T024. **Teste:** não criar arquivo fora mount/DB local.
- [ ] **T037** `[P]` Suite de regression unit/integration de estados, configuração, montagens, formats e rendering metadata. **Deps:** T023,T024,T026,T028. **Teste:** executa em CI.
- [ ] **T038** `[HIL]` Testes de remocão USB durante playback e falha áudio, com mensagens honestas sem promessa gapless. **Deps:** T030,T033. **Teste:** nenhum crash e nenhum write USB.
- [ ] **T039** Consolidar **Gate G1** (`G1-evidence.md`), publicar matriz PDB/cues/controls e instruções de uso offline. **Deps:** T025–T038. **Condição:** G0 evidenciado e sem fail crítico.

## Fase 2 — appliance confiável

- [ ] **T040** `[P]` Parametrizar perfis Pi4/Pi5 com valores de áudio *medidos*, temperatura, CPU e limites de cache. **Deps:** T039. **Teste:** config e benchmark por perfil; defaults safe.
- [ ] **T041** `[P]` Criar build de imagem Pi pinado, com installer idempotente e release manifest SHA. **Deps:** T007,T031,T039. **Teste:** flash limpo + boot em cartão separado.
- [ ] **T042** Implementar backup config/SQLite local + restore e migrations versionadas. **Deps:** T020,T041. **Teste:** migration N->N+1, rollback, integrity_check.
- [ ] **T043** Implementar updater manual transacional e rollback em falha, bloqueado durante set. **Deps:** T041,T042. **Teste:** image good/bad e corte de atualização em bancada.
- [ ] **T044** `[P]` Desenvolver captura e relatório de áudio xruns/CPU/thermal sem afetar RT. **Deps:** T032,T040. **Teste:** stress com instrumentação on/off.
- [ ] **T045** `[P]` Melhorar waveform/artwork cache local com LRU e limites; não cachear áudio em P2 sem autorização específica. **Deps:** T024,T039. **Teste:** no USB writes, storage limit.
- [ ] **T046** `[P]` Ajustar render e acessibilidade à referência 800x480/1024x600 e a outra resolução, touch landscape (OD-02, 21/10/2026); revisão humana. **Deps:** T027,T028. **Teste:** tela/rotação/touch real.
- [ ] **T047** `[P]` Harden SD, logs, configs, usuários/serviços, udev, USB parser quotas e política de rede. **Deps:** T036,T041. **Teste:** port scan, root escalation checks, fuzz.
- [ ] **T048** `[P]` Gerar SBOM, licença/NOTICE, bundle de fontes correspondentes de código modificado. **Deps:** T003,T041. **Teste:** auditoria legal e licença por arquivo.
- [ ] **T049** `[HIL]` Executar benchmark 120min com 2 decks; monitorar underruns audíveis, latência e throttling. **Deps:** T040,T044,T046. **Teste:** NFR-001/002/004.
- [ ] **T050** `[HIL]` Testar 10 cold boots/tempos e 20 power-loss em SD de laboratório; nunca com USB pessoal. **Deps:** T041,T042,T043. **Teste:** p95 boot, integrity_check, recovery.
- [ ] **T051** `[HIL]` Testar fontes/USB múltiplos e dataset sintético de 10k faixas, limites de scan e browse. **Deps:** T045,T047. **Teste:** NFR-005/006; montar RO.
- [ ] **T052** `[HIL]` Testar subalimentação, fan/throttling e hub USB alimentado como mitigação. **Deps:** T044,T049. **Teste:** alerta e registro, nunca testar com risco de danificar hardware.
- [ ] **T053** `[P]` Criar manual do appliance (first boot, usar palco, diagnosticar, backup, reflash, restaurar, avisos USB). **Deps:** T043,T047. **Teste:** usuário segue procedimento sem conhecimento de Linux.
- [ ] **T054** Consolidar **Gate G2** e assinar release candidate usando evidências HIL. **Deps:** T040–T053. **Produto:** imagem e release notes com hardware homologado.

## Fase 3 — analytics e assistente (P3)

- [ ] **T055** `[P]` Spike event bridge com Mixxx 2.5.6; comparar JS MIDI loopback vs queue C++; benchmark de impacto. **Deps:** G2 (T054). **Entrega:** ADR seleção sem sobrecarregar engine.
- [ ] **T056** Criar `deckbridge.db` migrations para sessions/events/trackplays e pipeline best-effort idempotente. **Deps:** T042,T055. **Teste:** events ordenados e drops contados.
- [ ] **T057** Implementar telemetria de controls/decks (load/play/cue/loop/volume/EQ/FX/etc.) com throttle. **Deps:** T055,T056. **Teste:** simulação e HIL 15min sem x-runs novos.
- [ ] **T058** Gerar tracklist e export CSV/JSON/M3U com timestamps, fallback revisão manual. **Deps:** T057. **Teste:** fixtures de 1h e discrepâncias explícitas.
- [ ] **T059** Implementar `LibraryAdapter` cache local e contratos paginados, sem competir com Mixxx DB. **Deps:** T054, T015. **Teste:** 10k fixture, timeout, mmap/SQLite read de cópia.
- [ ] **T060** Implementar recomendador determinístico por BPM/key/energy/recent repeats e explicações. **Deps:** T059,T058. **Teste:** topK reproducível + reason codes + edge cases sem key.
- [ ] **T061** `[P]` Implementar API REST local P3 conforme OpenAPI, com defaults em loopback/disabled e testes OpenAPI/security. **Deps:** T059,T056. **Teste:** 401/403/422/limits, nenhuma rota de mixer.
- [ ] **T062** Implementar pairing físico opt-in, token revogável, PWA read-only de status, biblioteca e histórico. **Deps:** T061. **Teste:** scan em rede e flows de token/CORS/CSRF.
- [ ] **T063** Habilitar gravação de áudio local opcional via Mixxx, quota e indicator, sem impactar engine. **Deps:** T054. **Teste:** armazenamento insuficiente gracefully, exclusão consentida.
- [ ] **T064** Testar P3 HIL sob concorrência de APIs/indexação + 2h de áudio e consolidar G3. **Deps:** T055–T063. **Teste:** nenhuma regressão G2.

## Fase 4 — Trainer (P4)

- [ ] **T065** Definir conjunto de exercícios, rubric e dataset sintético/rotulado para transições musicais. **Deps:** T064. **Teste:** anotação reproduzível e inter-anotador se possível.
- [ ] **T066** Implementar detector offline de frases por heurística e confidence, cache versionado. **Deps:** T065,T059. **Teste:** dataset benchmark; não alocar CPU em áudio.
- [ ] **T067** Implementar `GUIDED_MIX` overlay opt-in (8/16/32 bars, markers, indicação de graves). **Deps:** T066. **Teste:** sem tocar em transport/fader, latência UI aceitável.
- [ ] **T068** Implementar modo `TRAINING`, analytics e feedback após sessão. **Deps:** T065,T067. **Teste:** métricas coerentes com trilhas sintéticas rotuladas.
- [ ] **T069** Ensaiar UI/CPU e HIL com guided/trainer 2 decks 2h sem regressão G2; consolidar G4. **Deps:** T066–T068. **Teste:** desativação instantânea e modo performance sem efeitos colaterais.

## Fase 5 — OneLibrary e expansão (opcional, condicionado)

- [ ] **T070** `[P]` Spike legal/técnico OneLibrary: determinar alcance de dados exportados autorizadamente e disponibilidade de acesso documentada. **Deps:** T054. **Resultado:** ADR `SUPPORTED/UNSUPPORTED/REQUIRES_AUTHORIZED_CREDENTIAL`, sem contorno de proteção.
- [ ] **T071** Implementar copiar snapshot de DB+WAL para local protegido e abrir apenas cópia; reader OneLibrary com libdjinterop quando permitido. **Deps:** T070. **Teste:** nenhuma escrita USB, markers ANLZ tratados separadamente.
- [ ] **T072** Teste de matrizes de export legado/OneLibrary e fallback UI claro; publicar capacidades campo a campo. **Deps:** T071. **Teste:** 2 export versions, sem claim `full support` com lacuna.
- [ ] **T073** `[P]` Avaliar ControllerProfile extensível para FLX4/Hercules sem quebrar DDJ-400. **Deps:** T054. **Teste:** M0 regressão DDJ-400.
- [ ] **T074** Definir projeto separado para writeback bidirecional com backup, transaction & autorizações — apenas se proprietário aprovar. **Deps:** T070, decisão OD-06 e decisão explícita de writeback. **Status:** `DEFERRED` por default.

## Traçabilidade requisito -> tarefas -> testes/gates

| Requisito | Tarefas-chave | Gate |
|---|---|---|
| FR-001, NFR-004 | T004,T005,T017,T018,T044 | G0/G2 |
| FR-002, FR-005 | T008,T009,T011,T029,T035 | G0/G1 |
| FR-003, FR-004 | T010,T012,T035,T049 | G0/G1/G2 |
| FR-006, FR-007, FR-014 | T026–T031,T033,T046 | G1/G2 |
| FR-008, FR-024, NFR-006 | T021,T022,T034,T036,T047 | G1/G2 |
| FR-009–FR-011 | T014–T016,T024,T025,T051 | G0/G1/G2 |
| FR-012, FR-013 | T023,T030,T032,T038 | G1 |
| FR-015, FR-016 | T041–T044,T047,T050 | G2 |
| FR-017, FR-018 | T055–T058 | G3 |
| FR-019, FR-020 | T059–T064 | G3 |
| FR-021, FR-022 | T065–T069 | G4 |
| FR-023 | T070–T072 | G5 opcional |
| NFR-001,NFR-002 | T012,T017,T049,T064,T069 | G0/G2/G3/G4 |
| NFR-003 | T031,T041,T050 | G1/G2 |
| NFR-005 | T045,T051,T059 | G2/G3 |
| NFR-007–NFR-009 | T031,T041–T050,T061 | G2/G3 |
| NFR-010,NFR-011 | T027,T030,T046 | G1/G2 |
| NFR-012 | T042,T043,T050 | G2 |

## Gerenciamento de blockers

- `T005/T009/T010/T012/T014/T016/T018/T033/T034/T035/T038/T049/T050/T051/T052` exigem hardware físico ou export USB do proprietário.
- Na ausência de hardware, agentes podem executar T001–T004/T006–T008/T013/T015/T017/T020–T024/T026/T027/T030/T036/T037 e simulações, **sem fechar gates reais**.
- HIL não pode ser substituído por testes no desktop nem documentação de terceiros.
- Qualquer issue de upstream incompatível exige issue com repro, commit e contorno verificável.
