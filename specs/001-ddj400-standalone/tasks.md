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

- [x] **T020** `[P]` Criar `DeviceProfile` + `Config TOML` versionados e parser/validation. **Deps:** T001,T004. **Teste:** defaults, schema inválido, `usb.allow_write=true` recusado. **Estado:** `PASS` de software em 21/10/2026. Perfil `runtime`, referência 1024x600, escrita recusada. Sample rate e buffer continuam placeholder.
- [x] **T021** `[P]` Criar adapter de detecção udev/udevmon em daemon não-root (processamento fora do udev worker). **Deps:** T020. **Teste:** simulação coldplug/hotplug e desconexão. **Estado:** `PASS` do adaptador com eventos simulados. O daemon no aparelho não foi instalado.
- [ ] **T022** Implementar Mount Guard `ro,nodev,nosuid,noexec`, mount root saneado, timeout e verificação de `findmnt`. **Deps:** T021. **Teste:** loopback FAT/exFAT, fake paths, USB read-only. **Estado:** parcial em 21/10/2026. A lógica e o runner falso passam. `mkfs.vfat` criou uma imagem neste ambiente e o `mount` falhou com `unknown filesystem type 'vfat'`. Não houve montagem real.
- [x] **T023** `[P]` Implementar state machine de device/controller/audio/source, códigos de erro do plano. **Deps:** T020. **Teste:** transições e idempotência, property tests se possível. **Estado:** `PASS` de software em 21/10/2026, com semente fixa. Sem aparelho.
- [x] **T024** `[P]` Implementar Format Probe de legacy/OneLibrary/audio-only/unknown sem alterar USB, com recursos limitados. **Deps:** T022. **Teste:** PDB válido, arquivos truncados, huge paths, timeout. **Estado:** `PASS` da sonda estrutural em árvore sintética. Não abre SQLite, não carrega cue, versão rekordbox `UNKNOWN`. Pendrive real continua T016.
- [ ] **T025** Integrar mount/source com descoberta/rescan USB Mixxx; resolver SP-003 antes de patch C++. **Deps:** T015,T022,T024. **Teste:** conectar USB em set ativo e visualizar fonte sem resetar áudio. **Estado:** parcial. ADR-0005: não há controle `[Library],Rescan` no fonte 2.5.6; o pedido não reinicia o áudio e não aplica patch. O Mixxx não foi executado, então a fonte não apareceu num set.
- [x] **T026** `[P]` Definir skin compacta baseada em mecanismos Mixxx, wireframes de referência 800x480 e 1024x600, layout adaptável a outras resoluções (OD-02, 21/10/2026). **Deps:** T002. **Teste:** screenshots das 4 telas nessas duas referências e numa resolução diferente. **Estado:** `PASS` do layout SVG em 21/10/2026, também em 1366x768. O Mixxx não carregou pele.
- [x] **T027** Implementar `PERFORMANCE` skin com 2 decks, waveform, BPM, tempo, estado, cues e loops. **Deps:** T026. **Teste:** screenshots/visibilidade; sem uso de webview pesada. **Estado:** `PASS` do desenho. A waveform é um bloco do layout; some no perfil `off`. Não é forma de onda calculada pelo motor.
- [x] **T028** Implementar `BROWSE` e fluxo source/playlist/track/load para encoder e DDJ-400. **Deps:** T008,T025,T026. **Teste:** 100 faixas e navegação sem mouse. **Estado:** `PASS` da lista sintética de 100 faixas, MP3/WAV alternados, com espera de 200 ms na carga. Sem encoder físico.
- [ ] **T029** Mapear combinações de botões para alternar BROWSE/PERFORMANCE/STATUS, sem quebrar controles de performance. **Deps:** T008,T028. **Teste:** mapeamento explícito por estados + HIL. **Estado:** parcial. Mapa de computador lido do XML pinado. Troca de tela `OPEN_BROWSE`, `OPEN_STATUS`, `BACK` e `SAFE_SHUTDOWN` segue sem botão. Confirmação na controladora `BLOCKED_HARDWARE`.
- [x] **T030** Implementar `STATUS` e mensagens E_DDJ/E_AUDIO/E_RB/E_USB legíveis, com i18n pt-BR/en-US. **Deps:** T023,T026. **Teste:** states sintéticos. **Estado:** `PASS` dos textos e da tela STATUS em SVG. Sem display ligado.
- [ ] **T031** Configurar autostart supervisionado (systemd + desktop/Sway) com usuário não-root, grace shutdown e fallback recovery. **Deps:** T020,T027. **Teste:** 5 cold boots HIL. **Estado:** `BLOCKED_HARDWARE`.
- [x] **T032** Implementar diagnóstico mínimo do sistema (logs com privacy, temperatur/undervoltage, DDJ status e 4ch). **Deps:** T023,T031. **Teste:** eventos sintetizados, sem root shell injection. **Estado:** `PASS` do módulo em 21/10/2026. Temperatura, subtensão e x-runs ficam `UNKNOWN` se ninguém medir. Coleta no aparelho continua bloqueada.
- [ ] **T033** `[HIL]` Provar hotplug USB, DDJ reconnection e ausência de keyboard na apresentação. **Deps:** T025,T028,T029,T031. **Teste:** roteiro manual com vídeo/log. **Estado:** `BLOCKED_HARDWARE`.
- [ ] **T034** `[HIL]` Provar imutabilidade USB com hashes e variações de ações (load/cue/loop). **Deps:** T022,T025,T027. **Teste:** 100% hashes iguais, mount `ro`. **Estado:** `BLOCKED_HARDWARE`.
- [ ] **T035** `[HIL]` Provar master/cue/LEDs e todas as ações da matriz M0 por 15min na UI final. **Deps:** T027,T029,T033. **Teste:** report de DDJ-400 por firmware. **Estado:** `BLOCKED_HARDWARE`.
- [x] **T036** `[P]` Testar parsers com fuzz limitado e inputs maliciosos/Unicode/path traversal; confinamento para processo parser auxiliar. **Deps:** T024. **Teste:** não criar arquivo fora mount/DB local. **Estado:** `PASS` em árvore sintética e processo separado, em 21/10/2026. Não é um pendrive hostil no aparelho.
- [x] **T037** `[P]` Suite de regression unit/integration de estados, configuração, montagens, formats e rendering metadata. **Deps:** T023,T024,T026,T028. **Teste:** executa em CI. **Estado:** `PASS` local. O runner remoto do CI não foi observado.
- [ ] **T038** `[HIL]` Testes de remocão USB durante playback e falha áudio, com mensagens honestas sem promessa gapless. **Deps:** T030,T033. **Teste:** nenhum crash e nenhum write USB. **Estado:** `BLOCKED_HARDWARE`.
- [ ] **T039** Consolidar **Gate G1** (`G1-evidence.md`), publicar matriz PDB/cues/controls e instruções de uso offline. **Deps:** T025–T038. **Condição:** G0 evidenciado e sem fail crítico. **Estado:** `BLOCKED_HARDWARE`. O arquivo `docs/benchmarks/G1-evidence.md` existe e não fecha o gate.

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

