# Inspeção dos dados da ANP (M1)

Feita em 21/10/2026 e 06/11/2026, antes de escrever o parser, para testar as duas hipóteses da [SPEC](../../SPEC.md): a chave natural `(cnpj, produto, data_coleta)` e a precedência "semestral > ultimas4".

## Arquivos

| Arquivo | Baixado em | Tamanho | Registros (Brasil) | Registros GO | Período |
|---|---|---|---|---|---|
| `ca-2026-01.zip` (semestral 2026-1) | 21/10 | 8.488.624 B zip · 72.117.162 B csv | 422.418 | 16.573 | 01/01 – 30/06/2026 |
| `ultimas-4-semanas-gasolina-etanol.csv` | 21/10 | 8.043.292 B | 47.034 | 1.872 | 21/09 – 17/10/2026 |
| `ultimas-4-semanas-gasolina-etanol.csv` | 06/11 | 8.130.008 B | 47.555 | 1.922 | 05/10 – 30/10/2026 |
| `ultimas-4-semanas-diesel-gnv.csv` | 06/11 | 3.696.500 B | 21.759 | 968 | 05/10 – 30/10/2026 |

Cerca de 171 bytes por registro em todos: o formato é o mesmo, só muda o volume.

## Formato

- **Codificação:** UTF-8 válido do começo ao fim, **com BOM** (`EF BB BF`) e fim de linha `CRLF`. A prova independente foi o `COPY ... ENCODING 'UTF8'`, que aceitou os quatro arquivos.
- **BOM:** se não for removido, o nome da primeira coluna vira `"\uFEFFRegiao - Sigla"`, e o mapeamento por nome falha. O parser remove o BOM antes de ler o cabeçalho.
- **Cabeçalho:** idêntico nos quatro arquivos, com 16 colunas.
- **Quebras de linha dentro de campos:** nenhuma. Linhas − 1 = registros em todos os arquivos (ex.: 422.419 linhas e `COPY 422418`).
- **Nome do CSV dentro do zip:** gravado em CP437, sem a marca de UTF-8 (`ç` = byte `0x87`, que aparece como `‡` no Windows). O download não depende desse nome: usa o único `.csv` do zip.
- **Servidor:** responde `403` a `HEAD` e aceita `GET` (inclusive com `Range`). Não dá para checar mudança com `HEAD`.
- **Valor de Compra:** vazio em 100% dos registros dos quatro arquivos.
- **Produtos:** o semestral "AUTOMOTIVOS" traz GASOLINA, GASOLINA ADITIVADA, ETANOL, DIESEL, DIESEL S10 e GNV. O GNV vem em `R$ / m³` e fica fora do escopo (é descartado).
- **Número da rua:** `S/N`, `SN`, `S N`, `S/Nº`... É texto, não número.

## Chave natural `(cnpj, produto, data_coleta)`

| Arquivo | Chaves | Duplicatas (mesma chave, mesmo preço) | Conflitos (mesma chave, preços diferentes) |
|---|---|---|---|
| semestral 2026-1 | 422.412 | 6 (todas cópias idênticas em **todas** as colunas; nenhuma em GO) | **0** |
| ultimas4 gasolina-etanol (21/10) | 47.034 | 0 | **0** |
| ultimas4 gasolina-etanol (06/11) | 47.555 | 0 | **0** |
| ultimas4 diesel-gnv (06/11) | 21.759 | 0 | **0** |

**Conclusão:** a chave se sustenta. As regras de duplicata e de conflito da SPEC continuam, como proteção: a duplicata existe de fato, e o conflito é zero hoje, mas não há garantia de que seja zero amanhã.

## Precedência semestral × ultimas4

- **Não foi possível validar.** O semestral mais recente (2026-1) termina em 30/06, e os `ultimas4` disponíveis começam em 21/09. Não há período em comum, e o Wayback Machine não tinha cópias antigas acessíveis (`429`).
- **O que deu para medir:** dois snapshots do `ultimas4` (21/10 e 06/11), na janela comum de 05/10 a 17/10. As 23.564 chaves vieram **idênticas**: nenhum preço mudou, nenhuma linha sumiu e nenhuma apareceu depois. Nessa amostra, o dado "preliminar" não foi revisado.
- **Decisão:** a regra da SPEC continua (consolidado > preliminar), como **hipótese declarada**. Ela será validada quando a ANP publicar o semestral 2026-2 (jul–dez/2026), comparando com os `ultimas4` brutos que o pipeline guarda em `dados/brutos/`.

## Defasagem

Em 06/11/2026, o dado mais recente era de 30/10/2026 (cerca de 1 semana). Em 21/10, era de 17/10. A defasagem varia, e o painel mostra "dados até DD/MM".

## Tempos (máquina do Diogo, Windows 11, SSD)

| Operação | Semestral (422 mil) | ultimas4 (47 mil) |
|---|---|---|
| download | ~8,5 MB (tempo não registrado) | 5,6 s (8,1 MB) |
| contar linhas no PowerShell (`Get-Content`) | 8 s | 1 s |
| `COPY` para o PostgreSQL | 7,4 s | 0,2 s |

O tempo e a memória do parser Java estão no README principal.

## Como reproduzir

Com o banco no ar (`docker compose up -d`) e os arquivos em `dados/brutos/inspecao/`:

```bash
docker cp "dados/brutos/inspecao/semestral/<csv do zip>" combustiveis-go-db:/tmp/semestral-2026-01.csv
docker cp dados/brutos/inspecao/ultimas4-gasolina-etanol.csv combustiveis-go-db:/tmp/ultimas4-ge-2026-10-21.csv
docker cp dados/brutos/inspecao/ultimas4-gasolina-etanol-2026-11-06.csv combustiveis-go-db:/tmp/ultimas4-ge-2026-11-06.csv
docker cp dados/brutos/inspecao/ultimas4-diesel-gnv-2026-11-06.csv combustiveis-go-db:/tmp/ultimas4-dg-2026-11-06.csv
docker exec -i combustiveis-go-db psql -U combustiveis -d combustiveis < docs/inspecao/inspecao.sql
```

No Git Bash, prefixe `MSYS_NO_PATHCONV=1`, para ele não reescrever o `/tmp/`. O script cria tudo no schema `inspecao`, separado da aplicação.
