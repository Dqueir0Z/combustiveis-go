package dev.diogoqueiroz.combustiveis.leitura;

import static dev.diogoqueiroz.combustiveis.LinhasReais.CABECALHO;
import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_BRASILIA_COM_ASPAS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_GUARULHOS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.arquivo;
import static dev.diogoqueiroz.combustiveis.LinhasReais.leitor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

class LeitorAnpTest {

	@Test
	void removeOBomEMapeiaAsColunasPeloNome() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.ilegivel()).isFalse();
			assertThat(registro.numero()).isEqualTo(1);
			assertThat(registro.campo(ColunaAnp.REGIAO)).isEqualTo("CO");
			assertThat(registro.campo(ColunaAnp.CNPJ)).isEqualTo(" 07.049.692/0001-01");
			assertThat(registro.campo(ColunaAnp.BANDEIRA)).isEqualTo("ALE");
			assertThat(registro.conteudoBruto()).isEqualTo(GASOLINA_ANAPOLIS);
		}
	}

	@Test
	void respeitaPontoEVirgulaDentroDeAspas() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_BRASILIA_COM_ASPAS))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.ilegivel()).isFalse();
			assertThat(registro.campo(ColunaAnp.COMPLEMENTO)).isEqualTo("LOTE: 04;");
			assertThat(registro.campo(ColunaAnp.BAIRRO)).isEqualTo("NORTE (AGUAS CLARAS)");
		}
	}

	@Test
	void numeraOsRegistrosEDevolveNullNoFim() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS))) {
			assertThat(leitor.proximo().numero()).isEqualTo(1);
			assertThat(leitor.proximo().numero()).isEqualTo(2);
			assertThat(leitor.proximo()).isNull();
			assertThat(leitor.registrosLidos()).isEqualTo(2);
		}
	}

	@Test
	void aspasQuebradasViramRegistroIlegivelSemDerrubarOResto() throws IOException {
		String quebrada = GASOLINA_ANAPOLIS.replace("QD. 12", "\"QD. 12");
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, quebrada, ETANOL_ANAPOLIS))) {
			RegistroBruto ruim = leitor.proximo();
			RegistroBruto bom = leitor.proximo();

			assertThat(ruim.ilegivel()).isTrue();
			assertThat(ruim.erroParse()).startsWith("CSV malformado");
			assertThat(ruim.conteudoBruto()).isEqualTo(quebrada);
			assertThat(bom.ilegivel()).isFalse();
			assertThat(bom.numero()).isEqualTo(2);
		}
	}

	@Test
	void byteInvalidoViraRegistroIlegivelSemSubstituicaoSilenciosa() throws IOException {
		// "GOIÂNIA" gravado em Latin-1: o 0xC2 sozinho não é UTF-8 válido.
		byte[] latin1 = GASOLINA_ANAPOLIS.replace("ANAPOLIS", "GOIÂNIA").getBytes(StandardCharsets.ISO_8859_1);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes(arquivo(CABECALHO));
		bytes.writeBytes(latin1);
		bytes.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
		bytes.writeBytes((ETANOL_ANAPOLIS + "\r\n").getBytes(StandardCharsets.UTF_8));

		try (LeitorAnp leitor = leitor(bytes.toByteArray())) {
			RegistroBruto ruim = leitor.proximo();

			assertThat(ruim.ilegivel()).isTrue();
			assertThat(ruim.erroParse()).isEqualTo("byte inválido para UTF-8");
			assertThat(leitor.proximo().ilegivel()).isFalse();
		}
	}

	@Test
	void byteNuloViraRegistroIlegivelComACopiaBrutaGravavel() throws IOException {
		String comNulo = GASOLINA_ANAPOLIS.replace("ALVORADA", "ALVO" + (char) 0 + "RADA");
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, comNulo, ETANOL_ANAPOLIS))) {
			RegistroBruto ruim = leitor.proximo();

			assertThat(ruim.ilegivel()).isTrue();
			assertThat(ruim.erroParse()).contains("byte nulo");
			assertThat(ruim.conteudoBruto()).contains("ALVO\\0RADA").doesNotContain(String.valueOf((char) 0));
			assertThat(leitor.proximo().ilegivel()).isFalse();
		}
	}

	@Test
	void numeroErradoDeColunasViraRegistroIlegivel() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS + ";EXTRA"))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.ilegivel()).isTrue();
			assertThat(registro.erroParse()).isEqualTo("esperava 16 colunas, veio 17");
		}
	}

	@Test
	void linhaEmBrancoViraRegistroIlegivel() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, "", ETANOL_ANAPOLIS))) {
			assertThat(leitor.proximo().ilegivel()).isTrue();
			assertThat(leitor.proximo().numero()).isEqualTo(2);
		}
	}

	@Test
	void faltaDeColunaObrigatoriaAbortaALeitura() {
		String semBandeira = CABECALHO.replace(";Bandeira", "");

		assertThatThrownBy(() -> leitor(arquivo(semBandeira)))
			.isInstanceOf(LayoutInvalidoException.class)
			.hasMessageContaining("Bandeira");
	}

	@Test
	void arquivoVazioAbortaALeitura() {
		assertThatThrownBy(() -> leitor(new byte[0])).isInstanceOf(LayoutInvalidoException.class)
			.hasMessageContaining("vazio");
	}

	@Test
	void colunaExtraNoCabecalhoEIgnorada() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO + ";Nova Coluna", GASOLINA_ANAPOLIS + ";qualquer"))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.ilegivel()).isFalse();
			assertThat(registro.campo(ColunaAnp.BANDEIRA)).isEqualTo("ALE");
		}
	}

	@Test
	void pularRetomaNoRegistroSeguinte() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS))) {
			leitor.pular(1);
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.numero()).isEqualTo(2);
			assertThat(registro.campo(ColunaAnp.PRODUTO)).isEqualTo("ETANOL");
		}
	}

	@Test
	void retomarDeQualquerPontoReproduzALeituraContinua() throws IOException {
		// Contrato do restart no M2: reabrir + pular(k) tem que dar exatamente o resto da leitura contínua,
		// inclusive passando por linhas ilegíveis, em branco e por um último registro sem LF.
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes(arquivo(CABECALHO, GASOLINA_ANAPOLIS, GASOLINA_GUARULHOS,
				GASOLINA_ANAPOLIS.replace("QD. 12", "\"QD. 12"), "", GASOLINA_BRASILIA_COM_ASPAS));
		bytes.writeBytes(GASOLINA_ANAPOLIS.replace("ANAPOLIS", "GOIÂNIA").getBytes(StandardCharsets.ISO_8859_1));
		bytes.writeBytes(("\r\n" + ETANOL_ANAPOLIS).getBytes(StandardCharsets.UTF_8));
		byte[] conteudo = bytes.toByteArray();

		List<String> continua = lerTudo(leitor(conteudo));
		assertThat(continua).hasSize(7);

		for (int k = 0; k <= continua.size(); k++) {
			try (LeitorAnp retomado = leitor(conteudo)) {
				retomado.pular(k);
				assertThat(retomado.registrosLidos()).isEqualTo(k);
				assertThat(lerTudo(retomado)).as("retomando depois de %d registros", k)
					.isEqualTo(continua.subList(k, continua.size()));
			}
		}
	}

	@Test
	void ultimoRegistroSemLfEhLidoESemLinhaFantasma() throws IOException {
		byte[] semLfNoFim = (new String(arquivo(CABECALHO), StandardCharsets.UTF_8) + GASOLINA_ANAPOLIS)
			.getBytes(StandardCharsets.UTF_8);

		assertThat(lerTudo(leitor(semLfNoFim))).hasSize(1);
		assertThat(lerTudo(leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS)))).hasSize(1);
	}

	@Test
	void aspasEscapadasViramUmaAspa() throws IOException {
		String linha = GASOLINA_ANAPOLIS.replace("QD. 12 - LT. 01 A 06", "\"LOTE \"\"A\"\"\"");
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, linha))) {
			assertThat(leitor.proximo().campo(ColunaAnp.COMPLEMENTO)).isEqualTo("LOTE \"A\"");
		}
	}

	@Test
	void mapeiaPeloNomeMesmoComAsColunasEmOutraOrdem() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(invertida(CABECALHO), invertida(GASOLINA_ANAPOLIS)))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.campo(ColunaAnp.REGIAO)).isEqualTo("CO");
			assertThat(registro.campo(ColunaAnp.PRODUTO)).isEqualTo("GASOLINA");
			assertThat(registro.campo(ColunaAnp.BANDEIRA)).isEqualTo("ALE");
		}
	}

	@Test
	void colunaOpcionalAusenteNaoImpedeALeitura() throws IOException {
		String semComplemento = CABECALHO.replace(";Complemento", "");
		try (LeitorAnp leitor = leitor(arquivo(semComplemento, GASOLINA_ANAPOLIS.replace(";QD. 12 - LT. 01 A 06", "")))) {
			RegistroBruto registro = leitor.proximo();

			assertThat(registro.ilegivel()).isFalse();
			assertThat(registro.campo(ColunaAnp.COMPLEMENTO)).isNull();
			assertThat(registro.campo(ColunaAnp.BAIRRO)).isEqualTo("ALVORADA");
		}
	}

	@Test
	void colunaRepetidaNoCabecalhoAbortaALeitura() {
		assertThatThrownBy(() -> leitor(arquivo(CABECALHO + ";Valor de Venda")))
			.isInstanceOf(LayoutInvalidoException.class)
			.hasMessageContaining("repetida");
	}

	@Test
	void pularAlemDoFimFalha() throws IOException {
		try (LeitorAnp leitor = leitor(arquivo(CABECALHO, GASOLINA_ANAPOLIS))) {
			assertThatThrownBy(() -> leitor.pular(2)).isInstanceOf(IllegalStateException.class);
		}
	}

	/** Resume cada registro em número, legibilidade e conteúdo, para comparar leituras. */
	private static List<String> lerTudo(LeitorAnp leitor) throws IOException {
		List<String> registros = new ArrayList<>();
		try (leitor) {
			RegistroBruto registro;
			while ((registro = leitor.proximo()) != null) {
				registros.add(registro.numero() + "|" + registro.ilegivel() + "|" + registro.conteudoBruto());
			}
		}
		return registros;
	}

	private static String invertida(String linhaSemAspas) {
		List<String> campos = new ArrayList<>(Arrays.asList(linhaSemAspas.split(";", -1)));
		Collections.reverse(campos);
		return String.join(";", campos);
	}

}
