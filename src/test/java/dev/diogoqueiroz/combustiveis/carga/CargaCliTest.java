package dev.diogoqueiroz.combustiveis.carga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import dev.diogoqueiroz.combustiveis.fonte.Fonte;

class CargaCliTest {

	@Test
	void historicoViraOsSemestraisDoIntervaloEmOrdem() {
		assertThat(CargaCli.semestrais("2022-1:2023-2")).extracting(Fonte::id)
			.containsExactly("semestral:2022-1", "semestral:2022-2", "semestral:2023-1", "semestral:2023-2");
		assertThat(CargaCli.semestrais("2026-1:2026-1")).extracting(Fonte::url)
			.containsExactly(
					"https://www.gov.br/anp/pt-br/centrais-de-conteudo/dados-abertos/arquivos/shpc/dsas/ca/ca-2026-01.zip");
	}

	@Test
	void primeiroSemestreDe2022UsaONomeForaDoPadraoDaAnp() {
		assertThat(Fonte.semestral(2022, 1).url()).endsWith("/dsas/ca/precos-semestrais-ca.zip");
		assertThat(Fonte.semestral(2022, 2).url()).endsWith("/dsas/ca/ca-2022-02.zip");
	}

	@Test
	void intervaloInvalidoOuInvertidoERecusado() {
		assertThatThrownBy(() -> CargaCli.semestrais("2022:2023")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CargaCli.semestrais("2024-1:2023-2")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void atualizarSaoAsDuasFontesUltimas4() {
		assertThat(CargaCli.fontes(new DefaultApplicationArguments("--atualizar"))).extracting(Fonte::id)
			.containsExactly("ultimas4:gasolina-etanol", "ultimas4:diesel-gnv");
		assertThat(CargaCli.fontes(new DefaultApplicationArguments())).isEmpty();
	}

	@Test
	void fonteDesconhecidaERecusada() {
		assertThatThrownBy(() -> CargaCli.fontes(new DefaultApplicationArguments("--carga=ultimas4:glp")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("fonte desconhecida");
	}

}
