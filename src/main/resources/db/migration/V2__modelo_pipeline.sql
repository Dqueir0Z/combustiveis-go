-- Modelo do pipeline (SPEC seção 7): controle da carga, staging, quarentena e estrela.

-- Uma linha por arquivo processado. As contagens fecham a reconciliação da seção 6.
CREATE TABLE execucao (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte           text        NOT NULL,
    url             text        NOT NULL,
    sha256          char(64)    NOT NULL,
    obtido_em       timestamptz NOT NULL,
    periodo_min     date,
    periodo_max     date,
    inicio          timestamptz NOT NULL DEFAULT now(),
    fim             timestamptz,
    status          text        NOT NULL CHECK (status IN ('EM_ANDAMENTO', 'PUBLICADA', 'FALHOU')),
    lidas           bigint,
    aceitas         bigint,
    descartadas     bigint,
    duplicadas      bigint,
    rejeitadas      bigint,
    inseridos       bigint,
    atualizados     bigint,
    sem_mudanca     bigint,
    aviso_cobertura text
);

-- A pré-checagem procura "este sha256 já foi publicado?".
CREATE INDEX ix_execucao_sha256 ON execucao (sha256) WHERE status = 'PUBLICADA';

-- Rascunho de uma execução. A PK impede que um reinício grave o mesmo registro duas vezes.
CREATE TABLE stg_preco (
    execucao_id    bigint       NOT NULL REFERENCES execucao (id),
    n_registro     bigint       NOT NULL,
    uf             char(2)      NOT NULL,
    municipio      text         NOT NULL,
    revenda        text,
    cnpj           char(14)     NOT NULL,
    bairro         text,
    cep            text,
    produto        text         NOT NULL,
    data_coleta    date         NOT NULL,
    valor_venda    numeric(7,3) NOT NULL,
    valor_compra   numeric(7,3),
    unidade        text         NOT NULL,
    bandeira       text         NOT NULL,
    conteudo_bruto text         NOT NULL,
    PRIMARY KEY (execucao_id, n_registro)
);

-- Quarentena: tudo o que foi lido e não pôde ser aceito, com a linha original.
CREATE TABLE rejeito (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    execucao_id    bigint NOT NULL REFERENCES execucao (id),
    arquivo        text   NOT NULL,
    n_registro     bigint NOT NULL,
    motivo         text   NOT NULL,
    detalhe        text,
    conteudo_bruto text   NOT NULL,
    UNIQUE (execucao_id, n_registro)
);

CREATE TABLE dim_data (
    data       date     PRIMARY KEY,
    ano        smallint NOT NULL,
    mes        smallint NOT NULL,
    semana_iso smallint NOT NULL,
    ano_iso    smallint NOT NULL,
    dia_semana smallint NOT NULL  -- 1 = segunda ... 7 = domingo (ISO)
);

CREATE TABLE dim_municipio (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uf               char(2) NOT NULL,
    nome_normalizado text    NOT NULL,
    UNIQUE (uf, nome_normalizado)
);

CREATE TABLE dim_produto (
    id      smallint PRIMARY KEY,
    nome    text NOT NULL UNIQUE,
    unidade text NOT NULL
);

INSERT INTO dim_produto (id, nome, unidade) VALUES
    (1, 'GASOLINA', 'R$ / litro'),
    (2, 'GASOLINA ADITIVADA', 'R$ / litro'),
    (3, 'ETANOL', 'R$ / litro'),
    (4, 'DIESEL', 'R$ / litro'),
    (5, 'DIESEL S10', 'R$ / litro');

CREATE TABLE dim_bandeira (
    id   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome text NOT NULL UNIQUE
);

-- Cadastro atual do posto (SCD tipo 1): uma linha por CNPJ.
CREATE TABLE dim_revenda (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cnpj         char(14) NOT NULL UNIQUE,
    razao_social text,
    bairro       text,
    cep          text,
    visto_em     date     NOT NULL
);

-- Um preço de um posto, num produto, num dia. Município e bandeira são os observados naquela coleta.
CREATE TABLE fato_preco (
    data             date         NOT NULL REFERENCES dim_data (data),
    revenda_id       bigint       NOT NULL REFERENCES dim_revenda (id),
    municipio_id     bigint       NOT NULL REFERENCES dim_municipio (id),
    produto_id       smallint     NOT NULL REFERENCES dim_produto (id),
    bandeira_id      bigint       NOT NULL REFERENCES dim_bandeira (id),
    valor_venda      numeric(7,3) NOT NULL,
    valor_compra     numeric(7,3),
    fonte_prioridade smallint     NOT NULL CHECK (fonte_prioridade IN (1, 2)),
    execucao_id      bigint       NOT NULL REFERENCES execucao (id),
    UNIQUE (revenda_id, produto_id, data)
);

CREATE INDEX ix_fato_preco_municipio_data ON fato_preco (municipio_id, data);
CREATE INDEX ix_fato_preco_data ON fato_preco (data);
