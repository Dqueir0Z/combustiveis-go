package dev.diogoqueiroz.combustiveis.carga;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step 2: publica o staging de uma execução, numa única transação (a do tasklet).
 *
 * <p>Ordem: conflitos → duplicatas → dimensões → fato (upsert com precedência) → contagens. Se qualquer coisa
 * falhar, inclusive a reconciliação, a transação inteira volta e nada é publicado (SPEC seções 6 e 7).
 * Todo o SQL filtra pela execução, então duas execuções nunca se misturam no staging.
 */
public class PublicarTasklet implements Tasklet {

	private static final Logger log = LoggerFactory.getLogger(PublicarTasklet.class);

	/**
	 * Conflito: mesma chave com valores divergentes. Todas as ocorrências vão para a quarentena, inclusive
	 * cópias idênticas entre elas, que assim não contam como duplicadas.
	 */
	private static final String MOVER_CONFLITOS = """
			WITH chaves AS (
			    SELECT cnpj, produto, data_coleta
			    FROM stg_preco
			    WHERE execucao_id = ?
			    GROUP BY cnpj, produto, data_coleta
			    HAVING count(DISTINCT (municipio, revenda, bairro, cep, valor_venda, valor_compra, unidade, bandeira)) > 1
			), movidos AS (
			    DELETE FROM stg_preco s
			    USING chaves c
			    WHERE s.execucao_id = ?
			      AND (s.cnpj, s.produto, s.data_coleta) = (c.cnpj, c.produto, c.data_coleta)
			    RETURNING s.n_registro, s.conteudo_bruto
			)
			INSERT INTO rejeito (execucao_id, arquivo, n_registro, motivo, detalhe, conteudo_bruto)
			SELECT ?, ?, n_registro, 'CONFLITO', 'mesma chave natural com valores divergentes no arquivo', conteudo_bruto
			FROM movidos
			""";

	/** Sem conflitos, mesma chave = cópia idêntica. Fica a de menor n_registro. */
	private static final String REMOVER_DUPLICATAS = """
			DELETE FROM stg_preco s
			USING stg_preco o
			WHERE s.execucao_id = ? AND o.execucao_id = ?
			  AND (s.cnpj, s.produto, s.data_coleta) = (o.cnpj, o.produto, o.data_coleta)
			  AND o.n_registro < s.n_registro
			""";

	private static final String INSERIR_DATAS = """
			INSERT INTO dim_data (data, ano, mes, semana_iso, ano_iso, dia_semana)
			SELECT DISTINCT data_coleta,
			       extract(year FROM data_coleta), extract(month FROM data_coleta),
			       extract(week FROM data_coleta), extract(isoyear FROM data_coleta), extract(isodow FROM data_coleta)
			FROM stg_preco
			WHERE execucao_id = ?
			ON CONFLICT (data) DO NOTHING
			""";

	private static final String INSERIR_MUNICIPIOS = """
			INSERT INTO dim_municipio (uf, nome_normalizado)
			SELECT DISTINCT uf, municipio FROM stg_preco WHERE execucao_id = ?
			ON CONFLICT (uf, nome_normalizado) DO NOTHING
			""";

	private static final String INSERIR_BANDEIRAS = """
			INSERT INTO dim_bandeira (nome)
			SELECT DISTINCT bandeira FROM stg_preco WHERE execucao_id = ?
			ON CONFLICT (nome) DO NOTHING
			""";

	/**
	 * Um candidato por CNPJ (o de coleta mais recente; no empate, o menor n_registro). Só substitui o cadastro
	 * existente se for de uma coleta igual ou mais nova: um semestral antigo não apaga um cadastro atual.
	 */
	private static final String UPSERT_REVENDAS = """
			INSERT INTO dim_revenda (cnpj, razao_social, bairro, cep, visto_em)
			SELECT DISTINCT ON (cnpj) cnpj, revenda, bairro, cep, data_coleta
			FROM stg_preco
			WHERE execucao_id = ?
			ORDER BY cnpj, data_coleta DESC, n_registro
			ON CONFLICT (cnpj) DO UPDATE
			SET razao_social = EXCLUDED.razao_social,
			    bairro       = EXCLUDED.bairro,
			    cep          = EXCLUDED.cep,
			    visto_em     = EXCLUDED.visto_em
			WHERE EXCLUDED.visto_em >= dim_revenda.visto_em
			""";

