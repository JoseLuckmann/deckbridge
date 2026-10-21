# Research & Due Diligence — achados, referências e spikes

**Consulta:** 2026-10-21. Fontes são páginas e repositórios públicos; APIs, status e versões podem mudar. Fixar SHAs/versões antes do bootstrap.

## R-01. Mixxx release e suas APIs

- Fonte: https://mixxx.org/download/
- **Confirmado por página oficial:** versão estável recomendada para apresentações: **2.5.6**; **2.6 beta** e **2.7 development** são versões não recomendadas para apresentações.
- Decisão: base de produção `2.5.6`; upgrades só com release/gate. `Stable` não assegura performance no Pi antigo.
- Fonte MIDI scripting: https://github.com/mixxxdj/mixxx/wiki/midi-scripting
- Fonte MIDI mapping XML: https://github.com/mixxxdj/mixxx/wiki/MIDI-Controller-Mapping-File-Format
- A investigar: nomes exatos de controls e como acionar rescan USB pela versão 2.5.6, suporte de skin para 800x480 e comportamento em Pi 3. Não copiar APIs de Mixxx 2.7 como se existissem no 2.5.6.

## R-02. DDJ-400 em Linux

- Fonte: https://manual.mixxx.org/2.7/en/hardware/controllers/pioneer_ddj_400
- **Confirmado pelo manual:** DDJ-400 é USB Audio + MIDI class compliant em Linux; mixer com dois decks; outputs **Main 1-2, Headphones 3-4**; microphone TS não roteado para host/gravável por software.
- O manual alerta bug de firmware em versões antigas, capaz de produzir eventos de tempo slider sem toque; update oficial via Windows/macOS.
- A validar: o mapping real 2.5.6 da DDJ-400; nível funcional de cada tecla e LED com firmware do usuário, potência USB real.

## R-03. Rekordbox USB import no Mixxx

- Fonte: https://manual.mixxx.org/2.5/en_gb/chapters/library
- O Mixxx importa bibliotecas de dispositivos USB/SD exportados no modo Export do rekordbox; formatos de DB de desktop não são o mesmo formato. Documenta playlists, pastas, grids, hot cues, memory cues e loops e como estes são mapeados para o modelo de cues do Mixxx.
- A validar: comportamento de Mixxx 2.5.6 para exports de rekordbox 7 do usuário. Há variantes Device Library Plus em alguns USBs; não prometer import automaticamente de todas as variantes.

## R-04. StandaloneMixxx

- URL: https://github.com/ghztomash/StandaloneMixxx
- Documenta Raspberry Pi 5 e Pi 4B 4GB, Raspberry Pi OS Desktop Debian 13 Trixie, touchscreen Pimoroni HyperPixel 4.0 e controladoras DDJ-FLX4/FLX2; cita build customizada Mixxx 2.7 com melhorias da biblioteca RB e skins.
- Uso: bootstrap/provisioning, permissões RT, autostart, integração skin e scripts como referência de implementação, sem tratar DDJ-400 como validada pelo projeto original.
- **Risco:** código custom 2.7 dev e autostart verifica nome de dispositivo `DDJFLX`: requer alterações para DDJ-400. Não executar automaticamente scripts remotos sem revisar e pin SHA.

## R-05. TriMixxx

- URL: https://github.com/usr-ein/TriMixxx
- Projeto standalone de CDJ custom (Pi 4B + ESP32-S3 + placas próprias), UI otimizada em 1024x600, USB Rekordbox, patched Mixxx baseado em 2.5.6 e ponte de MIDI para hardware novo.
- Uso: referência de layout/tamanho, boot, hotplug e adaptação de player; **não usar control electronics** do TriMixxx na DDJ-400 sem necessidade.
- Licença: GPL-3.0-or-later para a maior parte do repo, com exceções explícitas e submodules.

## R-06. NauticMixxx

- URL: https://github.com/nauticsoftware/NauticMixxx
- UX RX3-like em Mixxx 2.5.6, modos orientados a export USB, presets que citam DDJ-400; produto principal macOS/Windows e documenta experimentos Linux/Pi.
- Uso: estudar arquitetura visual, browse via encoder e funcionalidades; **não reutilizar binary Windows/macOS no Pi**.
- Licença: componentes GPL-3.0; observar licença de assets.

## R-07. mixxx-pi-gen

- URL: https://github.com/fayaaz/mixxx-pi-gen
- Imagem Raspberry Pi com Mixxx 2.5, 64-bit Raspberry Pi OS Debian 13, Sway, skins compactas e configurações de performance.
- Uso: alternativa para build da imagem; pin release e auditar serviços. **Não presumir compatibilidade integral** com DDJ-400 ou Raspberry antigo.

