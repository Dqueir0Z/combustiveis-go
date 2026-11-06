package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_GUARULHOS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;
import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza;

/**
 * Cenários que dependem de o arquivo ocupar vários chunks: chunk de 2 registros e um writer que pode falhar
 * uma vez, num chunk escolhido.
 */
@TestPropertySource(properties = "combustiveis.chunk=2")
@Import(ChunkPequenoTest.FalhaInjetavel.class)
class ChunkPequenoTest extends IntegracaoBase {

	/** Em qual chunk o writer falha na próxima vez (0 = não falha). Volta a 0 depois de falhar. */
	static final AtomicInteger FALHAR_NO_CHUNK = new AtomicInteger();

	@Autowired
	JobRepository jobRepository;

	@AfterEach
	void desarmar() {
		FALHAR_NO_CHUNK.set(0);
	}

	@Test
	void conflitoQueAtravessaChunksVaiInteiroParaAQuarentena() {
		// Registros 1 e 4 têm a mesma chave com preços diferentes e ficam em chunks diferentes (1-2 e 3-4).
		// O 5 é cópia idêntica do 1: com o conflito, conta como rejeitado, não como duplicado.
		String outroPreco = com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_VENDA, "6,99");
		ResultadoCarga resultado = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL,
				GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS, dia(ETANOL_ANAPOLIS, "07"), outroPreco, GASOLINA_ANAPOLIS), false);

		assertThat(execucao(resultado.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 5L)
			.containsEntry("aceitas", 2L)
			.containsEntry("duplicadas", 0L)
			.containsEntry("rejeitadas", 3L);
		assertThat(jdbc.queryForList("SELECT n_registro FROM rejeito WHERE motivo = 'CONFLITO' ORDER BY 1", Long.class))
			.containsExactly(1L, 4L, 5L);
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM fato_preco f JOIN dim_produto p ON p.id = f.produto_id WHERE p.nome = 'GASOLINA'",
				Long.class))
			.as("nenhuma ocorrência do conflito é publicada")
			.isZero();
	}

	@Test
	void falhaEntreChunksSeguidaDeReinicioNaoDuplicaOStaging() {
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, dia(GASOLINA_ANAPOLIS, "01"),
				dia(GASOLINA_ANAPOLIS, "02"), dia(GASOLINA_ANAPOLIS, "03"), dia(GASOLINA_ANAPOLIS, "04"),
				dia(GASOLINA_ANAPOLIS, "05"));
		FALHAR_NO_CHUNK.set(2);

		ResultadoCarga falha = cargas.carregar(arquivo, false);

		assertThat(falha.situacao()).isEqualTo(Situacao.FALHOU);
		assertThat(execucao(falha.execucaoId())).containsEntry("status", "FALHOU");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco", Long.class))
			.as("só o primeiro chunk foi confirmado")
			.isEqualTo(2);

		ResultadoCarga reinicio = cargas.carregar(arquivo, false);

		assertThat(reinicio.situacao()).isEqualTo(Situacao.PUBLICADA);
		assertThat(reinicio.execucaoId()).as("mesma execução lógica").isEqualTo(falha.execucaoId());
		assertThat(execucao(reinicio.execucaoId())).containsEntry("lidas", 5L)
			.containsEntry("aceitas", 5L)
			.containsEntry("duplicadas", 0L)
			.containsEntry("inseridos", 5L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fato_preco", Long.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM batch.batch_job_execution", Long.class)).isEqualTo(2);
	}

	@Test
	void reinicioRestauraOContadorDeDescartadosSemContarDuasVezes() {
		// Chunks de 2: [GO, SP] [GO, SP] [GO]. O 2o chunk falha: o descarte dele voltou junto com o chunk e
		// não pode ser contado duas vezes; o descarte do 1o chunk foi confirmado e não pode ser perdido.
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, dia(GASOLINA_ANAPOLIS, "01"),
				GASOLINA_GUARULHOS, dia(GASOLINA_ANAPOLIS, "02"), dia(GASOLINA_GUARULHOS, "02"),
				dia(GASOLINA_ANAPOLIS, "03"));
		FALHAR_NO_CHUNK.set(2);

		assertThat(cargas.carregar(arquivo, false).situacao()).isEqualTo(Situacao.FALHOU);
		ResultadoCarga reinicio = cargas.carregar(arquivo, false);

		assertThat(execucao(reinicio.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 5L)
			.containsEntry("aceitas", 3L)
			.containsEntry("descartadas", 2L);
	}

	@Test
	void falhaNoCommitDoChunkNaoAdiantaOCheckpointEPedeReprocesso() {
		// O trigger adiado só dispara no COMMIT do 2o chunk (registro 3), depois de o reader e o processador já
		// terem atualizado o checkpoint em memória. O checkpoint persistido tem que continuar no 1o chunk.
		jdbc.execute("""
				CREATE FUNCTION falhar_no_commit() RETURNS trigger LANGUAGE plpgsql AS
				$$ BEGIN IF NEW.n_registro = 3 THEN RAISE EXCEPTION 'falha simulada no commit'; END IF; RETURN NEW; END $$
				""");
		jdbc.execute("""
				CREATE CONSTRAINT TRIGGER falhar_no_commit AFTER INSERT ON stg_preco
				DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION falhar_no_commit()
				""");
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, dia(GASOLINA_ANAPOLIS, "01"),
				dia(GASOLINA_ANAPOLIS, "02"), dia(GASOLINA_ANAPOLIS, "03"), GASOLINA_GUARULHOS,
				dia(GASOLINA_ANAPOLIS, "05"));
		try {
			assertThat(cargas.carregar(arquivo, false).situacao()).isEqualTo(Situacao.FALHOU);
		}
		finally {
			jdbc.execute("DROP TRIGGER falhar_no_commit ON stg_preco");
			jdbc.execute("DROP FUNCTION falhar_no_commit()");
		}

		Long passo = jdbc.queryForObject(
				"SELECT max(step_execution_id) FROM batch.batch_step_execution WHERE step_name = 'ler-limpar'", Long.class);
		ExecutionContext checkpoint = jobRepository.getStepExecution(passo).getExecutionContext();
		assertThat(checkpoint.getLong(LeitorAnpItemReader.LIDOS)).isEqualTo(2);
		assertThat(checkpoint.getLong(LimpezaProcessor.DESCARTADOS)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco", Long.class)).isEqualTo(2);

		// Com o commit derrubado, o Batch não consegue gravar os metadados do step e marca o job como UNKNOWN.
		// Ele recusa retomar de propósito; a carga diz o que fazer, e o reprocesso relê o arquivo inteiro.
		ResultadoCarga retomada = cargas.carregar(arquivo, false);
		assertThat(retomada.situacao()).isEqualTo(Situacao.FALHOU);
		assertThat(retomada.detalhe()).contains("--reprocessar");

		ResultadoCarga reprocesso = cargas.carregar(arquivo, true);

		assertThat(execucao(reprocesso.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 5L)
			.containsEntry("aceitas", 4L)
			.containsEntry("descartadas", 1L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fato_preco", Long.class)).isEqualTo(4);
	}

	private static String dia(String linha, String dia) {
		return com(linha, ColunaAnp.DATA_COLETA, dia + "/10/2026");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FalhaInjetavel {

		@Bean
		@StepScope
		@Primary
		StagingWriter stagingWriterComFalha(JdbcTemplate jdbc,
				@Value("#{jobExecutionContext['" + ParametrosCarga.EXECUCAO_ID + "']}") Long execucaoId,
				@Value("#{jobParameters['" + ParametrosCarga.ARQUIVO + "']}") String arquivo) {
			AtomicInteger chunks = new AtomicInteger();
			return new StagingWriter(jdbc, execucaoId, arquivo) {
				@Override
				public void write(Chunk<? extends ResultadoLimpeza> chunk) {
					super.write(chunk);
					if (chunks.incrementAndGet() == FALHAR_NO_CHUNK.get()) {
						FALHAR_NO_CHUNK.set(0);
						throw new IllegalStateException("falha simulada no chunk " + chunks.get());
					}
				}
			};
		}

	}

}
