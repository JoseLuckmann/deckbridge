# DeckBridge no Android

Alvo 1, a partir de 21/10/2026: tablet ligado direto na DDJ-400. O Raspberry continua como alvo 2.

- Kotlin e Jetpack Compose na interface.
- Motor de tons em C++ com Oboe 1.11.0, pelo NDK.
- minSdk 26 (Android 8). Isso inclui o Galaxy Tab S6 Lite, que está entre Android 10 e 14. targetSdk 34.
- Mapa de MIDI copiado do XML pinado do Mixxx 2.5.6. Não é captura da controladora.
- Leitor próprio de `export.pdb`, `exportExt.pdb` e ANLZ. Ver `FORMATO.md`. OneLibrary abre uma cópia local com a chave pública documentada; se não abrir, a tela avisa.
- O APK `multiDebug` inclui `x86_64` para o emulador, além de `arm64-v8a` e `armeabi-v7a`.
- O APK para o Tab S6 Lite é só `arm64-v8a`, com minify e shrink. Sai menor. A assinatura é a de debug, para instalar direto.
- A tela 0.12.2 abre no player. Uma pasta sem `export.pdb`, com MP3 ou WAV solto, é analisada em segundo plano (BPM, tom e onda RGB) e o resultado fica em cache. RGB lê PWV5/PWV4 com soma aditiva (grave vermelho, médio verde, agudo azul). 3 BANDAS lê PWV7/PWV6 e AZUL lê PWV3/PWAV. Sem a tag, o WAV é analisado no app e o USB não é escrito. O menu tem Cor da onda e “Usar configuração do pendrive”. O restante: barra alta com SOURCE, BROWSE, TAG LIST, PLAYLIST, SEARCH e MENU; faixa fina com REC, relógio e REMAIN; duas ondas empilhadas; hot cues; faixa de beat jump e loop; cartões dos decks. O menu tem navegação à esquerda (Player, Áudio, Controladora, Biblioteca, Gravação, Histórico, Diagnóstico, Sobre) e grava as preferências. RMX tem vista cheia com o dial de Scene FX. HISTORY lista o que esteve no ar. A gravação do master vai para WAV na pasta do app. O USB do rekordbox não é gravado.
- Na lista, o toque no cabeçalho ordena por título, BPM, tom ou tempo. REV inverte a reprodução. G−, G+, S−, S+ e G0 ajustam a grade na memória do app. O loop de memória espera a cabeça chegar. A contagem até o cue inclui o memory cue. Nada disso grava o pendrive.
- `python3 tools/fetch_real_corpus.py` baixa as nove faixas CC BY 3.0 para `/opt/cursor/artifacts/real-corpus` e confere o SHA-256. `python3 tools/inspect_usb.py PASTA` lê uma pasta PIONEER sem gravar nela. Com `--jvm`, o parser Kotlin imprime o relatório em `/tmp/deckbridge-inspect.txt`. As medidas estão em `docs/testing/real-music-corpus.md`.

```bash
export ANDROID_HOME="$HOME/android-sdk"
./gradlew testMultiDebugUnitTest assembleMultiDebug assembleArm64Release
```

O APK do tablet sai em `app/build/outputs/apk/arm64/release/`. O de debug com as três ABIs sai em `app/build/outputs/apk/multi/debug/`. Instalar a prova A0 está em `docs/android/prova-a0.md`. A biblioteca e os decks estão em `docs/android/biblioteca-rekordbox.md`. Enquanto o tablet não devolver o relatório, a escuta fica `BLOCKED_HARDWARE`.

Oboe é Apache-2.0. O código deste app segue a licença provisória GPL-2.0-or-later do repositório. OD-06 continua aberta. OD-12 a OD-16 estão provisórias.
