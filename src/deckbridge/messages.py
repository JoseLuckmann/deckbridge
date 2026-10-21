"""Textos de erro para o DJ. Sem rastreio de exceção."""

from __future__ import annotations

CODES = (
    "E_POWER_BUDGET",
    "E_DDJ_NOT_FOUND",
    "E_AUDIO_4CH_MISSING",
    "E_RB_EXPORT_NOT_FOUND",
    "E_RB_UNSUPPORTED_VARIANT",
    "E_USB_READONLY_UNVERIFIED",
    "E_USB_REMOVED",
    "E_DISK_LOW",
    "E_AUDIO_XRUN",
    "E_ENGINE_CRASH",
    "E_PARSER_TIMEOUT",
    "E_UPGRADE_FAILED",
)

_PT = {
    "E_POWER_BUDGET": "A energia pode estar curta. Confira a fonte antes de confiar no set.",
    "E_DDJ_NOT_FOUND": "A DDJ-400 não apareceu. O programa continua e espera o cabo.",
    "E_AUDIO_4CH_MISSING": "Master e fone não estão separados. Não há pré-escuta segura.",
    "E_RB_EXPORT_NOT_FOUND": "Não achei a exportação antiga do rekordbox. Exporte no modo CDJ/XDJ.",
    "E_RB_UNSUPPORTED_VARIANT": "Este pendrive não é uma exportação que o programa reconhece.",
    "E_USB_READONLY_UNVERIFIED": "Não confirmei somente leitura. A fonte não entra no set.",
    "E_USB_REMOVED": "O pendrive saiu. A fonte fica desconhecida até voltar.",
    "E_DISK_LOW": "Pouco espaço no aparelho. A música que já está tocando segue.",
    "E_AUDIO_XRUN": "O áudio falhou um trecho. Isso não é um número de desempenho.",
    "E_ENGINE_CRASH": "O mixer parou. Nada volta a tocar sozinho.",
    "E_PARSER_TIMEOUT": "A leitura do pendrive demorou demais e foi interrompida.",
    "E_UPGRADE_FAILED": "A atualização falhou. O pendrive de música não é alterado.",
    "_UNKNOWN": "Algo falhou. Abra STATUS para o código, sem detalhe interno.",
}

_EN = {
    "E_POWER_BUDGET": "Power may be short. Check the supply before trusting the set.",
    "E_DDJ_NOT_FOUND": "The DDJ-400 is not visible. The software keeps waiting for the cable.",
    "E_AUDIO_4CH_MISSING": "Master and headphones are not split. Cue is not safe.",
    "E_RB_EXPORT_NOT_FOUND": "The legacy rekordbox export is missing. Export in CDJ/XDJ mode.",
    "E_RB_UNSUPPORTED_VARIANT": "This drive is not an export the software recognizes.",
    "E_USB_READONLY_UNVERIFIED": "Read-only was not confirmed. The source stays out of the set.",
    "E_USB_REMOVED": "The drive was removed. The source stays unknown until it returns.",
    "E_DISK_LOW": "The device is low on space. Audio already playing continues.",
    "E_AUDIO_XRUN": "Audio missed a slice. This is not a performance figure.",
    "E_ENGINE_CRASH": "The mixer stopped. Nothing starts playing by itself.",
    "E_PARSER_TIMEOUT": "Reading the drive took too long and was stopped.",
    "E_UPGRADE_FAILED": "The update failed. The music drive is not modified.",
    "_UNKNOWN": "Something failed. Open STATUS for the code, without an internal trace.",
}


def message(code: str, locale: str = "pt-BR") -> str:
    table = _PT if locale == "pt-BR" else _EN
    text = table.get(code, table["_UNKNOWN"])
    if "Traceback" in text or "traceback" in text:
        raise RuntimeError("mensagem vazou rastreio")
    return text
