# DeckBridge Biblioteca (celular)

App Android (`os.deckbridge.manager`) para organizar a biblioteca no celular e exportá-la para um pendrive, no formato do rekordbox. Referência: Samsung Galaxy S25 Ultra (1440×3120, ≈411×891 dp), em pé e deitado. Decisão e limites: `docs/adr/0010-biblioteca-local-e-mobile.md`.

## O que faz

- **Biblioteca no aparelho** (`getExternalFilesDir/biblioteca/dados`), mesmo formato de um pendrive; mudanças ficam numa fila (barra "Salvar / Desfazer / Descartar") e são gravadas com cópia de segurança; ao sair do app o que estiver pendente é salvo.
- **Importar** músicas do aparelho (seletor de documentos) com análise (BPM, tom, grade, ondas, cues sugeridos).
- **Marcar**: nota, cor, comentário, campos; seleção múltipla (segurar) para playlist, nota, cor, remover.
- **Playlists**: criar, renomear, apagar, reordenar (▲▼), tirar faixas.
- **Grade e cues**: onda com batidas, hot cues A–H e cues de memória na posição escolhida, grade regular por BPM e primeira batida.
- **Pendrive**: escolher a pasta raiz (SAF), ver se já há biblioteca, exportar (substitui, com backup local do banco antigo; nenhum áudio é apagado) e trazer de um pendrive (substitui a local, com backup).

O núcleo (`core/`) é o mesmo do programa de computador. Nenhum outro código escreve no pendrive além do exportador (ADR-0009/0010).

## Build e testes

```
cd mobile
LC_ALL=C.UTF-8 ./gradlew :app:testDebugUnitTest :app:assembleDebug   # APK em app/build/outputs/apk/debug
```

Testes (11, JVM/Robolectric, SDK 33): `ScreenshotTest` (8 capturas em `docs/ui/mobile-*.png`), `SafStorageTest` (3: gravar/trocar arquivos, recusa de renomeação que devolve o antigo, exportar uma biblioteca inteira e trazê-la de volta por um provedor de documentos de brinquedo).

## Não verificado (`BLOCKED_HARDWARE`)

- Nada rodou no S25 Ultra: toque, rotação real, desempenho, memória.
- MP3/FLAC/AAC dependem do MediaCodec do aparelho (`MediaDecoder`); nos testes só WAV é decodificado.
- Pendrive USB real pelo seletor do Android (provedores reais podem se comportar diferente do de brinquedo, por exemplo ao renomear) e o espaço livre (o SAF não informa; uma falta de espaço aparece como erro de gravação, com o banco antigo intacto porque o banco é escrito por último).
- Nenhum player nem o rekordbox leu o que o app grava.
- Importar muitas faixas de uma vez mantém as análises na memória até gravar.
