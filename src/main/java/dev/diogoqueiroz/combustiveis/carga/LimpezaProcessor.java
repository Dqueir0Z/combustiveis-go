package dev.diogoqueiroz.combustiveis.carga;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStream;

import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;
import dev.diogoqueiroz.combustiveis.limpeza.Limpador;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza;

/**
 * Aplica o {@link Limpador} e filtra os descartados (devolve {@code null}), contando-os no checkpoint.
 *
 * <p>Por que contar aqui e não usar o filterCount do Batch: o step de chunk do Batch 6 soma no readCount (e
 * no filterCount) itens de um chunk que falhou e voltou. Num reinício, a soma das tentativas passa do
 * tamanho do arquivo. O ExecutionContext, ao contrário, só é gravado no commit do chunk, junto com o
 * staging: é a mesma fonte da verdade do checkpoint do reader.
 */
public class LimpezaProcessor implements ItemProcessor<RegistroBruto, ResultadoLimpeza>, ItemStream {

	static final String DESCARTADOS = "registrosDescartados";

	private final Limpador limpador;

	private long descartados;

	public LimpezaProcessor(Limpador limpador) {
		this.limpador = limpador;
	}

	@Override
	public ResultadoLimpeza process(RegistroBruto registro) {
		ResultadoLimpeza resultado = limpador.limpar(registro);
		if (resultado instanceof ResultadoLimpeza.Descartado) {
			descartados++;
			return null;
		}
		return resultado;
	}

	@Override
	public void open(ExecutionContext contexto) {
		descartados = contexto.containsKey(DESCARTADOS) ? contexto.getLong(DESCARTADOS) : 0;
	}

	@Override
	public void update(ExecutionContext contexto) {
		contexto.putLong(DESCARTADOS, descartados);
	}

}