## Trilha S — simulação sem hardware (IDs novos; T001–T074 não mudam)

O proprietário só terá DDJ-400 e Raspberry no fim do projeto (21/10/2026). Simulação não é prova no hardware. `SIMULATED` não marca tarefa HIL como `PASS` e não fecha G0 nem G1 (T079).

- [ ] **T075** Controladora DDJ-400 virtual: enviar e receber MIDI conforme o mapa pinado, por ALSA `snd-virmidi` ou equivalente. **Estado:** `TODO`. Não inventar byte. Não substitui T009 nem T035.
- [ ] **T076** Dispositivo de áudio virtual de 4 canais, master 1–2 e fone 3–4, para checar roteamento. **Estado:** `TODO`. Não substitui T010 nem T035.
- [ ] **T077** Executar os perfis de capacidade da OD-01 em emulação ARM `arm64` e `armhf` (QEMU ou container). **Estado:** `TODO`. Prova a arquitetura, não que uma placa toca.
- [ ] **T078** Roteiro numerado de validação final no hardware real, feito de uma vez no fim. **Entrega:** `docs/hil/roteiro-validacao-final.md`. **Estado:** documento escrito; execução `BLOCKED_HARDWARE`.
- [x] **T079** Regra de evidência: resultado `SIMULATED` não fecha tarefa HIL nem gate G0/G1. **Estado:** `PASS` da regra em `src/deckbridge/evidence.py` e no plano de testes, em 21/10/2026. Os ambientes T075–T077 ainda não existem.

