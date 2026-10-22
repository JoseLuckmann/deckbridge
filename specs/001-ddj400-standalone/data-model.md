# Data Model & Internal Contracts — DeckBridge OS

**Versão dos contratos:** 1.0.0 | **Data:** 2026-10-21 | **Emenda:** 2026-10-22 | **Escopo:** serviços previstos do alvo 2 e do companion P3. Não é o esquema do app Android 0.14.1. O histórico do tablet é a tabela local da A048. O USB continua `READ_ONLY` (OD-23). Este texto não modela o banco privado do Mixxx.

## 1. Regras globais

- **DB de propriedade única:** `deckbridge.db` é acessado por processos DeckBridge e nunca escreve diretamente no banco de biblioteca interno do Mixxx quando este está aberto.
- **IDs:** strings UUIDv7 ou UUIDv4 (registrar algoritmo por release), não usar track ID global de rekordbox isoladamente.
- **Tempo:** UTC RFC3339 `timestamp_utc` para auditoria; `monotonic_ns` para ordenação/duração no mesmo boot; `sample_index` + `sample_rate` para eventos dependentes de áudio. Não confundir relógio de parede com posição musical.
- **Origem imutável:** source UUID/FS + versão de export + `track_id` local à origem. Não assumir IDs persistentes entre reformatações; hash de caminho e metadados é fallback, não chave de identidade única.
- **Classificação:** metadata e áudio podem ser pessoais/protegidos; logs sanitizados, opção de apagar histórico, nenhum envio remoto padrão.
- **Versionamento:** eventos usam `schema_version` SemVer, campos desconhecidos ignorados quando seguro, validação estrita em entradas externas e controle de migrações.
- **Propriedade da fonte:** `READ_ONLY` P1; campos editáveis ficam em `local_override` com origem e versão base, sem sobrescrever arquivo do USB.
- **Tamanho:** APIs de listagem paginadas e limitadas (1 <= limit <= 100). Dados de análise musical extensos por arquivo/objeto, não embutidos nos eventos rápidos.

## 2. Modelo relacional proposto (SQLite local)

O SQL abaixo descreve contrato lógico; migrations reais são incrementais e devem usar constraints/índices adequados. Não inicializar todas as tabelas no P1 sem consumidor funcional.

### 2.1 `source_volume`

| Campo | Tipo | Semântica |
|---|---|---|
| `source_id` | TEXT PK | UUID local desta identidade de volume |
| `fs_uuid` | TEXT | UUID de filesystem, nullable em FS sem UUID |
| `device_path_hash` | TEXT | hash sanitizado, sem expor dev path em logs |
| `label` | TEXT | display name saneado |
| `fs_type` | TEXT | fat/exfat/hfs/unknown |
| `mount_path` | TEXT | mount dir local; não expor API externa por default |
| `mount_mode` | TEXT | `READ_ONLY` ou `NOT_MOUNTED`; P1 nunca RW |
| `format_kind` | TEXT | `REKORDBOX_LEGACY`, `ONELIBRARY`, `AUDIO_FILES`, `UNSUPPORTED`, `UNKNOWN` |
| `detected_at_utc` | TEXT | ISO 8601 UTC |
| `available` | INTEGER | boolean: source conectada e montada agora |
| `scan_status` | TEXT | `IDLE`, `SCANNING`, `READY`, `FAILED` |
| `last_error` | TEXT | código interno sanitizado |

Invariantes: `mount_mode=READ_ONLY` para qualquer source `available=1` exposta ao Mixxx no P1. `mount_path` deve ser subdiretório real de `/media/deckbridge`.

### 2.2 `library_track` (cache de metadados, P2/P3)

