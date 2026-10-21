# ADR-0002: USB rekordbox somente leitura até uma especificação nova

**Status:** ACCEPTED | **Data:** 2026-10-21

## Contexto

USB exportado contém banco e arquivos binários importantes (PDB/ANLZ/OneLibrary). A escrita/cue roundtrip pode corromper coleção e formatos evoluem. `libdjinterop` alerta sobre auxiliares WAL/SHM ao ler OneLibrary.

## Decisão

Todo dispositivo externo de música será montado `ro` e validado antes de a UI disponibilizar a fonte. Qualquer parsing SQLite/OneLibrary que possa escrever usa snapshot local. Configuração do produto rejeita `allow_write=true`. Alterações musicais locais persistem em arquivo/DB local e não são exportadas automaticamente. Hash e FS check entram no gate G1.

## Consequências

- Prós: segurança da biblioteca, previsibilidade do MVP.
- Contras: mudanças feitas no Pi não aparecem automaticamente ao voltar ao rekordbox.
- Extensão: projeto separado de writeback com backup, controle transacional, validação em USB descartável e opt-in explícito.

## Emenda Android — 21/10/2026

Na trilha Raspberry, a decisão acima continua: montar `ro,nodev,nosuid,noexec` e não oferecer a fonte se isso não for confirmado. OD-13, **provisório, confirmar com o dono**, vale só para o Android, onde o sistema é quem monta o pendrive. Lá o app pede só leitura, avisa essa limitação e não grava no pendrive. Isso não libera escrita na trilha Raspberry e não é writeback.

## Fontes

https://manual.mixxx.org/2.5/en_gb/chapters/library ; https://github.com/xsco/libdjinterop/blob/main/GUIDE.md
