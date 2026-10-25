#!/usr/bin/env python3
"""Lê um export.pdb com o parser gerado da especificação aberta (Kaitai, rekordbox_pdb.ksy do Crate Digger) e imprime
um resumo em JSON. É o terceiro leitor, independente do nosso código Kotlin e C++.

    python3 -I kaitai_check.py PARSERS_DIR export.pdb [--tsv]

PARSERS_DIR é a pasta com rekordbox_pdb.py gerado por setup_kaitai.sh. O resumo tem faixas, playlists e entradas, com os
nomes lidos das strings do banco; as linhas "não presentes" são ignoradas pelo próprio parser.
"""
import json
import sys

sys.path.insert(0, sys.argv[1])
from rekordbox_pdb import RekordboxPdb  # noqa: E402


def text(s):
    if s is None:
        return ""
    body = s.body
    return getattr(body, "text", "") or ""


def rows(table):
    page = table.first_page.body
    seen = set()
    while page is not None and page.page_index not in seen:
        seen.add(page.page_index)
        if page.is_data_page:
            for group in page.row_groups:
                for ref in group.rows:
                    if ref.present:
                        yield ref.body
        if page.page_index == table.last_page.index:
            break
        nxt = page.next_page.index
        if nxt == page.page_index or nxt * pdb.len_page >= len(raw):
            break
        page = page.next_page.body


raw = open(sys.argv[2], "rb").read()
pdb = RekordboxPdb(False, __import__("kaitaistruct").KaitaiStream(__import__("io").BytesIO(raw)))
out = {"page_len": pdb.len_page, "tables": {}, "tracks": [], "playlists": [], "entries": [], "artists": {}, "albums": {}, "genres": {}, "keys": {}}
for t in pdb.tables:
    kind = t.type.name if hasattr(t.type, "name") else str(t.type)
    body = list(rows(t))
    out["tables"][kind] = len(body)
    for r in body:
        if kind == "tracks":
            out["tracks"].append({"id": r.id, "title": text(r.title), "artist_id": r.artist_id, "album_id": r.album_id, "genre_id": r.genre_id, "key_id": r.key_id,
                                  "tempo": r.tempo, "year": r.year, "rating": r.rating, "comment": text(r.comment), "file_path": text(r.file_path), "analyze_path": text(r.analyze_path)})
        elif kind == "artists":
            out["artists"][str(r.id)] = text(r.name)
        elif kind == "albums":
            out["albums"][str(r.id)] = text(r.name)
        elif kind == "genres":
            out["genres"][str(r.id)] = text(r.name)
        elif kind == "keys":
            out["keys"][str(r.id)] = text(r.name)
        elif kind == "playlist_tree":
            out["playlists"].append({"id": r.id, "parent": r.parent_id, "name": text(r.name), "folder": bool(r.is_folder), "sort": r.sort_order})
        elif kind == "playlist_entries":
            out["entries"].append({"playlist": r.playlist_id, "track": r.track_id, "index": r.entry_index})
out["tracks"].sort(key=lambda x: x["id"])
out["playlists"].sort(key=lambda x: x["id"])
out["entries"].sort(key=lambda x: (x["playlist"], x["index"], x["track"]))
if "--tsv" in sys.argv:
    def clean(v):
        return str(v).replace("\t", " ").replace("\n", " ")

    def name(table, i):
        return out[table].get(str(i), "") if i else ""

    for t in out["tracks"]:
        print("\t".join(["T", clean(t["id"]), clean(t["title"]), clean(name("artists", t["artist_id"])), clean(name("albums", t["album_id"])),
                         clean(name("genres", t["genre_id"])), clean(name("keys", t["key_id"])), clean(t["rating"]), clean(t["comment"]), clean(t["file_path"])]))
    for p in out["playlists"]:
        print("\t".join(["P", clean(p["id"]), clean(p["parent"]), clean(p["name"]), "1" if p["folder"] else "0", clean(p["sort"])]))
    for e in out["entries"]:
        print("\t".join(["E", clean(e["playlist"]), clean(e["index"]), clean(e["track"])]))
    for kind in ("artists", "albums", "genres", "keys"):
        print("\t".join(["N", kind, clean(len(out[kind]))]))
else:
    json.dump(out, sys.stdout, ensure_ascii=False, indent=1, sort_keys=True)
    print()
