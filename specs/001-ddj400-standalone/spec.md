# Feature Specification 001 — DeckBridge OS: DDJ-400 standalone

**Status:** Draft pronto para implementação incremental; hardware ainda não validado.  
**Versão do documento:** 1.0.0 (2026-10-21)  
**Projeto:** DeckBridge OS (nome de trabalho, sujeito à decisão do proprietário)  
**Tipo:** spec de produto e critérios de aceite, independente da escolha de stack; detalhes técnicos em `plan.md`.

## 1. Resumo executivo

Construir um **módulo portátil ligado por USB à DDJ-400** que elimina a necessidade de notebook/telefone para tocar música. Um Raspberry Pi executará Mixxx com UI adequada a tela pequena e navegação pelo hardware. O usuário prepara pendrives no rekordbox, conecta-os ao módulo e consegue selecionar faixas e tocar dois decks, com master e pré-escuta saindo da interface de áudio da DDJ-400. O sistema opera offline, tolera eventos comuns (USB conectado depois do boot, mudança de playlist, erro de arquivo), protege a biblioteca externa contra escritas e proporciona futura camada de telemetria, recomendações musicais e treinamento guiado.

**Não estamos desenvolvendo um plugin Android para rekordbox nem substituindo o firmware da DDJ-400.** Nosso produto é um computador DJ standalone independente do software proprietário da AlphaTheta.

### 1.1 Problema

A DDJ-400 é dependente de host para o software de mixagem. O rekordbox Android não oferece suporte oficial à DDJ-400 neste cenário, embora outros aplicativos aceitem seus comandos. O DJ deseja viajar com a controladora e um dispositivo compacto em lugar do notebook, preservando seu preparo de biblioteca no rekordbox.

### 1.2 Visão de experiência final

1. Preparar faixas, playlists, hot cues, beatgrids e loops no rekordbox no computador.
2. Exportar biblioteca para USB em formato compatível de dispositivo CDJ/XDJ.
3. Ligar o módulo à DDJ-400 via USB e à energia; a tela mostra o estado do sistema.
4. Conectar o pendrive, navegar por pastas/playlists pelo encoder, carregar faixas nos dois decks.
5. Ouvir master na saída RCA da DDJ-400 e cue nos fones da DDJ-400, com botões, faders, jogs, pads e LEDs integrados.
6. Tocar sem internet, sem celular e sem monitor externo.
7. Opcionalmente gravar e analisar a performance; no futuro, receber dicas e exercícios de DJ.

### 1.3 Alvos de entrega

- **P0 — Prova técnica:** funciona em Pi antigo *quando viável*: USB/MIDI, áudio 4 canais, Mixxx 2 decks, importação básica USB; monitor/teclado temporários aceitáveis. Sem promessa de performance até HIL.
- **P1 — MVP standalone:** Pi 4B 4 GB ou Pi 5 4+ GB validado; boot para tela pequena; controladora integral; biblioteca rekordbox USB (legado `export.pdb`); confiabilidade e ações de falha; USB somente leitura.
- **P2 — Produto estável:** imagem reprodutível, atualização/rollback, melhorias UX, diagnósticos e robustez.
- **P3 — Smart DJ:** telemetria de sets, tracklist, ranking não intrusivo de próxima faixa, PWA local opt-in.
- **P4 — DJ Trainer:** guiado e analytics por frase/transição; evolução posterior.
- **P5 — Interoperabilidade avançada:** OneLibrary/Device Library Plus e eventual escrita bidirecional, com condições legais/técnicas e gates próprios.

P0 e P1 são obrigatórios. P2 antes de considerar apresentações públicas. P3–P5 são roadmap, não pré-requisitos para concluir P1.

## 2. Personas e casos de uso

- **DJ portátil:** quer ligar e tocar, sem notebook, usando a DDJ-400 que já conhece.
- **DJ que prepara no rekordbox:** exige playlists e informações preparadas reconhecíveis; não quer retrabalho de análise.
- **Maintainer:** precisa de logs, boot previsível, reflash/recovery e diagnóstico de hardware.
- **DJ em treinamento (futuro):** quer feedback de timing/beatmatching/transições sem substituir sua decisão.

### 2.1 Jornadas

