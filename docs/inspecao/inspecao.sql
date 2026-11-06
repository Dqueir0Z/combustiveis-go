-- Inspeção do M1 (SPEC seção 10): chave natural e comparação entre fontes.
-- Pré-requisito: os CSVs copiados para /tmp do container (ver docs/inspecao/README.md).
-- Rodar: docker exec -i combustiveis-go-db psql -U combustiveis -d combustiveis < docs/inspecao/inspecao.sql
-- Tudo fica no schema "inspecao", separado do schema da aplicação.

\set ON_ERROR_STOP on
\timing on

DROP SCHEMA IF EXISTS inspecao CASCADE;
CREATE SCHEMA inspecao;
SET search_path TO inspecao;

-- Tabela crua: o arquivo exatamente como veio, tudo text (desenho do Diogo, M1).
CREATE TABLE anp_cru (
    regiao       text,
    estado       text,
    municipio    text,
    revenda      text,
    cnpj         text,
    nome_rua     text,
    num_rua      text,
    complemento  text,
    bairro       text,
    cep          text,
    produto      text,
    data_coleta  text,
    valor_venda  text,
    valor_compra text,
    un_medida    text,
    bandeira     text,
    arquivo      text
);

-- ENCODING 'UTF8' faz o COPY falhar se houver byte inválido: é a prova da codificação.
\echo '== carga'
COPY anp_cru (regiao, estado, municipio, revenda, cnpj, nome_rua, num_rua, complemento, bairro, cep, produto, data_coleta, valor_venda, valor_compra, un_medida, bandeira)
    FROM '/tmp/semestral-2026-01.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true, ENCODING 'UTF8');
UPDATE anp_cru SET arquivo = 'semestral-2026-01' WHERE arquivo IS NULL;

COPY anp_cru (regiao, estado, municipio, revenda, cnpj, nome_rua, num_rua, complemento, bairro, cep, produto, data_coleta, valor_venda, valor_compra, un_medida, bandeira)
    FROM '/tmp/ultimas4-ge-2026-10-21.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true, ENCODING 'UTF8');
UPDATE anp_cru SET arquivo = 'ultimas4-ge-2026-10-21' WHERE arquivo IS NULL;

COPY anp_cru (regiao, estado, municipio, revenda, cnpj, nome_rua, num_rua, complemento, bairro, cep, produto, data_coleta, valor_venda, valor_compra, un_medida, bandeira)
    FROM '/tmp/ultimas4-ge-2026-11-06.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true, ENCODING 'UTF8');
UPDATE anp_cru SET arquivo = 'ultimas4-ge-2026-11-06' WHERE arquivo IS NULL;

COPY anp_cru (regiao, estado, municipio, revenda, cnpj, nome_rua, num_rua, complemento, bairro, cep, produto, data_coleta, valor_venda, valor_compra, un_medida, bandeira)
    FROM '/tmp/ultimas4-dg-2026-11-06.csv' WITH (FORMAT csv, DELIMITER ';', HEADER true, ENCODING 'UTF8');
UPDATE anp_cru SET arquivo = 'ultimas4-dg-2026-11-06' WHERE arquivo IS NULL;

-- Visão com a chave já normalizada: CNPJ só com dígitos, data e valor convertidos.
CREATE VIEW anp AS
SELECT arquivo,
       estado,
       regexp_replace(cnpj, '\D', '', 'g')          AS cnpj,
       produto,
       to_date(data_coleta, 'DD/MM/YYYY')           AS data_coleta,
       replace(valor_venda, ',', '.')::numeric      AS valor_venda,
       municipio,
       nome_rua,
       bandeira
FROM anp_cru;

\echo '== registros por arquivo (Brasil e GO) e período'
SELECT arquivo,
       count(*)                                  AS registros,
       count(*) FILTER (WHERE estado = 'GO')     AS registros_go,
       min(data_coleta)                          AS de,
       max(data_coleta)                          AS ate
FROM anp
GROUP BY arquivo
ORDER BY arquivo;