	private static final String MONTAR_CANDIDATOS = """
			CREATE TEMPORARY TABLE candidato_fato ON COMMIT DROP AS
			SELECT s.data_coleta AS data, r.id AS revenda_id, m.id AS municipio_id, p.id AS produto_id,
			       b.id AS bandeira_id, s.valor_venda, s.valor_compra
			FROM stg_preco s
			JOIN dim_revenda r   ON r.cnpj = s.cnpj
			JOIN dim_municipio m ON (m.uf, m.nome_normalizado) = (s.uf, s.municipio)
			JOIN dim_produto p   ON p.nome = s.produto
			JOIN dim_bandeira b  ON b.nome = s.bandeira
			WHERE s.execucao_id = ?
			""";

	/**
	 * Classifica cada candidato antes do upsert. "Pode sobrescrever" segue a precedência da SPEC seção 7:
	 * prioridade maior vence; na mesma prioridade, a execução mais nova; prioridade menor nunca sobrescreve.
	 * Bloqueado pela precedência, ou sem diferença de conteúdo, conta como sem_mudanca.
	 */
	private static final String CLASSIFICAR = """
			SELECT count(*) FILTER (WHERE f.revenda_id IS NULL) AS inseridos,
			       count(*) FILTER (WHERE f.revenda_id IS NOT NULL
			                          AND (?::smallint > f.fonte_prioridade
			                               OR (?::smallint = f.fonte_prioridade AND ? > f.execucao_id))
			                          AND (c.valor_venda, c.valor_compra, c.municipio_id, c.bandeira_id)
			                              IS DISTINCT FROM (f.valor_venda, f.valor_compra, f.municipio_id, f.bandeira_id)
			                       ) AS atualizados,
			       count(*) AS total
			FROM candidato_fato c
			LEFT JOIN fato_preco f USING (revenda_id, produto_id, data)
			""";

	private static final String UPSERT_FATO = """
			INSERT INTO fato_preco (data, revenda_id, municipio_id, produto_id, bandeira_id, valor_venda, valor_compra,
			                        fonte_prioridade, execucao_id)
			SELECT data, revenda_id, municipio_id, produto_id, bandeira_id, valor_venda, valor_compra, ?, ?
			FROM candidato_fato
			ON CONFLICT (revenda_id, produto_id, data) DO UPDATE
			SET municipio_id     = EXCLUDED.municipio_id,
			    bandeira_id      = EXCLUDED.bandeira_id,
			    valor_venda      = EXCLUDED.valor_venda,
			    valor_compra     = EXCLUDED.valor_compra,
			    fonte_prioridade = EXCLUDED.fonte_prioridade,
			    execucao_id      = EXCLUDED.execucao_id
			WHERE EXCLUDED.fonte_prioridade > fato_preco.fonte_prioridade
			   OR (EXCLUDED.fonte_prioridade = fato_preco.fonte_prioridade
			       AND EXCLUDED.execucao_id > fato_preco.execucao_id)
			""";

	/**
	 * Lacunas pela cobertura (SPEC seção 8): toda semana ISO entre a primeira e a última com dados no fato
	 * deveria ter dados. As que não têm viram um aviso com o semestral a carregar (pela quinta-feira da
	 * semana, que decide a qual semestre ela pertence). A recuperação é manual na v1.
	 */
	private static final String AVISO_COBERTURA = """
			WITH limites AS (
			    SELECT date_trunc('week', min(data))::date AS de, date_trunc('week', max(data))::date AS ate
			    FROM fato_preco
			), faltando AS (
			    SELECT s::date AS semana
			    FROM limites, generate_series(de, ate, interval '1 week') AS s
			    WHERE NOT EXISTS (SELECT 1 FROM fato_preco f WHERE f.data >= s::date AND f.data < s::date + 7)
			), semestrais AS (
			    SELECT DISTINCT 'semestral:' || extract(year FROM semana + 3) || '-'
			           || CASE WHEN extract(month FROM semana + 3) <= 6 THEN 1 ELSE 2 END AS fonte
			    FROM faltando
			)
			SELECT 'Faltam ' || count(*) || ' semana(s) sem dados ('
			       || string_agg(to_char(semana, 'IYYY-"S"IW'), ', ' ORDER BY semana)
			       || '). Carregue: ' || (SELECT string_agg(fonte, ', ' ORDER BY fonte) FROM semestrais) || '.'
			FROM faltando
			HAVING count(*) > 0
			""";

	private final JdbcTemplate jdbc;

	private final JobRepository jobRepository;

	private final long execucaoId;

	private final String arquivo;

	private final int prioridade;

	public PublicarTasklet(JdbcTemplate jdbc, JobRepository jobRepository, long execucaoId, String arquivo,
			int prioridade) {
		this.jdbc = jdbc;
		this.jobRepository = jobRepository;
		this.execucaoId = execucaoId;
		this.arquivo = arquivo;
		this.prioridade = prioridade;
	}

