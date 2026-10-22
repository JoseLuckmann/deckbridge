# Quickstart — POC, implementação por agentes e homologação

**Emenda 22/10/2026.** O alvo 1 se constrói em `android/`. O texto das secções A em diante é o laboratório do alvo 2 (Raspberry + Mixxx) e não foi executado neste ambiente.

```bash
cd android
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
./gradlew --offline :app:testMultiDebugUnitTest :app:assembleArm64Release :app:assembleMultiDebug
```

O APK do tablet sai em `app/build/outputs/apk/arm64/release/`. Assinatura de debug. A instalação e o roteiro de escuta estão em `docs/android/prova-a0.md` e em `STATUS.md`. A escuta continua `BLOCKED_HARDWARE`.



**Guia de laboratório; não executar diretamente em dispositivos com dados importantes.** Use SD de teste e USB com faixas que podem ser copiadas/testadas legalmente. Este documento descreve passos e comandos, **não afirma que foram executados**.

## A. Começar sem comprar Raspberry novo

Material mínimo: Raspberry Pi já existente, fonte apropriada ao modelo, microSD de laboratório, DDJ-400 com cabo USB-B para USB-A, monitor HDMI, teclado/mouse para configuração, fones/saída RCA e USB de teste exportado no rekordbox.

1. Ler modelo, RAM e arquitetura (sem alterar nada):

```bash
tr -d '\0' </proc/device-tree/model; echo
free -h
uname -m
cat /etc/os-release
uname -r
```

2. Inventariar periféricos com DDJ-400 ligada por USB:

```bash
lsusb
lsusb -t
aplay -l
arecord -l
amidi -l
aconnect -i -o 2>/dev/null || true
```

3. Sinal de alimentação/temperatura; `vcgencmd` se presente:

```bash
command -v vcgencmd && vcgencmd get_throttled || true
command -v vcgencmd && vcgencmd measure_temp || true
dmesg | grep -Ei 'undervolt|voltage|usb|audio' | tail -n 100
```

4. Registre o modelo DDJ, firmware (consultar software/ferramenta oficial, se preciso) e fonte. **Não** tente atualizar firmware por ferramenta desconhecida de Linux.

5. Mixxx na imagem de laboratório: use pacote do SO como primeira prova, **verificando sua versão**:

```bash
sudo apt update
sudo apt install mixxx alsa-utils usbutils
mixxx --version || true
```

Se a versão instalada for diferente da baseline 2.5.6, o teste **não homologa** o fork 2.5.6; deve apenas informar viabilidade da placa. Para execução por agentes, configurar build ARM da tag fixada e registrar o SHA.

6. No Mixxx Preferences > Sound Hardware, escolher **DDJ-400**, testar **Master ch1–2**, **Headphones ch3–4**, sample rate/buffer experimental. Nas preferências de controladoras, habilitar mapping DDJ-400 nativo da build (não usar mapping FLX2/FLX4). Com fones, monitor e duas faixas de teste, confirmar cue separado antes de prosseguir.

## B. Como obter material de teste para rekordbox

1. No computador que executa rekordbox, preparar pelo menos **5 faixas licenciadas ou produzidas pelo usuário**, sendo 3 playlists (uma aninhada), 4 hot cues em uma faixa, 2 memory cues, 1 loop, 1 beatgrid com downbeat e capas opcionais.
2. Exportar para pendrive como biblioteca de **dispositivo** (fluxo CDJ/XDJ; formato legacy se disponível), não simplesmente copiar `master.db` do computador.
3. Registrar a versão do rekordbox, sistema operacional e qual combinação `export.pdb`, `exportLibrary.db`, arquivos ANLZ realmente surgiu no USB.
4. Guardar uma tabela expected contendo faixa/playlist, BPM, key, cue position, loop position, cor. O arquivo expected pode ser versionado **sem áudio proprietário**.
5. O dispositivo pode ter outro layout de diretórios conforme versão/FS. Não codificar paths específicos sem probe.

## C. Verificar e montar pendrive manualmente em somente leitura

**Atenção:** comandos abaixo são para um pendrive de laboratório, substituindo `/dev/sdX1` pela partição confirmada por `lsblk`. Nunca executar sobre a partição do sistema. Se o desktop montou automaticamente em `rw`, desmonte primeiro e configure o automount do produto para `ro` antes de testes de integridade.

