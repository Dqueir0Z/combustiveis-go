# combustiveis-go — Especificação v0.2

Pipeline de dados com os preços de combustíveis de Goiás, a partir dos dados abertos da ANP.
Portfólio para vaga de Dados/Backend Jr e demonstração do serviço de limpeza de dados com painel (1B do PLANO).

Autor: Diogo Queiroz. v0.1: 2026-10-21. v0.2: 2026-10-21, depois de 2 rodadas de crítica do Codex (ver "Histórico" no fim).

## 1. Problema

Os dados da ANP são públicos, mas vêm brutos: um arquivo por semestre com o Brasil inteiro, separado por `;`, vírgula decimal, espaços sobrando, acentuação inconsistente e colunas vazias. Responder "onde o etanol compensa em Goiás?" exige baixar, limpar, modelar e consultar esses dados, deixando claro de que semana eles são. O projeto mostra esse caminho de ponta a ponta, com qualidade de dados medida e reconciliada.

## 2. Metas (v1)

1. Carregar o histórico de GO de **jan/2022 até o último dado disponível**, mais uma atualização semanal **idempotente**: repetir uma carga não muda nem valores nem contagens.
2. Limpeza com regras explícitas e testadas. Todo registro lido termina em exatamente uma categoria (seção 6).
3. Modelo dimensional (estrela) no PostgreSQL, com proveniência de cada fato.
4. **Três** perguntas de negócio respondidas em SQL (views) e um dashboard Power BI em cima delas.
5. Reproduzível: com o repositório clonado, `docker compose up -d` e um único comando para carregar. Testes verdes no CI.

## 3. Fora de escopo (v1)

Outros estados, GLP e GNV, API REST, deploy em nuvem, agendamento automático, retry automático de falhas transitórias, busca automática de lacunas, interface web própria. As views de bandeira e de dispersão ficam para a **v1.1**.

## 4. Fonte de dados (verificada em 2026-10-21)

Página: https://www.gov.br/anp/pt-br/centrais-de-conteudo/dados-abertos/serie-historica-de-precos-de-combustiveis

| Fonte | Tipo | URL | Natureza |
|---|---|---|---|
| `semestral:AAAA-S` | carga histórica | `.../arquivos/shpc/dsas/ca/ca-AAAA-0S.zip` | consolidada |
| `ultimas4:gasolina-etanol` | atualização semanal | `.../arquivos/shpc/qus/ultimas-4-semanas-gasolina-etanol.csv` | preliminar, muda toda semana |
| `ultimas4:diesel-gnv` | atualização semanal | `.../arquivos/shpc/qus/ultimas-4-semanas-diesel-gnv.csv` | preliminar, muda toda semana |

**Cada arquivo é uma fonte independente**, com identidade própria (sha256), execução própria e a mesma prioridade. Um job processa um único arquivo. A atualização semanal é um comando que roda as duas fontes uma depois da outra. Se só uma tiver mudado, a outra termina como "sem novidade".

Cabeçalho observado: `Regiao - Sigla;Estado - Sigla;Municipio;Revenda;CNPJ da Revenda;Nome da Rua;Numero Rua;Complemento;Bairro;Cep;Produto;Data da Coleta;Valor de Venda;Valor de Compra;Unidade de Medida;Bandeira`

Fatos observados: ~47 mil linhas no arquivo de 4 semanas (Brasil), 1.872 de GO e 666 de Goiânia. O CNPJ vem com espaço à esquerda, o valor tem vírgula decimal, "Valor de Compra" vem vazio, a data é `dd/MM/yyyy` e a acentuação é mista. Em 20/10 a página foi atualizada, mas os dados mais recentes eram de 22–24/09 (há defasagem). A **codificação, o tamanho e a chave dos arquivos semestrais não foram verificados**: isso é o primeiro passo do M1.

## 5. Arquitetura

```
CLI ─► pré-checagem (sha256 já publicado? → "sem novidade", não lança o job)
        │
        ▼ download para dados/brutos/<sha256>.<ext> (imutável durante a execução)
Spring Batch job "carga-precos"   parâmetros identificadores: fonte + sha256 (+ reprocesso=<uuid>)
  step 0  preparar: cria a execução lógica (execucao.id), limpa o staging dessa execução
  step 1  chunk (1000, configurável): ler CSV ► filtrar GO+produtos ► limpar/validar ► stg_preco | rejeito
  step 2  tasklet (UMA transação): conflitos ► rejeito; dimensões; fato (upsert com precedência); execucao.fim
        │
PostgreSQL 16 (Docker) ── views vw_* ──► Power BI Desktop
```

