package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.CABECALHO;
import static dev.diogoqueiroz.combustiveis.LinhasReais.DIESEL_S10_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;

import dev.diogoqueiroz.combustiveis.LinhasReais;
import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;
import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;

/**
 * SPEC seção 9: falhas na publicação, no layout, na codificação e na concorrência falham do jeito esperado.
 */
class FalhasTest extends IntegracaoBase {

	@Autowired
	ExecutorUnico executorUnico;

	@Autowired
	CargaCli cli;

	@Autowired
	JobRepository jobRepository;

	@Test
	void falhaNaPublicacaoDesfazTudoEOReinicioPublica() {
		// Um trigger recusa o insert no fato: a falha acontece depois das dimensões, no meio da transação.
		jdbc.execute("""
				CREATE FUNCTION falhar_fato() RETURNS trigger LANGUAGE plpgsql AS
				$$ BEGIN RAISE EXCEPTION 'falha simulada na publicação'; END $$
				""");
		jdbc.execute("CREATE TRIGGER falhar_fato BEFORE INSERT ON fato_preco FOR EACH ROW EXECUTE FUNCTION falhar_fato()");
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS);
		ResultadoCarga falha;
		try {
			falha = cargas.carregar(arquivo, false);
		}
		finally {
			jdbc.execute("DROP TRIGGER falhar_fato ON fato_preco");
			jdbc.execute("DROP FUNCTION falhar_fato()");
		}

