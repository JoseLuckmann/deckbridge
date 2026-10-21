# DeckBridge no Android

Alvo 1, a partir de 21/10/2026: tablet ligado direto na DDJ-400. O Raspberry continua como alvo 2.

- Kotlin e Jetpack Compose na interface.
- Motor de tons em C++ com Oboe 1.11.0, pelo NDK.
- minSdk 26 (Android 8). Isso inclui o Galaxy Tab S6 Lite, que está entre Android 10 e 14. targetSdk 34.
- Mapa de MIDI copiado do XML pinado do Mixxx 2.5.6. Não é captura da controladora.
- Leitor próprio de `export.pdb` e de ANLZ. Ver `FORMATO.md`. OneLibrary não é aberta.
- O APK de debug inclui `x86_64` para o emulador, além de `arm64-v8a` e `armeabi-v7a` para o tablet.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew test assembleDebug
```

O APK de debug sai em `app/build/outputs/apk/debug/`. Instalar a prova A0 está em `docs/android/prova-a0.md`. A biblioteca e os decks estão em `docs/android/biblioteca-rekordbox.md`. Enquanto o tablet não devolver o relatório, a escuta fica `BLOCKED_HARDWARE`.

Oboe é Apache-2.0. O código deste app segue a licença provisória GPL-2.0-or-later do repositório. OD-06 continua aberta. OD-12 a OD-16 estão provisórias.
