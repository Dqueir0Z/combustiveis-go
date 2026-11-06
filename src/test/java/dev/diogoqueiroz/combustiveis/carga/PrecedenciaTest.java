package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.diogoqueiroz.combustiveis.fonte.Fonte;
import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;

/**
 * SPEC seção 7: precedência no upsert. Prioridade maior vence; na mesma prioridade, a execução mais nova;
 * prioridade menor nunca sobrescreve.
 */
class PrecedenciaTest extends IntegracaoBase {

	private static final Fonte SEMESTRAL = Fonte.semestral(2026, 2);

	private static final String CORRIGIDA = com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_VENDA, "6,99");

	@Test
	void correcaoMaisNovaDeMesmaPrioridadeVence() {
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS), false);
		ResultadoCarga correcao = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, CORRIGIDA), false);

		assertThat(execucao(correcao.execucaoId())).containsEntry("atualizados", 1L).containsEntry("sem_mudanca", 0L);
		assertThat(fato()).containsEntry("valor_venda", "6.990")
			.containsEntry("fonte_prioridade", 1)
			.containsEntry("execucao_id", correcao.execucaoId());
	}

	@Test
	void semestralSobrescreveOPreliminar() {
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, CORRIGIDA), false);
		ResultadoCarga semestral = cargas.carregar(arquivo(SEMESTRAL, GASOLINA_ANAPOLIS), false);

		assertThat(execucao(semestral.execucaoId())).containsEntry("atualizados", 1L);
		assertThat(fato()).containsEntry("valor_venda", "6.770").containsEntry("fonte_prioridade", 2);
	}

	@Test
	void preliminarBaixadoDepoisNaoSobrescreveOSemestral() {
		cargas.carregar(arquivo(SEMESTRAL, GASOLINA_ANAPOLIS), false);
		ResultadoCarga preliminar = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, CORRIGIDA), false);

		assertThat(execucao(preliminar.execucaoId())).containsEntry("aceitas", 1L)
			.containsEntry("inseridos", 0L)
			.containsEntry("atualizados", 0L)
			.containsEntry("sem_mudanca", 1L);
		assertThat(fato()).containsEntry("valor_venda", "6.770").containsEntry("fonte_prioridade", 2);
	}

	@Test
	void cadastroDeColetaAntigaNaoApagaOCadastroMaisNovo() {
		String recente = com(GASOLINA_ANAPOLIS, ColunaAnp.REVENDA, "NOME NOVO LTDA");
		String antiga = com(com(GASOLINA_ANAPOLIS, ColunaAnp.REVENDA, "NOME ANTIGO LTDA"), ColunaAnp.DATA_COLETA,
				"05/01/2026");
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, recente), false);
		cargas.carregar(arquivo(Fonte.semestral(2026, 1), antiga), false);

		assertThat(jdbc.queryForMap("SELECT razao_social, visto_em::text FROM dim_revenda"))
			.containsEntry("razao_social", "NOME NOVO LTDA")
			.containsEntry("visto_em", "2026-10-06");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM fato_preco", Long.class)).isEqualTo(2);
	}

	private Map<String, Object> fato() {
		return jdbc.queryForMap("SELECT valor_venda::text, fonte_prioridade, execucao_id FROM fato_preco");
	}

}
