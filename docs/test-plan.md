# Test Plan / HIL & Release Qualification

**Data:** 2026-10-21 | **Estado:** protocolo especificado, **sem execução em hardware**.  
**OD-03 (21/10/2026):** testes de desempenho e de áudio priorizam MP3 e WAV. FLAC e AIFF podem entrar, sem serem o critério principal.  
**OD-01:** cada ensaio registra o aparelho lido na hora. Um resultado não vale para outro modelo.  
**OD-02:** checagem visual de referência em 800x480 e 1024x600, mais uma resolução diferente.

## 1. Métricas e instrumentos

| Métrica | Instrumento candidato | Unidade | Limite/resultado |
|---|---|---|---|
| Latência control-to-sound | MIDI timestamp + interface externa loopback/gravador | ms p50/p95 | <=25ms inicial; perfil validado |
| Buffer/period | ALSA/PipeWire logs Mixxx | frames / ms | configurado por hardware; documentar |
| XRUN/corte audível | backend counter quando disponível + captura externa | contagem / timestamp | 0 corte audível em 120min G2 |
| CPU core busy | `pidstat`, `top` amostrado | % por core | headroom >=25% médio candidato |
| Temp / throttling | `vcgencmd`/hwmon e kernel logs | °C / bit flags | nenhum throttling sustentado em G2 |
| Boot | timestamp power-on -> UI READY | s p95 de 10 ensaios | <=60s alvo Pi5, perfil revisável |
| Browse responsiveness | vídeo + logs de input/UI | ms p95 | <=300ms após indexação |
| Imutabilidade USB | hash de PDB/ANLZ e `findmnt` | igual/diferente | hashes iguais, mount RO |
| Integridade SQLite local | integrity_check pós falha energia | PASS/FAIL | PASS em 20 ciclos de bancada |

`AudioStatus.xruns_total=null` significa não instrumentado. Não inferir zero. Medição de latência não pode usar somente log timestamp sem sincronia do relógio/loopback. Registrar backend e interfaces envolvidas.

## 2. Cenários de áudio

- A-01: 44.1kHz/512 frames, 2 decks com MP3 (prioridade OD-03), no aparelho sob teste.
- A-02: 48kHz/512 frames, 2 decks com WAV (prioridade OD-03).
- A-03: 48kHz/256 frames para comparação; se xrun, retornar ao buffer mais seguro.
- A-04: scratches repetidos e pitch ajustado rapidamente, FX em pelo menos um deck, enquanto navega em 1000 tracks.
- A-05: DJ mix master crossfader A->B e PFL B independente durante 15 min.
- A-06: gravação de áudio ligada (P3) não reduz qualidade do core.
- A-07: análise offline em background com CPU quota (P3) não causa cortes.

**Não considerar valores de buffer universalmente bons** — medição e backend definem.

## 3. Injeção de falhas

- F-01: DDJ desconectada ao iniciar; UI state e reconexão.
- F-02: DDJ desconectada com áudio ativo; status e restart sem autoplay.
- F-03: USB removido no browse; graceful error.
- F-04: USB removido tocando; reportar drop/limitação, sem corromper fonte.
- F-05: USB com ANLZ truncado/corrompido e `export.pdb` inválido; parser abort isolado.
- F-06: USB OneLibrary-only; format probe sem acessar DB encriptado não autorizado.
- F-07: 10k entries e memory pressure; UI responsiva e áudio preservado.
- F-08: SD ficando sem espaço durante gravação (P3), interromper gravação e manter áudio.
- F-09: falha do daemon; áudio Mixxx segue tocando, UI mostra DEGRADED.
- F-10: reboot enquanto set está ativo: não haver autoplay nem restore de fader arriscado.
- F-11: atualização interrompida (P2), rollback eficaz.
- F-12: power loss bancada 20 ciclos, recupera dados locais.
- F-13: encoding e unicode paths, `..`, symlinks fora da mount, FS labels maliciosos.
- F-14: PWA desligado, firewall não expõe porta; PWA ligado com token inválido, retorna 401/403.

## 4. USB read-only test protocol

1. Identificar partição via serial/FS UUID e backups; não confiar em `/dev/sda` fixo.
2. Calcular SHA256 da seleção sensível e registrar tamanho/mtime.
3. Montar somente leitura e conferir kernel flags; testar leitura do Mixxx.
4. Executar load, hot cues, memory cues, loops, escaneamento, touch, restart app.
5. Encerrar playback, ejetar seguro e recalcular hashes em outro host quando possível.
6. Verificar **nenhuma escrita**, inclusive arquivos WAL/SHM, `.db`, ANLZ, `PIONEER/` e qualquer sidecar criado.
7. Falha de RO = release blocker. Permissão `rw` não pode ser exposta mesmo em laboratório como recurso do produto.

## 5. Compatibilidade rekordbox test matrix

| Versão/export | Legacy `export.pdb` | OneLibrary | Marcadores | Resultado |
|---|---|---|---|---|
| USB A, RB versão informada pelo proprietário | coletar | coletar | hot/memory/loop/grid | A EXECUTAR |
| USB B, segunda exportação gerada independente | coletar | coletar | variação tempo/key/artwork | A EXECUTAR |
| fixture sem RB, WAV simples (prioridade OD-03) | n/a | n/a | n/a | A EXECUTAR |
| fixture sem RB, MP3 simples (prioridade OD-03) | n/a | n/a | n/a | A EXECUTAR |
| OneLibrary-only (P5) | não | sim | em ANLZ | SP-009 bloqueável |

Não preencher tabela acima com compatibilidade de outra versão se não foi testada nesta implementação.

## 6. Formato relatório de aceite

`docs/benchmarks/G0-evidence.md`, `G1-evidence.md` etc.:

- SHA da imagem/build e commit, host/kernel/Mixxx, placa e fonte.
- Dados de músicas/USB usados anonimizados, fs mount e formato.
- Lista de FR/NFR provados com `PASS`, `FAIL`, `BLOCKED_HARDWARE`, provas e tempo.
- Métricas brutas, parâmetros de áudio e conclusão explícita de release.
- Regressões descobertas e issue/PR que as resolvem; não esconder discrepâncias.

## 7. Go/No-Go para uso real

NO-GO se qualquer um: saída cue não separa corretamente, drop audível em teste G2, USB montado RW, processo auto-play após recovery, firmware com falsos pitch sem mitigação, throttling/undervoltage persistente, release sem imagem/rollback testados. Demo doméstica experimental pode ocorrer antes de G2, mas não deve ser vendida como pronta para apresentações.