| ID | Jornada | Resultado esperado |
|---|---|---|
| J-01 | Primeiro boot com DDJ-400 conectada | Tela de inicialização, áudio e MIDI reconhecidos ou diagnóstico acionável |
| J-02 | Inserir USB rekordbox após boot | Biblioteca/playlist aparece sem reiniciar o processo musical |
| J-03 | Carregar deck A e B | Metadados, BPM/cues visíveis; reprodução independente |
| J-04 | Monitorar no fone e mixar no RCA | Pre-escuta isolada, crossfader/equalização funcionam |
| J-05 | Retirar USB por acidente | Aviso, sem corrupção externa; comportamento honesto se áudio não estiver armazenado em cache |
| J-06 | Queda/religamento de energia | Boot confiável; não tocar áudio automaticamente sem intenção explícita |
| J-07 | USB incompatível com formato futuro | Mostrar versão/formato não suportado e orientação de exportação; jamais escrever/"consertar" o USB |
| J-08 | Conectar tablet/celular (P3) | Visualizar status e configurações opt-in sem depender disso para tocar |
| J-09 | Ativar DJ Trainer (P4) | Recomendações observáveis, desligáveis, sem ações automáticas nos faders |

## 3. Escopo e prioridades

### 3.1 P0 — compatibilidade técnica

- Detectar Pi, versão de SO, RAM, energia e dispositivos USB; registrar resultados.
- Instalar/buildar Mixxx estável adequado à arquitetura; evitar fork invasivo se mapping nativo funcionar.
- Detectar DDJ-400 simultaneamente como USB MIDI e USB áudio.
- Validar canais 1–2 (master) e 3–4 (headphones) na controladora; confirmar medindo os sinais, não apenas vendo o dispositivo.
- Usar mapping oficial Mixxx DDJ-400; validar funções essenciais e firmware.
- Usar arquivos de teste lícitos e USB exportado com o rekordbox; registrar formato real encontrado.
- Fazer benchmark real de CPU/temperatura/x-runs na placa existente.

### 3.2 P1 — MVP standalone

- Login automático controlado/local, boot direto no software DJ, tela de 800x480 ou superior como baseline operacional.
- Biblioteca navega via encoder e botão LOAD, sem mouse ou teclado durante apresentação.
- Visualização de estado de dois decks: artista, faixa, BPM analisado/atual, tempo, posição, waveform, cue, loop, sync, tempo/pitch, VU e áudio.
- FX, EQ, play, cue, sync, browse, hot cues, loops, pitch slider, jog wheels, faders, crossfader, PFL/pre-escuta, volume e feedback LED conforme mapeamento DDJ-400; documentar diferenças físicas/firmware.
- USB autodetectado, montado RO e desmontado de forma segura; permitir re-scan da biblioteca sem reiniciar Mixxx.
- Ler formato legado USB rekordbox suportado pelo Mixxx: playlists, folders, beatgrid, hot cues, memory cues e loops com semântica explícita de mapeamento.
- Dizer claramente quando propriedades de cue não possuem correspondência 1:1 com Mixxx; não prometer importação sem perdas sem teste.
- Local library para trilhas/alterações internas; não alterar exportações do rekordbox.
- Tela de erro e modo recovery útil em ausência de USB/DDJ ou falha do processo.
- Reconfiguração e reboot/desligamento seguros, evitando corte imediato de energia durante escrita local.
- Configuração persistente local (volume padrão seguro, buffer, tema, idioma, dispositivo primário, caminho de montagens).

### 3.3 P2 — confiabilidade e experiência

- Imagem SD reprodutível, instalador idempotente, artefatos assinados/verificados, pin de upstream, rollback.
- Interface otimizada para 5/7 polegadas, variantes 800x480 e 1280x720 paisagem.
- Diagnóstico de alimentação, temperatura, CPU, x-runs, captura de logs de falha e checagem do mount read-only.
- Testes contínuos de 2 horas em perfil de hardware validado.
- Cache opcional de waveform/artwork/metadados em armazenamento **local** (não em USB), com limites e GC.
- Indicador de disco ausente/incompatível, recuperação segura de estado local, safe-mode sem plugins.
- Perfil de config para Pi 4/5 sem presumir equivalência de performance do Pi 3.

### 3.4 P3 — inteligência e companion

