package dev.diogoqueiroz.combustiveis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Teste de fumaça do M0: o contexto sobe contra um PostgreSQL 16 real e as migrações aplicam.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CombustiveisGoApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JobRepository jobRepository;

	@Test
	void migracaoV1AplicadaComSucesso() {
		List<String> versoes = jdbc.queryForList(
				"select version from flyway_schema_history where success order by installed_rank", String.class);

		assertThat(versoes).contains("1");
	}

	@Test
	void metadadosDoBatchFicamNoSchemaBatch() {
		List<String> tabelas = jdbc.queryForList(
				"select table_name from information_schema.tables where table_schema = 'batch' order by table_name",
				String.class);

		assertThat(tabelas).containsExactly("batch_job_execution", "batch_job_execution_context",
				"batch_job_execution_params", "batch_job_instance", "batch_step_execution",
				"batch_step_execution_context");
	}

	@Test
	void jobRepositoryGravaELeOsMetadadosPeloPrefixoConfigurado() {
		// Se o table-prefix não apontasse para batch.BATCH_, falharia com "relation does not exist".
		// Grava instância, execução, step e contexto: exercita as três sequences e as tabelas de contexto.
		JobParameters parametros = new JobParametersBuilder().addString("fonte", "smoke-test").toJobParameters();
		JobInstance instancia = jobRepository.createJobInstance("smoke-test", parametros);
		JobExecution execucao = jobRepository.createJobExecution(instancia, parametros, new ExecutionContext());
		StepExecution step = jobRepository.createStepExecution("smoke-step", execucao);

		step.getExecutionContext().putString("chave", "valor");
		jobRepository.updateExecutionContext(step);

		Integer contextos = jdbc.queryForObject(
				"select count(*) from batch.batch_step_execution_context where step_execution_id = ?", Integer.class,
				step.getId());
		assertThat(contextos).isEqualTo(1);
		assertThat(jobRepository.getJobNames()).contains("smoke-test");
	}

}
