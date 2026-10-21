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

Será preenchida no M1 (tamanho, número de registros, codificação, tempo, chave natural e comparação semestral × últimas 4 semanas).