- Eventos de performance: load, play/pause, cue, loop, sync, pitch, EQ, filter, faders, fx, crossfader, track transition; timestamps e correlação com decks.
- Tracklist com tempos de início/fim estimados e edição pelo DJ; exportação JSON/CSV/M3U (sem embutir música).
- Regras transparentes de sugestão de próxima faixa (intervalos de BPM, tonalidade, energia anotada, duração e repetição); processamento offline.
- UI opcional no celular via PWA local; pareamento físico, autenticação e opção de desligar Wi-Fi; endpoints read-only inicialmente.
- Gravação Mixxx opcional no armazenamento local, respeitando espaço livre e autorização de quem opera.

### 3.5 P4 — DJ Trainer

- `PERFORMANCE`, `GUIDED_MIX` e `TRAINING` como modos mutuamente exclusivos, selecionáveis pelo DJ.
- Detector offline de pontos prováveis de intro/outro/breakdown/drop (heurístico/sinal) com confiança explícita.
- Overlay de frases (8/16/32 compassos) e próximo ponto recomendado; sobreposições de EQ estimadas a partir de MIDI + ganho, não de gravação multicanal garantida.
- Exercícios com checkpoints e pontuação reprodutível: tempo de entrada, alinhamento de frase, intervalos com sobreposição de graves, consistência de BPM.
- Nenhuma operação automatizada do mixer sem feature futura separada e consentimento explícito.
- Prefetch/análise não simultâneos com uso de CPU excessivo durante performance; desativar em hardware insuficiente.

### 3.6 P5 — interoperabilidade avançada (não MVP)

- Detectar e eventualmente ler `exportLibrary.db`/OneLibrary com dependências legais/técnicas documentadas.
- Diferenciar suporte `detectado`, `metadados lidos`, `música carregada`, `cues carregados` e `escrita suportada` no formato OneLibrary.
- Sem obtenção, distribuição ou hardcoding de segredos/passphrases proprietárias; nenhuma evasão de DRM/controle de acesso. Caso acesso autorizado não esteja disponível, oferecer caminho de exportação legado.
- Escrita de volta ao USB só por arquitetura transacional separada com backup, validação em dispositivo de laboratório e confirmação explícita — **não habilitar na versão P1–P4**.

## 4. Fora de escopo explícito

- Executar aplicativo proprietário rekordbox (Windows/macOS/Android) no Raspberry ou enganar seu reconhecimento USB.
- Modificar firmware/hardware interno da DDJ-400.
- Clone visual idêntico de XDJ/RX3 ou uso de assets proprietários sem licença.
- Streaming Spotify/Beatport/Tidal, reprodução DRM ou download de conteúdo protegido.
- Integração obrigatória com nuvem, usuários e assinaturas.
- Stems neurais em tempo real no Raspberry antigo; somente pesquisas e stems pré-processados futuros.
- Pro DJ Link/compatibilidade de rede com CDJs como requisito do MVP.
- Mixagem automática inteligente durante apresentação sem aprovação do DJ.
- Suporte a todas as controladoras MIDI no P1 (arquitetura extensível; DDJ-400 é perfil inicial).

## 5. Requisitos funcionais verificáveis (IDs persistentes)

