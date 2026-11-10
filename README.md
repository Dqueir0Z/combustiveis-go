# combustiveis-go

Pipeline de dados com os preços de combustíveis de Goiás (2022 em diante), a partir dos dados abertos da ANP:
**ANP → Spring Batch → PostgreSQL (modelo estrela) → Power BI**.

A especificação completa, com metas, regras de limpeza, modelo de dados e marcos, está em [SPEC.md](SPEC.md).

> **Estado:** M4 concluído. O histórico de GO desde jan/2022 e a atualização semanal carregam no modelo estrela, e as três perguntas têm views testadas. Falta o M5: o painel no Power BI ([roteiro](docs/POWERBI.md)) e a medição de desempenho (ver "Carga histórica").

Documentos: [SPEC.md](SPEC.md) (especificação), [docs/DECISOES.md](docs/DECISOES.md) (decisões e alternativas descartadas), [docs/inspecao](docs/inspecao/README.md) (inspeção dos dados da ANP) e [docs/POWERBI.md](docs/POWERBI.md) (painel).

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

3. Carregue os dados. A aplicação aplica as migrações, baixa da ANP, carrega e encerra:

   ```bash
   ./gradlew bootRun --args="--atualizar"
   ```

   | Argumento | O que faz |
   |---|---|
   | `--atualizar` | atualização semanal: as duas fontes `ultimas4` (gasolina/etanol e diesel/GNV) |
   | `--carga=FONTE` | uma fonte: `ultimas4:gasolina-etanol`, `ultimas4:diesel-gnv` ou `semestral:2026-1` |
   | `--historico=2022-1:2026-1` | os semestrais do intervalo, em ordem |
   | `--reprocessar` | carrega de novo, mesmo que o arquivo (sha256) já tenha sido publicado |
   | `--rebaixar` | baixa o semestral de novo, ignorando o cache em `dados/brutos/` |

   Repetir uma carga do mesmo arquivo responde "sem novidade" sem tocar no banco. O código de saída é 0 quando tudo deu certo, 1 quando alguma fonte falhou, 2 quando outra carga já está rodando e 3 quando um argumento é inválido. A conexão pode ser trocada pelas variáveis `DB_URL`, `DB_USER` e `DB_PASSWORD`.

### Banco de dados

- As migrações ficam em `src/main/resources/db/migration` e são aplicadas pelo Flyway na subida.
- Os metadados do Spring Batch ficam no schema `batch` (migração `V1`), separados das tabelas do pipeline.

## Inspeção dos dados da ANP

Feita antes de escrever o parser. Os detalhes, os números e o SQL reproduzível estão em [docs/inspecao](docs/inspecao/README.md). Em resumo:

- **Formato:** UTF-8 **com BOM**, `CRLF`, `;` como separador, vírgula decimal, 16 colunas e nenhum campo multilinha. O servidor da ANP recusa `HEAD` (`403`).
- **Volume:** o semestral 2026-1 tem 72 MB e 422.418 registros (16.573 de GO). O `ultimas4` de gasolina e etanol tem cerca de 8 MB e 47 mil registros (cerca de 1.900 de GO).
- **Chave natural `(cnpj, produto, data_coleta)`:** zero conflitos nos quatro arquivos. O semestral tem 6 duplicatas idênticas.
- **Precedência semestral > ultimas4:** ainda não é validável, porque não há período em comum entre os arquivos disponíveis. Dois snapshots do `ultimas4` vieram idênticos nas semanas em comum.

## Carga histórica

`java -Xmx256m -jar build/libs/combustiveis-go-0.0.1-SNAPSHOT.jar --historico=2022-1:2026-1`, numa máquina com Windows 11, Docker Desktop e PostgreSQL 16 no Compose:

| | |
|---|---|
| Semestrais | 9 (2022-1 a 2026-1), todos publicados |
| Registros lidos | 3.946.187 (Brasil) |
| Preços de GO no fato | 154.337, de 03/01/2022 a 30/10/2026 (com a atualização semanal) |
| Rejeitados | 0. A reconciliação fechou em todas as execuções |
| Heap máximo | 256 MB (leitura em streaming) |
| Tempo | **1 min 43 s** com os zips em cache (7 a 11 s por semestral), mais ~100 s de downloads numa carga do zero |

**Desempenho, medido e não chutado.** A primeira medição deu 36 minutos, com tempos erráticos. O profiler (JFR) e o log de consultas lentas do PostgreSQL mostraram duas causas, nenhuma delas onde se esperava:

1. **Commits demais.** Com chunk de 1.000, eram 422 commits por semestral, cada um esperando o fsync do PostgreSQL. No Docker do Windows, essa espera chegou a 4,35 s. Com chunk de 10.000, são 43 commits.
2. **Um plano de consulta ruim.** A consulta de conflitos chegou a levar 215 s: o PostgreSQL embutia a CTE e, com o staging recém-carregado e sem estatísticas, recalculava o agrupamento para cada linha. A correção foi `MATERIALIZED` mais `ANALYZE stg_preco` antes da publicação, e a publicação caiu de 208 s para ~2 s.

**Lacunas:** a carga avisa 14 semanas sem dados. São a 2022-S38 e as semanas 28 a 40 de 2026, que o semestral 2026-2 vai cobrir quando a ANP publicá-lo.

## Testes

103 testes (JUnit + Testcontainers). Eles cobrem uma regra de limpeza por caso, com linhas reais da ANP, além de idempotência, precedência entre fontes, conflito atravessando chunks, reinício depois de falha, rollback da publicação, falha no commit, executor único e as views com resultado calculado à mão.

