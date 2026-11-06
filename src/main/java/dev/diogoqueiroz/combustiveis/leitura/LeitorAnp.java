package dev.diogoqueiroz.combustiveis.leitura;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lê o CSV da ANP registro a registro, em streaming.
 *
 * <p>Lê por linha no nível dos bytes, decodifica cada linha em UTF-8 estrito e só então faz o parse CSV
 * daquela linha (SPEC seção 5). Assim, uma linha com byte inválido, aspas quebradas ou número errado de
 * colunas vira um {@link RegistroBruto} ilegível, sem derrubar o resto do arquivo. Isso só é seguro porque a
 * inspeção do M1 não achou campo com quebra de linha.
 */
public final class LeitorAnp implements Closeable {

	private static final Logger log = LoggerFactory.getLogger(LeitorAnp.class);

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	private static final CSVFormat FORMATO = CSVFormat.DEFAULT.builder().setDelimiter(';').get();

	private final InputStream entrada;

	private final CharsetDecoder utf8Estrito = StandardCharsets.UTF_8.newDecoder()
		.onMalformedInput(CodingErrorAction.REPORT)
		.onUnmappableCharacter(CodingErrorAction.REPORT);

	private final ByteArrayOutputStream linha = new ByteArrayOutputStream(512);

	private final Map<ColunaAnp, Integer> indices = new EnumMap<>(ColunaAnp.class);

	private final int totalColunas;

	private long registrosLidos;

	public LeitorAnp(InputStream entrada) throws IOException {
		this.entrada = new BufferedInputStream(entrada, 1 << 16);
		List<String> cabecalho = lerCabecalho();
		this.totalColunas = cabecalho.size();
		mapearColunas(cabecalho);
	}

	/**
	 * Devolve o próximo registro, ou {@code null} no fim do arquivo.
	 */
	public RegistroBruto proximo() throws IOException {
		byte[] bytes = proximaLinha();
		if (bytes == null) {
			return null;
		}
		long numero = ++registrosLidos;

		String texto;
		try {
			texto = decodificar(bytes);
		}
		catch (CharacterCodingException e) {
			// A cópia bruta usa substituição só para poder ser gravada; o registro em si é recusado.
			return RegistroBruto.ilegivel(numero, new String(bytes, StandardCharsets.UTF_8), "byte inválido para UTF-8");
		}

		List<String> campos;
		try {
			campos = parse(texto);
		}
		catch (IOException | UncheckedIOException e) {
			return RegistroBruto.ilegivel(numero, texto, "CSV malformado: " + e.getMessage());
		}
		if (campos.size() != totalColunas) {
			return RegistroBruto.ilegivel(numero, texto,
					"esperava " + totalColunas + " colunas, veio " + campos.size());
		}

		Map<ColunaAnp, String> porColuna = new EnumMap<>(ColunaAnp.class);
		indices.forEach((coluna, indice) -> porColuna.put(coluna, campos.get(indice)));
		return RegistroBruto.lido(numero, texto, porColuna);
	}

	/**
	 * Avança {@code quantidade} registros sem interpretá-los. Usado para retomar uma carga do checkpoint.
	 */
	public void pular(long quantidade) throws IOException {
		for (long i = 0; i < quantidade; i++) {
			if (proximaLinha() == null) {
				throw new IllegalStateException(
						"arquivo terminou no registro " + registrosLidos + ", antes de pular " + quantidade);
			}
			registrosLidos++;
		}
	}

	public long registrosLidos() {
		return registrosLidos;
	}

	@Override
	public void close() throws IOException {
		entrada.close();
	}

	private List<String> lerCabecalho() throws IOException {
		byte[] bytes = proximaLinha();
		if (bytes == null) {
			throw new LayoutInvalidoException("arquivo vazio: não há cabeçalho");
		}
		if (comecaComBom(bytes)) {
			// Sem isto, o BOM grudaria no nome da primeira coluna ("Regiao - Sigla") e o mapeamento por nome falharia.
			bytes = Arrays.copyOfRange(bytes, BOM.length, bytes.length);
		}
		try {
			List<String> nomes = new ArrayList<>();
			for (String nome : parse(decodificar(bytes))) {
				nomes.add(nome.strip());
			}
			return nomes;
		}
		catch (IOException | UncheckedIOException e) {
			throw new LayoutInvalidoException("cabeçalho ilegível: " + e.getMessage());
		}
	}

	private void mapearColunas(List<String> cabecalho) {
		List<String> faltando = new ArrayList<>();
		for (ColunaAnp coluna : ColunaAnp.values()) {
			int indice = cabecalho.indexOf(coluna.cabecalho());
			if (indice >= 0 && indice != cabecalho.lastIndexOf(coluna.cabecalho())) {
				// Duas colunas com o mesmo nome: não dá para saber qual é a certa, então não adivinha.
				throw new LayoutInvalidoException("coluna repetida no cabeçalho: " + coluna.cabecalho());
			}
			if (indice >= 0) {
				indices.put(coluna, indice);
			}
			else if (coluna.obrigatoria()) {
				faltando.add(coluna.cabecalho());
			}
		}
		if (!faltando.isEmpty()) {
			throw new LayoutInvalidoException("faltam colunas obrigatórias: " + faltando);
		}
		List<String> extras = cabecalho.stream()
			.filter(nome -> Arrays.stream(ColunaAnp.values()).noneMatch(c -> c.cabecalho().equals(nome)))
			.toList();
		if (!extras.isEmpty()) {
			log.warn("Colunas extras ignoradas: {}", extras);
		}
	}

	/** Lê até o próximo LF e devolve a linha sem o CR/LF; {@code null} no fim do arquivo. */
	private byte[] proximaLinha() throws IOException {
		linha.reset();
		int b;
		while ((b = entrada.read()) != -1 && b != '\n') {
			linha.write(b);
		}
		if (b == -1 && linha.size() == 0) {
			return null;
		}
		byte[] bytes = linha.toByteArray();
		if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
			return Arrays.copyOf(bytes, bytes.length - 1);
		}
		return bytes;
	}

	private String decodificar(byte[] bytes) throws CharacterCodingException {
		return utf8Estrito.decode(ByteBuffer.wrap(bytes)).toString();
	}

	private static List<String> parse(String texto) throws IOException {
		try (CSVParser parser = CSVParser.parse(texto, FORMATO)) {
			List<CSVRecord> registros = parser.getRecords();
			if (registros.size() != 1) {
				throw new IOException("a linha virou " + registros.size() + " registros");
			}
			return registros.getFirst().toList();
		}
	}

	private static boolean comecaComBom(byte[] bytes) {
		return bytes.length >= BOM.length && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2];
	}

}
