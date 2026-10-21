# combustiveis-go — Especificação v0.1

Pipeline de dados com os preços de combustíveis de Goiás, a partir dos dados abertos da ANP.
Portfólio para vaga de Dados/Backend Jr e demonstração do serviço de limpeza de dados com painel (1B do PLANO).

Autor: Diogo Queiroz. Rascunho: 2026-10-21. Status: **em revisão** (crítica do Codex antes do código).

## 1. Problema

Os dados da ANP são públicos, mas vêm brutos: um CSV por semestre com o Brasil inteiro, separado por `;`, vírgula decimal, espaços sobrando, acentuação inconsistente e colunas vazias. Responder "onde o etanol compensa em Goiás esta semana?" exige baixar, limpar, modelar e consultar esses dados. O projeto mostra esse caminho de ponta a ponta, com qualidade de dados medida.

## 2. Metas

1. Carregar o histórico de GO de **jan/2022 até o último dado disponível**, mais uma atualização semanal **idempotente**: rodar duas vezes não duplica nada.
2. Limpeza com regras explícitas e testadas. Cada linha rejeitada fica registrada com o motivo.
3. Modelo dimensional (estrela) no PostgreSQL.
4. Cinco perguntas de negócio respondidas em SQL (views) e um dashboard Power BI em cima das views.
5. Reproduzível: `docker compose up -d` e um único comando para carregar. Testes verdes no CI.

## 3. Fora de escopo (v1)

Outros estados, GLP e GNV, API REST, deploy em nuvem, agendamento automático (a atualização semanal é um comando manual), interface web própria.

## 4. Fonte de dados (verificada em 2026-10-21)

Página: https://www.gov.br/anp/pt-br/centrais-de-conteudo/dados-abertos/serie-historica-de-precos-de-combustiveis

| Arquivo | Uso | URL |
|---|---|---|
| Combustíveis automotivos por semestre | carga histórica | `.../arquivos/shpc/dsas/ca/ca-AAAA-0S.zip` (S = 1 ou 2) |
| Últimas 4 semanas, gasolina e etanol | atualização semanal | `.../arquivos/shpc/qus/ultimas-4-semanas-gasolina-etanol.csv` |
| Últimas 4 semanas, diesel e GNV | atualização semanal | `.../arquivos/shpc/qus/ultimas-4-semanas-diesel-gnv.csv` |

Cabeçalho observado:
`Regiao - Sigla;Estado - Sigla;Municipio;Revenda;CNPJ da Revenda;Nome da Rua;Numero Rua;Complemento;Bairro;Cep;Produto;Data da Coleta;Valor de Venda;Valor de Compra;Unidade de Medida;Bandeira`

Fatos observados: ~47 mil linhas no arquivo de 4 semanas (Brasil), 1.872 de GO, 666 de Goiânia. O CNPJ vem com espaço à esquerda, o valor tem vírgula decimal, "Valor de Compra" vem vazio, a data é `dd/MM/yyyy` e a acentuação é mista ("COMÉRCIO" e "GOIANIA"). A página foi atualizada em 20/10, mas os dados mais recentes eram de 22–24/09 (há defasagem). A codificação dos arquivos semestrais **não foi verificada** (pode ser Latin-1).

## 5. Arquitetura

```
ANP (zip/csv) ──download──► dados/brutos/ (cache, gitignored)
                               │
              Spring Batch job "carga-precos"
   step 1 (chunk): ler CSV ► filtrar GO + produtos ► limpar/validar ► stg_preco | rejeito
   step 2 (tasklet SQL): stg_preco ► dimensões (upsert) ► fato_preco (upsert)
                               │
                PostgreSQL 16 (Docker) ── views vw_* ──► Power BI Desktop
```

- **Stack:** Java 21, Spring Boot 3 + Spring Batch 5, Gradle (wrapper), PostgreSQL 16 em Docker Compose, Flyway, JUnit 5, Testcontainers, GitHub Actions.
- **ELT parcial:** o Java faz parsing e validação linha a linha. As dimensões e o fato são montados em SQL (step 2), para o SQL ficar visível e testável.
- **Parâmetros do job:** `fonte=semestral:2024-1` ou `fonte=ultimas4`. Download com cache: se o arquivo já existe em `dados/brutos/` com o mesmo tamanho, não baixa de novo.
- **Falha rápida:** se o cabeçalho for diferente do esperado, o job aborta com mensagem clara (a ANP pode mudar o layout).

## 6. Regras de limpeza