```bash
lsblk -fp -o NAME,FSTYPE,LABEL,UUID,SIZE,MOUNTPOINTS
sudo mkdir -p /media/deckbridge/lab
# Substitua sdX1; confirme a partição antes de rodar
sudo mount -o ro,nodev,nosuid,noexec /dev/sdX1 /media/deckbridge/lab
findmnt -no SOURCE,FSTYPE,OPTIONS /media/deckbridge/lab
find /media/deckbridge/lab -type f \( -iname 'export.pdb' -o -iname 'exportLibrary.db' \) -print
```

**Proteção adicional de laboratório:** registrar hashes de arquivos de análise antes do ensaio (em local persistente diferente do USB):

```bash
find /media/deckbridge/lab -type f \( -iname '*.pdb' -o -iname 'ANLZ*.DAT' -o -iname 'ANLZ*.EXT' -o -iname 'exportLibrary.db' \) -print0 \
  | sort -z \
  | xargs -0 -r sha256sum > "$HOME/rekordbox-before.sha256"
```

Repetir ao final em arquivo `after`, diff os hashes. Não registrar/distribuir passphrases nem publicar dados privados. O hash de tudo pode levar algum tempo e deve ser executado fora de apresentação.

## D. Protocolo de prova inicial

1. Iniciar Mixxx e verificar DDJ no Preferences, mapping ativado.
2. Reproduzir faixa A no master; verificar fone de B via PFL sem que B vaze para RCA.
3. Operar play, cue, hot cues, jog, pitch, loop, browse, FX, faders e crossfader.
4. Importar USB no item Rekordbox do Mixxx e capturar screenshot e tabela expected/actual.
5. Reproduzir A/B simultaneamente por 15 minutos, verificar CPU/temperatura/hardware logs.
6. Retirar USB de teste **somente após encerrar reprodução** no primeiro ensaio; depois realizar teste de remoção durante reprodução **em mídia descartável**, com resultados honestos (pode parar música).
7. Validar hashes antes/depois, `findmnt ro` e erros claros. Gate G0 exige evidências.

## E. Ambiente de desenvolvimento e fluxo Spec Kit

```bash
# No computador de desenvolvimento
uv tool install specify-cli
# Depois de criar/copiar o repositório, execute o init oficial preservando specs existentes:
specify init --here --integration codex --force
```

**Cuidado:** `--force` pode modificar arquivos existentes. Execute em cópia/branch limpa, examine o diff e restaure `AGENTS.md`, `.specify/memory/constitution.md` e `specs/001-ddj400-standalone/` caso o CLI tenha substituído conteúdo. O pacote aqui é spec-first, não contém o CLI e seus scripts.

Depois, no Codex (sintaxe de skills, se instalada): leia AGENTS.md, leia `tasks.md` e implemente T001 em diante. Quando houver dúvida de sintaxe, rode `specify --help`; as skills variam por versão. Evitar executar `/speckit.specify` sem preservar a spec existente, pois pode sobrescrever requisitos.

## F. Testes automatizados esperados (a criar pelo agente)

```text
tests/unit/test_config_validation.py
tests/unit/test_device_state_machine.py
tests/unit/test_volume_identity.py
tests/unit/test_path_safety.py
tests/unit/test_format_probe.py
tests/unit/test_cue_mapping.py
tests/unit/test_timebase_conversion.py
tests/unit/test_event_serialization.py
tests/unit/test_recommendation_rules.py
tests/integration/test_mount_ro_loopback.py
tests/integration/test_hotplug_simulated.py
tests/integration/test_library_diff.py
tests/integration/test_sqlite_migrations.py
tests/integration/test_api_auth.py
tests/integration/test_api_pagination.py
tests/security/test_malicious_anlz.py
tests/security/test_traversal_symlink.py
tests/security/test_pairing_bruteforce.py
tests/hil/test_ddj400_4ch.md
tests/hil/test_ddj400_controls.md
tests/hil/test_usb_rekordbox_roundtrip_readonly.md
tests/soak/test_two_decks_120min.md
```

## G. Matriz específica de fixtures e esperado

