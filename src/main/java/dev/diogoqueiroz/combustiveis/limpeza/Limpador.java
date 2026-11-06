package dev.diogoqueiroz.combustiveis.limpeza;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Optional;
import java.util.regex.Pattern;

import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;
import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Aceito;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Descartado;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Rejeitado;

/**
 * Aplica as regras de limpeza da SPEC (seção 6) a um registro. É uma função pura: mesmo registro e mesmo
 * relógio, mesmo resultado. A ordem importa: primeiro o que é ilegível, depois o que está fora do escopo
 * (descartado, sem ser erro), e só então as validações que mandam para a quarentena.
 */
public final class Limpador {

	static final String UF_ALVO = "GO";

	static final String UNIDADE_LITRO = "R$ / litro";

	static final String BANDEIRA_VAZIA = "NAO INFORMADA";

	private static final DateTimeFormatter DATA_ANP = DateTimeFormatter.ofPattern("dd/MM/uuuu")
		.withResolverStyle(ResolverStyle.STRICT);

	/** Vírgula decimal, no máximo 4 dígitos inteiros e 3 decimais: cabe em numeric(7,3). */
	private static final Pattern NUMERO_BR = Pattern.compile("\\d{1,4}(,\\d{1,3})?");

	private static final Pattern MASCARA_CNPJ = Pattern.compile("[.\\-/\\s]");

	private static final Pattern CNPJ_14_DIGITOS = Pattern.compile("\\d{14}");

	private final Clock relogio;

	public Limpador(Clock relogio) {
		this.relogio = relogio;
	}

	public ResultadoLimpeza limpar(RegistroBruto registro) {
		long numero = registro.numero();
		String bruto = registro.conteudoBruto();
		if (registro.ilegivel()) {
			return new Rejeitado(numero, MotivoRejeicao.PARSE_INVALIDO, registro.erroParse(), bruto);
		}

		if (!UF_ALVO.equals(Textos.normalizar(registro.campo(ColunaAnp.ESTADO)))) {
			return new Descartado(numero, MotivoDescarte.OUTRO_ESTADO);
		}
		Optional<Produto> produto = Produto.doNomeAnp(Textos.normalizar(registro.campo(ColunaAnp.PRODUTO)));
		if (produto.isEmpty()) {
			return new Descartado(numero, MotivoDescarte.OUTRO_PRODUTO);
		}

		String cnpjBruto = Textos.aparar(registro.campo(ColunaAnp.CNPJ));
		String cnpj = cnpjBruto == null ? null : MASCARA_CNPJ.matcher(cnpjBruto).replaceAll("");
		if (cnpj == null || !CNPJ_14_DIGITOS.matcher(cnpj).matches()) {
			return rejeitar(registro, MotivoRejeicao.CNPJ_INVALIDO, "CNPJ '" + cnpjBruto + "'");
		}

		String municipio = Textos.normalizar(registro.campo(ColunaAnp.MUNICIPIO));
		if (municipio == null) {
			return rejeitar(registro, MotivoRejeicao.MUNICIPIO_AUSENTE, "município vazio");
		}

		String dataBruta = Textos.aparar(registro.campo(ColunaAnp.DATA_COLETA));
		LocalDate data;
		try {
			data = dataBruta == null ? null : LocalDate.parse(dataBruta, DATA_ANP);
		}
		catch (DateTimeParseException e) {
			data = null;
		}
		if (data == null) {
			return rejeitar(registro, MotivoRejeicao.DATA_INVALIDA, "data '" + dataBruta + "'");
		}
		if (data.isAfter(LocalDate.now(relogio))) {
			return rejeitar(registro, MotivoRejeicao.DATA_INVALIDA, "data futura " + dataBruta);
		}

		String vendaBruta = Textos.aparar(registro.campo(ColunaAnp.VALOR_VENDA));
		BigDecimal venda = numero(vendaBruta);
		if (venda == null) {
			return rejeitar(registro, MotivoRejeicao.PRECO_NAO_NUMERICO, "valor de venda '" + vendaBruta + "'");
		}
		if (!produto.get().precoNaFaixa(venda)) {
			return rejeitar(registro, MotivoRejeicao.PRECO_FORA_FAIXA,
					produto.get().nomeAnp() + " a R$ " + vendaBruta);
		}

		String unidade = Textos.aparar(registro.campo(ColunaAnp.UNIDADE));
		if (!UNIDADE_LITRO.equals(unidade)) {
			return rejeitar(registro, MotivoRejeicao.UNIDADE_INCOMPATIVEL, "unidade '" + unidade + "'");
		}

		String compraBruta = Textos.aparar(registro.campo(ColunaAnp.VALOR_COMPRA));
		BigDecimal compra = compraBruta == null ? null : numero(compraBruta);
		if (compraBruta != null && compra == null) {
			return rejeitar(registro, MotivoRejeicao.PRECO_NAO_NUMERICO, "valor de compra '" + compraBruta + "'");
		}

		String bandeira = Textos.normalizar(registro.campo(ColunaAnp.BANDEIRA));
		return new Aceito(new PrecoLimpo(numero, UF_ALVO, municipio, Textos.aparar(registro.campo(ColunaAnp.REVENDA)),
				cnpj, Textos.aparar(registro.campo(ColunaAnp.BAIRRO)), Textos.aparar(registro.campo(ColunaAnp.CEP)),
				produto.get(), data, venda, compra, unidade, bandeira == null ? BANDEIRA_VAZIA : bandeira, bruto));
	}

	private static Rejeitado rejeitar(RegistroBruto registro, MotivoRejeicao motivo, String detalhe) {
		return new Rejeitado(registro.numero(), motivo, detalhe, registro.conteudoBruto());
	}

	/** "6,79" vira 6.790; qualquer outra coisa (ponto decimal, milhar, letras) vira {@code null}. */
	private static BigDecimal numero(String valor) {
		if (valor == null || !NUMERO_BR.matcher(valor).matches()) {
			return null;
		}
		return new BigDecimal(valor.replace(',', '.')).setScale(3);
	}

}
