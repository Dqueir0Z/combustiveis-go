package dev.diogoqueiroz.combustiveis.carga;

/**
 * O que aconteceu com uma fonte numa execução da linha de comando.
 *
 * @param execucaoId {@code null} quando não houve job (sem novidade)
 */
public record ResultadoCarga(String fonte, Situacao situacao, Long execucaoId, String sha256, String detalhe) {

	public enum Situacao {
		PUBLICADA, SEM_NOVIDADE, FALHOU
	}

	public boolean falhou() {
		return situacao == Situacao.FALHOU;
	}

}