| Campo | Tipo | Semântica |
|---|---|---|
| `track_uid` | TEXT PK | `sha256(source_id + ':' + source_track_id + ':' + normalized_path)` ou UUID persistido |
| `source_id` | TEXT FK | source |
| `source_track_id` | TEXT | ID de export, se disponível |
| `relative_path` | TEXT | relativo à raiz, validado; sem traversal |
| `title` | TEXT | opcional |
| `artist` | TEXT | opcional |
| `album` | TEXT | opcional |
| `duration_ms` | INTEGER | >=0 |
| `bpm` | REAL | >0 se disponível; bpm nominal, não substitui beatgrid |
| `musical_key` | TEXT | representação preservada (`8A`, `Am`, etc.) |
| `file_type` | TEXT | MIME/extension verificada |
| `artwork_ref` | TEXT | cache local opcional |
| `available` | INTEGER | estado após rescans |
| `analysis_format` | TEXT | legacy/ANLZ version |
| `provenance_json` | TEXT | versão parser e campos suportados |

Índices: `(source_id, title)`, `(source_id, artist)`, `(source_id, bpm)`. Caminho relativo é utilizado apenas após normalização e conferência que fica dentro de source mount.

### 2.3 `playlist` e `playlist_track`

- `playlist(playlist_uid PK, source_id FK, source_playlist_id, parent_playlist_uid NULL, name, position, available, provenance_json)`.
- `playlist_track(playlist_uid FK, track_uid FK, ordinal INTEGER, PRIMARY KEY (playlist_uid, ordinal))`.
- Invariante: preservar ordenação manual e múltiplas aparições se formato permitir, não colapsar repetidos pelo track_uid.

### 2.4 `analysis_marker` e `beatgrid_marker`

- `analysis_marker(marker_id PK, track_uid FK, type ENUM('HOT_CUE','MEMORY_CUE','LOOP_IN','LOOP_OUT','PHRASE','UNKNOWN'), slot NULLABLE INT, sample_index INTEGER, sample_rate INTEGER, position_ms INTEGER, color_hex NULLABLE, label TEXT NULLABLE, provenance_json)`.
- Para loops, agrupar pares com `loop_pair_id`; rejeitar `LOOP_OUT < LOOP_IN` e markers fora da duração, documentar conversões de timebase.
- `beatgrid_marker(grid_id PK, track_uid FK, sample_index INTEGER, sample_rate INTEGER, beat_number INTEGER NULLABLE, bar_number INTEGER NULLABLE, bpm REAL, downbeat BOOLEAN, provenance_json)`.
- Não gravar grade como coluna única `bpm`; conservar vários trechos/offsets quando disponíveis.
- Cues importados para Mixxx podem ser remapeados: registrar campo `mapped_as_mixxx_slot` em provenance ou tabela auxiliar `cue_mapping`, e diferenciar original x interpretação.

### 2.5 `local_override`

`override_id PK`, `source_id`, `track_uid`, `property`, `value_json`, `base_metadata_revision`, `updated_utc`, `conflict_state ENUM('NONE','SOURCE_CHANGED','MANUAL_REVIEW')`. Alterações feitas pelo DJ em nosso sistema são locais; não escrever no USB. Export futuro é processo separado, nunca merge silencioso.

### 2.6 `device_profile`, `device_health`

- `device_profile(profile_id PK, pi_model, revision, arch, ram_bytes, os_release, kernel_version, ddj_vendor_id, ddj_product_id, ddj_firmware, usb_audio_device, primary_display, screen_width, screen_height, power_supply_note, current_limits_json, created_utc)`.
- `device_health(sample_id PK, ts_utc, monotonic_ns, cpu_percent, memory_bytes, temperature_c, throttled_flags, audio_xruns_total NULLABLE, controller_connected, master_available, cue_available, active_source_count, storage_free_bytes)`.
- Coleção default: 1–10s conforme métrica, retenção de amostras brutas 7 dias (configurável), agregados por hora por 90 dias em P2; desativar se Pi fraco. Não logar identificadores USB sensíveis.

### 2.7 `performance_session`, `performance_event`, `track_play`

