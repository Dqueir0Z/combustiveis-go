package dev.diogoqueiroz.combustiveis.carga;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.JobRestartException;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Downloader;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;

/**
 * Carrega uma fonte: baixa, faz a pré-checagem do sha256 e, se houver novidade, lança o job.
 *
 * <p>Quem chama tem que estar com o lock do {@link ExecutorUnico} (a {@link CargaCli} sempre está): é ele que
 * permite tratar uma execução "em andamento" como órfã de uma queda.
 */
@Service
public class CargaService {

	private static final Logger log = LoggerFactory.getLogger(CargaService.class);

	private final Downloader downloader;

	private final JobOperator jobOperator;

	private final Job cargaPrecos;

	private final JdbcTemplate jdbc;

	private final JobRepository jobRepository;

	public CargaService(Downloader downloader, JobOperator jobOperator, Job cargaPrecos, JdbcTemplate jdbc,
			JobRepository jobRepository) {
		this.downloader = downloader;
		this.jobOperator = jobOperator;
		this.cargaPrecos = cargaPrecos;
		this.jdbc = jdbc;
		this.jobRepository = jobRepository;
	}

	/** Uma falha no download vira FALHOU desta fonte, sem impedir as próximas (ex.: a segunda do --atualizar). */
	public ResultadoCarga carregar(Fonte fonte, boolean reprocessar, boolean rebaixar) {
		ArquivoBaixado arquivo;
		try {
			arquivo = downloader.baixar(fonte, rebaixar);
		}
		catch (RuntimeException e) {
			log.error("{}: falha ao baixar", fonte.id(), e);
			return new ResultadoCarga(fonte.id(), Situacao.FALHOU, null, null, "download: " + e.getMessage());
		}
		return carregar(arquivo, reprocessar);
	}

	/**
	 * Pré-checagem: o mesmo sha256 já publicado termina em "sem novidade" sem lançar o job. Com os mesmos
	 * parâmetros de uma tentativa que falhou, o Batch reinicia aquela instância a partir do checkpoint.
	 */
	public ResultadoCarga carregar(ArquivoBaixado arquivo, boolean reprocessar) {
		String fonte = arquivo.fonte().id();
		if (!reprocessar && jaPublicado(arquivo.sha256())) {
			log.info("{}: sem novidade (sha256 {} já publicado)", fonte, arquivo.sha256());
			return new ResultadoCarga(fonte, Situacao.SEM_NOVIDADE, null, arquivo.sha256(), "sha256 já publicado");
		}
		try {
			JobParameters parametros = ParametrosCarga.de(arquivo, reprocessar);
			recuperarExecucaoOrfa(fonte, parametros);
			JobExecution job = jobOperator.start(cargaPrecos, parametros);
			Long execucaoId = job.getExecutionContext().containsKey(ParametrosCarga.EXECUCAO_ID)
					? job.getExecutionContext().getLong(ParametrosCarga.EXECUCAO_ID) : null;
			if (job.getStatus() == BatchStatus.COMPLETED) {
				return new ResultadoCarga(fonte, Situacao.PUBLICADA, execucaoId, arquivo.sha256(), "publicada");
			}
			return new ResultadoCarga(fonte, Situacao.FALHOU, execucaoId, arquivo.sha256(), causa(job));
		}
		catch (JobRestartException e) {
			// Ex.: a tentativa anterior falhou no commit e ficou UNKNOWN. O Batch recusa retomar de propósito,
			// porque não sabe em que ponto parou; o caminho seguro é reler o arquivo inteiro numa instância nova.
			log.error("{}: a tentativa anterior não pode ser retomada", fonte, e);
			return new ResultadoCarga(fonte, Situacao.FALHOU, null, arquivo.sha256(),
					"não dá para retomar a tentativa anterior (" + e.getMessage() + "). Rode de novo com --reprocessar.");
		}
		catch (Exception e) {
			log.error("{}: o job não pôde ser lançado", fonte, e);
			return new ResultadoCarga(fonte, Situacao.FALHOU, null, arquivo.sha256(), e.getMessage());
		}
	}

	/**
	 * Se o processo morreu no meio de uma carga, os metadados do Batch ficam "em andamento" para sempre, e o
	 * Batch recusa lançar de novo. Como só um executor roda por vez (lock), uma execução "em andamento" que não é
	 * a nossa só pode ser órfã: recover() a marca como FALHOU, e o start seguinte retoma do checkpoint.
	 */
	private void recuperarExecucaoOrfa(String fonte, JobParameters parametros) {
		JobExecution ultima = jobRepository.getLastJobExecution(CargaPrecosJobConfig.JOB, parametros);
		if (ultima != null && ultima.isRunning()) {
			log.warn("{}: a execução {} ficou em andamento depois de uma queda; recuperando para retomar", fonte,
					ultima.getId());
			jobOperator.recover(ultima);
		}
	}

	private boolean jaPublicado(String sha256) {
		Boolean existe = jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM execucao WHERE sha256 = ? AND status = 'PUBLICADA')", Boolean.class,
				sha256);
		return Boolean.TRUE.equals(existe);
	}

	private static String causa(JobExecution job) {
		List<Throwable> falhas = job.getAllFailureExceptions();
		return falhas.isEmpty() ? job.getExitStatus().getExitDescription() : falhas.getFirst().getMessage();
	}

}
