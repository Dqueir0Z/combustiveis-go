package dev.diogoqueiroz.combustiveis.carga;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.diogoqueiroz.combustiveis.LinhasReais;
import dev.diogoqueiroz.combustiveis.TestcontainersConfiguration;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;

/**
 * Base dos testes de integração: PostgreSQL 16 real (Testcontainers) e banco limpo antes de cada teste.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
abstract class IntegracaoBase {

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected CargaService cargas;

	@TempDir
	protected Path pasta;

	@BeforeEach
	void limparBanco() {
		jdbc.execute("""
				TRUNCATE fato_preco, dim_revenda, dim_municipio, dim_bandeira, dim_data, rejeito, stg_preco, execucao,
				         batch.batch_step_execution_context, batch.batch_job_execution_context,
				         batch.batch_step_execution, batch.batch_job_execution_params, batch.batch_job_execution,
				         batch.batch_job_instance
				RESTART IDENTITY CASCADE
				""");
	}

	/** Grava um arquivo como o da ANP (BOM, CRLF) e o apresenta como se tivesse acabado de ser baixado. */
	protected ArquivoBaixado arquivo(Fonte fonte, String... linhas) {
		return arquivo(fonte, LinhasReais.arquivo(LinhasReais.CABECALHO, linhas));
	}

	protected ArquivoBaixado arquivo(Fonte fonte, byte[] conteudo) {
		try {
			String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
			Path csv = pasta.resolve(sha256 + ".csv");
			Files.write(csv, conteudo);
			return new ArquivoBaixado(fonte, sha256, OffsetDateTime.now(), csv);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	protected Map<String, Object> execucao(long id) {
		return jdbc.queryForMap("SELECT * FROM execucao WHERE id = ?", id);
	}

}
