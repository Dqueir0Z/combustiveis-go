# Painel no Power BI

O painel lê **só as views** do PostgreSQL. A regra de negócio fica no SQL, testado, e não no Power BI.

> Este roteiro ainda não foi testado: o Power BI Desktop não estava instalado na máquina em que o pipeline foi construído. Os passos marcados com ⚠️ são os mais prováveis de precisar de ajuste.

## 1. Conectar

1. Suba o banco (`docker compose up -d`) e carregue os dados (veja o README).
2. No Power BI Desktop: **Obter dados → Banco de dados PostgreSQL**.
   - Servidor: `localhost:5432`
   - Banco de dados: `combustiveis`
   - Modo: **Importar**
3. Credenciais: aba **Banco de dados**, usuário `combustiveis`, senha `combustiveis`.
4. ⚠️ Se aparecer erro de criptografia, desmarque **Criptografar conexões**: o PostgreSQL local do Docker não usa SSL.
5. ⚠️ Se pedir um provedor (Npgsql), instale o que o próprio Power BI indicar e reabra.
6. Selecione apenas as views:

| View | Para quê |
|---|---|
| `vw_etanol_compensa` | mediana da razão etanol/gasolina por município e semana, com `n_postos` |
| `vw_etanol_compensa_posto` | a razão de cada posto (para o parâmetro de limite) |
| `vw_preco_semanal` | preço semanal por produto, em GO e em Goiânia (`nivel`) |
| `vw_variacao_semanal` | variação contra a semana anterior, sobre os mesmos postos |
| `vw_cobertura` | quantos postos e coletas há por produto, município e semana |
| `vw_atualizacao` | dados até, última ingestão, última semana encerrada e aviso de lacunas |

## 2. O limite do "etanol compensa" como parâmetro

O 0,70 é só o padrão; o certo depende do consumo do carro. Por isso, o limite é um parâmetro:

1. **Modelagem → Novo parâmetro → Intervalo numérico**. Nome `Limite`, de 0,60 a 0,80, incremento 0,01, padrão 0,70. Isso cria a tabela `Limite` e a medida `[Limite Valor]`.
2. Medidas:

```DAX
Postos que compensam =
CALCULATE (
    COUNTROWS ( vw_etanol_compensa_posto ),
    FILTER ( vw_etanol_compensa_posto, vw_etanol_compensa_posto[razao] <= [Limite Valor] )
)

% de postos em que compensa =
DIVIDE ( [Postos que compensam], COUNTROWS ( vw_etanol_compensa_posto ) )
```

## 3. Páginas sugeridas

1. **Onde o etanol compensa**
   - Controle deslizante do `Limite`.
   - Segmentação por `inicio_semana`.
   - Tabela por município com `mediana_razao`, `n_postos` e a medida `% de postos em que compensa`.
2. **Preço semanal**
   - Gráfico de linhas de `preco_medio` por `inicio_semana`.
   - Legenda `nivel` (GO × Goiânia) e segmentação por `produto`.
   - `n_postos` na dica de ferramenta: uma média com poucos postos vale menos.
3. **Variação da semana**
   - Tabela de `vw_variacao_semanal` filtrada na última semana, com `variacao_pct` e `n_postos_comuns`.
4. **Em todas as páginas**
   - Cartões com `dados_ate` e `ultima_ingestao` (de `vw_atualizacao`).
   - Cartão com o `aviso_cobertura`, quando houver.
   - A ANP publica com defasagem, e o painel deixa isso visível.

## 4. Atualizar

1. Rode a atualização semanal: `./gradlew bootRun --args="--atualizar"`.
2. No Power BI, clique em **Atualizar**.

Salve o arquivo em `powerbi/combustiveis-go.pbix` e os prints em `powerbi/prints/`.
