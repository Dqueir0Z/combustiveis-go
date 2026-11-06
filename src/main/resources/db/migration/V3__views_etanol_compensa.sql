-- Views da pergunta 2 (SPEC seção 8): onde o etanol compensa em Goiás.

-- Preço representativo (SPEC seção 8): a média do posto na semana ISO (segunda a domingo).
-- Todas as views partem desta, para a regra existir num lugar só. Nada é arredondado nos cálculos:
-- arredondar a média antes de dividir pode inverter o resultado na borda do limite (revisão do Codex, M2).
CREATE VIEW vw_preco_posto_semana AS
SELECT d.ano_iso,
       d.semana_iso,
       date_trunc('week', f.data)::date      AS inicio_semana,
       f.revenda_id,
       f.municipio_id,
       p.nome                                AS produto,
       avg(f.valor_venda)                    AS preco_medio,
       count(*)                              AS n_coletas
FROM fato_preco f
JOIN dim_data d    ON d.data = f.data
JOIN dim_produto p ON p.id = f.produto_id
GROUP BY d.ano_iso, d.semana_iso, date_trunc('week', f.data), f.revenda_id, f.municipio_id, p.nome;

-- Por posto: só postos com gasolina comum e etanol na MESMA SEMANA (aproximação declarada, não "mesma data").
-- O limite de 0,70 é só o padrão; o painel usa "razao" com um parâmetro, porque ele depende do carro.
-- Valores exatos: o arredondamento é só de apresentação (no painel).
CREATE VIEW vw_etanol_compensa_posto AS
SELECT g.ano_iso,
       g.semana_iso,
       g.inicio_semana,
       m.nome_normalizado                             AS municipio,
       r.cnpj,
       r.razao_social,
       g.preco_medio                                  AS preco_gasolina,
       e.preco_medio                                  AS preco_etanol,
       e.preco_medio / g.preco_medio                  AS razao,
       (e.preco_medio / g.preco_medio) <= 0.70        AS compensa_no_limite_padrao
FROM vw_preco_posto_semana g
JOIN vw_preco_posto_semana e
  ON e.revenda_id = g.revenda_id
 AND e.ano_iso = g.ano_iso
 AND e.semana_iso = g.semana_iso
 AND e.municipio_id = g.municipio_id
JOIN dim_municipio m ON m.id = g.municipio_id
JOIN dim_revenda r   ON r.id = g.revenda_id
WHERE g.produto = 'GASOLINA'
  AND e.produto = 'ETANOL';

-- Agregado municipal: a MEDIANA das razões por posto (não a razão das médias), com n_postos.
-- Só os valores finais são arredondados.
CREATE VIEW vw_etanol_compensa AS
SELECT ano_iso,
       semana_iso,
       inicio_semana,
       municipio,
       count(*)                                                             AS n_postos,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY razao)::numeric, 4) AS mediana_razao,
       count(*) FILTER (WHERE compensa_no_limite_padrao)                    AS n_postos_compensa,
       round(avg(preco_gasolina), 3)                                        AS media_gasolina,
       round(avg(preco_etanol), 3)                                          AS media_etanol
FROM vw_etanol_compensa_posto
GROUP BY ano_iso, semana_iso, inicio_semana, municipio;
