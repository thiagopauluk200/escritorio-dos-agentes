# Escritório dos Agentes

O painel web local e o aplicativo Android são a **Rede Neural**, o visualizador 3D do vault compartilhado do Obsidian. A versão de distribuição usa uma conta local e pareamento por QR na rede privada. Veja [como instalar e conectar](rede/README.md) e [como publicar uma versão no GitHub](rede/PUBLICAR.md).

## Serviços e ferramentas

- **Rede Neural:** mostra o grafo de notas, as chamadas MCP dos agentes e o volume de tráfego observado.
- **Central de tarefas:** organiza agentes, tarefas e notas em uma interface web adaptada para uso no celular.
- **Ponte com o computador:** conecta a Central aos agentes locais, executa tarefas escolhidas e devolve resultados e estados.
- **Importação de conversas:** transforma um arquivo de exportação do ChatGPT em notas Markdown.
- **Proteções operacionais:** restringe a ponte a pastas configuradas localmente, evita execuções concorrentes e trata operações repetidas de forma idempotente.

## Arquitetura da Rede Neural

```text
Android ─── HTTPS privado ──┐                  ┌── Índice do Obsidian
                            ├── Conector PC ───┤
Navegador ─── loopback ──────┘                  └── Registros Claude Code e Codex
```

A Central de tarefas e a ponte têm entradas próprias. A Rede Neural consulta as notas e os registros locais em modo de leitura.

## Tecnologias

- Python para os serviços locais, a Central e as ferramentas de integração.
- Kotlin para o aplicativo Android e o transporte HTTPS autenticado.
- HTML, CSS e JavaScript para as interfaces web.
- SQLite e Room para armazenamento local, com sincronização orientada a operações idempotentes.

## Estrutura

| Caminho | Conteúdo |
|---|---|
| `rede/` | Servidor e interface da Rede Neural |
| `server.py`, `index.html` | Painel local anterior |
| `ponte.py` | Comunicação entre a Central e os agentes no computador |
| `celular/` | Serviço da Central e sua interface web |
| `android/` | Aplicativo Android nativo |
| `importar_chatgpt.py` | Importação de conversas exportadas para Markdown |
| `tests/` | Testes automatizados do backend e da concorrência |

## Execução local

Requisitos: Python 3.10 ou superior. Para métricas completas da rede, instale as dependências:

```bash
python -m pip install -r requirements.txt
```

Inicie o painel apontando para um vault Obsidian existente:

```bash
python rede/servidor.py --abrir
```

O painel fica disponível em `http://127.0.0.1:8770/`. Use `--vault` para indicar outra pasta.

Para compilar o aplicativo, abra `android/` no Android Studio com o JDK e o SDK Android instalados. A configuração local do SDK fica em `android/local.properties`, que não deve ser versionada.

## Configuração e dados locais

Endereços de serviço, caminhos do computador, credenciais, logs, bancos de dados, exportações e artefatos de build são específicos de cada instalação e ficam fora do repositório. Configure a Central e a ponte no ambiente local antes de usá-las; não publique arquivos de configuração preenchidos.

## Segurança

- Não inclua senhas, tokens, chaves, endereços privados ou dados reais em commits, issues ou capturas de tela.
- Arquivos locais de configuração e dados operacionais estão cobertos pelo `.gitignore`.
- Use HTTPS ao acessar a Central remotamente e restrinja o acesso à rede conforme seu ambiente.
- Revise alterações e arquivos novos antes de publicar uma atualização.

## Status

Projeto experimental de portfólio. A integração depende de ferramentas instaladas e configuradas pelo usuário; a publicação do código não disponibiliza uma Central hospedada nem credenciais de acesso.