| ID | Requisito | Marco | Critério resumido |
|---|---|---|---|
| FR-001 | Descobrir modelo Pi, OS, arquitetura, RAM, fonte/USB | P0 | Relatório JSON diagnósticos sem exceção |
| FR-002 | Reconhecer DDJ-400 MIDI e áudio USB | P0 | Eventos MIDI + dispositivo ALSA enumerados |
| FR-003 | Reproduzir deck A/B sem conflito | P0 | Dois arquivos simultâneos audíveis |
| FR-004 | Master 1–2 e cue 3–4 independentes | P0 | Captura/escuta prova separação |
| FR-005 | Mapear controles críticos da DDJ-400 | P1 | Matriz de 100% do conjunto M0 testado |
| FR-006 | Boot direto em UI de DJ | P1 | Boot sem login/manual; testes após 5 reinícios |
| FR-007 | Navegar por hardware sem teclado | P1 | Browse, selecionar playlist, load em A/B |
| FR-008 | Montar USB rekordbox RO | P1 | fs mount `ro` e hash idêntico pré/pós |
| FR-009 | Importar playlists/folders `export.pdb` | P1 | Comparação determinística com fixture |
| FR-010 | Importar BPM/beatgrid/hotcues/loops compatíveis | P1 | Fixtures reconhecidas e diferenças reportadas |
| FR-011 | Detecção hotplug + rescan | P1 | USB novo aparece sem restart engine |
| FR-012 | Erros explícitos de USB/formatos | P1 | UI informativa sem travamento |
| FR-013 | Estado local separado de origem USB | P1 | Nenhuma escrita na origem |
| FR-014 | UI landscape 800x480 / 1280x720 | P2 | Legibilidade e controles mensurados |
| FR-015 | Diagnóstico de áudio, alimentação, crashes | P2 | Logs exportáveis com dados mínimos |
| FR-016 | Instalação e rollback | P2 | Upgrade/rollback sem perder config |
| FR-017 | Registro de eventos / histórico de set | P3 | Eventos no banco com timestamps válidos |
| FR-018 | Tracklist editável e exportável | P3 | Exportação por 1 hora de set |
| FR-019 | Recomendações offline explicáveis | P3 | Ranking determinístico com razões |
| FR-020 | PWA local opt-in autenticada | P3 | Pairing, revogação, rede desativada por default |
| FR-021 | Guided Mix não intrusivo | P4 | Indicação de pontos sem automatizar faders |
| FR-022 | Feedback de exercícios | P4 | Métricas reproduzíveis por dados de teste |
| FR-023 | OneLibrary detectável, com fallback | P5 | Mensagem correta com/sem parser disponível |
| FR-024 | Dispositivo desconectado não causa corrupção USB | P1 | Hash/FS verificados em cenários HIL |

### 5.1 Matriz M0 de controles DDJ-400

**A tabela abaixo é contratual por função, não presume IDs MIDI.** Os CC/notes/LED statuses devem ser extraídos do mapping Mixxx fixado e capturados com `aseqdump` ou equivalente no dispositivo real.

| Grupo | Controles / ações a validar | Aceite |
|---|---|---|
| Transporte | PLAY/PAUSE, CUE, SHIFT | ambos os decks, ação/feedback sem travar |
| Navegação | encoder BROWSE (giro/click), LOAD A/B | foco correto e seleção observável |
| Ritmo | jog touch/turn, pitch fader, SYNC, tempo range se mapeado | resposta apropriada e sem saltos espúrios |
| Hot cues | pads HOT CUE e mudança de modo | disparo conforme mapping e LED correspondente |
| Loops | loop IN/OUT, reloop/exit, beat loop | marcador temporal e feedback coerentes |
| Mixer | faders A/B, crossfader, gain/trim, EQ high/mid/low | direção, escala, ganho e limites coerentes |
| Cue/master | PFL A/B, volume headphone, cue/master blend se disponível, master knob | separação de canais e comportamento audível |
| FX | seleção, ON/OFF, parâmetro, filter | controles mapeados ao Mixxx e testados |
| Segurança | reconexão DDJ, restart Mixxx com DDJ conectada, firmware | sem estados presos e sem MIDI fantasma |

Limite: algumas funções/controles são de áudio analógico/firmware e podem não gerar MIDI; registrar comportamento real, não simular detecção inexistente. Firmware DDJ-400 tem aviso de slider MIDI espúrio em versões antigas: inventariar firmware e seguir processo oficial externo quando aplicável.

## 6. Requisitos não funcionais e indicadores

| ID | Meta | Medição e gate |
|---|---|---|
| NFR-001 | 0 x-runs audíveis introduzidos em teste contínuo de 120 min, 2 decks, perfil validado | JACK/PipeWire/ALSA + logs e loopback; falha se houver corte audível |
| NFR-002 | Latência control-to-sound perceptualmente utilizável; alvo inicial <=25 ms com hardware validado | medição loopback/jog/pads; ajustar buffer, documentar limites |
| NFR-003 | Boot até UI utilizável: alvo <=60 s Pi 5; Pi 4 e Pi 3 somente benchmark | 10 cold boots; p95 registrado, falha ao exceder somente depois da baseline |
| NFR-004 | CPU de pico sustentável: reservar ao menos 25% de margem média no perfil escolhido | 120min CPU por processo/core, temperatura, throttling |
| NFR-005 | Biblioteca 10 mil faixas (metadados) navegável sem congelamento de áudio | fixture sintética; <300ms p95 interação após indexação (meta) |
| NFR-006 | 0 escrita em USB durante MVP | montagem RO + hashes de arquivos sensíveis antes/depois; monitor de I/O |
| NFR-007 | Processo Mixxx e daemon recuperam-se após falhas controladas | systemd, crash recovery, sem reprodução involuntária |
| NFR-008 | Construção reprodutível em arm64, com SHA registrado | CI + release manifest e checksum SHA256 |
| NFR-009 | Integrações não expostas por default | portas/serviços auditados e testes de autenticação |
| NFR-010 | Tela operável a 50–80 cm, sob luz doméstica | teste humano em protótipo, contraste e botões físicos |
| NFR-011 | Volume inicial seguro | não iniciar reprodução automaticamente; níveis recuperados com limites documentados |
| NFR-012 | Recuperação após desligamento súbito sem corrupção de origem | 20 ciclos de bancada + fsck/SQLite integrity_check local |

