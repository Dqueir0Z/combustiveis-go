package dev.diogoqueiroz.combustiveis.leitura;

/**
 * O arquivo não tem o layout esperado (cabeçalho ausente, ilegível ou sem uma coluna obrigatória).
 * Diferente de um registro ruim, isso aborta a carga inteira: é falha rápida de layout (SPEC seção 5).
 */
public class LayoutInvalidoException extends RuntimeException {

	public LayoutInvalidoException(String mensagem) {
		super(mensagem);
	}

}
