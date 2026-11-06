package dev.diogoqueiroz.combustiveis.limpeza;

/**
 * O destino de um registro depois da limpeza. Todo registro lido termina em exatamente um destes
 * (SPEC seção 6); duplicadas e conflitos só aparecem na publicação, porque dependem do arquivo inteiro.
 */
public sealed interface ResultadoLimpeza {

	long numero();

	record Aceito(PrecoLimpo preco) implements ResultadoLimpeza {

		@Override
		public long numero() {
			return preco.numero();
		}

	}

	record Descartado(long numero, MotivoDescarte motivo) implements ResultadoLimpeza {
	}

	record Rejeitado(long numero, MotivoRejeicao motivo, String detalhe, String conteudoBruto)
			implements ResultadoLimpeza {
	}

}