**Todas as metas de desempenho são objetivos de engenharia, não valores já comprovados.** Gates dependentes de hardware ficarão `BLOCKED_HARDWARE` até benchmark no modelo exato.

## 7. Compatibilidade de formatos rekordbox — contrato do produto

### 7.1 Modos e garantias

| Origem | Prioridade | Operação | Observações |
|---|---|---|---|
| Exportação USB rekordbox em formato tradicional com `export.pdb` | P1 | READ-ONLY (Mixxx) | caminho primário suportado pelo manual Mixxx |
| `ANLZ*.DAT`, `ANLZ*.EXT`, variantes conhecidas | P1 | READ-ONLY, via parser Mixxx | cues, grids e waveforms quando suportados; lacunas são detectadas |
| USB com OneLibrary `exportLibrary.db` | P5 | Detecção em P1; leitura condicionada a acesso técnico/lícito | SQLCipher, ANLZ externos, WAL; nunca escrita no pendrive |
| Biblioteca `master.db` interna do rekordbox PC | Fora MVP | Não importar | formato diferente, não é export USB |
| XML exportado pelo rekordbox | P3 opcional | Importação offline de metadados | não substitui garantia de USB para palco |
| MP3/FLAC/WAV/AIFF no USB comum | P0/P1 | leitura do decoder Mixxx | track files sem metadata RB ainda reproduzem |

### 7.2 Semântica

- Não afirmar que cues são equivalentes 1:1: Mixxx pode mapear memory cues e loops adicionais a hot cues, e o cue/loop principal a slots específicos; consultar manual da versão fixada.
- Posição temporal deve manter precisão em amostras/frames quando disponível; tempo em milissegundos apenas para UI/API, com taxa de amostragem explícita.
- Os BPMs podem ter variação local e beatgrid não pode ser representado por BPM único quando há múltiplos marcadores. Manter estrutura de markers e seus offsets.
- Faixas ausentes ou caminhos inválidos: mostrar como indisponíveis, sem alterar dados originais.
- Duplicação de IDs entre múltiplos USB: usar identidade `(source_volume_uuid, rekordbox_track_id)` e hash de conteúdo apenas quando explicitamente necessário.
- A leitura OneLibrary requer estudo da licença e do mecanismo de acesso; sem senha autorizada, recuar para exportação tradicional com mensagem clara.
- Compatibilidade por versão deverá ser declarada conforme fixtures coletadas (`rb_export_version`, `format_variant`, `parser_version`), não por marketing 'suporta rekordbox 7 inteiro'.

### 7.3 Comportamento com USB removido

Enquanto o deck está tocando arquivos no USB, a remoção física pode interromper a reprodução, dependendo do buffering do Mixxx. Não alegar comportamento gapless nem que faixas inteiras foram carregadas. P1 exibe aviso e impede novos loads da origem; P2 estuda *precache* explícito em disco local, consentido pelo DJ, com restrição de espaço. Um sistema de arquivos ro preserva dados contra escritas locais, mas não impede falhas físicas ou falhas provocadas externamente.

## 8. Fluxos e estados

### 8.1 Máquina de estados do dispositivo

`BOOTING -> CHECKING_HW -> READY_NO_LIBRARY -> LIBRARY_SCANNING -> READY -> PLAYING -> READY`; estados de exceção `DEGRADED_AUDIO`, `USB_UNSUPPORTED`, `CONTROLLER_DISCONNECTED`, `RECOVERY`. `PLAYING` pode coexistir com `USB_SCANNING` e `DEGRADED` visual; na implementação separar dimensões `system/audio/controller/library` em vez de uma enum monolítica para evitar estado impossível.

