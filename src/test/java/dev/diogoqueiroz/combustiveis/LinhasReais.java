package dev.diogoqueiroz.combustiveis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;
import dev.diogoqueiroz.combustiveis.leitura.LeitorAnp;
import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;

/**
 * Linhas copiadas dos arquivos da ANP baixados em 06/11/2026 (ultimas-4-semanas-*.csv).
 */
public final class LinhasReais {

	public static final String CABECALHO = "Regiao - Sigla;Estado - Sigla;Municipio;Revenda;CNPJ da Revenda;"
			+ "Nome da Rua;Numero Rua;Complemento;Bairro;Cep;Produto;Data da Coleta;Valor de Venda;"
			+ "Valor de Compra;Unidade de Medida;Bandeira";

	public static final String GASOLINA_ANAPOLIS = "CO;GO;ANAPOLIS;IMPERIAL COMÉRCIO DE COMBUSTÍVEIS LTDA.;"
			+ " 07.049.692/0001-01;AVENIDA BRASIL NORTE;2582;QD. 12 - LT. 01 A 06;ALVORADA;75084-555;GASOLINA;"
			+ "06/10/2026;6,77;;R$ / litro;ALE";

	public static final String ETANOL_ANAPOLIS = "CO;GO;ANAPOLIS;IMPERIAL COMÉRCIO DE COMBUSTÍVEIS LTDA.;"
			+ " 07.049.692/0001-01;AVENIDA BRASIL NORTE;2582;QD. 12 - LT. 01 A 06;ALVORADA;75084-555;ETANOL;"
			+ "06/10/2026;4,47;;R$ / litro;ALE";

	public static final String DIESEL_S10_ANAPOLIS = "CO;GO;ANAPOLIS;IMPERIAL COMÉRCIO DE COMBUSTÍVEIS LTDA.;"
			+ " 07.049.692/0001-01;AVENIDA BRASIL NORTE;2582;QD. 12 - LT. 01 A 06;ALVORADA;75084-555;DIESEL S10;"
			+ "06/10/2026;7,29;;R$ / litro;ALE";

	public static final String GASOLINA_GUARULHOS = "SE;SP;GUARULHOS;AUTO POSTO SAKAMOTO LTDA; 49.051.667/0001-02;"
			+ "RODOVIA PRESIDENTE DUTRA;S/N;KM 210,5-SENT SP/RJ;BONSUCESSO;07178-580;GASOLINA;07/10/2026;6,79;;"
			+ "R$ / litro;VIBRA";

	/** O complemento tem um ";" dentro das aspas: separar por ";" na mão quebraria esta linha. */
	public static final String GASOLINA_BRASILIA_COM_ASPAS = "CO;DF;BRASILIA;AUTO POSTO JPC DERIVADOS DE PETROLEO LTDA;"
			+ " 07.129.219/0001-34;QUADRA 301 RUA A CONJUNTO 2;SN;\"LOTE: 04;\";NORTE (AGUAS CLARAS);71901-020;"
			+ "GASOLINA;06/10/2026;6,45;;R$ / litro;BRANCA";

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private LinhasReais() {
	}

	/** Monta um arquivo como o da ANP: BOM, cabeçalho e linhas com CRLF. */
	public static byte[] arquivo(String cabecalho, String... linhas) {
		ByteArrayOutputStream saida = new ByteArrayOutputStream();
		saida.writeBytes(BOM);
		saida.writeBytes((cabecalho + "\r\n").getBytes(StandardCharsets.UTF_8));
		for (String linha : linhas) {
			saida.writeBytes((linha + "\r\n").getBytes(StandardCharsets.UTF_8));
		}
		return saida.toByteArray();
	}

	public static LeitorAnp leitor(byte[] arquivo) {
		try {
			return new LeitorAnp(new ByteArrayInputStream(arquivo));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Lê uma linha pelo leitor de verdade, com o cabeçalho real. */
	public static RegistroBruto registro(String linha) {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, linha))) {
			return leitor.proximo();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Troca um campo de uma linha real sem aspas. */
	public static String com(String linha, ColunaAnp coluna, String valor) {
		String[] campos = linha.split(";", -1);
		campos[coluna.ordinal()] = valor;
		return String.join(";", Arrays.asList(campos));
	}

}
