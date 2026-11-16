#!/data/data/com.termux/files/usr/bin/bash
# Liga a Central e o tunel da Cloudflare. Rode: bash ~/escritorio/iniciar.sh
cd "$(dirname "$0")" || exit 1
PORTA=8765
bash ./parar.sh >/dev/null 2>&1
command -v termux-wake-lock >/dev/null && termux-wake-lock
mkdir -p dados
nohup python hub.py --porta $PORTA --host 0.0.0.0 > dados/central.log 2>&1 &
echo $! > dados/central.pid
sleep 2
if ! kill -0 "$(cat dados/central.pid)" 2>/dev/null; then echo "ERRO: a Central nao iniciou:"; cat dados/central.log; exit 1; fi
IP=$(ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print $2}' | cut -d/ -f1)
echo "Central ligada."
echo "  Rede local (use este endereco na ponte do PC): http://${IP:-IP-DO-CELULAR}:$PORTA"

if ! command -v cloudflared >/dev/null; then echo "  Tunel: cloudflared nao instalado (so rede local)."; exit 0; fi
if [ -f "$HOME/.cloudflared/config.yml" ]; then
  nohup cloudflared tunnel run > dados/tunel.log 2>&1 &
  echo $! > dados/tunel.pid
  echo "  Tunel fixo ligado (endereco: o hostname do seu ~/.cloudflared/config.yml)."
else
  nohup cloudflared tunnel --no-autoupdate --url http://127.0.0.1:$PORTA > dados/tunel.log 2>&1 &
  echo $! > dados/tunel.pid
  for i in $(seq 1 30); do
    URL=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' dados/tunel.log | head -1)
    [ -n "$URL" ] && break; sleep 1
  done
  if [ -n "$URL" ]; then
    echo "  Fora de casa (tunel temporario, muda a cada reinicio): $URL"
    echo "$URL" > dados/url.txt
  else
    echo "  O tunel nao respondeu em 30 s. Veja: tail dados/tunel.log"
  fi
fi