	@Override
	public RepeatStatus execute(StepContribution contribuicao, ChunkContext contexto) {
		jdbc.update(MOVER_CONFLITOS, execucaoId, execucaoId, execucaoId, arquivo);
		long duplicadas = jdbc.update(REMOVER_DUPLICATAS, execucaoId, execucaoId);

		jdbc.update(INSERIR_DATAS, execucaoId);
		jdbc.update(INSERIR_MUNICIPIOS, execucaoId);
		jdbc.update(INSERIR_BANDEIRAS, execucaoId);
		jdbc.update(UPSERT_REVENDAS, execucaoId);

		jdbc.update(MONTAR_CANDIDATOS, execucaoId);
		Map<String, Object> classe = jdbc.queryForMap(CLASSIFICAR, prioridade, prioridade, execucaoId);
		long inseridos = ((Number) classe.get("inseridos")).longValue();
		long atualizados = ((Number) classe.get("atualizados")).longValue();
		long aceitas = ((Number) classe.get("total")).longValue();
		jdbc.update(UPSERT_FATO, prioridade, execucaoId);

		long[] lidasEDescartadas = lidasEDescartadas(contexto.getStepContext().getStepExecution().getJobExecution());
		long lidas = lidasEDescartadas[0];
		long descartadas = lidasEDescartadas[1];
		long rejeitadas = jdbc.queryForObject("SELECT count(*) FROM rejeito WHERE execucao_id = ?", Long.class,
				execucaoId);
		long emStaging = jdbc.queryForObject("SELECT count(*) FROM stg_preco WHERE execucao_id = ?", Long.class,
				execucaoId);
		if (emStaging != aceitas) {
			throw new ReconciliacaoException("staging tem " + emStaging + " registros, mas " + aceitas
					+ " viraram candidatos ao fato: algum registro não achou a dimensão");
		}
		if (lidas != aceitas + descartadas + duplicadas + rejeitadas) {
			throw new ReconciliacaoException("lidas=" + lidas + " ≠ aceitas=" + aceitas + " + descartadas="
					+ descartadas + " + duplicadas=" + duplicadas + " + rejeitadas=" + rejeitadas);
		}

		// Sem lacuna, a consulta não devolve linha (HAVING), e o aviso fica nulo.
		String avisoCobertura = jdbc.queryForList(AVISO_COBERTURA, String.class).stream().findFirst().orElse(null);
		if (avisoCobertura != null) {
			log.warn("Execução {}: {}", execucaoId, avisoCobertura);
		}

		jdbc.update("""
				UPDATE execucao
				SET status = 'PUBLICADA', fim = now(), aviso_cobertura = ?,
				    periodo_min = (SELECT min(data_coleta) FROM stg_preco WHERE execucao_id = ?),
				    periodo_max = (SELECT max(data_coleta) FROM stg_preco WHERE execucao_id = ?),
				    lidas = ?, aceitas = ?, descartadas = ?, duplicadas = ?, rejeitadas = ?,
				    inseridos = ?, atualizados = ?, sem_mudanca = ?
				WHERE id = ?
				""", avisoCobertura, execucaoId, execucaoId, lidas, aceitas, descartadas, duplicadas, rejeitadas,
				inseridos, atualizados, aceitas - inseridos - atualizados, execucaoId);
		jdbc.update("DELETE FROM stg_preco WHERE execucao_id = ?", execucaoId);

		log.info("Execução {} publicada: lidas={} aceitas={} descartadas={} duplicadas={} rejeitadas={} "
				+ "inseridos={} atualizados={} sem_mudanca={}", execucaoId, lidas, aceitas, descartadas, duplicadas,
				rejeitadas, inseridos, atualizados, aceitas - inseridos - atualizados);
		return RepeatStatus.FINISHED;
	}

	/**
	 * Lidas e descartadas vêm do checkpoint da última execução concluída do step de leitura: o reader e o
	 * processador gravam os contadores no ExecutionContext a cada commit de chunk, junto com o staging. Num
	 * reinício, o checkpoint retoma de onde parou, então cada registro conta exatamente uma vez. (Os contadores
	 * readCount/filterCount do Batch 6 não servem: incluem chunks que voltaram no rollback.)
	 */
	private long[] lidasEDescartadas(JobExecution atual) {
		StepExecution leitura = jobRepository.getLastStepExecution(atual.getJobInstance(), CargaPrecosJobConfig.STEP_LER);
		if (leitura == null || leitura.getStatus() != BatchStatus.COMPLETED) {
			throw new ReconciliacaoException("o step de leitura não terminou; não há o que publicar");
		}
		ExecutionContext checkpoint = leitura.getExecutionContext();
		return new long[] { checkpoint.getLong(LeitorAnpItemReader.LIDOS),
				checkpoint.getLong(LimpezaProcessor.DESCARTADOS) };
	}

}