## R-08. pyrekordbox

- URL: https://github.com/dylanljones/pyrekordbox
- MIT; suporta interfaces de biblioteca, XML, ANLZ, Device Library Plus e `master.db` de desktop (contextos diferentes). Docs de versão indicam algumas versões 5/6/7 testadas.
- Uso: laboratório de análise de fixtures, extração de markers e validação cross-parser; não inserir em thread RT e não assumir cobertura de export PDB sem checar módulos.

## R-09. libdjinterop / OneLibrary

- URLs: https://github.com/xsco/libdjinterop e https://github.com/xsco/libdjinterop/blob/main/GUIDE.md
- Biblioteca C++ LGPL-3.0-or-later. Suporte OneLibrary read-only, com SQLCipher e passphrase fornecida externamente; não inclui beatgrid, waveforms nem cues no banco (ficam em ANLZ). O guia alerta que SQLite WAL pode escrever arquivos auxiliares na origem, mesmo em read.
- Decisão: **não abrir a biblioteca OneLibrary na mídia original**; usar cópia temporária local, se um dia houver acesso autorizado. Não distribuir senha/procurar contornar encriptação.

## R-10. Energia e Pi

- URL: https://www.raspberrypi.com/documentation/hardware/raspberrypi/power.html
- Pi 5: limites USB agregados de 600mA com alimentação 3A e até 1600mA com 5A reconhecida, compartilhados com fan; Pi 2/3/4: 1200mA total. Periféricos USB podem falhar por fonte insuficiente.
- Decisão: coletar undervoltage/throttling e considerar hub alimentado; alimentação correta é condição do gate de palco.

## R-11. GitHub Spec Kit

- URL: https://github.com/github/spec-kit
- Fluxo com `.specify/memory/constitution.md`, `specs/.../spec.md`, `plan.md`, `tasks.md`, prompts do agente. CLI `specify` instala via `uv tool install specify-cli`; comando oficial `specify init`, integração depende do agente.
- Este pacote é **Spec Kit-ready** na organização e orientações, não um repo de aplicação criado por CLI; não alegar que `/speckit.implement` foi executado.

## Spikes de pesquisa mandatórios

| ID | Hipótese/questão | Procedimento | Critério de saída |
|---|---|---|---|
| SP-001 | Modelo antigo do Pi roda Mixxx 2 decks com áudio USB? | inventário + 15min áudio/CPU/HIL | relatório por hardware, sem extrapolar |
| SP-002 | Mapping DDJ-400 2.5.6 cobre funções M0? | inspecionar mapping + midi dump + HIL | matriz funções/CC/LED |
| SP-003 | Mixxx rescan USB após hotplug é scriptável sem patch C++? | código fonte + test GUI | patch mínimo ou UI com refresh manual |
| SP-004 | 7" 1280x720 via DSI em Pi 4/5 atende orientação horizontal? | datasheet, driver, test de rotação/touch | imagem consistente touch+display |
| SP-005 | Rekordbox do usuário gera export legacy? | 1 USB synthetic autorizado por versão do rekordbox | coleta segura de metadados/paths |
| SP-006 | Formatos de file system aceitos no Pi OS? | USBs FAT32/exFAT; HFS apenas com driver presente | matriz real de mount RO |
| SP-007 | Mensagens MIDI para telemetry sem custo de RT? | JS mapping bridge vs C++ queue em x86/Pi | benchmark + ADR |
| SP-008 | Stable Mixxx suporta backends sound estáveis Pi 4/5? | ALSA/PipeWire/JACK perf | menor latência sem x-runs |
| SP-009 | OneLibrary pode ser lido com direitos e sem tocar origem? | cópia local + libdjinterop com credencial autorizada | parser independente funcional ou não suportado |
| SP-010 | Licenciamento de skins upstream/forks | inventário arquivos/assets/licenças | NOTICE/SBOM e ADR para código reutilizado |

## Limitações conhecidas, não escondidas

- Os repositórios externos são referência, não uma receita pronta que funciona na DDJ-400 + Pi antigo.
- A interface de áudio da DDJ é 4 canais USB, mas o dispositivo *físico* e o setup de ALSA ainda precisam de HIL.
- Mixxx não tem um endpoint REST documentado como seu padrão de administração; a API local proposta é nosso software e nasce apenas P3.
- Não existe neste pacote um parser OneLibrary funcional nem credencial de acesso; essa etapa pode permanecer bloqueada por restrições legais/técnicas.
- Performance, boot, latência e x-runs ainda não foram mensurados neste projeto.
