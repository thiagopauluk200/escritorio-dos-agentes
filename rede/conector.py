"""Assistente gráfico para escolher o vault e conectar o Android."""
import json
import os
from pathlib import Path
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import webbrowser

import qrcode
import pareamento
import seguranca
import servidor

PORTA_WEB = 8772
TAMANHO_QR = 320


def vault_aberto():
    casa = Path.home()
    if os.name == "nt":
        base = Path(os.environ.get("APPDATA", str(casa)))
    elif os.sys.platform == "darwin":
        base = casa / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", str(casa / ".config")))
    try:
        dados = json.loads((base / "obsidian" / "obsidian.json").read_text(encoding="utf-8"))
        for vault in dados["vaults"].values():
            p = Path(vault["path"])
            if vault.get("open") and (p / ".obsidian").is_dir():
                return str(p)
    except (OSError, ValueError, KeyError, AttributeError, TypeError):
        pass
    return ""


class Conector:
    def __init__(self, janela):
        self.janela = janela
        self.pasta = Path(seguranca.pasta_dados())
        self.config = self.pasta / "conector.json"
        self.web = None
        self.lan = None
        self.pin = None
        self.codigo = ""
        self.conta_existe = None
        self.verificacao = None

        janela.title("Conector Rede Neural")
        janela.minsize(520, 720)
        janela.configure(background="#090e18")
        janela.protocol("WM_DELETE_WINDOW", self.fechar)
        estilo = ttk.Style(janela)
        estilo.theme_use("clam")
        estilo.configure("TFrame", background="#101925")
        estilo.configure("TLabel", background="#101925", foreground="#aebcd1", font=("Segoe UI", 10))
        estilo.configure("Titulo.TLabel", foreground="#edf3ff", font=("Segoe UI", 22, "bold"))
        estilo.configure("TEntry", fieldbackground="#18263d", foreground="#edf3ff",
                          insertcolor="#edf3ff", bordercolor="#586e96", padding=7)
        estilo.configure("TButton", background="#223452", foreground="#edf3ff",
                          bordercolor="#6683ae", padding=(12, 7), font=("Segoe UI", 10))
        estilo.map("TButton", background=[("active", "#345788")],
                   foreground=[("disabled", "#8190a8")])
        estilo.configure("Conectar.TButton", background="#adcaff", foreground="#142443")
        estilo.map("Conectar.TButton", background=[("active", "#c3daff")])
        base = ttk.Frame(janela, padding=22)
        base.pack(fill="both", expand=True)
        ttk.Label(base, text="Conector Rede Neural", style="Titulo.TLabel").pack(anchor="w")
        ttk.Label(base, text="Conecte o aplicativo ao vault e aos agentes deste computador.",
                  wraplength=480).pack(anchor="w", pady=(4, 14))
        try:
            anterior = json.loads(self.config.read_text(encoding="utf-8")).get("vault", "")
        except (OSError, ValueError, AttributeError):
            anterior = ""
        self.vault = tk.StringVar(value=anterior or vault_aberto())
        self.ip = tk.StringVar()
        ttk.Label(base, text="Pasta do vault Obsidian").pack(anchor="w")
        escolha = ttk.Frame(base)
        escolha.pack(fill="x", pady=4)
        ttk.Entry(escolha, textvariable=self.vault).pack(side="left", fill="x", expand=True)
        ttk.Button(escolha, text="Escolher pasta", command=self.escolher).pack(side="right", padx=(8, 0))
        opcoes = ttk.Frame(base)
        opcoes.pack(fill="x", pady=(5, 8))
        ttk.Label(opcoes, text="IPv4 do PC (opcional)").pack(side="left")
        ttk.Entry(opcoes, textvariable=self.ip, width=19).pack(side="left", padx=8)
        self.botao = ttk.Button(opcoes, text="Iniciar conexão", command=self.iniciar, style="Conectar.TButton")
        self.botao.pack(side="right")
        self.estado = tk.StringVar(value="Abra o Obsidian no vault que deseja visualizar.")
        ttk.Label(base, textvariable=self.estado, wraplength=470).pack(anchor="w", pady=(6, 10))
        self.qr = tk.Canvas(base, width=TAMANHO_QR, height=TAMANHO_QR, highlightthickness=0, background="#101925")
        self.qr.pack()
        botoes = ttk.Frame(base)
        botoes.pack(pady=10)
        self.copiar = ttk.Button(botoes, text="Copiar código", command=lambda: self.clipboard(self.codigo), state="disabled")
        self.copiar.pack(side="left", padx=3)
        self.painel = ttk.Button(botoes, text="Abrir painel web", command=self.abrir_web, state="disabled")
        self.painel.pack(side="left", padx=3)
        self.chave = ttk.Button(botoes, text="Copiar chave web", command=self.copiar_chave, state="disabled")
        self.chave.pack(side="left", padx=3)
        self.endereco_web = tk.StringVar()
        ttk.Label(base, textvariable=self.endereco_web, wraplength=470).pack(anchor="w", pady=(0, 6))
        ttk.Label(base, wraplength=470, text="Se o firewall perguntar, permita o acesso apenas em redes privadas. "
                  "Use Claude Code e Codex nas contas habituais neste computador.").pack(anchor="w")
        if anterior and Path(anterior).is_dir():
            janela.after(100, self.iniciar)

    def escolher(self):
        pasta = filedialog.askdirectory(parent=self.janela, initialdir=self.vault.get() or str(Path.home()))
        if pasta:
            self.vault.set(pasta)

    def iniciar(self):
        if self.lan:
            self.parar()
            return
        valor = self.vault.get().strip()
        if not valor or not Path(valor).is_dir():
            messagebox.showerror("Escolha o vault", "Selecione a pasta que você abre no Obsidian.", parent=self.janela)
            return
        cfg = servidor.argumentos(["--vault", valor, "--porta", str(PORTA_WEB)])
        cfg.ip = self.ip.get().strip() or None
        try:
            try:
                self.web = servidor.criar_servidor(cfg)
            except OSError:
                cfg.porta = 0
                self.web = servidor.criar_servidor(cfg)
            self.lan, self.pin = servidor.criar_lan(cfg, self.web.estado, str(self.pasta))
            pareamento.salvar_privado(self.config, json.dumps({"vault": str(Path(valor).resolve())}).encode())
        except (OSError, ValueError, ImportError) as erro:
            for s in (self.lan, self.web):
                if s:
                    s.server_close()
            self.lan = self.web = None
            messagebox.showerror("Falha ao iniciar",
                                 "Confira a pasta, a rede Wi-Fi e se outro Conector já está aberto.\n\n" + str(erro),
                                 parent=self.janela)
            return
        for s in (self.web, self.lan):
            threading.Thread(target=s.serve_forever, daemon=True).start()
        self.botao.configure(text="Parar conexão", command=self.parar)
        self.endereco_web.set(f"Painel neste PC · {self.url_web()}")
        for b in (self.copiar, self.painel, self.chave):
            b.configure(state="normal")
        self.conta_existe = None
        self.atualizar_codigo()

    def atualizar_codigo(self):
        self.verificacao = None
        if not self.lan:
            return
        existe = self.lan.contas.existe()
        if existe != self.conta_existe:
            self.conta_existe = existe
            self.codigo = servidor.codigo_lan(self.lan, self.pin)
            matriz = qrcode.QRCode(border=5)
            matriz.add_data(self.codigo)
            modulos = matriz.get_matrix()
            lado = TAMANHO_QR // len(modulos)
            inicio = (TAMANHO_QR - lado * len(modulos)) // 2
            self.qr.delete("all")
            self.qr.create_rectangle(0, 0, TAMANHO_QR, TAMANHO_QR, fill="white", width=0)
            for y, linha in enumerate(modulos):
                for x, escuro in enumerate(linha):
                    if escuro:
                        a, b = inicio + x * lado, inicio + y * lado
                        self.qr.create_rectangle(a, b, a + lado, b + lado, fill="black", width=0)
        ip, porta = self.lan.server_address
        passo = "Entre com sua conta local." if existe else "Depois, crie sua conta local."
        self.estado.set(f"Conexão ativa em {ip}:{porta}. Leia o QR no Android. {passo}")
        self.verificacao = self.janela.after(2000, self.atualizar_codigo)

    def clipboard(self, texto):
        self.janela.clipboard_clear()
        self.janela.clipboard_append(texto)

    def copiar_chave(self):
        if self.web:
            self.clipboard(self.web.estado.guarda.token)

    def abrir_web(self):
        if self.web:
            webbrowser.open(self.url_web())

    def url_web(self):
        return f"http://127.0.0.1:{self.web.server_port}/"

    def parar(self):
        if self.verificacao is not None:
            self.janela.after_cancel(self.verificacao)
            self.verificacao = None
        for s in (self.lan, self.web):
            if s:
                s.shutdown()
                s.server_close()
        self.lan = self.web = None
        self.codigo = ""
        self.endereco_web.set("")
        self.qr.delete("all")
        self.estado.set("Conexão encerrada.")
        self.botao.configure(text="Iniciar conexão", command=self.iniciar)
        for b in (self.copiar, self.painel, self.chave):
            b.configure(state="disabled")

    def fechar(self):
        self.parar()
        self.janela.destroy()


if __name__ == "__main__":
    janela = tk.Tk()
    Conector(janela)
    janela.mainloop()
