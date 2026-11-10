package dev.diogoqueiroz.combustiveis.fonte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O cache do semestral, sem rede: o arquivo em cache é tratado como se tivesse vindo da ANP.
 */
class DownloaderTest {

	@TempDir
	Path pasta;

	@Test
	void cacheValidoViraOCsvIdentificadoPeloSha256DoZip() throws IOException {
		Path cache = pasta.resolve("semestral-2026-1.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cache))) {
			zip.putNextEntry(new ZipEntry("Preços semestrais.csv"));
			zip.write("Regiao - Sigla;Estado - Sigla\r\n".getBytes(StandardCharsets.UTF_8));
		}

		ArquivoBaixado arquivo = new Downloader(pasta, Clock.systemUTC()).baixar(Fonte.semestral(2026, 1), false);

		assertThat(arquivo.csv()).isEqualTo(pasta.resolve(arquivo.sha256() + ".csv"));
		assertThat(Files.readString(arquivo.csv())).startsWith("Regiao - Sigla");
		assertThat(cache).exists();
	}

	@Test
	void cacheInvalidoEApagadoParaAProximaTentativaBaixarDeNovo() throws IOException {
		// Ex.: o servidor respondeu 200 com uma página HTML no lugar do zip.
		Path cache = pasta.resolve("semestral-2026-1.zip");
		try (OutputStream saida = Files.newOutputStream(cache)) {
			saida.write("<html>manutenção</html>".getBytes(StandardCharsets.UTF_8));
		}

		assertThatThrownBy(() -> new Downloader(pasta, Clock.systemUTC()).baixar(Fonte.semestral(2026, 1), false))
			.isInstanceOf(UncheckedIOException.class);
		assertThat(cache).doesNotExist();
		try (var restos = Files.list(pasta)) {
			assertThat(restos).as("nem CSV nem temporários sobram").isEmpty();
		}
	}

}
