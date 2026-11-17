"""Gera o ZIP Windows com dependências Python puras e sem dados de usuário."""
from pathlib import Path
import platform
import subprocess
import sys
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile

REDE = Path(__file__).resolve().parent
DIST = REDE.parent / "android" / "dist"
CODIGO = (
    "Abrir-Conector.bat", "iniciar_conector.py", "conector.py", "servidor.py",
    "seguranca.py", "pareamento.py", "contas.py", "coleta.py", "README.md",
)
REDE_WEB = ("index.html", "estilo.css", "app.js", "motor3d.js", "personagens.js")


def incluir_runtime(zipado):
    if sys.platform != "win32" or platform.machine().lower() != "amd64":
        raise SystemExit("Gere o pacote com uma instalação oficial Python x64 no Windows.")
    raiz = Path(sys.base_prefix)
    # Lista de distribuição do Python oficial. Nunca copie site-packages do PC.
    for nome in ("python.exe", "pythonw.exe", "python3.dll", "LICENSE.txt"):
        zipado.write(raiz / nome, "runtime/" + nome)
    for binario in [*raiz.glob("python3??.dll"), *raiz.glob("vcruntime*.dll")]:
        zipado.write(binario, "runtime/" + binario.name)
    ignorar = {"__pycache__", "site-packages", "test", "tests", "ensurepip", "turtledemo"}
    for pasta in ("Lib", "DLLs", "tcl"):
        for arq in sorted((raiz / pasta).rglob("*")):
            relativo = arq.relative_to(raiz)
            if arq.is_file() and not ignorar.intersection(relativo.parts):
                zipado.write(arq, "runtime/" + relativo.as_posix())


def main():
    DIST.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as pasta:
        subprocess.run([sys.executable, "-m", "pip", "download", "--only-binary=:all:",
                        "--platform", "win_amd64", "--python-version", "3.13",
                        "--dest", pasta, "qrcode==8.2", "colorama==0.4.6", "networkx>=3.4,<4"], check=True)
        with ZipFile(DIST / "Conector-Rede-Neural-3.1.1-Windows.zip", "w", ZIP_DEFLATED) as zipado:
            incluir_runtime(zipado)
            for nome in CODIGO:
                zipado.write(REDE / nome, nome)
            for nome in REDE_WEB:
                zipado.write(REDE / "web" / nome, "web/" + nome)
            for pacote in sorted(Path(pasta).glob("*.whl")):
                zipado.write(pacote, "deps/" + pacote.name)


if __name__ == "__main__":
    main()