- **Stack:** Java 21, Spring Boot **4.1.1** (com o Spring Batch 6.0 que ela gerencia), Gradle wrapper, PostgreSQL 16 em Docker Compose, Flyway, Apache Commons CSV, JUnit (Jupiter 6, gerenciado pelo Boot), Testcontainers 2, GitHub Actions. O 3.5.x saiu porque perdeu o suporte gratuito em 30/06/2026 (ver "Histórico").
- **Metadados do Batch:** criados por migração Flyway no schema `batch`, a partir do script da versão efetiva do Spring Batch. `spring.batch.jdbc.initialize-schema=never`.
- **Identidade da carga:** a CLI calcula o sha256 do arquivo baixado e consulta `execucao`. Se o mesmo sha256 já foi publicado, termina com "sem novidade" **sem lançar o job**. Um arquivo novo gera um sha256 novo, e com ele uma nova instância. Um **reinício** usa os mesmos parâmetros e retoma do checkpoint. Para **reprocessar de propósito**, passe `--reprocessar`, que gera um parâmetro `reprocesso=<uuid>`.
- **Uma fonte = um arquivo = um sha256.** A fonte fica entre os parâmetros identificadores, então os dois arquivos `ultimas4:*` nunca se confundem.
- **Cache:** as fontes `ultimas4:*` são sempre baixadas de novo. O semestral fica em cache pelo sha256. Como o hash não detecta uma revisão no servidor da ANP, existe `--rebaixar` para forçar o download de novo.
- **Um executor por vez** (vale para qualquer instância): lock consultivo do PostgreSQL (`pg_try_advisory_lock`) adquirido pela CLI. Se não conseguir o lock, aborta com mensagem.
- **Execução lógica estável:** `execucao.id` é criado no step 0 e guardado no ExecutionContext do job. Num reinício, o step 0 já está COMPLETED e não roda de novo, então o staging dos chunks já confirmados é preservado.
- **Falha rápida de layout:** colunas mapeadas por nome. Se faltar uma coluna obrigatória, o job aborta. Colunas extras são ignoradas e registradas no log.
- **Codificação por fonte**, com `CodingErrorAction.REPORT`: caractere inválido gera `PARSE_INVALIDO`, nunca substituição silenciosa.

## 6. Regras de limpeza e reconciliação

Todo registro lido cai em **exatamente uma** categoria:

`lidas = aceitas + descartadas + duplicadas + rejeitadas`

- **descartadas:** fora do escopo, sem ser erro (outro estado, outro produto).
- **duplicadas:** cópia idêntica de outro registro do mesmo arquivo. Fica uma, as outras contam aqui.
- **rejeitadas:** vão para `rejeito` com um motivo. Um conflito conta só como rejeitado, nunca também como duplicado.

| Campo | Regra | Motivo da rejeição |
|---|---|---|
| registro | não dá para fazer parse (aspas, nº de colunas, codificação) | `PARSE_INVALIDO` |
| todos | `trim`; string vazia vira `null` | — |
| Estado | só `GO` (os outros são descartados) | — |
| Produto | `GASOLINA`, `GASOLINA ADITIVADA`, `ETANOL`, `DIESEL`, `DIESEL S10` (os outros são descartados) | — |
| CNPJ | só dígitos, exatamente 14 | `CNPJ_INVALIDO` |
| Município | maiúsculas e sem acento | — |
| Data da Coleta | `dd/MM/yyyy`, não futura | `DATA_INVALIDA` |
| Valor de Venda | número com vírgula decimal | `PRECO_NAO_NUMERICO` |
| Valor de Venda | gasolina/aditivada: 2,00–12,00; etanol: 1,50–10,00; diesel/S10: 2,00–12,00 (R$/L) | `PRECO_FORA_FAIXA` |
| Unidade | `R$ / litro` para os 5 produtos | `UNIDADE_INCOMPATIVEL` |
| Valor de Compra | vazio vira `null` (não é erro) | — |
| Bandeira | maiúsculas e sem acento; vazia vira `NAO INFORMADA` (diferente de `BRANCA`, que é um valor explícito da ANP) | — |
| Chave | mesma chave natural com valores **divergentes** no mesmo arquivo: **todas** as ocorrências são rejeitadas | `CONFLITO` |

