package dev.diogoqueiroz.combustiveis.limpeza;

import static dev.diogoqueiroz.combustiveis.LinhasReais.DIESEL_S10_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_BRASILIA_COM_ASPAS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_GUARULHOS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static dev.diogoqueiroz.combustiveis.LinhasReais.registro;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;
import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Aceito;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Descartado;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Rejeitado;

/**
 * Um caso por regra da SPEC seção 6, sempre partindo de linhas reais da ANP.
 */
class LimpadorTest {

	/** "Hoje" fixo em 06/11/2026, o dia em que as linhas foram baixadas. */
	private final Limpador limpador = new Limpador(
			Clock.fixed(Instant.parse("2026-11-06T15:00:00Z"), ZoneId.of("America/Sao_Paulo")));

	@Test
	void aceitaLinhaRealDeGoiasEConverteOsCampos() {
		PrecoLimpo preco = aceito(GASOLINA_ANAPOLIS);

		assertThat(preco.numero()).isEqualTo(1);
		assertThat(preco.uf()).isEqualTo("GO");
		assertThat(preco.municipio()).isEqualTo("ANAPOLIS");
		assertThat(preco.revenda()).isEqualTo("IMPERIAL COMÉRCIO DE COMBUSTÍVEIS LTDA.");
		assertThat(preco.cnpj()).isEqualTo("07049692000101");
		assertThat(preco.bairro()).isEqualTo("ALVORADA");
		assertThat(preco.cep()).isEqualTo("75084-555");
		assertThat(preco.produto()).isEqualTo(Produto.GASOLINA);
		assertThat(preco.dataColeta()).isEqualTo(LocalDate.of(2026, 10, 6));
		assertThat(preco.valorVenda()).isEqualByComparingTo("6.77");
		assertThat(preco.valorCompra()).isNull();
		assertThat(preco.unidade()).isEqualTo("R$ / litro");
		assertThat(preco.bandeira()).isEqualTo("ALE");
		assertThat(preco.conteudoBruto()).isEqualTo(GASOLINA_ANAPOLIS);
	}

