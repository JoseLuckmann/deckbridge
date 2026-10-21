# ADR-0004 — Viabilidade de Android

**Status:** aceito | **Data:** 2026-10-21 | **Aceite:** 2026-10-21 | **Idioma:** pt-BR
**Arquivo sugerido:** `docs/adr/0004-android-viabilidade.md`
**Relaciona-se com:** ADR-0001 (Mixxx), ADR-0002 (pendrive somente leitura), ADR-0003 (offline), decisões D-01, D-06 e OD-01/02/03 do `spec.md`

---

## Resumo em linguagem simples

- **Dá para tentar**, mas só tablet + DDJ-400 + cabo é um alvo mais arriscado que o Raspberry.
- O maior risco é o **fone separado do som principal**. Muitos aparelhos Android só mandam 2 canais pela USB, e aí o cue no fone não funciona. Isso varia de modelo para modelo, até entre os mais caros.
- O **Mixxx para Android ainda está em alfa** e hoje não aceita controladoras MIDI. Não dá para depender dele agora.
- O caminho realista é um **app Android próprio**: motor de áudio em C++ com Oboe, interface em Kotlin, e o mapa da DDJ-400 e o leitor do pendrive que já temos na especificação.
- Sem carregar o tablet durante o set, a bateria dele alimenta a DDJ-400. Carregar ao mesmo tempo exige um hub USB-C com PD, que é um equipamento extra.
- Muita coisa dá para fazer e testar sem a DDJ-400. O teste de 4 canais e de latência só fecha com o tablet e a controladora na mão.
- **Recomendação:** criar uma trilha Android como produto separado, começando por uma prova de 4 canais. Só depois decidir se ela substitui o Raspberry.

---

## Como ler as afirmações

Segue a **Política de afirmações** do README:

- **[Ref]** = vem de fonte externa (documentação, issue, loja). É referência, **não** prova no nosso sistema.
- **[Hipótese]** = estimativa nossa, ainda sem teste.
- **Nada aqui é "comprovado".** Nenhum teste em hardware Android foi feito neste projeto. Todos os gates Android ficam `BLOCKED_HARDWARE` até haver HIL.

---

## 1. Contexto e novo objetivo

A especificação atual (D-01, D-06) tem como alvo **Raspberry Pi + Mixxx 2.5.6**. Ela diz explicitamente que **não** é uma tentativa de fazer o rekordbox para Android reconhecer a DDJ-400.

O dono pediu um novo cenário:

> **Tablet Android sozinho + DDJ-400 por USB (OTG).** Sem Raspberry e sem interface de áudio extra.

Requisitos que não mudam com o tablet:

- Master na saída RCA (canais 1–2) e fone separado (canais 3–4) **da própria DDJ-400**.
- Controle pelo hardware via MIDI, com LEDs.
- Leitura **somente leitura** do pendrive exportado pelo rekordbox.
- Funcionar offline.
- OD-01: funcionar no maior número possível de aparelhos. OD-02: interface adaptável, padrão 7". OD-03: MP3 e WAV primeiro.

Isso **não** viola a regra de não mexer no rekordbox. Continuamos com software próprio, não com o app da AlphaTheta.

---

## 2. O que a DDJ-400 exige do tablet

| Item | O que diz a fonte | Tipo |
|---|---|---|
| Classe USB | USB Audio + MIDI *class compliant*. Main nos canais 1–2, fone nos 3–4 | [Ref] manual Mixxx DDJ-400 |
| Energia | Alimentada pela USB, 5 V, **500 mA nominal**. O manual pede ligação direta, sem hub | [Ref] manual/quickstart DDJ-400 |
| Entrada de energia própria | Não tem. Só a porta USB-B | [Ref] manual |

Fontes: https://manual.mixxx.org/2.7/en/hardware/controllers/pioneer_ddj_400 ; https://downloads.support.alphatheta.com/manuals/dj-controllers/DDJ-400/DDJ-400_DRH1505B_quickstart-manual.pdf ; https://forums.pioneerdj.com/hc/en-us/community/posts/360055104472-DDJ-400-USB-power-consumption

---

