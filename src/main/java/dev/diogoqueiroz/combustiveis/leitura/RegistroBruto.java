package dev.diogoqueiroz.combustiveis.leitura;

import java.util.Map;

/**
 * Um registro do arquivo como veio, antes da limpeza.
 *
 * @param numero        posição do registro no arquivo, a partir de 1 (o cabeçalho não conta)
 * @param conteudoBruto a linha original, para a quarentena
 * @param campos        os campos por coluna; vazio quando o registro não pôde ser lido
 * @param erroParse     por que o registro não pôde ser lido; {@code null} quando foi lido
 */
public record RegistroBruto(long numero, String conteudoBruto, Map<ColunaAnp, String> campos, String erroParse) {

	public static RegistroBruto lido(long numero, String conteudoBruto, Map<ColunaAnp, String> campos) {
		return new RegistroBruto(numero, conteudoBruto, Map.copyOf(campos), null);
	}

	public static RegistroBruto ilegivel(long numero, String conteudoBruto, String erroParse) {
		return new RegistroBruto(numero, conteudoBruto, Map.of(), erroParse);
	}

	public boolean ilegivel() {
		return erroParse != null;
	}

	public String campo(ColunaAnp coluna) {
		return campos.get(coluna);
	}

}