	@Test
	void aceitaOsCincoProdutosDoEscopo() {
		assertThat(aceito(ETANOL_ANAPOLIS).produto()).isEqualTo(Produto.ETANOL);
		assertThat(aceito(DIESEL_S10_ANAPOLIS).produto()).isEqualTo(Produto.DIESEL_S10);
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.PRODUTO, "GASOLINA ADITIVADA")).produto())
			.isEqualTo(Produto.GASOLINA_ADITIVADA);
		assertThat(aceito(com(DIESEL_S10_ANAPOLIS, ColunaAnp.PRODUTO, "DIESEL")).produto()).isEqualTo(Produto.DIESEL);
	}

	@Test
	void registroIlegivelVaiParaQuarentenaComoParseInvalido() {
		RegistroBruto ilegivel = RegistroBruto.ilegivel(7, "linha;quebrada", "CSV malformado");

		Rejeitado rejeitado = (Rejeitado) limpador.limpar(ilegivel);

		assertThat(rejeitado.motivo()).isEqualTo(MotivoRejeicao.PARSE_INVALIDO);
		assertThat(rejeitado.numero()).isEqualTo(7);
		assertThat(rejeitado.conteudoBruto()).isEqualTo("linha;quebrada");
	}

	@Test
	void outroEstadoEDescartadoSemSerErro() {
		Descartado descartado = (Descartado) limpador.limpar(registro(GASOLINA_GUARULHOS));

		assertThat(descartado.motivo()).isEqualTo(MotivoDescarte.OUTRO_ESTADO);
	}

	@Test
	void outroEstadoEDescartadoMesmoComCampoInvalido() {
		// Fora do escopo vem antes da validação: um CNPJ ruim em SP não conta como rejeitado.
		String spComCnpjRuim = com(GASOLINA_GUARULHOS, ColunaAnp.CNPJ, "abc");

		assertThat(limpador.limpar(registro(spComCnpjRuim))).isInstanceOf(Descartado.class);
	}

	@Test
	void linhaComAspasDeOutroEstadoTambemEDescartada() {
		assertThat(limpador.limpar(registro(GASOLINA_BRASILIA_COM_ASPAS))).isInstanceOf(Descartado.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "GNV", "GLP", "" })
	void produtoForaDoEscopoEDescartado(String produto) {
		Descartado descartado = (Descartado) limpador.limpar(registro(com(GASOLINA_ANAPOLIS, ColunaAnp.PRODUTO, produto)));

		assertThat(descartado.motivo()).isEqualTo(MotivoDescarte.OUTRO_PRODUTO);
	}

	@ParameterizedTest
	@ValueSource(strings = { " 07.049.692/0001-0", "07.049.692/0001-011", "07.O49.692/0001-01", "" })
	void cnpjSemExatamente14DigitosERejeitado(String cnpj) {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.CNPJ, cnpj))).isEqualTo(MotivoRejeicao.CNPJ_INVALIDO);
	}

	@Test
	void cnpjSemMascaraTambemEAceito() {
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.CNPJ, "07049692000101")).cnpj()).isEqualTo("07049692000101");
	}

	@Test
	void municipioVazioERejeitado() {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.MUNICIPIO, "  "))).isEqualTo(MotivoRejeicao.MUNICIPIO_AUSENTE);
	}

	@Test
	void municipioFicaEmMaiusculasSemAcentoESemEspacoSobrando() {
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.MUNICIPIO, " Goiânia  ")).municipio()).isEqualTo("GOIANIA");
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.MUNICIPIO, "APARECIDA  DE GOIÂNIA")).municipio())
			.isEqualTo("APARECIDA DE GOIANIA");
	}

	@ParameterizedTest
	@ValueSource(strings = { "31/02/2026", "2026-10-06", "6/10/2026", "06/10/26", "" })
	void dataForaDoFormatoOuInexistenteERejeitada(String data) {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.DATA_COLETA, data))).isEqualTo(MotivoRejeicao.DATA_INVALIDA);
	}

	@Test
	void dataFuturaERejeitadaEHojeEAceito() {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.DATA_COLETA, "07/11/2026")))
			.isEqualTo(MotivoRejeicao.DATA_INVALIDA);
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.DATA_COLETA, "06/11/2026")).dataColeta())
			.isEqualTo(LocalDate.of(2026, 11, 6));
	}

	@ParameterizedTest
	@ValueSource(strings = { "6.77", "R$ 6,77", "6,7777", "1.006,77", "abc", "" })
	void valorDeVendaQueNaoEUmNumeroComVirgulaERejeitado(String valor) {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_VENDA, valor)))
			.isEqualTo(MotivoRejeicao.PRECO_NAO_NUMERICO);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "GASOLINA|1,99", "GASOLINA|12,01", "GASOLINA ADITIVADA|12,50",
			"ETANOL|1,49", "ETANOL|10,01", "DIESEL|1,99", "DIESEL S10|12,01" })
	void precoForaDaFaixaDoProdutoERejeitado(String produto, String valor) {
		String linha = com(com(GASOLINA_ANAPOLIS, ColunaAnp.PRODUTO, produto), ColunaAnp.VALOR_VENDA, valor);

		assertThat(motivo(linha)).isEqualTo(MotivoRejeicao.PRECO_FORA_FAIXA);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "GASOLINA|2,00", "GASOLINA|12,00", "ETANOL|1,50", "ETANOL|10,00",
			"DIESEL S10|2,00" })
	void limitesDaFaixaSaoAceitos(String produto, String valor) {
		String linha = com(com(GASOLINA_ANAPOLIS, ColunaAnp.PRODUTO, produto), ColunaAnp.VALOR_VENDA, valor);

		assertThat(limpador.limpar(registro(linha))).isInstanceOf(Aceito.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "R$ / m³", "R$ / litros", "" })
	void unidadeDiferenteDeLitroERejeitada(String unidade) {
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.UNIDADE, unidade)))
			.isEqualTo(MotivoRejeicao.UNIDADE_INCOMPATIVEL);
	}

	@Test
	void valorDeCompraPreenchidoEConvertidoEInvalidoERejeitado() {
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_COMPRA, "5,90")).valorCompra())
			.isEqualTo(new BigDecimal("5.900"));
		assertThat(motivo(com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_COMPRA, "5.90")))
			.isEqualTo(MotivoRejeicao.PRECO_NAO_NUMERICO);
	}

	@Test
	void bandeiraVaziaViraNaoInformadaEBrancaContinuaBranca() {
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.BANDEIRA, "")).bandeira()).isEqualTo("NAO INFORMADA");
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.BANDEIRA, "BRANCA")).bandeira()).isEqualTo("BRANCA");
		assertThat(aceito(com(GASOLINA_ANAPOLIS, ColunaAnp.BANDEIRA, "Petrobrás ")).bandeira()).isEqualTo("PETROBRAS");
	}

	@Test
	void campoVazioViraNull() {
		PrecoLimpo preco = aceito(com(com(GASOLINA_ANAPOLIS, ColunaAnp.BAIRRO, "   "), ColunaAnp.REVENDA, ""));

		assertThat(preco.bairro()).isNull();
		assertThat(preco.revenda()).isNull();
	}

	private PrecoLimpo aceito(String linha) {
		ResultadoLimpeza resultado = limpador.limpar(registro(linha));
		assertThat(resultado).isInstanceOf(Aceito.class);
		return ((Aceito) resultado).preco();
	}

	private MotivoRejeicao motivo(String linha) {
		ResultadoLimpeza resultado = limpador.limpar(registro(linha));
		assertThat(resultado).isInstanceOf(Rejeitado.class);
		return ((Rejeitado) resultado).motivo();
	}

}
