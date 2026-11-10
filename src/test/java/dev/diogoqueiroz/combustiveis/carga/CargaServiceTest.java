package dev.diogoqueiroz.combustiveis.carga;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Clock;

import org.junit.jupiter.api.Test;

import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Downloader;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;

class CargaServiceTest {

	@Test
	void falhaNoDownloadViraFalhouDaquelaFonteSemExcecao() {
		Downloader semRede = new Downloader(Path.of("dados/brutos"), Clock.systemUTC()) {
			@Override
			public ArquivoBaixado baixar(Fonte fonte, boolean rebaixar) {
				throw new UncheckedIOException("falha ao obter " + fonte.id(), new ConnectException("sem rede"));
			}
		};
		CargaService cargas = new CargaService(semRede, null, null, null, null);

		ResultadoCarga resultado = cargas.carregar(Fonte.ULTIMAS4_GASOLINA_ETANOL, false, false);

		assertThat(resultado.situacao()).isEqualTo(Situacao.FALHOU);
		assertThat(resultado.detalhe()).startsWith("download:");
	}

}
