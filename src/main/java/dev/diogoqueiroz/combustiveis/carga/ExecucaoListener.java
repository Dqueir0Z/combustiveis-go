package dev.diogoqueiroz.combustiveis.carga;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mantém {@code execucao.status} coerente com o job: FALHOU quando o job falha, EM_ANDAMENTO de novo quando
 * ele é reiniciado. O PUBLICADA é gravado pela própria publicação, dentro da transação dela.
 */
public class ExecucaoListener implements JobExecutionListener {

	private final JdbcTemplate jdbc;

	public ExecucaoListener(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void beforeJob(JobExecution job) {
		if (job.getExecutionContext().containsKey(ParametrosCarga.EXECUCAO_ID)) {
			jdbc.update("UPDATE execucao SET status = 'EM_ANDAMENTO', fim = NULL WHERE id = ? AND status = 'FALHOU'",
					job.getExecutionContext().getLong(ParametrosCarga.EXECUCAO_ID));
		}
	}

	@Override
	public void afterJob(JobExecution job) {
		if (job.getStatus() != BatchStatus.COMPLETED
				&& job.getExecutionContext().containsKey(ParametrosCarga.EXECUCAO_ID)) {
			jdbc.update("UPDATE execucao SET status = 'FALHOU', fim = now() WHERE id = ? AND status <> 'PUBLICADA'",
					job.getExecutionContext().getLong(ParametrosCarga.EXECUCAO_ID));
		}
	}

}
