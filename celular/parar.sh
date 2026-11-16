#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1
for f in dados/central.pid dados/tunel.pid; do
  [ -f "$f" ] && kill "$(cat "$f")" 2>/dev/null; rm -f "$f"
done
echo "Central e tunel parados."