		assertThat(falha.situacao()).isEqualTo(Situacao.FALHOU);
		assertThat(execucao(falha.execucaoId())).containsEntry("status", "FALHOU");
		for (String tabela : new String[] { "fato_preco", "dim_revenda", "dim_municipio", "dim_bandeira", "dim_data" }) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM " + tabela, Long.class)).as(tabela).isZero();
		}
		// O staging fica, para o reinício não precisar ler o arquivo de novo.
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco", Long.class)).isEqualTo(2);

		ResultadoCarga reinicio = cargas.carregar(arquivo, false);

		assertThat(reinicio.situacao()).isEqualTo(Situacao.PUBLICADA);
		assertThat(reinicio.execucaoId()).isEqualTo(falha.execucaoId());
		assertThat(execucao(reinicio.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 2L)
			.containsEntry("inseridos", 2L);
	}

	@Test
	void falhaNaPublicacaoDesfazConflitosDuplicatasEAtualizacaoDeFatoExistente() {
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS), false);
		Long execucaoAntiga = jdbc.queryForObject("SELECT execucao_id FROM fato_preco", Long.class);
		// Segunda carga: atualiza a gasolina (6,99), duplica o etanol e tem um conflito no diesel S10.
		ArquivoBaixado segunda = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL,
				com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_VENDA, "6,99"), ETANOL_ANAPOLIS, ETANOL_ANAPOLIS,
				DIESEL_S10_ANAPOLIS, com(DIESEL_S10_ANAPOLIS, ColunaAnp.VALOR_VENDA, "7,49"));
		jdbc.execute("""
				CREATE FUNCTION falhar_fato() RETURNS trigger LANGUAGE plpgsql AS
				$$ BEGIN RAISE EXCEPTION 'falha simulada na publicação'; END $$
				""");
		jdbc.execute("CREATE TRIGGER falhar_fato BEFORE INSERT ON fato_preco FOR EACH ROW EXECUTE FUNCTION falhar_fato()");
		ResultadoCarga falha;
		try {
			falha = cargas.carregar(segunda, false);
		}
		finally {
			jdbc.execute("DROP TRIGGER falhar_fato ON fato_preco");
			jdbc.execute("DROP FUNCTION falhar_fato()");
		}

		assertThat(falha.situacao()).isEqualTo(Situacao.FALHOU);
		// Nada da segunda carga sobreviveu: o fato antigo intacto, nenhum conflito na quarentena, staging inteiro.
		assertThat(jdbc.queryForMap("SELECT valor_venda::text, execucao_id FROM fato_preco"))
			.containsEntry("valor_venda", "6.770")
			.containsEntry("execucao_id", execucaoAntiga);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM rejeito WHERE execucao_id = ?", Long.class,
				falha.execucaoId())).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco WHERE execucao_id = ?", Long.class,
				falha.execucaoId())).isEqualTo(5);

		ResultadoCarga reinicio = cargas.carregar(segunda, false);

		assertThat(execucao(reinicio.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 5L)
			.containsEntry("aceitas", 2L)
			.containsEntry("duplicadas", 1L)
			.containsEntry("rejeitadas", 2L)
			.containsEntry("inseridos", 1L)
			.containsEntry("atualizados", 1L);
		assertThat(jdbc.queryForObject("SELECT f.valor_venda::text FROM fato_preco f "
				+ "JOIN dim_produto p ON p.id = f.produto_id WHERE p.nome = 'GASOLINA'", String.class)).isEqualTo("6.990");
	}

	@Test
	void falhaNoCommitDoPrepararNaoDeixaExecucaoOrfaEOReprocessoPublica() {
		// O trigger adiado derruba o COMMIT do step "preparar", depois de o id já ter sido gravado no contexto
		// do job. O INSERT volta, e o Batch marca o job como UNKNOWN (como numa falha de commit de chunk).
		jdbc.execute("""
				CREATE FUNCTION falhar_no_commit() RETURNS trigger LANGUAGE plpgsql AS
				$$ BEGIN RAISE EXCEPTION 'falha simulada no commit do preparar'; END $$
				""");
		jdbc.execute("""
				CREATE CONSTRAINT TRIGGER falhar_no_commit AFTER INSERT ON execucao
				DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION falhar_no_commit()
				""");
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS);
		try {
			assertThat(cargas.carregar(arquivo, false).situacao()).isEqualTo(Situacao.FALHOU);
		}
		finally {
			jdbc.execute("DROP TRIGGER falhar_no_commit ON execucao");
			jdbc.execute("DROP FUNCTION falhar_no_commit()");
		}

		assertThat(jdbc.queryForObject("SELECT count(*) FROM execucao", Long.class)).isZero();
		JobExecution tentativa = jobRepository.getLastJobExecution(CargaPrecosJobConfig.JOB,
				ParametrosCarga.de(arquivo, false));
		assertThat(tentativa.getExecutionContext().containsKey(ParametrosCarga.EXECUCAO_ID))
			.as("o id ficou no contexto mesmo com o INSERT desfeito: por isso o preparar confere no banco")
			.isTrue();
		assertThat(cargas.carregar(arquivo, false).detalhe()).contains("--reprocessar");

		ResultadoCarga reprocesso = cargas.carregar(arquivo, true);

		assertThat(reprocesso.situacao()).isEqualTo(Situacao.PUBLICADA);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM execucao", Long.class)).as("sem execução órfã")
			.isEqualTo(1);
	}

	@Test
	void layoutSemColunaObrigatoriaFalhaAntesDeGravarQualquerCoisa() {
		String semBandeira = CABECALHO.replace(";Bandeira", "");
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, LinhasReais.arquivo(semBandeira,
				GASOLINA_ANAPOLIS.substring(0, GASOLINA_ANAPOLIS.lastIndexOf(';'))));

		ResultadoCarga resultado = cargas.carregar(arquivo, false);

		assertThat(resultado.situacao()).isEqualTo(Situacao.FALHOU);
		assertThat(resultado.detalhe()).contains("Bandeira");
		assertThat(execucao(resultado.execucaoId())).containsEntry("status", "FALHOU");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fato_preco", Long.class)).isZero();
	}

	@Test
	void linhaComCodificacaoInvalidaVaiParaQuarentenaEORestoEPublicado() {
		ByteArrayOutputStream conteudo = new ByteArrayOutputStream();
		conteudo.writeBytes(LinhasReais.arquivo(CABECALHO, ETANOL_ANAPOLIS));
		conteudo.writeBytes(GASOLINA_ANAPOLIS.replace("ANAPOLIS", "GOIÂNIA").getBytes(StandardCharsets.ISO_8859_1));
		conteudo.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));

		ResultadoCarga resultado = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, conteudo.toByteArray()),
				false);

		assertThat(execucao(resultado.execucaoId())).containsEntry("status", "PUBLICADA")
			.containsEntry("lidas", 2L)
			.containsEntry("aceitas", 1L)
			.containsEntry("rejeitadas", 1L);
		assertThat(jdbc.queryForMap("SELECT motivo, n_registro FROM rejeito"))
			.containsEntry("motivo", "PARSE_INVALIDO")
			.containsEntry("n_registro", 2L);
	}

	@Test
	void segundoExecutorAbortaSemFazerNada() throws Exception {
		try (ExecutorUnico.Lock primeiro = executorUnico.tentarAdquirir()) {
			assertThat(primeiro).isNotNull();
			assertThat(executorUnico.tentarAdquirir()).isNull();

			// A linha de comando inteira: aborta com código 2 antes de baixar qualquer coisa.
			cli.run(new DefaultApplicationArguments("--atualizar"));
			assertThat(cli.getExitCode()).isEqualTo(2);
		}
		try (ExecutorUnico.Lock depois = executorUnico.tentarAdquirir()) {
			assertThat(depois).as("o lock é liberado quando o primeiro termina").isNotNull();
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM execucao", Long.class)).isZero();
	}

}
