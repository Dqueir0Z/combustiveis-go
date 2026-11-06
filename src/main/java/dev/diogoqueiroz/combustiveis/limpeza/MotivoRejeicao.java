package dev.diogoqueiroz.combustiveis.limpeza;

/**
 * Por que um registro foi para a quarentena ({@code rejeito}). SPEC seção 6.
 */
public enum MotivoRejeicao {

	PARSE_INVALIDO,
	CNPJ_INVALIDO,
	MUNICIPIO_AUSENTE,
	DATA_INVALIDA,
	PRECO_NAO_NUMERICO,
	PRECO_FORA_FAIXA,
	UNIDADE_INCOMPATIVEL,
	/** Mesma chave natural com valores divergentes no mesmo arquivo. Detectado na publicação, não aqui. */
	CONFLITO

}
