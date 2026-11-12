#!/bin/bash
BASE="https://ghproxy.net/https://raw.githubusercontent.com/PC2005-cloud/dsh-pet/main"
DEST="/storage/emulated/0/AndroidPEProjects/dsh-pet/src_repo"
mkdir -p "$DEST"
while IFS= read -r p; do
  [ -z "$p" ] && continue
  out="$DEST/$p"
  mkdir -p "$(dirname "$out")"
  if [ -s "$out" ]; then continue; fi
  code=$(curl -sS -m 60 --retry 3 --retry-delay 2 -o "$out" -w '%{http_code}' "$BASE/$p")
  if [ "$code" != "200" ]; then echo "FAIL $code $p"; rm -f "$out"; fi
done < files.txt
echo DONE
