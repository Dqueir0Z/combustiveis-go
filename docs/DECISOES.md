# Decisões técnicas

Cada decisão importante do combustiveis-go, com o motivo e a alternativa descartada. A especificação está em [SPEC.md](../SPEC.md).

## Dados e limpeza (M1)

**Inspecionar os arquivos reais antes de escrever o parser.**
A spec se apoiava em duas hipóteses: a chave natural e a precedência entre fontes. A inspeção em SQL ([docs/inspecao](inspecao/README.md)) confirmou a chave (zero conflitos em 4 arquivos) e mostrou que a precedência ainda não dá para validar. Também revelou o BOM, o CRLF, aspas com `;` dentro e o nome do arquivo dentro do zip em CP437.
*Descartado:* escrever o parser a partir da documentação. Os detalhes que quebram parser não estão documentados.

**Tabela crua com tudo `text` na inspeção.**
Tudo entra, nada quebra a carga, e a investigação é em SQL. Os tipos certos só aparecem no modelo final, depois da limpeza.
*Descartado:* tipar já na carga crua. O `6,29` com vírgula derrubaria o `COPY` na primeira linha.

**Leitura por linha no nível dos bytes, com UTF-8 estrito por linha.**
Uma linha com byte inválido, aspas quebradas ou número errado de colunas vira `PARSE_INVALIDO`, e o resto do arquivo segue. O `n_registro` e o conteúdo bruto saem exatos. Isso só é seguro porque a inspeção não achou nenhum campo multilinha. Se aparecer um, ele vira rejeição visível, e não erro silencioso.
*Descartado:* um único `CSVParser` sobre o arquivo inteiro. Um erro de codificação no meio derrubaria tudo, ou seria substituído em silêncio.

**Colunas mapeadas pelo nome do cabeçalho; coluna obrigatória ausente ou repetida aborta.**
Falha rápida quando o layout muda, sem ler a coluna errada.
*Descartado:* mapear pela posição. Uma coluna nova no meio deslocaria tudo sem erro.

**`Limpador` como função pura, com relógio injetado.**
O mesmo registro sempre dá o mesmo resultado, e cada regra da seção 6 tem teste com linha real da ANP. A ordem é: ilegível → fora do escopo (descarte, sem ser erro) → validações.
*Descartado:* validar antes de filtrar o escopo. Um CNPJ ruim em SP contaria como rejeição de GO.

## Carga (M2 e M3)

**Um job por arquivo, identificado por fonte + sha256.**
O mesmo arquivo dá a mesma instância do Spring Batch. A pré-checagem responde "sem novidade" sem lançar o job, e um arquivo novo gera um hash novo. Reprocessar de propósito exige `--reprocessar`.
*Descartado:* identificar a carga pela data. A ANP republica sem mudar o nome do arquivo.

**Publicação em SQL puro, numa transação só (step 3).**
Conflitos, duplicatas, dimensões e upsert com precedência são operações sobre conjuntos de linhas, e o banco faz isso numa transação atômica. Se qualquer coisa falha, nada é publicado.
*Descartado:* um segundo chunk em Java linha a linha. Seriam milhares de idas ao banco e uma transação difícil de manter atômica.

**Conflito antes de duplicata.**
Com A, A e B na mesma chave, as três linhas vão para a quarentena como `CONFLITO`. Assim, nenhuma linha conta em duas categorias, e a reconciliação fecha.

**Precedência explícita no `ON CONFLICT ... WHERE`.**
Prioridade maior vence (semestral = 2, ultimas4 = 1). Na mesma prioridade, vence a execução mais nova. Prioridade menor nunca sobrescreve. A mesma regra classifica cada linha antes do upsert, para contar `inseridos`, `atualizados` e `sem_mudanca`.

**Lidas e descartadas vêm do checkpoint, não dos contadores do Spring Batch.**
O teste de reinício mostrou que, no Batch 6, o `readCount` inclui os itens de um chunk que voltou no rollback: um arquivo de 5 registros contou 7. O reader e o processador gravam os próprios contadores no `ExecutionContext`, que só é persistido no commit do chunk, junto com o staging. A trava de reconciliação é que pegou o problema: a publicação foi recusada, em vez de publicar um número errado.
*Descartado:* corrigir a soma dos `readCount`. Isso dependeria de detalhes internos que mudaram de uma versão para outra.

