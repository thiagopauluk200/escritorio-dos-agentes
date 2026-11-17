"""Carrega as dependências puras incluídas no ZIP de distribuição."""
from pathlib import Path
import sys

if __name__ == "__main__":
    pasta = Path(__file__).resolve().parent
    sys.path.insert(0, str(pasta))
    for pacote in sorted((pasta / "deps").glob("*.whl")):
        sys.path.insert(0, str(pacote))
    from conector import Conector, tk

    janela = tk.Tk()
    Conector(janela)
    janela.mainloop()