## 3. Áudio USB de 4 canais no Android (o ponto crítico)

### 3.1 O que o Android promete

- A documentação de USB áudio do Android **só garante mono ou estéreo** no modo host. [Ref] https://source.android.com/docs/core/audio/usb
- Desde o Android 13, aparelhos que **declaram Media Performance Class** precisam aceitar placas USB com 4 canais ou mais. A justificativa é literalmente *"usado por controladoras DJ para pré-escuta"*. [Ref] https://source.android.com/docs/compatibility/13/android-13-cdd e https://github.com/google/oboe/wiki/OboeTester_MultiChannelOutput
- O CDD do Android 17 mantém o requisito [5.6/H-1-4] (≥4 canais USB) e o [5.6/H-1-5] (MIDI class compliant). Ambos valem só para aparelhos que **declaram MPC**. O texto fica na seção *Handheld* (telas de 4 a 8"). [Ref] https://source.android.com/docs/compatibility/17/android-17-cdd
  - **[Hipótese]** Muitos tablets, principalmente os baratos e os maiores que 8", **não declaram MPC**. Então não têm essa garantia.

### 3.2 O que acontece na prática

- **Canais 3–4 caindo nos 1–2** (o fone não sai separado): visto em Pixel 2 e Galaxy S10/S20, inclusive no Android 11. Funcionou em Pixel 3a, Pixel 6, A51 e Note10 Lite. [Ref] https://github.com/google/oboe/issues/943
- **Regressão em aparelho topo de linha:** no Pixel 9 / 9 Pro com Android 15, os canais 3–4 ficam mudos com DDJ-FLX4, FLX2, GRV6 e Denon DS1. Funcionava no Pixel 8a com Android 14. O relato é de um engenheiro da Algoriddim (djay). [Ref] https://github.com/google/oboe/issues/2204
- **Latência alta com mais de 2 canais:** cerca de 150–200 ms em alguns aparelhos. Um Galaxy S9 com DDJ-WeGO3 ficou em cerca de 80 ms mesmo em estéreo. Um Pixel 4a com 4 canais mediu cerca de 93 ms de ida e volta. [Ref] https://github.com/google/oboe/issues/942
- **Som distorcido** com 4 canais em Samsung S10, S20 e Tab S6 no Android 10, pelo AAudio. [Ref] https://github.com/google/oboe/issues/1138

### 3.3 Plano B: driver USB próprio dentro do app

Um app pode **pular o sistema de áudio do Android** e falar direto com a placa USB, sem root:

- eXtream (USB Audio Player PRO, Audio Evolution): driver próprio, multicanal e de baixa latência. Exceções citadas: aparelhos **Huawei/Honor** e chips **UNISOC** (ex.: Samsung Tab A8, Lenovo M10). [Ref] https://www.extreamsd.com/index.php/technology/usb-audio-driver
- Superpowered SDK: exemplo de USB áudio e MIDI pelo `UsbDeviceConnection`. [Ref] https://github.com/superpoweredSDK/Low-Latency-Android-iOS-Linux-Windows-tvOS-macOS-Interactive-Audio-Platform
- decent-player: driver UAC2 aberto via `usbdevfs`, para Android 10+. Usa um buffer de cerca de 80 ms, focado em qualidade e não em latência. [Ref] https://github.com/Ma145/decent-player
- **[Hipótese]** Esse caminho deixa o 4 canais **menos dependente do fabricante**, que é o que a OD-01 pede. Mas custa caro: isócrono USB, relógio, UAC1/UAC2 e reconexão. Também tira o áudio do sistema enquanto o app usa a placa. O SDK Superpowered é comercial (licença a avaliar). O driver da eXtream não é licenciável para outros apps.

### 3.4 Latência: alvo e referência

- Melhor caso documentado pelo Google: **cerca de 20 ms ida e volta**, com AAudio/Oboe em baixa latência, modo EXCLUSIVE/MMAP, callback e 48 kHz. O mesmo guia mostra 160 ms usando 44,1 kHz direto no AAudio. [Ref] https://developer.android.com/games/sdk/oboe/low-latency-audio
- Flags do sistema: `android.hardware.audio.low_latency` significa saída ≤45 ms contínua. `android.hardware.audio.pro` significa ida e volta ≤20 ms. [Ref] https://developer.android.com/ndk/guides/audio/audio-latency
- **[Hipótese]** Alvo para jog e scratch aceitáveis: **≤ 20–30 ms de saída** nos 4 canais. Medir com OboeTester e um teste próprio de "toque no jog → som". Não prometer antes do HIL.

---

## 4. MIDI USB no Android

- `android.media.midi` existe desde o Android 6. Aceita USB host, BLE e MIDI virtual. [Ref] https://developer.android.com/reference/android/media/midi/package-summary
- Ele é **opcional para o fabricante**: depende do kernel ter `CONFIG_SND_USB_MIDI`. Conferir no app com `FEATURE_MIDI`. [Ref] https://source.android.com/docs/core/audio/midi
- Plano B: ler o MIDI pelo `UsbDeviceConnection`, como faz o Superpowered. [Ref] link acima.
- **[Hipótese]** MIDI é o risco **menor**. Apps comerciais já leem a DDJ-400 no Android (ver §6).

---

## 5. Energia e OTG: o tablet precisa carregar durante o set?

- A DDJ-400 tira **até cerca de 500 mA a 5 V** da porta do tablet. [Ref] §2
- **[Hipótese]** A maioria dos tablets USB-C fornece pelo menos 500 mA em modo host. **A confirmar no aparelho escolhido.**
- **Carregar e ser host ao mesmo tempo** só funciona se o tablet tiver USB-C **Power Delivery** com troca de papel de energia, **e** com um hub ou adaptador PD. Muitos tablets não têm, por exemplo Galaxy Tab A7 e Lenovo Tab M10 FHD Plus. [Ref] https://lavalink.com/lavablog/articles/why-some-usb-c-mobile-devices-cant-do-simultaneous-power-and-data/
- O manual da DDJ-400 pede **sem hub**. Um hub PD contraria esse pedido e é equipamento extra. [Ref] §2
- **Consequência para o "sem equipamento extra":** o set dura o que a bateria do tablet aguentar alimentando tablet + DDJ-400 + tela. **[Hipótese]** Medir no HIL: % de bateria por hora, temperatura e se o tablet reduz desempenho.
- Item mínimo, além do tablet: um **cabo USB-C → USB-B** (ou o cabo da DDJ com um adaptador OTG USB-C → USB-A). Isso é um cabo, não uma interface.

---

## 6. Apps Android que já tocam com controladora (evidência do que é possível)

| App | DDJ-400 no Android | Cue no fone | Tipo |
|---|---|---|---|
| **rekordbox para Android** | **Não suporta** (só DDJ-200, FLX2, FLX4, GRV6) | n/a | [Ref] https://rekordbox.com/en/support/faq/connecting-to-dj-units-android/ |
| **djay (Algoriddim)** | **Não oficial**. "Pode funcionar em alguns aparelhos" | Em Samsung sem multicanal, só com cabo divisor (master em mono) | [Ref] https://community.algoriddim.com/t/problem-with-connection-of-ddj-400-and-djay-android-app/11636 ; https://help.algoriddim.com/user-manual/djay-android/getting-started/audio |
| **Cross DJ (Mixvibes)** | **Listada como compatível** | "Multicanal para placas USB compatíveis", **mas depende do aparelho** | [Ref] https://play.google.com/store/apps/details?id=com.mixvibes.crossdjapp ; https://forum.mixvibes.com/viewtopic.php?f=395&t=38590 |

**Leitura:** com app comercial, tablet + DDJ-400 **já funciona em alguns aparelhos**. Mesmo empresas grandes não conseguem garantir o cue separado em todos. Para nós isso é uma boa notícia, porque mostra que é possível. Também é um aviso, porque a OD-01 ("o máximo de aparelhos") fica limitada pelo hardware.

**Dica prática para depois:** quando o dono tiver a DDJ-400, testar o Cross DJ no tablet candidato é um "teste de fumaça" barato. Se ali o fone sair separado, o Android daquele tablet aceita os 4 canais.

---

## 7. Ler o pendrive do rekordbox no Android (somente leitura)

- **Acesso:** Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`). O usuário escolhe o pendrive uma vez e o app pede só permissão de **leitura**. [Ref] https://developer.android.com/training/data-storage/shared/documents-files
- **Parser:** já existe a descrição aberta do `export.pdb` e do ANLZ em Kaitai Struct, e uma biblioteca Java (Crate Digger, Deep Symmetry) que dá para usar ou portar para Kotlin. [Ref] https://github.com/Deep-Symmetry/crate-digger ; https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/crate_digger.html . **Licença a verificar antes de copiar código** (SP-010).
- **Conflito com a Constituição II e o ADR-0002:**
  - No Android **quem monta o pendrive é o sistema**, não o app. O app não consegue exigir `mount ro`. Só garante que **ele próprio** não escreve. **[Hipótese]** O sistema pode rodar verificação de disco (fsck) ao montar, e isso pode alterar o pendrive.
  - Há relato de pendrive exFAT protegido contra escrita que **não monta** em algumas versões do Android. [Ref] https://kkocdko.site/post/202510062251 ; https://github.com/GrapheneOS/os-issue-tracker/issues/5524
  - **Mitigação proposta:** somente leitura **no app**, mais um alerta claro, mais a opção de copiar a biblioteca para o tablet e tocar da cópia. Isso precisa de **emenda ao ADR-0002** aprovada pelo dono.
- **OneLibrary / `exportLibrary.db`:** fica igual à especificação, adiado para P5. Abrir só uma **cópia local**, nunca o original. [Ref] R-09

---

## 8. Alternativas avaliadas

### (a) Portar o Mixxx inteiro para Android

- **Estado:** existe um port **oficial em andamento**. O APK é compilado no CI com Qt 6 + vcpkg. O épico continua aberto, mira o **3.0-beta** e tem bloqueios de QML e estabilidade. [Ref] https://github.com/mixxxdj/mixxx/wiki/Building-for-Android ; https://github.com/mixxxdj/mixxx/issues/15679
- Em março de 2026 a equipe chamou de *"alfa instável, não para breve"*. [Ref] https://mixxx.discourse.group/t/mixxx-tablet-android/33472
- **Áudio:** usa o PortAudio com um backend Oboe que **ainda não entrou no PortAudio oficial**. [Ref] https://github.com/mixxxdj/mixxx/commit/4239279cfd3be7e8dabd5b820b604ca63b34b4cd ; https://github.com/PortAudio/portaudio/pull/840
- **MIDI:** o PortMidi vem **desligado no Android**. O substituto (libremidi) ainda é um rascunho. Ou seja, **hoje não há controladora**. [Ref] mesmo commit ; https://github.com/mixxxdj/mixxx/pull/16135
- **Base de versão:** o port está na linha 2.7/3.0 (QML), **não na 2.5.6** que o ADR-0001 fixou.
- **Viabilidade agora:** baixa. **Esforço:** alto e fora do nosso controle (anos, ritmo do upstream). **Risco:** muito alto.
- **Licença:** GPL-2.0-or-later. É compatível com a Play Store desde que o código-fonte seja oferecido (§9).
- **Sem hardware dá para:** compilar o APK no CI e acompanhar o épico. Não é útil para o produto agora.

### (b) App Android próprio (Kotlin + C++/Oboe), reaproveitando a lógica — **recomendada**

- **Ideia:** motor de 2 decks simples e focado (play, cue, sync, pitch e keylock depois, EQ de 3 bandas, filtro, crossfader, PFL) num callback Oboe de 4 canais, mais:
  - mapa DDJ-400 (já extraído na fase 0) como **tabela de dados**;
  - parser PDB/ANLZ;
  - modelo de eventos e analytics (P3/P4) do `data-model.md`;
  - UI adaptável com padrão de 7" (OD-02).
- **Decodificação:** MP3 e WAV primeiro (OD-03). WAV é trivial. MP3 pelo `MediaCodec` do sistema ou por um decodificador embutido. **[Hipótese]** Decodificar a faixa inteira para a memória antes de tocar, porque isso também protege contra o pendrive ser retirado (J-05).
- **Áudio:** caminho 1 é AAudio/Oboe com 4 canais. **Caminho 2** (se o 4 canais falhar no tablet) é um driver USB próprio (§3.3). O caminho 2 entra **só se a prova do caminho 1 falhar** e o dono aprovar o custo.
- **Viabilidade:** **média**. Depende do tablet passar no teste de 4 canais.
- **Esforço [Hipótese]:** MVP de 2 decks em **vários meses** de trabalho de agentes. O driver USB próprio soma **semanas a meses**.
- **Riscos:** fragmentação do 4 canais, latência por fabricante, Android matando o app em segundo plano (usar foreground service), bateria, e a qualidade do motor ficar abaixo do Mixxx (time-stretch, beatgrid).
- **Licença:** o nosso código pode ser GPL-3.0-or-later. Oboe é Apache-2.0, compatível com GPLv3. Copiar o script de mapping do Mixxx (GPL-2.0-or-later) também funciona sob GPLv3. Se o projeto quiser o SDK Superpowered, avaliar a licença comercial.
- **Sem hardware dá para fazer e testar:**
  - motor offline em testes (render para arquivo, comparação de amostras), mixer e EQ em testes unitários;
  - parser contra fixtures sintéticas;
  - mapa MIDI usando um **dispositivo MIDI virtual** (o `MidiDeviceService` do Android) que simula a DDJ-400;
  - UI no emulador em 7", 8" e 10";
  - **o próprio celular do dono**, com o APK instalado direto: interface, reprodução no alto-falante, decodificação e desempenho.
  - Não dá para testar sem hardware: canais 3–4 na DDJ, latência real e energia.

### (c) Android como controle remoto ou companion do Raspberry — *nota de rodapé*

Já está previsto na fase 3 (`contracts/openapi.yaml`). **Não atende ao novo objetivo**, porque continua precisando do Raspberry. Fica como está no roadmap.

### (d) Rodar Linux no Android (chroot, proot, Termux, postmarketOS)

- chroot e proot **não chegam ao áudio USB nem ao ALSA** sem root. Em geral o som sai por PulseAudio sobre o áudio do Android, que tem latência alta e não abre 4 canais.
- postmarketOS e Linux nativo exigem **desbloquear o bootloader e trocar o sistema**. Isso só funciona em poucos modelos, apaga o tablet e contraria a OD-01.
- **Viabilidade:** muito baixa para o produto. Serve no máximo como laboratório. **[Hipótese]** com base no funcionamento conhecido dessas ferramentas. Não testamos.

---

## 9. Licenças e loja

- A Play Store **aceita apps GPL** desde que se cumpram as obrigações (código-fonte completo, avisos). [Ref] https://play.google.com/about/developer-distribution-agreement.html ; https://f-droid.org/docs/FAQ_-_App_Developers/
- O **F-Droid** exige que o app seja aberto e compilável a partir do código. Aceita GPLv2 só quando é "or later". [Ref] https://f-droid.org/docs/Repository_Style_Guide/
- Para começar, **não é preciso loja nenhuma**: um APK instalado direto no tablet basta para uso pessoal.
- Nomes e marcas: continuar sem usar Pioneer, AlphaTheta ou rekordbox como nome do app (D-10).

---

## 10. Tablet mínimo sugerido (para validar, não para comprar agora)

| Item | Mínimo | Preferível | Por quê |
|---|---|---|---|
| Android | **13** | 14 ou mais (cuidado com regressões, ver Pixel 9/15) | MPC com 4 canais nasceu no 13. O rekordbox Android pede 13–16 [Ref] https://rekordbox.com/en/feature/mobile/ |
| CPU/ABI | ARM 64-bit, chip de gama média | Qualcomm Snapdragon ou MediaTek Dimensity recente | **Evitar UNISOC e Huawei/Honor** (ver §3.3) |
| RAM | **4 GB** | 6–8 GB | 2 faixas decodificadas na memória mais a UI. **[Hipótese]** Uma faixa de 6 min em float estéreo 48 kHz ocupa cerca de 140 MB |
| USB | USB-C com **OTG/host** | USB-C com **Power Delivery** | PD só importa se um dia quiser carregar durante o set |
| Tela | 8" | 8–11" paisagem | O padrão de 7" (OD-02) roda bem em 8". A UI é adaptável |
| Recursos do sistema | `FEATURE_MIDI`, `audio.low_latency` | `audio.pro`, MPC declarado | Conferível por um app de diagnóstico nosso |
| **Teste obrigatório** | OboeTester → *Test Output* → **4 canais** com a DDJ-400: som separado em RCA e fone | idem, com latência medida | Único critério que decide [Ref] https://github.com/google/oboe/wiki/OboeTester_MultiChannelOutput |

**Conselho:** comprar o tablet **só quando der para testar com a DDJ-400**, ou numa loja com troca fácil. Nenhuma ficha técnica garante o cue separado.

---

## 11. Decisão proposta

1. **Não portar o Mixxx agora.** Acompanhar o épico Android do Mixxx. Reavaliar quando houver beta com controladoras.
2. **Abrir a trilha "DeckBridge Android"** como **alternativa (b)**: app próprio Kotlin + C++/Oboe. Reaproveitar a especificação (mapa DDJ-400, parser, modelo de dados, UI adaptável, gates) e adaptar ao Android.
3. **Primeiro entregável (prova A0):** um APK de diagnóstico que lista áudio e MIDI USB, toca tons diferentes nos canais 1–2 e 3–4, lê o MIDI da DDJ-400 e mede latência e bateria. Pode ser feito agora. O teste fica `BLOCKED_HARDWARE` até o dono ter a DDJ-400.
4. **O driver USB próprio** (§3.3) só entra se a A0 mostrar que o tablet escolhido não faz 4 canais pelo Android.
5. **Emendar o ADR-0002** para o Android: somente leitura no app, cópia local opcional, alerta sobre o sistema montar o pendrive.

### Consequências

- **Prós:** atende "só tablet + DDJ-400". Não depende do ritmo do Mixxx. Boa parte pode ser feita e testada sem hardware, inclusive no celular do dono.
- **Contras:** perdemos o motor maduro do Mixxx (ADR-0001) e teremos que escrever e manter um motor. A compatibilidade **varia por aparelho**. A bateria limita o set.
- **Gatilho para rever:** o Mixxx Android chegar a beta com MIDI; ou a A0 falhar em todos os tablets candidatos; ou o dono aceitar um hub PD ou uma interface extra.

---

## 12. O que o dono precisa decidir

| ID | Pergunta | Opções | Default se não responder |
|---|---|---|---|
| **OD-11** | O Android **substitui** o Raspberry ou vira **um segundo alvo**? | substituir / os dois / só estudo | **Os dois**: o Pi continua como referência, o Android vira trilha nova |
| **OD-12** | Aceita escrever um **motor próprio** em vez do Mixxx no Android (mexe no ADR-0001)? | sim / esperar Mixxx Android | sim, só na trilha Android |
| **OD-13** | Aceita o **somente leitura no app** (o Android monta o pendrive) mais a opção de copiar para o tablet (mexe no ADR-0002)? | sim / só tocar de cópia local | sim, com alerta |
| **OD-14** | Se o tablet não fizer 4 canais: investir no **driver USB próprio** ou aceitar **cue por cabo divisor (master mono)**? | driver / cabo divisor / trocar de tablet | trocar de tablet antes de tudo |
| **OD-15** | Set só na **bateria** ou aceita um **hub USB-C PD** para carregar? | só bateria / hub PD | só bateria |
| **OD-16** | Distribuição: APK direto, F-Droid ou Play Store? | APK / F-Droid / Play | APK direto (uso pessoal) |

---

## 13. Aceite do proprietário em 21/10/2026

A proposta passa a **aceita**. OD-11 está decidida: o Android é o alvo 1 (tablet ligado direto na DDJ-400 por USB, sem equipamento extra) e o Raspberry é o alvo 2. OD-12 a OD-16 entram como **provisório, confirmar com o dono**, nos padrões desta proposta: motor próprio no Android; somente leitura no app, com aviso; se faltar 4 canais, tentar outro tablet primeiro; só bateria; APK direto.

A trilha Raspberry não foi apagada. O Mixxx 2.5.6 continua valendo nela (emenda na ADR-0001). A montagem `ro` do Linux continua valendo nela (emenda na ADR-0002).

### Tablet e relato do djay

O tablet alvo é um Samsung Galaxy Tab S6 Lite. O modelo exato e o ano **não** foram confirmados. Pode ser SM-P610 ou SM-P615 (Exynos 9611) ou as versões 2022/2024 (Snapdragon 720G ou Exynos 1280), com 4 GB de RAM. O sistema Android desse aparelho fica entre 10 e 14.

O dono relatou que, com o app djay, a DDJ-400 tocou master e fone **separados** nesse tablet. Isso é relato do dono, não medição deste projeto. Reduz o risco de 4 canais **nesse aparelho**, no registro de riscos. Não prova o nosso app, não confirma o SoC e não fecha a prova A0. O acesso ao tablet e à controladora ainda vai demorar. Até o relatório do diagnóstico voltar, a escuta continua `BLOCKED_HARDWARE`.

## Fontes (consultadas em 2026-10-21)

- Mixxx Android: https://github.com/mixxxdj/mixxx/wiki/Building-for-Android · https://github.com/mixxxdj/mixxx/issues/15679 · https://github.com/mixxxdj/mixxx/pull/15959 · https://github.com/mixxxdj/mixxx/commit/4239279cfd3be7e8dabd5b820b604ca63b34b4cd · https://github.com/mixxxdj/mixxx/pull/16135 · https://github.com/PortAudio/portaudio/pull/840 · https://mixxx.discourse.group/t/mixxx-tablet-android/33472
- Áudio USB Android: https://source.android.com/docs/core/audio/usb · https://source.android.com/docs/compatibility/13/android-13-cdd · https://source.android.com/docs/compatibility/17/android-17-cdd · https://github.com/google/oboe/wiki/OboeTester_MultiChannelOutput · https://github.com/google/oboe/issues/943 · https://github.com/google/oboe/issues/942 · https://github.com/google/oboe/issues/1138 · https://github.com/google/oboe/issues/2204
- Latência: https://developer.android.com/games/sdk/oboe/low-latency-audio · https://developer.android.com/ndk/guides/audio/audio-latency
- Drivers USB próprios: https://www.extreamsd.com/index.php/technology/usb-audio-driver · https://github.com/Ma145/decent-player · https://github.com/superpoweredSDK/Low-Latency-Android-iOS-Linux-Windows-tvOS-macOS-Interactive-Audio-Platform
- MIDI: https://developer.android.com/reference/android/media/midi/package-summary · https://source.android.com/docs/core/audio/midi
- Energia: https://downloads.support.alphatheta.com/manuals/dj-controllers/DDJ-400/DDJ-400_DRH1505B_quickstart-manual.pdf · https://lavalink.com/lavablog/articles/why-some-usb-c-mobile-devices-cant-do-simultaneous-power-and-data/
- Apps DJ: https://rekordbox.com/en/support/faq/connecting-to-dj-units-android/ · https://rekordbox.com/en/feature/mobile/ · https://help.algoriddim.com/hc/en-us/articles/360014702752-Which-MIDI-controllers-are-supported-by-djay · https://community.algoriddim.com/t/problem-with-connection-of-ddj-400-and-djay-android-app/11636 · https://play.google.com/store/apps/details?id=com.mixvibes.crossdjapp · https://forum.mixvibes.com/viewtopic.php?f=395&t=38590
- Pendrive: https://developer.android.com/training/data-storage/shared/documents-files · https://kkocdko.site/post/202510062251 · https://github.com/GrapheneOS/os-issue-tracker/issues/5524 · https://github.com/Deep-Symmetry/crate-digger
- Licenças: https://play.google.com/about/developer-distribution-agreement.html · https://f-droid.org/docs/FAQ_-_App_Developers/ · https://f-droid.org/docs/Repository_Style_Guide/