| Campo | Regra |
|---|---|
| todos | `trim`; string vazia vira `null` |
| Estado | manter só `GO` |
| Produto | manter `GASOLINA`, `GASOLINA ADITIVADA`, `ETANOL`, `DIESEL`, `DIESEL S10`; os outros são descartados (não é erro) |
| CNPJ | só dígitos; exatamente 14; senão a linha é rejeitada (`CNPJ_INVALIDO`) |
| Município | maiúsculas e sem acento (`GOIÂNIA` → `GOIANIA`) |
| Data da Coleta | `dd/MM/yyyy`; inválida ou no futuro → rejeitada (`DATA_INVALIDA`) |
| Valor de Venda | vírgula para ponto, `BigDecimal`; ≤ 0 ou acima do limite por produto (ex.: R$ 15/L) → rejeitada (`PRECO_FORA_FAIXA`) |
| Valor de Compra | vazio → `null` (não é erro) |
| Bandeira | maiúsculas e sem acento; vazia → `SEM BANDEIRA` |
| Duplicata | mesma chave natural no mesmo arquivo → fica a primeira, as outras são contadas como `DUPLICADA` |

Chave natural do fato: `(cnpj, produto, data_coleta)`.

## 7. Modelo de dados

- `stg_preco`: linhas limpas do arquivo da execução atual, com `execucao_id`.
- `rejeito(execucao_id, linha_arquivo, motivo, conteudo_bruto)`
- `execucao(id, fonte, inicio, fim, lidas, aceitas, descartadas, rejeitadas)`: métricas de qualidade.
- `dim_data(data PK, ano, mes, semana_iso, dia_semana)`
- `dim_municipio(id, nome, uf)` · `dim_produto(id, nome, unidade)` · `dim_bandeira(id, nome)`
- `dim_revenda(id, cnpj UNIQUE, razao_social, bairro, cep)`: a razão social mais recente vence.
- `fato_preco(data, revenda_id, municipio_id, produto_id, bandeira_id, valor_venda, valor_compra)`, `UNIQUE(revenda_id, produto_id, data)`, carregado com `ON CONFLICT DO UPDATE`.
- A bandeira fica no fato (e não na revenda) porque um posto pode trocar de bandeira ao longo do tempo.

## 8. Perguntas de negócio (views)

1. `vw_preco_semanal`: preço médio semanal por produto em GO e em Goiânia.
2. `vw_etanol_compensa`: por município, na última semana, a razão etanol/gasolina e se ela fica ≤ 0,70.
3. `vw_variacao_semanal`: variação semana a semana por produto e município, com `LAG`.
4. `vw_bandeira_relativa`: quanto cada bandeira cobra acima ou abaixo da média **do próprio município** (controla a localização).
5. `vw_dispersao`: os postos mais baratos e mais caros por município (`NTILE`/percentis), só em municípios com pelo menos N postos.

## 9. Testes

- **Unitários (limpeza):** um caso por regra da seção 6, usando linhas reais da ANP (inclusive o CNPJ com espaço).
- **Integração (Testcontainers):** carregar uma amostra pequena duas vezes deve dar as mesmas contagens (idempotência). Rejeitos registrados com o motivo certo.
- **SQL:** cada view roda sobre uma fixture com o resultado esperado calculado à mão.
- **CI:** `./gradlew test` no GitHub Actions a cada push.

## 10. Marcos (PRs pequenos, ≤ ~400 linhas)

| Marco | Entrega | Quem escreve |
|---|---|---|
| M0 | Esqueleto: Gradle, Compose, Flyway vazio, CI, README inicial | Claude (boilerplate) |
| M1 | Parser e regras de limpeza com testes unitários | **Diogo** (Claude orienta e revisa) |
| M2 | Job Spring Batch step 1 (staging + rejeitos + execução) | **Diogo** com guia |
| M3 | Migrações do modelo estrela + step 2 em SQL + teste de idempotência | **Diogo** |
| M4 | As 5 views + testes de SQL | **Diogo** |
| M5 | Dashboard Power BI, README com diagrama, prints, post no LinkedIn | **Diogo** |

Revisão: cada PR passa por Claude e Codex (só leitura).

## 11. Pronto quando

- Com o repositório clonado, `docker compose up -d` + `./gradlew bootRun --args='--fonte=semestral:2024-1'` carregam os dados sem erro, e rodar de novo não muda as contagens.
- `execucao` mostra lidas, aceitas, descartadas e rejeitadas de cada carga.
- As 5 views respondem as perguntas e o dashboard usa só as views.
- CI verde. O README explica arquitetura, decisões e qualidade de dados, com prints do painel.

## 12. Riscos e perguntas em aberto

- O arquivo semestral do Brasil inteiro é grande: ler em streaming e filtrar GO cedo. A memória não pode crescer com o tamanho do arquivo.
- Codificação dos arquivos semestrais (UTF-8 ou Latin-1): verificar no M1.
- A ANP pode mudar o layout: falhar rápido no cabeçalho.
- Defasagem dos dados: o dashboard mostra "dados até DD/MM", não "hoje".
- O conector PostgreSQL do Power BI Desktop pode precisar de driver: verificar no M5.
- Spring Batch traz tabelas de metadados próprias: elas ficam no mesmo banco, num schema separado (`batch`).