| Fixture | Entrada | Resultado esperado |
|---|---|---|
| FX01 | USB legacy com 5 faixas, 3 playlists | source descoberta, hierarquia e ordem preservadas |
| FX02 | Faixa com 4 hot cues | positions/colors quando Mixxx suportar; diff dos não mapeados |
| FX03 | Faixa com 2 memory cues, 2 loops | primeira cue/loop principal, extras remapeados conforme Mixxx |
| FX04 | Grid com BPM variável e downbeat | não reduzir só a BPM constante sem aviso |
| FX05 | ANLZ truncado | parser falha com código, não trava engine |
| FX06 | 100 caracteres Unicode, nomes longos | render com ellipsis, não perde caminho/ID |
| FX07 | Export Only OneLibrary sem acesso autorizado | source detectada e fallback explicado, sem acesso indevido |
| FX08 | Dois USB, track IDs iguais | `track_uid` composto desambigua |
| FX09 | USB sem export, somente WAV | modo áudio comum, não fingir metadata RB |
| FX10 | USB com symlink traversal | recusar path externo |
| FX11 | Pasta com 10.000 registros sintéticos | browse paginado/indexado, sem travar áudio |
| FX12 | DDJ desconectada durante UI | status e recover seguro; sem autoplay |
| FX13 | Saída DDJ com menos de 4 ch | `E_AUDIO_4CH_MISSING`, gate não passa |
| FX14 | Under-voltage | alertar/registrar, não marcar sistema como homologado |
| FX15 | Conexão remota sem pairing (P3) | 401/403; porta não exposta por default |
| FX16 | Crash do daemon de analytics | Mixxx continua, aviso não crítico |
| FX17 | DB local corrompido | backup/recuperação, nenhuma escrita no USB |
| FX18 | Mídia removida em playback | informar problema, nenhum write/crash; áudio não garantido |

## H. Evidências por teste HIL

Cada execução deve produzir:

```yaml
case_id: HIL-DDJ-001
build:
  mixxx_tag: '2.5.6'
  mixxx_commit: <registrar>
  deckbridge_commit: <registrar>
hardware:
  pi_model: <registrar>
  ram_gib: <registrar>
  os_release: <registrar>
  kernel: <registrar>
  ddj_firmware: <registrar|UNKNOWN>
  usb_audio: <registrar>
  power: <registrar>
media:
  export_format: <LEGACY|ONELIBRARY|OTHER>
  rekordbox_version: <registrar>
  fixture_id: FX01
metrics:
  duration_seconds: 900
  xruns_total: <integer|null>
  audible_dropouts: <integer>
  cpu_mean: <number>
  temp_peak_c: <number>
  readonly_mount: <true|false>
  hash_equal_before_after: <true|false|null>
result: <PASS|FAIL|BLOCKED_HARDWARE>
evidence_files: []
notes: <limitações e reprodução>
```

Os campos `<>` são placeholders para o relatório a preencher depois do teste. Nunca copiar esse YAML como se representasse resultado real.

## I. Princípios de suporte/uso no palco

- Testar todo release 2 horas antes de qualquer apresentação real.
- Não usar beta 2.6/alpha 2.7 em live sem aceitar risco experimental e homologar separadamente.
- Backup da biblioteca rekordbox no computador; pendrive dedicado, sem ser o único exemplar.
- Fonte adequada, cooler e hub alimentado quando necessário. Não apoiar confiabilidade apenas em 'parece funcionar'.
- HDMI/áudio do Pi não substitui silenciosamente DDJ; fone e RCA saem dela.
- Captura/gravação do microfone físico da DDJ-400 não é suportada através do áudio USB segundo manual Mixxx.

## J. Primeiro prompt para o agente

> Leia AGENTS.md e todos os arquivos em specs/001-ddj400-standalone. Crie a implementação incremental do DeckBridge OS. Comece por T001–T004 e T006–T008 em paralelo onde permitido. NÃO codifique um motor DJ novo. Use Mixxx estável e seu mapping DDJ-400. Produza testes e logs verificáveis. Trate DDJ/USB/benchmarks reais como BLOCKED_HARDWARE enquanto não houver hardware. Não permita nenhuma escrita no pendrive rekordbox. Depois faça a primeira POC funcional, sem adiantar PWA/IA antes de G1/G2.