**Inicialização:** nunca disparar play automático; se DDJ ausente, entrar em `READY_NO_CONTROLLER` com instruções para reconexão e detecção sem restart quando factível.

**USB reconhecido:** validar label/FS, verificar read-only, identificar diretório Pioneer/rekordbox, usar importação Mixxx. Recusar paths fora da raiz (symlink traversal) e arquivos malformados sem afetar engine.

**Audio device desaparece:** display de alerta visível e estado de saída inválido; silenciar seguramente; recuperação guiada de áudio, não alternar silenciosamente para HDMI.

**Desligar:** opção na UI/tecla configurada que fecha sessão e sync local antes do `poweroff`; não executável inadvertidamente ao pressionar botão de performance.

## 9. Histórias de usuário (BDD / rastreáveis)

### US-01 — Tocar uma apresentação sem notebook (P1)

**Given** Pi com imagem testada, DDJ-400, fonte adequada, monitor/tela e USB válido  
**When** ligo o dispositivo  
**Then** a UI DJ inicia, reconhece controladora/USB e consigo navegar, carregar e reproduzir dois decks sem computador e internet.

**Aceite:** gates FR-002/003/004/005/006/007/008/009, HIL com vídeo/áudio e logs.

### US-02 — Aproveitar biblioteca do rekordbox (P1)

**Given** pendrive exportado com playlists, 5 faixas de teste, cue, memory cue, loop e beatgrid documentados  
**When** o insiro com Mixxx em execução  
**Then** playlists/faixas surgem, carregam e exibem marcadores compatíveis, sem escrita no USB.

**Aceite:** diff de expected vs actual, hashes de PDB/ANLZ antes/depois; lista de incompatibilidades.

### US-03 — Pré-escuta independente (P0/P1)

**Given** deck A audível no master e deck B em cue  
**When** pressiono PFL B e ajusto crossfader  
**Then** deck B é audível nos fones sem ser enviado ao master até mistura deliberada.

**Aceite:** captura/certificação áudio master RCA vs fones; curva de isolamento registrada.

### US-04 — Recuperar quando o USB falha (P1)

**Given** set ativo e USB com arquivo ilegível/retirado  
**When** ocorre erro de mídia  
**Then** UI apresenta causa e orientação; a origem permanece íntegra; não há crash ou escrita na origem.

### US-05 — Usar controles físicos (P1)

**Given** nenhum mouse/teclado conectado  
**When** navego e mixo  
**Then** execução de set de 15 minutos é concluída; tela serve como feedback, não depende do toque.

### US-06 — Ver estatísticas de um set (P3)

**Given** registro de set consentido  
**When** finalizo a sessão  
**Then** tracklist e eventos cronológicos podem ser exibidos/exportados localmente e apagados pelo DJ.

### US-07 — Receber orientação sem automação (P4)

**Given** Guided Mix ativado e 2 tracks com análise de frases  
**When** a próxima seção se aproxima  
**Then** indicação de ponto aparece e não altera mixer ou transporte.

## 10. Edge cases e tratamento exigido

| Situação | Ação esperada |
|---|---|
| USB FAT/exFAT/HFS conforme drivers presentes | Detectar capacidade real e montar `ro`; HFS não garantido sem suporte do SO |
| USB criptografado, sem PDB, com OneLibrary-only | Fallback explicativo, nunca pedir/extraír senha de forma insegura |
| USB fisicamente corrompido | Limitar parsing, no retry infinito, música atual tratada com degradação explicada |
| Caminho com caracteres unicode, espaço ou case | Preservar nomes; testes Unicode NFC/NFD; evitar interpretações indevidas |
| Vários USB ao mesmo tempo | Sources por UUID, labels amigáveis, desambiguar duplicados |
| Disco quase cheio | Gravação/cache bloqueados e log, playback existente preservado |
| Logs/caches grandes | quotas e rotação; não esgotar filesystem raiz |
| Fonte insuficiente / undervoltage | alerta e telemetria; recomendar hub USB alimentado; não alegar estabilidade |
| DDJ ligada após boot | redescoberta/reconexão quando suportada, sem perda de settings |
| Conflito de ALSA/PipeWire | seleção explícita da DDJ-400; sem fallback silencioso |
| Firmware com pitch messages sem toque | testar e recomendar update oficial no Windows/Mac quando necessário |
| Fones desconectados, RCA sem caixas | áudio não depende de confirmação visual; healthcheck por sinais/captura |
| Usuário toca com touchscreen desativado | teclado/encoder controla 100% do fluxo crítico |
| Queda de energia em gravação | arquivo de áudio pode ficar incompleto; preservar DB via transação e reportar |
| Atualização interrompida | boot anterior/rollback quando P2 implantado |

