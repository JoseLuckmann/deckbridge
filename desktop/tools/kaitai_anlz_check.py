#!/usr/bin/env python3
"""Lê um arquivo ANLZ (.DAT/.EXT/.2EX) com o parser gerado da especificação aberta (Kaitai, rekordbox_anlz.ksy do Crate
Digger) e imprime linhas TSV: S tag | B nº tempo tempo_ms | K tipo hot tempo_ms loop_ms status (PCOB) | X tipo hot tempo_ms
loop_ms comentário cor (PCO2).   python3 -I kaitai_anlz_check.py PARSERS_DIR arquivo.DAT
"""
import io
import sys

sys.path.insert(0, sys.argv[1])
from kaitaistruct import KaitaiStream  # noqa: E402
from rekordbox_anlz import RekordboxAnlz  # noqa: E402

anlz = RekordboxAnlz(KaitaiStream(io.BytesIO(open(sys.argv[2], "rb").read())))
for s in anlz.sections:
    tag = s.fourcc.name if hasattr(s.fourcc, "name") else str(s.fourcc)
    print("S", tag, s.len_tag, sep="\t")
    name = tag
    body = s.body
    if name == "beat_grid":
        for b in body.beats:
            print("B", b.beat_number, b.tempo, b.time, sep="\t")
    elif name == "cues":
        for c in body.cues:
            print("K", body.type.name if hasattr(body.type, "name") else body.type, c.hot_cue, c.time, c.loop_time, c.status.name if hasattr(c.status, "name") else c.status, c.type.name if hasattr(c.type, "name") else c.type, sep="\t")
    elif name == "cues_2":
        for c in body.cues:
            text = c.comment if isinstance(c.comment, str) else ""
            print("X", body.type.name if hasattr(body.type, "name") else body.type, c.hot_cue, c.time, c.loop_time, text.rstrip("\x00"), "%02x%02x%02x" % (c.color_red, c.color_green, c.color_blue), c.type.name if hasattr(c.type, "name") else c.type, sep="\t")
