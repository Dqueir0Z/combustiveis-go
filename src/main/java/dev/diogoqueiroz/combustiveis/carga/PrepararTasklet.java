package dev.diogoqueiroz.combustiveis.carga;

import java.time.OffsetDateTime;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Step 0: cria a execução lógica ({@code execucao.id}) e a guarda no ExecutionContext do job.
 *
 * <p>Num reinício este step já está COMPLETED e não roda de novo, então o id é o mesmo e o staging dos
 * chunks já confirmados é preservado (SPEC seção 5).
 */
public class PrepararTasklet implements Tasklet {

	private final JdbcTemplate jdbc;

	private final JobRepository jobRepository;

	public PrepararTasklet(JdbcTemplate jdbc, JobRepository jobRepository) {
		this.jdbc = jdbc;
		this.jobRepository = jobRepository;
	}

	@Override
	public RepeatStatus execute(StepContribution contribuicao, ChunkContext contexto) {
		JobExecution job = contexto.getStepContext().getStepExecution().getJobExecution();
		JobParameters parametros = job.getJobParameters();
		ExecutionContext contextoDoJob = job.getExecutionContext();

		// Uma tentativa anterior pode ter confirmado este step e caído antes de marcá-lo COMPLETED: reusa a execução
		// em vez de criar uma segunda. Mas só se ela existir no banco: o id no contexto sobrevive a um rollback do
		// INSERT (verificado em FalhasTest), então o contexto sozinho não prova nada.
		if (contextoDoJob.containsKey(ParametrosCarga.EXECUCAO_ID) && Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM execucao WHERE id = ?)", Boolean.class,
				contextoDoJob.getLong(ParametrosCarga.EXECUCAO_ID)))) {
			return RepeatStatus.FINISHED;
		}

		Long execucaoId = jdbc.queryForObject("""
				INSERT INTO execucao (fonte, url, sha256, obtido_em, status)
				VALUES (?, ?, ?, ?, 'EM_ANDAMENTO')
				RETURNING id
				""", Long.class, parametros.getString(ParametrosCarga.FONTE), parametros.getString(ParametrosCarga.URL),
				parametros.getString(ParametrosCarga.SHA256),
				OffsetDateTime.parse(parametros.getString(ParametrosCarga.OBTIDO_EM)));
		contextoDoJob.putLong(ParametrosCarga.EXECUCAO_ID, execucaoId);
		// Grava o contexto do job já aqui: se o processo morrer depois do commit e antes de o Batch marcar o step,
		// o reinício encontra o id. (Essa gravação não volta junto com um rollback do tasklet; por isso a
		// verificação de existência acima.)
		jobRepository.updateExecutionContext(job);
		return RepeatStatus.FINISHED;
	}

}
