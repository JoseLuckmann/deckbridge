# ADR-0012 — Um app, dois modos: DJ e Biblioteca em processos separados

**Estado:** aceita (pedido do dono, 2026-10-28: "os dois apps deveriam ser um só… o modo de DJ deve só carregar as coisas
de DJ mesmo… para economizar RAM, CPU e tudo que é essencial; e aí vamos ter um modo de DJ para o PC também").
Emenda o item 4 do ADR-0010 (o app de celular era um app separado) e o item 1 do ADR-0011 (o app de performance compilava
os fontes do núcleo).

## Contexto

Até a 0.21.0 havia dois APKs: o de performance (`os.deckbridge.android`, toca com a DDJ-400) e a Biblioteca DJ
(`os.deckbridge.manager`, organizar, montar sets, exportar). O dono os via "desconectados": dois ícones, duas instalações,
e a única ponte era o pendrive. Juntar tudo numa tela só, porém, poria a biblioteca inteira (análise, prévia do MIX,
Compose das telas de set, a fila de mudanças) na memória do processo que roda o áudio, que é o que não pode falhar.

## Decisão

1. **Um app, um ícone** ("DeckBridge", `applicationId = os.deckbridge.manager`). O id é o da antiga Biblioteca DJ:
   o armazenamento do Android é por id, e quem já tem a biblioteca no aparelho não perde nada ao atualizar.
2. **Dois modos, dois processos.** O modo DJ (`MainActivity`: motor C++, controladora, decks) roda no processo
   principal. O modo Biblioteca (`ManagerActivity`, módulo `:biblioteca`, que é o antigo `mobile/app`) roda no processo
   `:biblioteca` (`android:process`). O modo DJ não carrega nada da biblioteca: só o objeto `Modes` (poucas linhas).
3. **Só um modo vivo.** Trocar de modo (MENU do DJ → BIBLIOTECA ›; menu ⋯ da Biblioteca → Modo DJ ›) grava o que estiver
   pendente na Biblioteca, abre o outro modo e **encerra o processo do atual**: a memória e o processador ficam todos
   para quem está na frente. Durante uma importação a Biblioteca não deixa trocar (perderia o trabalho).
4. **A entrada abre o último modo** (`StartActivity`, transparente e sem histórico). O modo fica num arquivo
   (`files/modo.txt`), não em `SharedPreferences`, que guarda cópia em memória por processo.
5. **O modo DJ lê a biblioteca do aparelho, só para ler.** Sem pendrive (ou ao vir da Biblioteca), ele abre a pasta da
   biblioteca local (que já está no formato de um pendrive exportado) com `FolderVolume`, só leitura. O modo DJ continua
   sem escrever em nada da biblioteca nem no pendrive; quem escreve é só o modo Biblioteca, e no pendrive só ao Exportar
   (ADR-0009, ADR-0010).
6. **O núcleo é uma dependência**, não fontes copiados: o app usa `os.deckbridge:core` (build incluído), como a
   Biblioteca e o desktop. Um conceito, um lugar (ADR-0011).
7. **Modo DJ no computador**, com o mesmo princípio: o programa de computador abre num modo só (`--dj` abre o DJ; o ⋯ da
   Biblioteca → "Modo DJ ›" e o MENU do DJ → "BIBLIOTECA ›" reabrem o programa no outro modo e encerram o processo
   atual). O DJ do computador usa:
   - as **mesmas telas e a mesma lógica dos decks** do celular: `DeckPage`, `PlayerScreen`, ondas, mixer, browse, RMX,
     menu e histórico estão em `stage/src` (como `studio/src` para as telas de set), compilados pelos dois;
   - o que muda por plataforma fica atrás de `DjHost` (decodificar, AAC, ajustes, sessão, histórico, capa, exportar
     texto): no Android, MediaCodec/DataStore/SQLite/seletor de documentos; no computador, ffmpeg/arquivos locais;
   - o **mesmo motor C++** (`engine.cpp`), com o Oboe trocado pelo ALSA do Pi (`pi/alsa/oboe/Oboe.h`), compilado como
     biblioteca JNI do computador pelo Gradle do desktop (só Linux por enquanto);
   - a **DDJ-400 pelo `javax.sound.midi`**, entrando no mesmo montador e na mesma regra C++ (`controller.h`).

## Por que processos separados (e não telas no mesmo processo)

- No Android, cada processo tem o próprio heap e o próprio limite de memória; um processo que não está na frente pode ser
  encerrado pelo sistema para dar memória a quem está (Android Developers, "Processes and app lifecycle" e "Overview of
  memory management"). Separar garante que nada da biblioteca disputa memória ou coleta de lixo com o áudio.
- Encerrar o processo ao trocar devolve tudo de uma vez, sem depender do coletor de lixo nem de liberar cache à mão.
- O custo é a troca levar o tempo de abrir o outro modo do zero. Aceitável: troca-se de modo antes do show, não durante.

## Alternativas descartadas

- **Manter dois apps:** é o que o dono pediu para mudar.
- **Um processo com as duas telas e carregamento preguiçoso:** a memória que a biblioteca usou (imagens, análises,
  Compose) só volta quando o coletor quiser; um vazamento na biblioteca viraria um risco para o áudio.
- **Módulos dinâmicos (Play Feature Delivery):** dependem da Play Store para instalar e não liberam memória de quem já
  carregou; resolvem tamanho de download, não RAM.

## Consequências

- O histórico e os ajustes do antigo app de performance (`os.deckbridge.android`) não passam para o app novo (outro id);
  ele pode ser desinstalado. Mapeamento corrigido (MIDI learn) e preferências do player começam do padrão.
- O projeto Android é um só (`android/`, módulos `:app` e `:biblioteca`); `mobile/` guarda só o código do módulo, o
  README e as ferramentas. Testes: `./gradlew :app:testMultiDebugUnitTest :biblioteca:testDebugUnitTest`.
- `BLOCKED_HARDWARE`: quanto de memória a troca realmente devolve, o tempo de troca e o comportamento do Android ao
  encerrar o processo com a DDJ-400 ligada não foram medidos em aparelho.