- `performance_session(session_id PK, started_at_utc, ended_at_utc NULL, status ENUM('RECORDING','STOPPED','RECOVERED','INCOMPLETE'), owner_label NULL, notes NULL, schema_version)`.
- `performance_event(event_id PK, session_id FK, sequence INTEGER, ts_utc, monotonic_ns, source ENUM('MIDI','MIXXX_CONTROL','SYSTEM','MANUAL'), deck ENUM('A','B','MASTER','SYSTEM') NULLABLE, type ENUM(...), payload_json TEXT, schema_version, UNIQUE(session_id,sequence))`.
- `track_play(play_id PK, session_id FK, track_uid, deck, started_at_utc, ended_at_utc, start_position_ms, end_position_ms, inferred BOOLEAN, confidence REAL, manual_override BOOLEAN)`.
- Telemetria é best-effort e pode perder eventos; índices `(session_id,sequence)`, `(session_id,monotonic_ns)` e `(session_id, type)`.
- `PerformanceEvent` enum inicial: `DECK_LOAD`, `DECK_UNLOAD`, `PLAY`, `PAUSE`, `CUE`, `SYNC_TOGGLE`, `LOOP_START`, `LOOP_END`, `PFL_TOGGLE`, `EQ_CHANGE`, `FADER_CHANGE`, `CROSSFADER_CHANGE`, `PITCH_CHANGE`, `FX_CHANGE`, `USB_CHANGED`, `ERROR`, `SESSION_MARKER`. Evento `EQ_CHANGE` deve ser debounced/coalesced; não capturar mensagem MIDI de alta frequência em arquivo ilimitado.

### 2.8 `recommendation` (P3) e `training_attempt` (P4)

- `recommendation(rec_id PK, session_id FK, produced_utc, current_track_uid, candidate_track_uid, score REAL, rank INTEGER, algorithm_version, reasons_json, feature_snapshot_json, dismissed BOOLEAN)`.
- `training_attempt(attempt_id PK, exercise_id, session_id FK, start_utc, end_utc, score REAL, metrics_json, rubric_version, engine_version, feedback_json)`.
- Resultados devem ser recalculáveis com fixtures fixadas; `reasons_json` contém regra de BPM, tonalidade, energia e histórico, nunca justificativas opacas inventadas.

## 3. Schemas de eventos interprocessos

### 3.1 Envelope comum

```json
{
  "schema_version": "1.0.0",
  "event_id": "f757adbe-246a-45b3-89f1-4dd8a639f87a",
  "event_type": "volume.mounted_ro",
  "timestamp_utc": "2026-10-21T12:00:00Z",
  "monotonic_ns": 67588102000,
  "origin": "device-daemon",
  "payload": {}
}
```

### 3.2 `volume.detected`

```json
{
  "source_id": "11111111-1111-4111-8111-111111111111",
  "fs_type": "vfat",
  "format_kind": "REKORDBOX_LEGACY",
  "label": "RB_USB",
  "status": "DETECTED"
}
```

### 3.3 `volume.mounted_ro`

```json
{
  "source_id": "11111111-1111-4111-8111-111111111111",
  "mount_mode": "READ_ONLY",
  "mount_path": "/media/deckbridge/11111111-1111-4111-8111-111111111111",
  "format_kind": "REKORDBOX_LEGACY"
}
```

**Invariante:** não emitir mounted_ro enquanto `findmnt` e opção RO não forem confirmadas por API de sistema. Mensagem pública nunca incluir path de filesystem de host em redes.

### 3.4 `audio.status` (interno)

```json
{
  "audio_device_name": "DDJ-400",
  "backend": "ALSA",
  "sample_rate": 48000,
  "buffer_frames": 512,
  "master_channels": [1, 2],
  "headphones_channels": [3, 4],
  "master_available": true,
  "headphones_available": true,
  "xruns_total": null,
  "status": "READY"
}
```

**Nota:** nomes/canais podem ser expostos com indexação zero no backend real. Este contrato usa canais lógicos numerados a partir de 1 e o adapter normaliza. `xruns_total=null` = não instrumentado, **não** = zero.

### 3.5 `performance.event` (P3)

```json
{
  "schema_version": "1.0.0",
  "event_id": "f1cf2c36-1dc6-45c2-b756-4fd996285e8a",
  "session_id": "8e8ce97b-0d8a-4d59-bd1b-2f4d8cb59a4f",
  "sequence": 56,
  "timestamp_utc": "2026-10-21T12:31:42.115Z",
  "monotonic_ns": 166575818105000,
  "source": "MIXXX_CONTROL",
  "deck": "B",
  "type": "DECK_LOAD",
  "payload": {
    "track_uid": "22222222-2222-4222-8222-222222222222",
    "position_ms": 0
  }
}
```

