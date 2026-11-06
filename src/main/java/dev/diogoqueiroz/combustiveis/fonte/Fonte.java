package dev.diogoqueiroz.combustiveis.fonte;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um arquivo da ANP, tratado como fonte independente (SPEC seção 4): identidade própria, execução própria.
 *
 * @param id         como aparece em {@code execucao.fonte}, ex.: "semestral:2026-1"
 * @param url        de onde baixar
 * @param prioridade 2 = consolidado (semestral), 1 = preliminar (ultimas4); a maior vence na publicação
 * @param zip        o arquivo vem compactado e o CSV está dentro
 */
public record Fonte(String id, String url, int prioridade, boolean zip) {

	static final String BASE = "https://www.gov.br/anp/pt-br/centrais-de-conteudo/dados-abertos/arquivos/shpc";

	public static final int PRIORIDADE_SEMESTRAL = 2;

	public static final int PRIORIDADE_ULTIMAS4 = 1;

	private static final Pattern SEMESTRAL = Pattern.compile("semestral:(\\d{4})-([12])");

	public static final Fonte ULTIMAS4_GASOLINA_ETANOL = new Fonte("ultimas4:gasolina-etanol",
			BASE + "/qus/ultimas-4-semanas-gasolina-etanol.csv", PRIORIDADE_ULTIMAS4, false);

	public static final Fonte ULTIMAS4_DIESEL_GNV = new Fonte("ultimas4:diesel-gnv",
			BASE + "/qus/ultimas-4-semanas-diesel-gnv.csv", PRIORIDADE_ULTIMAS4, false);

	/** A atualização semanal: as duas fontes, uma depois da outra. */
	public static final List<Fonte> ATUALIZACAO_SEMANAL = List.of(ULTIMAS4_GASOLINA_ETANOL, ULTIMAS4_DIESEL_GNV);

	public static Fonte semestral(int ano, int semestre) {
		if (semestre != 1 && semestre != 2) {
			throw new IllegalArgumentException("semestre deve ser 1 ou 2: " + semestre);
		}
		String arquivo = ano == 2022 && semestre == 1 ? ARQUIVO_2022_1 : "ca-" + ano + "-0" + semestre + ".zip";
		return new Fonte("semestral:" + ano + "-" + semestre, BASE + "/dsas/ca/" + arquivo, PRIORIDADE_SEMESTRAL,
				true);
	}

	/**
	 * A ANP publicou o 1º semestre de 2022 fora do padrão "ca-AAAA-0S.zip" (o padrão dá 404). Conferido em
	 * 06/11/2026: o zip traz precos-semestrais-ca-2022-01.csv, de 03/01 a 30/06/2022, no mesmo layout.
	 */
	private static final String ARQUIVO_2022_1 = "precos-semestrais-ca.zip";

	/** Interpreta o id usado na linha de comando. */
	public static Fonte doId(String id) {
		if (ULTIMAS4_GASOLINA_ETANOL.id().equals(id)) {
			return ULTIMAS4_GASOLINA_ETANOL;
		}
		if (ULTIMAS4_DIESEL_GNV.id().equals(id)) {
			return ULTIMAS4_DIESEL_GNV;
		}
		Matcher semestral = SEMESTRAL.matcher(id);
		if (semestral.matches()) {
			return semestral(Integer.parseInt(semestral.group(1)), Integer.parseInt(semestral.group(2)));
		}
		throw new IllegalArgumentException("fonte desconhecida: " + id
				+ " (use ultimas4:gasolina-etanol, ultimas4:diesel-gnv ou semestral:AAAA-S)");
	}

	public boolean semestral() {
		return prioridade == PRIORIDADE_SEMESTRAL;
	}

}