## 11. Matriz de verificação por perfis de hardware

| Perfil | Uso | Status de suporte (inicial) |
|---|---|---|
| H0: Raspberry Pi existente do proprietário (modelo desconhecido) | provas mínimas/benchmark | **a descobrir por comandos** |
| H1: Pi 4B, 4 GB, Raspberry Pi OS 64 bit | alvo funcional mínimo candidato | **suporte planejado, validar HIL DDJ-400** |
| H2: Pi 5, 4/8 GB, Raspberry Pi OS 64 bit | alvo recomendado final | **suporte planejado, validar HIL DDJ-400** |
| H3: Linux desktop x86_64 | CI, UX, testes parser, simulação MIDI | **sem substituir HIL** |
| H4: Pi 3B/3B+ | descoberta MIDI/áudio; Mixxx se possível | **POC best effort; sem compromisso com dois decks estáveis** |

Inventariar: CPU/RAM, distro/versão, kernel, display (tipo e resolução), fonte, armazenamento, modelo DDJ e firmware, filesystem e origem USB, mixxx build tag, temperatura e ambiente. Não declarar 'Pi 4/5 funcionam' para nossa combinação antes de testar.

## 12. Acessibilidade, UX e observabilidade

- UI com hierarquia visual: informação de deck > tempo/waveform > metadata > diagnóstico.
- Controles físicos são fonte primária durante tocar; áreas touch opcionais devem ser >=44px de alvo visual quando aplicável.
- Em 800x480 apresentar 2 decks e estado da biblioteca sem elementos não essenciais; toggle Browse/Performance pelo encoder ou SHIFT sem depender de toque.
- Feedback na tela para source ausente, áudio sem dispositivo, sem saída master e status de gravação.
- Logs estruturados locais: `ts_utc`, `monotonic_ns`, `severity`, `subsystem`, `device_id`, `session_id`, `event`, `details_sanitized`.
- Saúde local: CPU, memória, temperatura, throttling, USB disconnect, status de outputs, contagem de underruns quando disponível.
- Não registrar nomes completos de faixas/metadados em logs diagnósticos exportáveis por padrão; permitir opt-in.
- Idiomas: strings externalizadas; pt-BR como tradução principal e en-US fallback.

## 13. Definição de pronto e release gates

### Gate G0 — feasibility (fecha P0)

1. Modelo Pi/fonte, OS e DDJ inventariados.
2. MIDI DDJ mapeado e dump salvo (sem informações pessoais).
3. Master/cue separados e dois decks audíveis, benchmark mínimo 15 min.
4. 1 USB real/export de teste analisado e formatos encontrados documentados.
5. Relatório `G0-evidence.md` com PASS/FAIL/BLOCKED (sem claims implícitas).

### Gate G1 — MVP (fecha P1)

1. A DDJ é controlada inteiramente por hardware em execução de set de 15min.
2. Desktop/tela boot automatizado 5/5 vezes; sem teclado após provisioning.
3. 5 ou mais faixas de fixtures, 3 playlists, hotcue/memory cue/loop/beatgrid validados.
4. Mídia exportada RO e hashes pré/pós idênticos.
5. Erros `USB_UNSUPPORTED`, `CONTROLLER_DISCONNECTED`, `AUDIO_ERROR` comunicados e recuperáveis.
6. Não há escrita no pendrive, mesmo ao navegar, carregar e alterar cues no Mixxx.

### Gate G2 — palco confiável (fecha P2)

1. 120min contínuos com 2 decks no hardware homologado, nenhum corte audível.
2. p95 boot/latência e relatório térmico completos.
3. 20 ciclos de power-loss em bancada sem corrupção de configuração persistente.
4. Imagem com checksums, rollback comprovado, restauração de backup testada.
5. 2 USB de fornecedores/diferentes exports físicos, coleção >=10k de fixture sintética.

