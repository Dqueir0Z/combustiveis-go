package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.ArquivoBaixado;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;

/**
 * SPEC seção 9: repetir uma carga não muda nem valores nem contagens.
 */
class IdempotenciaTest extends IntegracaoBase {

	private static final String FATO = "SELECT revenda_id, produto_id, data, valor_venda, municipio_id, bandeira_id "
			+ "FROM fato_preco ORDER BY revenda_id, produto_id, data";

	@Test
	void mesmoArquivoDeNovoDaSemNovidadeSemLancarOJob() {
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS);
		cargas.carregar(arquivo, false);
		List<Map<String, Object>> fatoAntes = jdbc.queryForList(FATO);

		ResultadoCarga segunda = cargas.carregar(arquivo, false);

		assertThat(segunda.situacao()).isEqualTo(Situacao.SEM_NOVIDADE);
		assertThat(segunda.execucaoId()).isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM execucao", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM batch.batch_job_instance", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM batch.batch_job_execution", Long.class)).isEqualTo(1);
		assertThat(jdbc.queryForList(FATO)).isEqualTo(fatoAntes);
	}

	@Test
	void reprocessarDePropositoNaoMudaOsValoresEContaTudoComoSemMudanca() {
		ArquivoBaixado arquivo = arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS);
		cargas.carregar(arquivo, false);
		List<Map<String, Object>> fatoAntes = jdbc.queryForList(FATO);

		ResultadoCarga reprocesso = cargas.carregar(arquivo, true);

		assertThat(reprocesso.situacao()).isEqualTo(Situacao.PUBLICADA);
		assertThat(execucao(reprocesso.execucaoId())).containsEntry("aceitas", 2L)
			.containsEntry("inseridos", 0L)
			.containsEntry("atualizados", 0L)
			.containsEntry("sem_mudanca", 2L);
		assertThat(jdbc.queryForList(FATO)).isEqualTo(fatoAntes);
		// Mesma prioridade e execução mais nova: a proveniência passa para o reprocesso, os valores não mudam.
		assertThat(jdbc.queryForList("SELECT DISTINCT execucao_id FROM fato_preco", Long.class))
			.containsExactly(reprocesso.execucaoId());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM dim_revenda", Long.class)).isEqualTo(1);
	}

}
