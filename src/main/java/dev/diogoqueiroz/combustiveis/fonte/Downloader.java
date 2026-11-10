package dev.diogoqueiroz.combustiveis.fonte;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Baixa uma fonte e a guarda em {@code dados/brutos/<sha256>.csv}, identificada pelo sha256 do que veio da ANP.
 *
 * <p>Baixa para um temporário e calcula o hash dele em seguida. O servidor da ANP responde 403 a HEAD
 * (inspeção do M1), por isso sempre GET. As fontes ultimas4 são baixadas toda vez. O semestral fica em cache
 * por fonte ({@code semestral-AAAA-S.zip}) e só é baixado de novo com {@code rebaixar}, porque o hash não
 * detecta uma revisão no servidor (SPEC seção 5).
 */
@Component
public class Downloader {

	private static final Logger log = LoggerFactory.getLogger(Downloader.class);

	/** O nome do CSV dentro do zip vem em CP437 (inspeção do M1); ler com UTF-8 falharia nesse nome. */
	private static final Charset NOMES_DO_ZIP = Charset.forName("Cp437");

	private final HttpClient http = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	private final Path pastaBrutos;

	private final Clock relogio;

	public Downloader(@Value("${combustiveis.pasta-brutos:dados/brutos}") Path pastaBrutos, Clock relogio) {
		this.pastaBrutos = pastaBrutos;
		this.relogio = relogio;
	}

	public ArquivoBaixado baixar(Fonte fonte, boolean rebaixar) {
		try {
			Files.createDirectories(pastaBrutos);
			Path cacheSemestral = pastaBrutos.resolve(fonte.id().replace(':', '-') + ".zip");
			boolean usarCache = fonte.semestral() && Files.exists(cacheSemestral) && !rebaixar;
			Path original;
			if (usarCache) {
				log.info("{}: usando o arquivo em cache {}", fonte.id(), cacheSemestral);
				original = cacheSemestral;
			}
			else {
				original = baixarParaTemporario(fonte);
			}
			try {
				ArquivoBaixado arquivo = prepararCsv(fonte, original);
				if (fonte.semestral() && !usarCache) {
					// Só vira cache depois de o CSV ser extraído: um download inválido (HTML, zip corrompido)
					// nunca substitui o cache, e a próxima tentativa baixa de novo.
					Files.move(original, cacheSemestral, StandardCopyOption.REPLACE_EXISTING);
				}
				return arquivo;
			}
			catch (IOException e) {
				if (usarCache) {
					log.warn("{}: o arquivo em cache é inválido e foi apagado; a próxima tentativa baixa de novo",
							fonte.id());
					Files.deleteIfExists(cacheSemestral);
				}
				throw e;
			}
			finally {
				if (!usarCache) {
					Files.deleteIfExists(original);
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("falha ao obter " + fonte.id() + " de " + fonte.url(), e);
		}
	}

	/** Identifica o arquivo pelo sha256 e deixa o CSV pronto em {@code dados/brutos/<sha256>.csv}. */
	private ArquivoBaixado prepararCsv(Fonte fonte, Path original) throws IOException {
		OffsetDateTime obtidoEm = OffsetDateTime.ofInstant(Files.getLastModifiedTime(original).toInstant(),
				relogio.getZone());
		String sha256 = sha256(original);
		Path csv = pastaBrutos.resolve(sha256 + ".csv");
		if (!Files.exists(csv)) {
			Path temporario = Files.createTempFile(pastaBrutos, sha256, ".parcial");
			try {
				if (fonte.zip()) {
					extrairUnicoCsv(original, temporario);
				}
				else {
					Files.copy(original, temporario, StandardCopyOption.REPLACE_EXISTING);
				}
				Files.move(temporario, csv, StandardCopyOption.ATOMIC_MOVE);
			}
			finally {
				Files.deleteIfExists(temporario);
			}
		}
		return new ArquivoBaixado(fonte, sha256, obtidoEm, csv);
	}

	private Path baixarParaTemporario(Fonte fonte) throws IOException {
		HttpRequest pedido = HttpRequest.newBuilder(URI.create(fonte.url()))
			.timeout(Duration.ofMinutes(5))
			.header("User-Agent", "combustiveis-go (projeto de portfolio)")
			.GET()
			.build();
		Path temporario = Files.createTempFile(pastaBrutos, "download", ".parcial");
		long inicio = System.nanoTime();
		try {
			HttpResponse<Path> resposta = http.send(pedido,
					HttpResponse.BodyHandlers.ofFile(temporario));
			if (resposta.statusCode() != 200) {
				throw new IOException("HTTP " + resposta.statusCode() + " ao baixar " + fonte.url());
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("download interrompido", e);
		}
		catch (IOException e) {
			Files.deleteIfExists(temporario);
			throw e;
		}
		log.info("{}: {} bytes em {} ms", fonte.id(), Files.size(temporario), (System.nanoTime() - inicio) / 1_000_000);
		return temporario;
	}

	/** O zip da ANP traz um único CSV; o nome dele é irrelevante (e vem em CP437). */
	private static void extrairUnicoCsv(Path zip, Path destino) throws IOException {
		try (ZipInputStream entrada = new ZipInputStream(Files.newInputStream(zip), NOMES_DO_ZIP)) {
			ZipEntry csv = proximoCsv(entrada);
			if (csv == null) {
				throw new IOException("o zip " + zip + " não tem CSV");
			}
			try (OutputStream saida = Files.newOutputStream(destino)) {
				entrada.transferTo(saida);
			}
			if (proximoCsv(entrada) != null) {
				throw new IOException("o zip " + zip + " tem mais de um CSV; não dá para escolher sozinho");
			}
		}
	}

	private static ZipEntry proximoCsv(ZipInputStream zip) throws IOException {
		ZipEntry entrada;
		while ((entrada = zip.getNextEntry()) != null) {
			if (!entrada.isDirectory() && entrada.getName().toLowerCase().endsWith(".csv")) {
				return entrada;
			}
		}
		return null;
	}

	static String sha256(Path arquivo) throws IOException {
		try (InputStream entrada = new DigestInputStream(Files.newInputStream(arquivo),
				MessageDigest.getInstance("SHA-256"))) {
			entrada.transferTo(OutputStream.nullOutputStream());
			return HexFormat.of().formatHex(((DigestInputStream) entrada).getMessageDigest().digest());
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 sempre existe na JVM", e);
		}
	}

}
