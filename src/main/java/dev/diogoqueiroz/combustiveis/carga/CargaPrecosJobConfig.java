package dev.diogoqueiroz.combustiveis.carga;

import java.nio.file.Path;

import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;
import dev.diogoqueiroz.combustiveis.limpeza.Limpador;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza;

/**
 * O job "carga-precos" (SPEC seção 5): preparar → ler e limpar (chunks) → publicar (uma transação).
 * Um job processa um único arquivo.
 */
@Configuration(proxyBeanMethods = false)
public class CargaPrecosJobConfig {

	public static final String JOB = "carga-precos";

	public static final String STEP_PREPARAR = "preparar";

	public static final String STEP_LER = "ler-limpar";

	public static final String STEP_PUBLICAR = "publicar";

	@Bean
	Job cargaPrecos(JobRepository repositorio, Step preparar, Step lerLimpar, Step publicar, JdbcTemplate jdbc) {
		return new JobBuilder(JOB, repositorio).listener(new ExecucaoListener(jdbc))
			.start(preparar)
			.next(lerLimpar)
			.next(publicar)
			.build();
	}

	@Bean
	Step preparar(JobRepository repositorio, PlatformTransactionManager transacoes, JdbcTemplate jdbc) {
		return new StepBuilder(STEP_PREPARAR, repositorio).tasklet(new PrepararTasklet(jdbc, repositorio), transacoes)
			.build();
	}

	@Bean
	Step lerLimpar(JobRepository repositorio, PlatformTransactionManager transacoes,
			LeitorAnpItemReader leitorAnp, LimpezaProcessor limpeza,
			StagingWriter stagingWriter, @Value("${combustiveis.chunk:10000}") int tamanhoDoChunk) {
		return new StepBuilder(STEP_LER, repositorio).<RegistroBruto, ResultadoLimpeza>chunk(tamanhoDoChunk)
			.transactionManager(transacoes)
			.reader(leitorAnp)
			.processor(limpeza)
			.writer(stagingWriter)
			.stream(limpeza)
			.build();
	}

	@Bean
	Step publicar(JobRepository repositorio, PlatformTransactionManager transacoes, PublicarTasklet publicarTasklet) {
		return new StepBuilder(STEP_PUBLICAR, repositorio).tasklet(publicarTasklet, transacoes).build();
	}

	@Bean
	@StepScope
	LeitorAnpItemReader leitorAnp(@Value("#{jobParameters['" + ParametrosCarga.ARQUIVO + "']}") String arquivo) {
		return new LeitorAnpItemReader(Path.of(arquivo));
	}

	@Bean
	@StepScope
	LimpezaProcessor limpeza(Limpador limpador) {
		return new LimpezaProcessor(limpador);
	}

	@Bean
	@StepScope
	StagingWriter stagingWriter(JdbcTemplate jdbc,
			@Value("#{jobExecutionContext['" + ParametrosCarga.EXECUCAO_ID + "']}") Long execucaoId,
			@Value("#{jobParameters['" + ParametrosCarga.ARQUIVO + "']}") String arquivo) {
		return new StagingWriter(jdbc, execucaoId, Path.of(arquivo).getFileName().toString());
	}

	@Bean
	@StepScope
	PublicarTasklet publicarTasklet(JdbcTemplate jdbc, JobRepository repositorio,
			@Value("#{jobExecutionContext['" + ParametrosCarga.EXECUCAO_ID + "']}") Long execucaoId,
			@Value("#{jobParameters['" + ParametrosCarga.ARQUIVO + "']}") String arquivo,
			@Value("#{jobParameters['" + ParametrosCarga.PRIORIDADE + "']}") Long prioridade) {
		return new PublicarTasklet(jdbc, repositorio, execucaoId, Path.of(arquivo).getFileName().toString(),
				prioridade.intValue());
	}

}