\echo '== chave (cnpj, produto, data_coleta): grupos repetidos, duplicatas e conflitos por arquivo'
WITH grupos AS (
    SELECT arquivo, cnpj, produto, data_coleta,
           count(*)                    AS linhas,
           count(DISTINCT valor_venda) AS precos_diferentes
    FROM anp
    GROUP BY arquivo, cnpj, produto, data_coleta
)
SELECT arquivo,
       count(*)                                                       AS chaves,
       count(*) FILTER (WHERE linhas > 1)                             AS chaves_repetidas,
       count(*) FILTER (WHERE linhas > 1 AND precos_diferentes = 1)   AS duplicatas,
       count(*) FILTER (WHERE precos_diferentes > 1)                  AS conflitos,
       coalesce(sum(linhas) FILTER (WHERE precos_diferentes > 1), 0)  AS linhas_em_conflito
FROM grupos
GROUP BY arquivo
ORDER BY arquivo;

\echo '== o mesmo, só GO'
WITH grupos AS (
    SELECT arquivo, cnpj, produto, data_coleta,
           count(*)                    AS linhas,
           count(DISTINCT valor_venda) AS precos_diferentes
    FROM anp
    WHERE estado = 'GO'
    GROUP BY arquivo, cnpj, produto, data_coleta
)
SELECT arquivo,
       count(*)                                                       AS chaves,
       count(*) FILTER (WHERE linhas > 1 AND precos_diferentes = 1)   AS duplicatas,
       count(*) FILTER (WHERE precos_diferentes > 1)                  AS conflitos
FROM grupos
GROUP BY arquivo
ORDER BY arquivo;

\echo '== duplicatas: as linhas repetidas são cópias idênticas em todas as colunas?'
SELECT arquivo,
       count(*) AS grupos_de_linhas_identicas
FROM (
    SELECT arquivo
    FROM anp_cru
    GROUP BY arquivo, regiao, estado, municipio, revenda, cnpj, nome_rua, num_rua, complemento, bairro, cep,
             produto, data_coleta, valor_venda, valor_compra, un_medida, bandeira
    HAVING count(*) > 1
) t
GROUP BY arquivo
ORDER BY arquivo;

\echo '== exemplos de conflito (até 10)'
SELECT a.arquivo, a.cnpj, a.produto, a.data_coleta,
       string_agg(a.valor_venda::text || ' [' || a.bandeira || ' / ' || a.nome_rua || ']', ' | ' ORDER BY a.valor_venda) AS ocorrencias
FROM anp a
JOIN (
    SELECT arquivo, cnpj, produto, data_coleta
    FROM anp
    GROUP BY arquivo, cnpj, produto, data_coleta
    HAVING count(DISTINCT valor_venda) > 1
    LIMIT 10
) c USING (arquivo, cnpj, produto, data_coleta)
GROUP BY a.arquivo, a.cnpj, a.produto, a.data_coleta
ORDER BY a.arquivo, a.cnpj;

\echo '== snapshots do ultimas4 (21/10 x 06/11) nas datas em comum: o dado preliminar muda?'
WITH antigo AS (
    SELECT DISTINCT cnpj, produto, data_coleta, valor_venda FROM anp WHERE arquivo = 'ultimas4-ge-2026-10-21'
), novo AS (
    SELECT DISTINCT cnpj, produto, data_coleta, valor_venda FROM anp WHERE arquivo = 'ultimas4-ge-2026-11-06'
), janela AS (
    SELECT greatest((SELECT min(data_coleta) FROM novo), (SELECT min(data_coleta) FROM antigo)) AS de,
           least((SELECT max(data_coleta) FROM novo), (SELECT max(data_coleta) FROM antigo))    AS ate
)
SELECT (SELECT de FROM janela) AS de,
       (SELECT ate FROM janela) AS ate,
       count(*) FILTER (WHERE a.cnpj IS NOT NULL AND n.cnpj IS NOT NULL AND a.valor_venda = n.valor_venda)  AS iguais,
       count(*) FILTER (WHERE a.cnpj IS NOT NULL AND n.cnpj IS NOT NULL AND a.valor_venda <> n.valor_venda) AS preco_mudou,
       count(*) FILTER (WHERE n.cnpj IS NULL)                                                             AS so_no_antigo,
       count(*) FILTER (WHERE a.cnpj IS NULL)                                                             AS so_no_novo
FROM (SELECT * FROM antigo WHERE data_coleta BETWEEN (SELECT de FROM janela) AND (SELECT ate FROM janela)) a
FULL JOIN (SELECT * FROM novo WHERE data_coleta BETWEEN (SELECT de FROM janela) AND (SELECT ate FROM janela)) n
       USING (cnpj, produto, data_coleta);
