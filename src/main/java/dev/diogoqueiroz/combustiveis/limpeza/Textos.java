package dev.diogoqueiroz.combustiveis.limpeza;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalização de texto usada pela limpeza.
 */
final class Textos {

	private static final Pattern ACENTOS = Pattern.compile("\\p{M}");

	private static final Pattern ESPACOS = Pattern.compile("\\s+");

	private static final char ESPACO_NAO_SEPARAVEL = (char) 0xA0;

	private Textos() {
	}

	/** Apara (inclusive espaço não separável) e transforma string vazia em {@code null}. */
	static String aparar(String valor) {
		if (valor == null) {
			return null;
		}
		String aparado = valor.replace(ESPACO_NAO_SEPARAVEL, ' ').strip();
		return aparado.isEmpty() ? null : aparado;
	}

	/** Maiúsculas, sem acento e com um espaço só entre as palavras: "Goiânia  " vira "GOIANIA". */
	static String normalizar(String valor) {
		String aparado = aparar(valor);
		if (aparado == null) {
			return null;
		}
		String semAcento = ACENTOS.matcher(Normalizer.normalize(aparado, Normalizer.Form.NFD)).replaceAll("");
		return ESPACOS.matcher(semAcento).replaceAll(" ").toUpperCase(Locale.ROOT);
	}

}