- A chave natural é `(cnpj, produto, data_coleta)`, as três obrigatórias. É uma **hipótese** a validar na inspeção do M1, nas duas fontes.
- O conflito é detectado no step 2, sobre o staging inteiro da execução, antes de publicar. Por isso um conflito que atravessa chunks também é pego.
- `rejeito(execucao_id, arquivo, n_registro, motivo, conteudo_bruto)`.
- A publicação conta separadamente `inseridos`, `atualizados` e `sem_mudanca`. Um upsert que não muda o conteúdo conta como `sem_mudanca`, mesmo que atualize a proveniência.

## 7. Modelo de dados

- `execucao(id, fonte, url, sha256, obtido_em, periodo_min, periodo_max, inicio, fim, status, lidas, aceitas, descartadas, duplicadas, rejeitadas, inseridos, atualizados, sem_mudanca)`
- `stg_preco(execucao_id, n_registro, ...campos limpos)`: isolado por `execucao_id`. Todo o SQL do step 2 filtra pela execução.
- `dim_data(data PK, ano, mes, semana_iso, ano_iso, dia_semana)`
- `dim_municipio(id, uf, nome_normalizado, UNIQUE(uf, nome_normalizado))`
- `dim_produto(id, nome UNIQUE, unidade)` · `dim_bandeira(id, nome UNIQUE)`
- `dim_revenda(id, cnpj UNIQUE, razao_social, bairro, cep, visto_em)`: cadastro atual. É escolhido **um candidato por CNPJ** antes do upsert: a maior data_coleta e, no empate, o menor `n_registro`. Só substitui o cadastro existente se `visto_em` for ≥ ao que já está lá.
- `fato_preco(data, revenda_id, municipio_id, produto_id, bandeira_id, valor_venda numeric(7,3), valor_compra numeric(7,3), fonte_prioridade, execucao_id, UNIQUE(revenda_id, produto_id, data))`. Todas as FKs são `NOT NULL`. O município e a bandeira **observados** ficam no fato, porque um posto pode trocar de bandeira.
- **Precedência no upsert**, explícita:
  1. `fonte_prioridade`: semestral (consolidado) = 2, `ultimas4:*` (preliminar) = 1. A prioridade maior vence.
  2. Com a mesma prioridade, vence a execução publicada por último (`execucao_id` maior).
  3. Prioridade menor **nunca** sobrescreve prioridade maior.
  É uma hipótese ("consolidado é mais correto que preliminar"), validada no M1 comparando as semanas que aparecem nas duas fontes. Se a inspeção mostrar o contrário, a regra muda aqui antes do código.
- A publicação (dimensões, fato, rejeitos de conflito e `execucao.fim`/`status`) acontece numa **única transação**.

## 8. Perguntas de negócio (views da v1)

Regras comuns:
- **Preço representativo** = a média do posto na semana ISO (segunda a domingo). Todas as views agregam a partir dele e mostram `n_postos`.
- **Última semana** = a última semana ISO que já terminou no calendário e tem dados. **Limitação declarada:** uma semana encerrada não prova que a ANP já publicou tudo dela. O painel mostra "dados até DD/MM", a data de ingestão e a cobertura (postos por produto e município).

1. `vw_preco_semanal`: preço semanal por produto em dois níveis **sobrepostos** e rotulados, GO e Goiânia, com `n_postos`.
2. `vw_etanol_compensa`:
   - **por posto:** postos com gasolina comum e etanol na **mesma semana** (aproximação rotulada como "mesma semana", não "mesma data"), com a razão etanol/gasolina e se ela fica ≤ limite;
   - **agregado municipal:** **mediana das razões por posto**, com `n_postos`. O limite padrão de 0,70 é um parâmetro (depende do consumo do carro).
3. `vw_variacao_semanal`: variação por produto e município **só entre a semana e a imediatamente anterior**, com as **duas médias recalculadas sobre a interseção** dos postos presentes nas duas semanas. Mostra `n_postos_comuns`.

Gaps: o pipeline detecta lacunas pela **cobertura das cargas** (semanas ISO esperadas × semanas com dados) e grava o aviso em `execucao`, com a instrução de carregar o semestral que falta. A recuperação é manual na v1.

v1.1: `vw_bandeira_relativa` (comparando dentro de município + produto + semana, sem alegação causal) e `vw_dispersao` (`percentile_cont`, mínimo de 5 postos distintos, como corte operacional).

## 9. Testes