## 4. Config versionada — TOML exemplificativo

```toml
schema_version = "1.0.0"
profile = "pi4"
locale = "pt-BR"
boot_into_mixxx = true
allow_internet = false

[audio]
device_name_hint = "DDJ-400"
preferred_backend = "ALSA"
master_channels = [1, 2]
headphone_channels = [3, 4]
sample_rate = 48000
buffer_frames = 512
# Valores de áudio são placeholders iniciais. Determinar na calibração HIL.

[controller]
profile = "pioneer-ddj400"
auto_reconnect = true

[usb]
mount_root = "/media/deckbridge"
read_only = true
allow_write = false
probe_timeout_seconds = 10
max_file_scan_bytes = 33554432

[ui]
mode = "PERFORMANCE"
orientation = "landscape"
preferred_width = 1280
preferred_height = 720
skin = "DeckBridgeCompact"

[health]
enabled = true
sample_interval_seconds = 5
local_retention_days = 7

[companion]
enabled = false
bind_host = "127.0.0.1"
port = 8765
pairing_required = true

[performance]
enable_event_recording = false
save_audio = false

[trainer]
enabled = false
mode = "PERFORMANCE"
```

**Validação:** `allow_write=true` é inválido enquanto P1/P2/P3/P4; não existir bypass por config em release. Mudanças em `config.toml` não podem editar Mixxx controls em tempo de palco sem uma reconciliação segura.

## 5. Contratos do Library Adapter (fase P2/P3)

Interfaces de domínio independentes de um parser específico:

```text
probe(source_path: Path) -> FormatProbeResult
list_playlists(source_id: str, cursor?: str, limit: int=50) -> Page[Playlist]
list_tracks(source_id: str, playlist_uid?: str, cursor?: str, limit: int=50) -> Page[Track]
get_track(track_uid: str) -> Track|NotFound
get_markers(track_uid: str) -> Markers|Unsupported
get_source_diagnostics(source_id: str) -> SourceDiagnostics
```

- `probe` é idempotente e limitado por timeout/memória.
- `list_*` não executa scan de USB de O(N) a cada requisição; responde de cache/index.
- `source_path` vem exclusivamente do Mount Guard validado, não da entrada HTTP.
- `Unsupported` informa capacidade/campo, e não inventa metadados vazios para parecer sucesso.
- `format_kind` permite `UNKNOWN` e `UNSUPPORTED`; não forçar `legacy` ao ver arquivo `exportLibrary.db`.

## 6. Política de recursos e filas (P3)

- Eventos de knobs/faders coalescidos por `(session,deck,control)` em janela de 50–200ms, mantendo início/fim do gesto se relevante.
- Teto configurável de eventos persistidos por segundo; em overload descartar telemetria com contador de drops, nunca travar mixer.
- `analysis_job` roda com nice/CPU quota baixos, pausa ao detectar alta carga e durante teste HIL.
- Cache de artwork e waveforms versionado por origem e metadados; limites por tamanho total e LRU.
- Recomendação offline consome snapshots de biblioteca, sem acesso a áudio PCM em tempo real.

## 7. API externa local (P3)

`contracts/openapi.yaml` cobre `/health`, `/devices`, `/sources`, `/library/*`, `/sessions`, `/recommendations`, `/pairing` e `/config` em escopo limitado. Os endpoints `play`, `cue`, `crossfader`, `fx`, `eject-source` e `shutdown` **não** pertencem à API padrão. Nenhuma ação remota crítica enquanto set ativo sem especificação separada.

## 8. Migrations e integridade

- Criar ferramenta `deckbridge-db migrate --dry-run` e backup transacional.
- Testar migration N->N+1 com rollback, `PRAGMA integrity_check`, numeração de schema e sinalização `MIGRATION_REQUIRED`.
- Retenção e exclusão de sessões são operações locais e explícitas. O usuário consegue exportar e apagar histórico sem apagar arquivos de música.
- Falha de DB não pode derrubar Mixxx. O software volta a tocar sem analytics e exibe aviso `DEGRADED_ANALYTICS`.
