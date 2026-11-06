package dev.diogoqueiroz.combustiveis.limpeza;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

/**
 * Os cinco produtos do escopo, com a faixa de preço plausível em R$/L (SPEC seção 6).
 * Qualquer outro produto da ANP (GNV, GLP...) é descartado, não rejeitado.
 */
public enum Produto {

	GASOLINA("GASOLINA", "2.00", "12.00"),
	GASOLINA_ADITIVADA("GASOLINA ADITIVADA", "2.00", "12.00"),
	ETANOL("ETANOL", "1.50", "10.00"),
	DIESEL("DIESEL", "2.00", "12.00"),
	DIESEL_S10("DIESEL S10", "2.00", "12.00");

	private final String nomeAnp;
	private final BigDecimal minimo;
	private final BigDecimal maximo;

	Produto(String nomeAnp, String minimo, String maximo) {
		this.nomeAnp = nomeAnp;
		this.minimo = new BigDecimal(minimo);
		this.maximo = new BigDecimal(maximo);
	}

	public static Optional<Produto> doNomeAnp(String nome) {
		return Arrays.stream(values()).filter(p -> p.nomeAnp.equals(nome)).findFirst();
	}

	public String nomeAnp() {
		return nomeAnp;
	}

	public boolean precoNaFaixa(BigDecimal valor) {
		return valor.compareTo(minimo) >= 0 && valor.compareTo(maximo) <= 0;
	}

}