**O `preparar` grava o id no contexto do job na hora, e só reusa o id se a execução existir no banco.**
A gravação imediata cobre a queda entre o commit do `preparar` e o momento em que o Batch salvaria o contexto. Mas o teste com um trigger que derruba o commit mostrou que essa gravação **não volta** com o rollback: o `INSERT` sumia e o id ficava. Por isso, num reinício depois de uma queda do processo, o id do contexto só é reusado se a linha de `execucao` existir. Senão, cria uma execução de verdade. (Uma falha **no commit** do `preparar` deixa o job `UNKNOWN`, como no caso do chunk abaixo, e aí o caminho é o `--reprocessar`.) A revisão do Codex levantou a dúvida, e o teste resolveu.

**Falha no commit de um chunk: o Batch recusa retomar, e a carga pede `--reprocessar`.**
Quando o commit de um chunk falha, o Batch não consegue gravar os metadados do step e marca o job como `UNKNOWN`. Ele recusa a retomada de propósito, porque não sabe em que ponto parou. O teste confirmou que o checkpoint persistido não adianta. A CLI transforma a recusa numa instrução: rode com `--reprocessar`, que relê o arquivo inteiro numa instância nova.
*Descartado:* forçar a retomada marcando o job como abandonado ou mexendo nos metadados. Seria trocar uma garantia do framework por um palpite.

**Lock consultivo do PostgreSQL para um executor por vez.**
Vale entre processos e máquinas, e o banco o libera sozinho se o processo morrer.
*Descartado:* um arquivo `.lock` no disco. Só vale na mesma máquina e fica para trás quando o processo cai.

**Falhas de teste provocadas de fora: writer que falha uma vez, triggers temporários (inclusive adiados para o commit).**
As falhas acontecem de verdade no meio da transação, sem caminho de teste dentro do código de produção.

**Desempenho: medir antes de otimizar.**
A carga histórica levou 36 minutos. Em vez de chutar a causa, a medição usou o JFR, o profiler da JVM, e o log de consultas lentas do PostgreSQL. O resultado: a CPU ficava parada, esperando o banco. Havia commits demais (chunk de 1.000 → 10.000) e uma CTE recalculada por linha (`MATERIALIZED` + `ANALYZE` no staging). O tempo caiu para 1 min 43 s.
*Descartado:* `synchronous_commit=off`. Seria mais rápido, mas uma queda poderia perder commits já confirmados, e isso quebraria a garantia do checkpoint.

## Modelo e views (M2 e M4)

**Estrela com chaves substitutas nas dimensões e a chave natural como `UNIQUE` no fato.**
O `id` é o que o banco usa para ligar as tabelas. O `UNIQUE (revenda_id, produto_id, data)` impede que o mesmo preço entre duas vezes.
*Descartado:* só o `id`. A carga semanal duplicaria 3 das 4 semanas a cada execução.

**`dim_revenda` como SCD tipo 1, com o município e a bandeira observados no fato.**
Para as perguntas do painel, só o cadastro atual importa. O que muda com o tempo e importa (bandeira, município) fica gravado em cada preço. Um cadastro de coleta mais antiga nunca sobrescreve um mais novo.

**Preço representativo numa view base (`vw_preco_posto_semana`), sem arredondamento intermediário.**
A regra "média do posto na semana ISO" existe num lugar só. Arredondar a média antes de dividir pode inverter o "etanol compensa" na borda de 0,70 (exemplo da revisão do Codex, coberto por teste). Só os valores finais são arredondados.

**Variação semanal sobre a interseção dos postos e só contra a semana imediatamente anterior.**
Sem isso, a média sobe só porque um posto caro entrou na amostra. `inicio_semana - 7` atravessa a virada do ano ISO sem caso especial.

**Aviso de lacunas calculado sobre o fato inteiro, na publicação.**
Uma lacuna é "semana sem nenhum dado no banco", e o aviso já diz qual semestral carregar.
*Descartado:* checar só o período do arquivo. Isso nunca acharia o buraco entre duas cargas.

## Stack

**Spring Boot 4.1.1 / Spring Batch 6.0**, e não o 3.5 da spec original: o 3.5 perdeu o suporte gratuito em 30/06/2026. Os conceitos são os mesmos (Job, Step, chunk, JobRepository), mas as APIs mudaram.

**Metadados do Batch por migração Flyway (schema `batch`)**, com o DDL exato do JAR da versão em uso. O schema fica versionado e revisável.
*Descartado:* `initialize-schema=always`.
