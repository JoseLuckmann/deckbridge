#!/bin/sh
# Gera o parser Python do export.pdb a partir da especificação aberta do Crate Digger (commit fixo) e instala o
# runtime do Kaitai. Nada disto entra no repositório: a especificação é EPL-2.0/MPL-2.0/LGPL-3.0 e fica numa pasta
# de trabalho. Uso: sh setup_kaitai.sh PASTA_DE_TRABALHO   (imprime a pasta dos parsers no fim)
set -eu
WORK=${1:?pasta de trabalho}
CRATE_SHA=7c9d5358d8a3ce663f566380f2dc79ab1393201e
KAITAI_COMPILER=0.11.0
KAITAI_RUNTIME=0.11
mkdir -p "$WORK" && cd "$WORK"
if [ ! -d crate-digger ]; then
    git clone --quiet https://github.com/Deep-Symmetry/crate-digger.git
fi
git -C crate-digger checkout --quiet "$CRATE_SHA"
[ -f package.json ] || npm init -y >/dev/null
npm install --silent "kaitai-struct-compiler@$KAITAI_COMPILER" js-yaml
python3 -m pip install --quiet "kaitaistruct==$KAITAI_RUNTIME"
cat > compile.js <<'JS'
const fs = require('fs'); const yaml = require('js-yaml'); const compiler = require('kaitai-struct-compiler');
(async () => {
  for (const name of ['rekordbox_pdb', 'rekordbox_anlz']) {
    const ksy = yaml.load(fs.readFileSync(`crate-digger/src/main/kaitai/${name}.ksy`, 'utf8'));
    const files = await compiler.compile('python', ksy, null, false);
    for (const [f, c] of Object.entries(files)) fs.writeFileSync(f, c);
  }
})().catch(e => { console.error(e); process.exit(1); });
JS
node compile.js
echo "$WORK"
