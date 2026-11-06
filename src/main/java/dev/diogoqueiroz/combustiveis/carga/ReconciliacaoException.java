package dev.diogoqueiroz.combustiveis.carga;

/**
 * A equação {@code lidas = aceitas + descartadas + duplicadas + rejeitadas} não fechou. Lançada dentro da
 * transação da publicação, então nada é publicado: um número que não fecha é bug, não dado.
 */
public class ReconciliacaoException extends RuntimeException {

	public ReconciliacaoException(String mensagem) {
		super(mensagem);
	}

}
