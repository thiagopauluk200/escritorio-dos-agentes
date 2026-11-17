# Publicar a Rede Neural no GitHub

## Arquivos da versão 3.1.1

Na pasta `android/dist`, use:

| Arquivo | Uso |
|---|---|
| `RedeNeural-3.1.1.apk` | Aplicativo Android assinado para instalação. |
| `Conector-Rede-Neural-3.1.1-Windows.zip` | Conector portátil para Windows x64, com Python oficial e dependências incluídas. |
| `RedeNeural-3.1.1-SHA256SUMS.txt` | Hashes dos dois downloads. |

O Android usa o pacote `br.escritorio.central`. A versão de depuração usa outro identificador. No Windows, extraia o ZIP antes de abrir `Abrir-Conector.bat`.

## 1. Registre o aplicativo Android quando aplicável

Para distribuir para o público no Brasil e em outros mercados com verificação Android, crie ou acesse sua conta no [Android Developer Console](https://developer.android.com/distribute/console). Conclua a verificação de identidade, escolha distribuição completa e registre `br.escritorio.central` apresentando o APK assinado desta versão. Essa etapa é feita pelo titular da conta. Dependendo do dispositivo, aplicativos ainda não registrados passam pelo fluxo avançado ou por ADB. [Guia oficial de verificação](https://developer.android.com/developer-verification/guides?hl=pt-BR)

## 2. Suba o código da versão

Abra o terminal na pasta do repositório `escritorio` e confira `git status --short`. Adicione as mudanças da Rede Neural, conferindo o diff antes de enviar:

```powershell
git add -- README.md rede/ tests/test_rede_acesso.py android/app/build.gradle.kts android/app/src/main/ android/app/src/debug/AndroidManifest.xml android/app/src/debug/res/xml/network_security_config.xml .gitignore
git diff --cached --stat
git diff --cached
git commit -m "Corrige o painel web e a leitura do QR"
git push origin HEAD
```

Chaves de assinatura, chaves do Obsidian, QR de pareamento, vaults e logs ficam fora do repositório. No Windows em que esta versão foi assinada, a chave fica em `%LOCALAPPDATA%\RedeNeural\distribuicao`. Faça backup privado dela e guarde a senha em um gerenciador. A mesma chave assina as próximas versões para os usuários poderem atualizar.

## 3. Crie a release

1. Abra o [repositório no GitHub](https://github.com/thiagopauluk200/escritorio-dos-agentes). Para downloads públicos, use um repositório público.
2. Entre em **Releases → Draft a new release**. Crie a tag `v3.1.1` no commit da versão. Use o título **Rede Neural 3.1.1**.
3. Em **Assets**, anexe os três arquivos da tabela. Cole a descrição abaixo e publique.
4. Abra a página da release em janela anônima. Confira que os downloads aparecem e que os hashes conferem com o arquivo SHA-256.

[Documentação de releases do GitHub](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)

### Texto para a release

> Visualize em 3D o grafo de notas e a atividade local do Claude Code e do Codex.
>
> A versão 3.1 traz navegação inferior flutuante, controles translúcidos e um novo visual para os painéis e o acesso.
>
> Abra o Conector no Windows e escolha o vault. Instale o APK no Android, conecte os dois aparelhos à mesma rede e leia o QR exibido pelo Conector. Crie a conta local no primeiro acesso.
>
> [Guia de instalação e integração](https://github.com/thiagopauluk200/escritorio-dos-agentes/blob/v3.1.1/rede/README.md)

## Repetir a compilação

Em um ambiente de desenvolvimento com SDK Android e JDK configurados, execute:

```powershell
.\android\gradlew.bat -p android :app:assembleRelease :app:testDebugUnitTest
```

Use o APK sem assinatura em `android/app/build/outputs/apk/release/app-release-unsigned.apk` com o `apksigner` do SDK Android. Informe um arquivo privado de senha ao assinador por `--ks-pass file:CAMINHO` e valide com `apksigner verify --verbose --print-certs APK`. Incremente `versionCode` antes de distribuir uma versão seguinte.

Para reconstruir o ZIP, execute o comando abaixo em uma instalação oficial do Python x64 para Windows. O script inclui os arquivos de runtime do Python, a licença e as dependências públicas:

```powershell
python rede/empacotar_windows.py
```