## Trilha A — Android, alvo 1 (IDs novos; T001–T079 não mudam)

OD-11 em 21/10/2026: tablet ligado direto na DDJ-400, sem equipamento extra. Raspberry é o alvo 2. OD-12 a OD-16 estão provisórias. O acesso ao tablet ainda vai demorar: o que exige o aparelho fica `BLOCKED_HARDWARE`.

- [x] **A001** Registrar OD-11 e as provisórias OD-12..OD-16. Aceitar a ADR de Android e emendar ADR-0001 e ADR-0002 sem apagar a trilha Raspberry. **Estado:** `PASS` de documento em 21/10/2026.
- [x] **A002** Projeto `android/`: Kotlin, Compose, Oboe 1.11.0 pelo NDK, minSdk 26, targetSdk 34. Cobre Android 10 a 14 do Tab S6 Lite e também Android 8 da OD-01. **Estado:** `PASS` se o APK de debug for gerado nesta rodada.
- [x] **A003** Mapa oficial da DDJ-400 dentro do app, com teste JVM. Bytes só do XML pinado. **Estado:** `PASS` de software.
- [x] **A004** Relatório de diagnóstico: modelo, hardware, SoC só se o sistema informar, Android, bateria, latência e x-runs. Ausente vira UNKNOWN. O relato do djay entra como referência, não como medição. **Estado:** `PASS` do texto em teste.
- [ ] **A005** Motor Oboe: tons diferentes nos canais 1–2 e 3–4, sem jogar o fone para o master se só houver 2 canais. **Estado:** código no APK. Escuta `BLOCKED_HARDWARE`.
- [ ] **A006** Listar dispositivos de áudio USB e canais. **Estado:** código no APK. Lista real `BLOCKED_HARDWARE`.
- [ ] **A007** Ler MIDI com `android.media.midi` e mostrar o nome do mapa. **Estado:** código no APK. Eventos reais `BLOCKED_HARDWARE`.
- [ ] **A008** Medir latência, x-runs e bateria e compartilhar o texto. **Estado:** código no APK. Números do tablet `BLOCKED_HARDWARE`.
- [x] **A009** A prova A0 é a primeira tela. **Estado:** `PASS` da tela no código. Uso no tablet `BLOCKED_HARDWARE`.
- [x] **A010** Layout de referência em 800x480, 1024x600 e 2000x1200. **Estado:** `PASS` dos testes de encaixe. Não é a tela física.
- [x] **A011** Perfil de capacidade: 4 GB e 8 núcleos continuam dentro da política, sem usar o nome do modelo. Android 10 a 14 coberto. **Estado:** `PASS` dos testes.
- [x] **A012** Dois decks com MP3/WAV local, lado a lado na paisagem, inclusive 2000x1200. Não mede canais. **Estado:** o player de arquivos locais passou a usar o motor de mixagem em A020. Tocar no tablet `BLOCKED_HARDWARE`.
- [x] **A013** APK debug instalável. **Estado:** `PASS` da geração em 21/10/2026. A escuta continua `BLOCKED_HARDWARE`. O APK seguinte, com biblioteca, substitui o artefato.
- [ ] **A014** `[HIL]` No Tab S6 Lite, com a DDJ-400: ouvir master e fone separados, conferir o modelo lido na hora, mandar o relatório. **Estado:** `BLOCKED_HARDWARE`. O djay não substitui este passo.
- [x] **A015** Leitor DeviceSQL de `export.pdb` em Kotlin, sem copiar Crate Digger, rekordcrate nem pyrekordbox. Faixas, artistas, álbuns, gêneros, tom, BPM, duração e árvore de playlists. **Estado:** `PASS` dos testes JVM em 21/10/2026. Pendrive real `BLOCKED_HARDWARE`.
- [x] **A016** ANLZ `.DAT` e `.EXT`: grade, hot cues, memory cues e forma de onda curta (`PWAV`). **Estado:** `PASS` dos testes na fixture sintética. Não foi conferido num export real.
- [x] **A017** Fixture sintética de `export.pdb`, ANLZ e WAV, gerada no app. **Estado:** `PASS` do teste de ida e volta. Sem música comercial.
- [x] **A018** `exportLibrary.db` detectado. **Estado:** a abertura tenta a chave pública documentada numa cópia local, somente leitura. O arquivo sintético não é SQLCipher e o teste confirma que não abre. Um export real continua `BLOCKED_HARDWARE`.
- [ ] **A019** Permissão persistente de leitura (Storage Access Framework) e aviso quando um USB chega ou sai. **Estado:** código no APK. Pendrive real `BLOCKED_HARDWARE`.
- [ ] **A020** Dois decks no motor Oboe: play, cue, pitch, volume, crossfader, pré-escuta nos canais 3–4, sync por BPM, hot cues, loop em ciclo e estéreo. **Estado:** código no APK. Escuta no tablet `BLOCKED_HARDWARE`.
- [x] **A021** Botão de MIDI simulado com bytes do mapa oficial. **Estado:** `PASS` de software. Não é a DDJ-400 e não fecha T009 nem A014.
- [ ] **A022** Prints do emulador em 2000x1200 e 1024x600. **Estado:** o KVM desta máquina falhou (`kvm_spurious_fault`) e a CPU em software deixou a interface do sistema em "Application Not Responding". A022 continua aberta. Os prints da A023 não fecham este item.
- [x] **A023** Prints das telas reais na JVM, com Roborazzi e Robolectric em modo de gráficos nativos, usando o pendrive simulado. **Estado:** `PASS` de renderização. Não é o tablet. Arquivos em `/opt/cursor/artifacts/prints/`.
- [x] **A024** Loop do rekordbox toca em ciclo no motor. **Estado:** `PASS` da fórmula no teste JVM e código no motor. Escuta `BLOCKED_HARDWARE`.
- [x] **A025** Estéreo de verdade no WAV, no MP3 e no motor (master L/R e fone L/R). **Estado:** `PASS` do WAV sintético. Escuta `BLOCKED_HARDWARE`.
- [x] **A026** Pitch de 14 bits (MSB+LSB) como o script oficial: centro `0x2000`, faixas 6/10/16/25%. **Estado:** `PASS` do teste. A curva ouvida na controladora continua `BLOCKED_HARDWARE`.
- [x] **A027** Leitura de `exportExt.pdb` (My Tags). **Estado:** `PASS` da fixture sintética. Export real `BLOCKED_HARDWARE`.
- [x] **A028** OneLibrary com a chave pública documentada, só numa cópia local. **Estado:** `PASS` do caminho que não abre arquivo inválido e do caminho que lê um SQLite local de teste. A cifra de um `exportLibrary.db` real não foi exercida aqui.
- [x] **A029** Sequências MIDI virtuais de jog, volume, crossfader, hot cue e pitch, com bytes do mapa oficial. **Estado:** `PASS` dos testes JVM. O jog não move o áudio. Não é a DDJ-400.
- [x] **A030** Tema de palco do `docs/ui-spec.md`: contraste AA, BPM grande, dois decks com mixer ao centro, waveform maior, hot cues coloridos, navegação e faixa de simulação. **Estado:** `PASS` de renderização JVM nos seis prints. Não é o tablet.
- [x] **A031** APK só `arm64-v8a`, com minify e shrinkResources. **Estado:** artefato gerado. Não instalado no tablet.
- [x] **A032** Player no espírito de duas telas de CDJ, desenho próprio: onda com zoom, grade de beats, visão geral, hot cues A–H, fase, badges. **Estado:** `PASS` de renderização JVM. Não é o tablet. Sem logo nem asset de terceiro.
- [x] **A033** Biblioteca abre por SOURCE/BROWSE e fecha ao carregar. Encoder BROWSE e LOAD da DDJ-400 navegam esse browser. **Estado:** `PASS` do teste com bytes do mapa. O sentido do encoder não foi medido na controladora.
- [x] **A034** Indicador de Beat FX (nome, fração, canal, ON, nível) e filtro por deck, a partir do MIDI. Zoom na tela. APK 0.6.0 arm64. **Estado:** o indicador não processa o efeito. Escuta `BLOCKED_HARDWARE`. Não instalado no tablet.
- [x] **A035** Tela 0.7.0 no espírito da waveform empilhada: barra SOURCE/BROWSE/TAG LIST/PLAYLIST/SEARCH/MENU, coluna de deck, duas ondas com playhead a cerca de 30%, painel BEAT FX, hot cues 2×4, cartões de baixo. Desenho próprio, sem logo nem nome de produto. **Estado:** `PASS` de renderização JVM em 2000×1200 e 1024×600. Não é o tablet.
- [x] **A036** Browser denso: miniatura da onda, número, título, BPM, tom com realce de tom compatível, linha selecionada. **Estado:** `PASS` de renderização JVM. Não é um export real.
- [x] **A037** Pendrive simulado com 24 faixas (2 cliques + 22 peças sintéticas), cues nomeados e seção PWB3 (graves/médios/agudos por coluna, normalizados pelo pico de cada banda). **Estado:** `PASS` do teste JVM. Não é FFT de um arquivo comercial nem PWV3/PWV4/PWV5.
- [x] **A038** Echo, Delay e Flanger processam o mixer (rota 3). Os outros nomes ficam secos. APK 0.7.0 arm64. **Estado:** o código está no motor. Escuta `BLOCKED_HARDWARE`. Não instalado no tablet. A022 continua aberto.
- [x] **A039** Barra alta centrada e MENU com PLAYER, AUDIO, CONTROLADORA, BIBLIOTECA, DIAGNÓSTICO e SOBRE. Preferências em DataStore e aplicadas ao tempo, quantize, jog, cor da onda, zoom, playhead e buffer pedido. **Estado:** `PASS` de renderização JVM. A latência do buffer continua `UNKNOWN`. Não é o tablet.
- [x] **A040** TAG LIST adiciona e tira etiquetas na sessão. PLAYLIST abre as playlists. SEARCH filtra título, artista, BPM e tom. **Estado:** `PASS` de renderização JVM. A etiqueta não é gravada no USB (ADR-0002).
- [x] **A041** Beat jump de 1/2/4/8/16/32 para a frente e para trás, na grade, com a faixa a tocar ou em pausa, e dentro do loop. Loop de 4 batidas, IN/OUT, metade, dobro, reloop e beat loop 1/4–32, com crossfade de 48 amostras. Faixa de toque e mapa oficial. **Estado:** `PASS` do teste C++ e do teste JVM dos bytes. Escuta `BLOCKED_HARDWARE`.
- [x] **A042** Lista de Beat FX da DDJ-400 no motor, com fração, nível, canal e cauda ao desligar. **Estado:** `PASS` do render offline (diferente do seco, finito, eco no tempo da batida). Spiral, Helix, Mobius, Pitch e reverb são aproximações. Escuta `BLOCKED_HARDWARE`.
- [x] **A043** Filtro do knob FILTER por canal (grave abaixo do centro, agudo acima). **Estado:** `PASS` do render offline. Não há seletor de seis Sound Color FX neste mapa.
- [x] **A044** RMX: Scene FX, X-Pad, isolador e release no motor; painel e vista cheia; pads de SAMPLER reutilizados só com o painel ligado. **Estado:** `PASS` do render offline. Mapeamento MIDI provisório, item 8 do checklist físico, `BLOCKED_HARDWARE`.
- [x] **A045** Testes offline de salto, loop, Beat FX, Scene FX, X-Pad, isolador e release. **Estado:** `PASS` do binário hospedeiro e dos 27 testes JVM. Não é escuta.
- [x] **A046** Prints 0.8.0 e APK arm64 0.8.0. **Estado:** artefato gerado nesta rodada. Não instalado no tablet. A022 continua aberto. Escuta `BLOCKED_HARDWARE`.
- [x] **A047** MENU e vista cheia de RMX no mesmo acabamento da tela principal: navegação à esquerda, linhas compactas, seleção azul-escura, dial de Scene FX, células escuras, isolador com gradiente e fila de release. **Estado:** `PASS` de renderização JVM em 2000×1200. A conferência de 1024×600 é a tela principal. Não é o tablet.
- [x] **A048** Histórico do que esteve no ar (fader acima e a tocar pelo menos N segundos) com hora, deck, BPM e tom, em SQLite local. Lista e exportação CSV/M3U8 para um documento escolhido. **Estado:** `PASS` do teste JVM. O USB do rekordbox não é destino. Não é o tablet.
- [x] **A049** Gravação do master em WAV, com REC, cronómetro e medidor. Anel sem alocação na thread de áudio. AAC opcional pelo MediaCodec. LAME fora do APK. Aviso abaixo de 64 MB. **Estado:** `PASS` do teste C++ hospedeiro (o `push` não aloca). O WAV não foi ouvido. AAC não foi exercido num aparelho. `BLOCKED_HARDWARE` para a escuta.
- [x] **A050** Corpus sintético de variantes de export, parser que não derruba, fuzz de PDB/ANLZ, e preferência PWV7/PWV5/PWV3 sobre PWB3. **Estado:** `PASS` na JVM, com as 5.000 faixas abaixo de 2 s neste processo. Não é um export real nem o tablet. A leitura de PWV é a deste projeto.
- [x] **A051** Prints 0.9.0 e APK arm64 0.9.0. Itens novos no roteiro de validação final, ainda `BLOCKED_HARDWARE`. **Estado:** artefato gerado nesta rodada. Não instalado no tablet. A022 continua aberto.
- [x] **A052** 0.9.1: histórico lê BPM e tom da faixa carregada (BPM da biblioteca e BPM no ar), MENU com alvos de 48 dp e grelha de cartões, RMX sem o vão entre o dial e os pads, rótulos de cena distintos, isolador com dB e kill a vermelho, histórico com cabeçalho de sessão. **Estado:** `PASS` do teste JVM da biblioteca e da renderização. Escuta `BLOCKED_HARDWARE`. A022 aberto.
- [ ] **A053** Busca por voz. **Estado:** `DEFERRED` em 2026-10-22, a pedido do dono. Não implementar nesta linha.
- [ ] **A054** Quatro decks. **Estado:** `DEFERRED` em 2026-10-22, a pedido do dono. O alvo continua dois decks. Não implementar nesta linha.
- [x] **A055** Capa do export: tabela artwork (tipo 13), JPEG/PNG em `PIONEER/Artwork`, miniatura em cache do app, fallback ♪. USB só leitura. **Estado:** `PASS` do teste JVM. Pendrive real `BLOCKED_HARDWARE`.
- [x] **A056** Plano de cobertura do leitor de dois decks em `docs/research/cdj3000-coverage.md`. **Estado:** documento. Não é implementação de rede.
- [x] **A057** Key Shift, Key Sync aproximado, filtro de biblioteca, contagem até o próximo cue, frase de 16 compassos simulada, agulha na visão geral, slip com retorno, emergency loop de 4 batidas, tempo com duas casas, realce de tom compatível e banco de hot cue A–H na memória do app. **Estado:** `PASS` dos testes JVM. Escuta `BLOCKED_HARDWARE`.
- [ ] **A058** O que o plano deixa de fora: Pro DJ Link, nuvem, Wi-Fi, ecrã do prato, PSSI real, bancos gravados no export, pré-escuta por mesa, ordenação de colunas, reverse, grelha editável, toque de pré-escuta. **Estado:** `TODO`.
- [x] **A059** Prints 0.10.0 e APK arm64 0.10.0. **Estado:** artefato gerado nesta rodada. Não instalado no tablet. A022 aberto.

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

- O acesso ao tablet e à DDJ-400 ainda vai demorar. A014, a escuta da prova A0, a leitura de um pendrive rekordbox real (A019) e a escuta dos decks (A020) ficam `BLOCKED_HARDWARE`. O Raspberry é o alvo 2: T005/T009/T010/T011/T012/T014/T016/T018/T031/T033/T034/T035/T038/T039 e a execução da T078 continuam `BLOCKED_HARDWARE`. OD-04 segue aberta. OD-12 a OD-16 estão provisórias.
- Na ausência de hardware, agentes seguem em software e na trilha T075–T079, **sem fechar G0 ou G1**.
- Simulação, teste de desktop e emulação não substituem o roteiro `docs/hil/roteiro-validacao-final.md`.
- Qualquer issue de upstream incompatível exige issue com repro, commit e contorno verificável.
