# combustiveis-go

Pipeline de dados com os preços de combustíveis de Goiás (2022 em diante), a partir dos dados abertos da ANP:
**ANP → Spring Batch → PostgreSQL (modelo estrela) → Power BI**.

A especificação completa, com metas, regras de limpeza, modelo de dados e marcos, está em [SPEC.md](SPEC.md).

> **Estado:** M0 (esqueleto). Ainda não há carga de dados.

## Stack

Java 21 · Spring Boot 4.1.1 (Spring Batch 6.0) · PostgreSQL 16 · Flyway · Apache Commons CSV · JUnit · Testcontainers · Gradle · GitHub Actions

## Como rodar

Pré-requisitos: **Java 21** e **Docker** (com o Docker Desktop aberto, no Windows). Não precisa instalar Gradle: o projeto usa o wrapper (`gradlew`).

1. Suba o banco:

   ```bash
   docker compose up -d
   ```

   Isso sobe um PostgreSQL 16 em `localhost:5432` (banco, usuário e senha `combustiveis`, só para desenvolvimento local).

2. Rode os testes:

   ```bash
   ./gradlew test
   ```

   No Windows (PowerShell ou cmd), use `.\gradlew.bat test`. Os testes **não** usam o banco do Compose: o Testcontainers sobe um PostgreSQL descartável para cada execução.

3. Suba a aplicação (por enquanto ela só aplica as migrações e encerra):

   ```bash
   ./gradlew bootRun
   ```

   A conexão pode ser trocada pelas variáveis `DB_URL`, `DB_USER` e `DB_PASSWORD`.

### Banco de dados

- As migrações ficam em `src/main/resources/db/migration` e são aplicadas pelo Flyway na subida.
- Os metadados do Spring Batch ficam no schema `batch` (migração `V1`), separados das tabelas do pipeline.

## Inspeção dos dados da ANP

Feita antes de escrever o parser. Os detalhes, os números e o SQL reproduzível estão em [docs/inspecao](docs/inspecao/README.md). Em resumo:

- **Formato:** UTF-8 **com BOM**, `CRLF`, `;` como separador, vírgula decimal, 16 colunas e nenhum campo multilinha. O servidor da ANP recusa `HEAD` (`403`).
- **Volume:** o semestral 2026-1 tem 72 MB e 422.418 registros (16.573 de GO). O `ultimas4` de gasolina e etanol tem cerca de 8 MB e 47 mil registros (cerca de 1.900 de GO).
- **Chave natural `(cnpj, produto, data_coleta)`:** zero conflitos nos quatro arquivos. O semestral tem 6 duplicatas idênticas.
- **Precedência semestral > ultimas4:** ainda não é validável, porque não há período em comum entre os arquivos disponíveis. Dois snapshots do `ultimas4` vieram idênticos nas semanas em comum.