- **Unitários (limpeza):** um caso por regra da seção 6, com linhas reais da ANP.
- **Integração (Testcontainers):**
  - repetir a mesma carga não muda nem valores nem contagens, e a pré-checagem devolve "sem novidade";
  - uma instância já concluída não é relançada;
  - um dado corrigido mais novo, de mesma prioridade, vence;
  - um histórico de prioridade menor **baixado depois** da correção não sobrescreve;
  - um conflito vai para a quarentena, inclusive quando atravessa chunks, e nenhuma das ocorrências é publicada;
  - uma falha entre chunks seguida de reinício não duplica o staging;
  - uma falha na publicação faz rollback total;
  - codificação ou layout inválido falham do jeito esperado;
  - a equação de reconciliação fecha;
  - dois executores ao mesmo tempo: o segundo aborta.
- **SQL:** cada view roda sobre uma fixture com o resultado calculado à mão, incluindo semana ausente, virada de ano ISO e interseção de postos.
- **CI:** `./gradlew test` no GitHub Actions a cada push.

## 10. Marcos (fatia vertical primeiro; ~400 linhas por PR é orientação, não limite)

| Marco | Entrega | Quem escreve |
|---|---|---|
| M0 | Esqueleto: Gradle, Compose, Flyway (incl. metadados do Batch), CI, README inicial | Claude (boilerplate) |
| M1 | **Inspeção real** das duas fontes (tamanho, registros, codificação, tempo, chave, comparação semestral × ultimas4) registrada no README. Parser e regras de limpeza com testes unitários | **Diogo** (Claude orienta e revisa) |
| M2 | **Fatia vertical:** ultimas4 → staging → fato → `vw_etanol_compensa` → conexão do Power BI testada | **Diogo** com guia |
| M3 | Idempotência, pré-checagem, conflitos, precedência, reinício, lock, reconciliação + testes de integração | **Diogo** |
| M4 | Histórico 2022+ e as views 1 e 3 + testes de SQL | **Diogo** |
| M5 | README (arquitetura, decisões, qualidade, cobertura, tempo e memória medidos), `.pbix`, prints, post no LinkedIn | **Diogo** |

Revisão: cada PR passa por Claude e Codex (só leitura). O prazo de 2–3 semanas depende das horas reais por dia. Se apertar, as views 1 e 3 escorregam para a v1.1 antes de cortar teste.

## 11. Pronto quando

- Clone limpo → `docker compose up -d` → as migrações aplicam sem erro.
- Um comando documentado faz a **carga histórica completa** (2022+), e outro faz a atualização semanal.
- Repetir qualquer carga dá "sem novidade" ou `sem_mudanca`, com valores idênticos.
- `execucao` mostra a reconciliação fechada e a cobertura de cada carga.
- As 3 views respondem as perguntas e o dashboard usa só as views. O `.pbix` e a instrução de conexão e atualização estão no repositório.
- CI verde com todos os cenários da seção 9.
- O README traz o tempo e a memória da carga histórica, medidos uma vez numa máquina descrita.

## 12. Riscos que restam

- A chave natural e a precedência entre fontes são hipóteses: o M1 confirma ou muda as duas antes do M2.
- O conector PostgreSQL do Power BI Desktop pode precisar de driver: testado já no M2.
- O arquivo semestral pode ser grande: download para disco, leitura em streaming. Memória e tempo medidos no M1.
- O prazo depende das horas disponíveis.

## Histórico

- **v0.1 → v0.2** (crítica do Codex, thread `01a15ea9-2854-7c43-a9e9-61e1c621ad50`): identidade da carga por sha256 com pré-checagem; precedência explícita por prioridade de fonte; quarentena de conflitos; execução lógica estável; lock de executor único; reconciliação por categorias exclusivas; dimensões com chaves únicas e candidato único por CNPJ; semana ISO e cobertura; views por preço representativo do posto; marcos em fatia vertical; views 4 e 5 movidas para a v1.1; testes de proveniência, reinício e precedência.
- **v0.2 (ajuste final, rodada 3):** `ultimas4` dividido em duas fontes independentes, uma por arquivo, cada uma com o seu sha256. **Consenso Claude + Codex sobre o plano**, sujeito às validações do M1 (chave, codificação, precedência).
- **v0.2.1 (M0, 2026-10-21):** Spring Boot 3.5.x → **4.1.1**. O 3.5 perdeu o suporte gratuito em 30/06/2026 (endoflife.date) e o start.spring.io não gera mais projetos 3.5. O 4.0 perde o suporte em 31/12/2026, e o 4.1 vai até 31/07/2027. Consequência: Spring Batch 6.0 em vez de 5.2. Os conceitos (Job, Step, chunk, JobRepository) são os mesmos, mas as APIs mudam. Decisão do Diogo.
