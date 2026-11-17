"""Pareamento por código e certificado HTTPS exclusivo de cada computador."""
import base64
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile

REDES = tuple(ipaddress.ip_network(r) for r in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.1/32"))


def ip_local(valor):
    ip = ipaddress.IPv4Address(valor)
    if not any(ip in rede for rede in REDES):
        raise ValueError("Escolha o IPv4 privado do computador (10.x, 172.16–31.x ou 192.168.x).")
    return str(ip)


def descobrir_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("1.1.1.1", 443))  # Seleciona uma interface pela tabela de rotas, sem enviar dados.
        return ip_local(s.getsockname()[0])


def salvar_privado(caminho, dados):
    fd, temporario = tempfile.mkstemp(dir=caminho.parent)
    try:
        with os.fdopen(fd, "wb") as f:
            f.write(dados)
        os.replace(temporario, caminho)
    finally:
        if os.path.exists(temporario):
            os.unlink(temporario)


def _pem(tipo, dados):
    corpo = base64.encodebytes(dados).decode("ascii")
    return f"-----BEGIN {tipo}-----\n{corpo}-----END {tipo}-----\n".encode("ascii")


def _der(tipo, dados):
    n = len(dados)
    if n < 128:
        tamanho = bytes([n])
    else:
        bruto = n.to_bytes((n.bit_length() + 7) // 8, "big")
        tamanho = bytes([128 + len(bruto)]) + bruto
    return bytes([tipo]) + tamanho + dados


def _inteiro_der(valor):
    valor = valor.lstrip(b"\0") or b"\0"
    return _der(2, b"\0" + valor if valor[0] & 0x80 else valor)


def _identidade_windows():
    # A chave é criada pelo provedor criptográfico do Windows. O subprocesso
    # devolve os parâmetros por um pipe privado para gravar PEM no perfil.
    script = r"""
$ErrorActionPreference = 'Stop'
$r = [Security.Cryptography.RSACryptoServiceProvider]::new(3072)
$r.PersistKeyInCsp = $false
$req = [Security.Cryptography.X509Certificates.CertificateRequest]::new(
    'CN=Rede Neural', $r, [Security.Cryptography.HashAlgorithmName]::SHA256,
    [Security.Cryptography.RSASignaturePadding]::Pkcs1)
$cert = $req.CreateSelfSigned([DateTimeOffset]::UtcNow.AddDays(-1), [DateTimeOffset]::UtcNow.AddDays(3650))
[Convert]::ToBase64String($cert.RawData)
$p = $r.ExportParameters($true)
foreach ($v in @($p.Modulus, $p.Exponent, $p.D, $p.P, $p.Q, $p.DP, $p.DQ, $p.InverseQ)) {
    [Convert]::ToBase64String($v)
}
$cert.Dispose()
$r.Dispose()
"""
    powershell = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    try:
        saida = subprocess.run([str(powershell), "-NoProfile", "-NonInteractive", "-Command", script],
                               capture_output=True, check=True, timeout=20, creationflags=subprocess.CREATE_NO_WINDOW)
        cert, *chave = [base64.b64decode(linha, validate=True) for linha in saida.stdout.splitlines()]
        if len(chave) != 8:
            raise ValueError("estrutura inválida")
    except (OSError, subprocess.SubprocessError, ValueError):
        raise OSError("Não foi possível criar o certificado HTTPS pelo Windows.") from None
    pkcs1 = _der(0x30, b"".join(_inteiro_der(i) for i in [b"\0", *chave]))
    return _pem("CERTIFICATE", cert) + _pem("RSA PRIVATE KEY", pkcs1)


def _identidade_posix():
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    chave = ec.generate_private_key(ec.SECP256R1())
    nome = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Rede Neural")])
    agora = datetime.now(timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(nome).issuer_name(nome)
            .public_key(chave.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(agora - timedelta(days=1)).not_valid_after(agora + timedelta(days=3650))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(chave, hashes.SHA256()))
    return cert.public_bytes(serialization.Encoding.PEM) + chave.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())


def tls(diretorio):
    pem = Path(diretorio) / "identidade.pem"
    if not pem.exists():
        salvar_privado(pem, _identidade_windows() if os.name == "nt" else _identidade_posix())
    texto = pem.read_text(encoding="ascii").split("-----END CERTIFICATE-----", 1)[0] + "-----END CERTIFICATE-----\n"
    fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(texto)).hexdigest()
    contexto = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    contexto.minimum_version = ssl.TLSVersion.TLSv1_2
    contexto.load_cert_chain(pem)
    return contexto, fingerprint


def codigo(ip, porta, fingerprint, convite):
    conteudo = json.dumps({"h": ip, "p": porta, "f": fingerprint, "c": convite}, separators=(",", ":"))
    return "RN1." + base64.urlsafe_b64encode(conteudo.encode()).decode().rstrip("=")
