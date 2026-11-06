package dev.diogoqueiroz.combustiveis.leitura;

/**
 * Colunas do CSV da ANP, mapeadas pelo nome do cabeçalho (nunca pela posição).
 * As obrigatórias são as que a limpeza usa: se faltar uma, o arquivo é recusado.
 */
public enum ColunaAnp {

	REGIAO("Regiao - Sigla", false),
	ESTADO("Estado - Sigla", true),
	MUNICIPIO("Municipio", true),
	REVENDA("Revenda", true),
	CNPJ("CNPJ da Revenda", true),
	NOME_RUA("Nome da Rua", false),
	NUMERO_RUA("Numero Rua", false),
	COMPLEMENTO("Complemento", false),
	BAIRRO("Bairro", true),
	CEP("Cep", true),
	PRODUTO("Produto", true),
	DATA_COLETA("Data da Coleta", true),
	VALOR_VENDA("Valor de Venda", true),
	VALOR_COMPRA("Valor de Compra", true),
	UNIDADE("Unidade de Medida", true),
	BANDEIRA("Bandeira", true);

	private final String cabecalho;
	private final boolean obrigatoria;

	ColunaAnp(String cabecalho, boolean obrigatoria) {
		this.cabecalho = cabecalho;
		this.obrigatoria = obrigatoria;
	}

	public String cabecalho() {
		return cabecalho;
	}

	public boolean obrigatoria() {
		return obrigatoria;
	}

}
