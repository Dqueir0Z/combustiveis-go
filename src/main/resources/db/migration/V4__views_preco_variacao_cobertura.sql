-- Views das perguntas 1 e 3 e da cobertura (SPEC seção 8). Todas partem do preço representativo
-- (vw_preco_posto_semana, criada na V3).

-- Pergunta 1: preço semanal por produto em dois níveis sobrepostos e rotulados, GO e Goiânia.
-- No nível GO, o representativo do posto é recalculado sem o município: um posto observado em dois municípios
-- na mesma semana conta uma vez, com todas as coletas (revisão do Codex, M4).
CREATE VIEW vw_preco_semanal AS
SELECT ano_iso,
       semana_iso,
       inicio_semana,
       produto,
       'GO'                          AS nivel,
       round(avg(preco_posto), 3)    AS preco_medio,
       count(*)                      AS n_postos
FROM (
    SELECT d.ano_iso, d.semana_iso, date_trunc('week', f.data)::date AS inicio_semana, f.revenda_id,
           p.nome AS produto, avg(f.valor_venda) AS preco_posto
    FROM fato_preco f
    JOIN dim_data d      ON d.data = f.data
    JOIN dim_produto p   ON p.id = f.produto_id
    JOIN dim_municipio m ON m.id = f.municipio_id
    WHERE m.uf = 'GO'
    GROUP BY d.ano_iso, d.semana_iso, date_trunc('week', f.data), f.revenda_id, p.nome
) postos
GROUP BY ano_iso, semana_iso, inicio_semana, produto
UNION ALL
SELECT s.ano_iso,
       s.semana_iso,
       s.inicio_semana,
       s.produto,
       'GOIANIA'                     AS nivel,
       round(avg(s.preco_medio), 3)  AS preco_medio,
       count(*)                      AS n_postos
FROM vw_preco_posto_semana s
JOIN dim_municipio m ON m.id = s.municipio_id
WHERE m.uf = 'GO' AND m.nome_normalizado = 'GOIANIA'
GROUP BY s.ano_iso, s.semana_iso, s.inicio_semana, s.produto;

-- Pergunta 3: variação por produto e município, só entre a semana e a IMEDIATAMENTE anterior, com as duas
-- médias recalculadas sobre a interseção dos postos presentes nas duas semanas. Se a semana anterior não
-- tem dado, não há linha (não compara com uma semana mais antiga). "inicio_semana - 7" atravessa a virada
-- do ano ISO sem caso especial.
CREATE VIEW vw_variacao_semanal AS
SELECT a.ano_iso,
       a.semana_iso,
       a.inicio_semana,
       m.nome_normalizado                                       AS municipio,
       a.produto,
       count(*)                                                 AS n_postos_comuns,
       round(avg(b.preco_medio), 3)                             AS media_semana_anterior,
       round(avg(a.preco_medio), 3)                             AS media_semana,
       round(avg(a.preco_medio) - avg(b.preco_medio), 3)        AS variacao,
       round(100 * (avg(a.preco_medio) / avg(b.preco_medio) - 1), 2) AS variacao_pct
FROM vw_preco_posto_semana a
JOIN vw_preco_posto_semana b
  ON b.revenda_id = a.revenda_id
 AND b.produto = a.produto
 AND b.municipio_id = a.municipio_id
 AND b.inicio_semana = a.inicio_semana - 7
JOIN dim_municipio m ON m.id = a.municipio_id
GROUP BY a.ano_iso, a.semana_iso, a.inicio_semana, m.nome_normalizado, a.produto;

-- Cobertura: quantos postos por produto e município em cada semana (o painel mostra junto com os preços).
CREATE VIEW vw_cobertura AS
SELECT s.ano_iso,
       s.semana_iso,
       s.inicio_semana,
       m.nome_normalizado  AS municipio,
       s.produto,
       count(*)            AS n_postos,
       sum(s.n_coletas)::bigint AS n_coletas
FROM vw_preco_posto_semana s
JOIN dim_municipio m ON m.id = s.municipio_id
GROUP BY s.ano_iso, s.semana_iso, s.inicio_semana, m.nome_normalizado, s.produto;

-- "Dados até", ingestão e última semana encerrada com dados (SPEC seção 8). Uma semana encerrada não prova
-- que a ANP já publicou tudo dela: é uma limitação declarada, por isso o painel mostra as três datas.
-- O "hoje" é o de São Paulo, não o do fuso da sessão. O aviso vale o da publicação mais recente (por "fim"):
-- um reinício publica depois de uma execução criada mais tarde. O aviso só detecta semanas inteiramente vazias
-- entre a primeira e a última com dados; não detecta falta nas pontas nem falta de um produto só.
CREATE VIEW vw_atualizacao AS
SELECT (SELECT max(data) FROM fato_preco)                                        AS dados_ate,
       (SELECT max(fim) FROM execucao WHERE status = 'PUBLICADA')                AS ultima_ingestao,
       (SELECT max(date_trunc('week', data)::date) FROM fato_preco
         WHERE date_trunc('week', data)::date + 7 <= (now() AT TIME ZONE 'America/Sao_Paulo')::date)
                                                                                 AS ultima_semana_encerrada,
       (SELECT aviso_cobertura FROM execucao WHERE status = 'PUBLICADA' ORDER BY fim DESC, id DESC LIMIT 1)
                                                                                 AS aviso_cobertura;