### Gate G3 — smart DJ (fecha P3)

1. Histórico de eventos confiável, exportações revisáveis pelo DJ, falhas isoladas do áudio.
2. API local autenticada e desligada por default; nenhuma dependência online.
3. Ranking reproducível com reason codes e nenhum controle automático do mixer.

### Gate G4 — trainer (fecha P4)

1. Métricas validadas com simulações rotuladas e sets de bancada.
2. Usuário pode desativar em 1 ação; CPU e buffer sem regressão.

## 14. Decisões tomadas e pendências do proprietário

### 14.1 Decisões tomadas nesta especificação (podem ser revisadas por ADR)

- D-01: produto standalone **Raspberry+DDJ-400**, não driver Android.
- D-02: usar **Mixxx 2.5.6 estável** como baseline; melhoria de forks externa só com port + testes.
- D-03: read-only rekordbox em P1; OneLibrary completa adiada a P5.
- D-04: priorizar controles físicos e **display landscape**; sem tela é possível desenvolver P0.
- D-05: módulo independente da DDJ; sem abrir/modificar a controladora.
- D-06: Linux Raspberry Pi OS 64-bit; código x86_64 de desenvolvimento e arm64 para distribuição.
- D-07: offline-first, sem conta/servidor; PWA opt-in no futuro.
- D-08: manter UI em skins Qt/Mixxx inicialmente; evitar reescrever player.
- D-09: ferramentas de analytics/IA somente fora da thread de áudio.
- D-10: licença open source respeitando as dependências; nome independente de marcas existentes.

### 14.2 OWNER_DECISION — perguntas que não impedem P0/P1

| ID | Pergunta | Default escolhido | Quando precisa decidir |
|---|---|---|---|
| OD-01 | Qual Raspberry antigo, RAM e SO atual? | tratar como desconhecido; coletar com comandos antes de HIL | Gate G0 |
| OD-02 | Qual tela: 5", 7" ou sem tela inicial? | 7" 1280x720 landscape para produto; HDMI monitor para P0 | antes de comprar carcaça/UI final |
| OD-03 | Fonte de músicas principal? MP3, FLAC, WAV, AIFF? | MP3 e WAV P0; FLAC e AIFF P1 se Mixxx suportar na build | fixtures G1 |
| OD-04 | Qual versão do rekordbox e tipo de export USB? | export tradicional em paralelo ao OneLibrary quando disponível | fixtures G1 |
| OD-05 | Quer operar com fonte na tomada ou bateria? | tomada oficial; bateria adiada | escolha de carcaça P2 |
| OD-06 | Licença do produto final / intenção comercial? | estudo pessoal open source com GPL apropriada; sem exploração de marcas | antes de publicar repo/fork distribuído |
| OD-07 | Nome definitivo e identidade visual? | DeckBridge OS provisório e tema dark/high-contrast | antes da release pública |
| OD-08 | PWA remota deve permitir comandos do mixer? | não, read-only/config no P3 | P3 |
| OD-09 | Quais métricas importam mais no DJ Trainer? | alinhamento temporal/frases e sobreposição de grave | P4 |
| OD-10 | Precisa gravar o set no MVP? | não; recurso P3, sem bloquear P1 | P3 |

### 14.3 Protocolo de dúvidas bloqueantes

Se o agente depender de dados não observáveis (modelo real Pi, firmware, USB exportado, potência), **não deve supor que o teste passou**. Solicitar esses dados, continuar com tarefas independentes e registrar o gate em `BLOCKED_HARDWARE` até solução. Owner decisions de longo prazo não interrompem o MVP.

## 15. Critérios de sucesso do projeto

- **Técnico:** DDJ-400+Pi homologado realmente tocam um set contínuo com cue separado e export USB rekordbox imutável.
- **Operacional:** o usuário prepara no rekordbox, conecta USB e atua sem notebook/celular/internet.
- **Extensibilidade:** skin, mapping, engine e serviços têm fronteiras versionadas e testes, permitindo Smart DJ/Trainer depois.
- **Segurança:** sem perda de biblioteca, sem exposição de API por default, rollback e observabilidade disponíveis.
- **Honestidade:** matriz pública de formatos/hardware testados e não testados.

---

**Fim de `spec.md`.** A execução é descrita em `plan.md`, `tasks.md` e `quickstart.md`.
