#!/data/data/com.termux/files/usr/bin/bash
# Instala a Central do Escritorio no Termux. Rode: bash ~/escritorio/instalar.sh
cd "$(dirname "$0")" || exit 1
echo "== 1/4 Pacotes (python, matplotlib, cloudflared) =="
pkg update -y >/dev/null 2>&1
pkg install -y python matplotlib || { echo "ERRO: nao consegui instalar python/matplotlib."; exit 1; }
python -m pip install --user packaging cycler fonttools pyparsing python-dateutil || { echo "ERRO: dependencias Python do matplotlib."; exit 1; }
pkg install -y cloudflared || echo "AVISO: 'pkg install cloudflared' falhou. O painel funciona na rede local; veja LEIA-ME-CELULAR.md para o tunel."
chmod +x iniciar.sh parar.sh 2>/dev/null
chmod 700 . 

echo "== 2/4 Senha do painel e chave da ponte =="
if [ ! -f dados/config.json ]; then
  python hub.py --mostrar-chave | sed '$d'   # primeira execucao: gera e MOSTRA a senha e a chave (anote agora)
else
  echo "Ja configurado. Nova senha: python hub.py --nova-senha | Chave da ponte: python hub.py --mostrar-chave"
fi

echo "== 3/4 Iniciar junto com o aparelho (precisa do app Termux:Boot) =="
mkdir -p ~/.termux/boot
printf '#!/data/data/com.termux/files/usr/bin/bash\ntermux-wake-lock\nbash %s/iniciar.sh\n' "$(pwd)" > ~/.termux/boot/escritorio.sh
chmod +x ~/.termux/boot/escritorio.sh

echo "== 4/4 Ligando =="
bash ./iniciar.sh
