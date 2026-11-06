package dev.diogoqueiroz.combustiveis.carga;

import java.util.UUID;

import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;

import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;

/**
 * Os parâmetros do job "carga-precos". Identificam a instância: fonte + sha256 (+ reprocesso). Um reinício
 * com os mesmos parâmetros retoma do checkpoint; um arquivo novo gera um sha256 novo e uma instância nova.
 */
public final class ParametrosCarga {

	public static final String FONTE = "fonte";

	public static final String SHA256 = "sha256";

	public static final String REPROCESSO = "reprocesso";

	public static final String ARQUIVO = "arquivo";

	public static final String URL = "url";

	public static final String PRIORIDADE = "prioridade";

	public static final String OBTIDO_EM = "obtidoEm";

	/** Chave do ExecutionContext do job onde o step "preparar" guarda o id da execução lógica. */
	public static final String EXECUCAO_ID = "execucaoId";

	private ParametrosCarga() {
	}

	public static JobParameters de(ArquivoBaixado arquivo, boolean reprocessar) {
		JobParametersBuilder parametros = new JobParametersBuilder()
			.addString(FONTE, arquivo.fonte().id(), true)
			.addString(SHA256, arquivo.sha256(), true)
			.addString(ARQUIVO, arquivo.csv().toAbsolutePath().toString(), false)
			.addString(URL, arquivo.fonte().url(), false)
			.addLong(PRIORIDADE, (long) arquivo.fonte().prioridade(), false)
			.addString(OBTIDO_EM, arquivo.obtidoEm().toString(), false);
		if (reprocessar) {
			parametros.addString(REPROCESSO, UUID.randomUUID().toString(), true);
		}
		return parametros.toJobParameters();
	}

}
