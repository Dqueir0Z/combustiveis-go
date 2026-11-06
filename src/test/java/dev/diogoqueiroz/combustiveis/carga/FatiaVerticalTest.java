package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.ETANOL_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_BRASILIA_COM_ASPAS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_GUARULHOS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.diogoqueiroz.combustiveis.carga.ResultadoCarga.Situacao;
import dev.diogoqueiroz.combustiveis.fonte.Fonte;
import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;

/**
 * M2: um arquivo ultimas4 atravessa o pipeline inteiro e responde a pergunta 2 na view.
 */
class FatiaVerticalTest extends IntegracaoBase {

	@Test
	void cargaPublicaOFatoEFechaAReconciliacao() {
		ResultadoCarga resultado = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL,
				GASOLINA_ANAPOLIS,                                   // aceita
				ETANOL_ANAPOLIS,                                     // aceita
				GASOLINA_GUARULHOS,                                  // descartada: SP
				GASOLINA_BRASILIA_COM_ASPAS,                         // descartada: DF
				GASOLINA_ANAPOLIS.replace("QD. 12", "\"QD. 12"),     // rejeitada: aspas quebradas
				GASOLINA_ANAPOLIS),                                  // duplicada
				false);

		assertThat(resultado.situacao()).isEqualTo(Situacao.PUBLICADA);
		Map<String, Object> execucao = execucao(resultado.execucaoId());
		assertThat(execucao).containsEntry("status", "PUBLICADA")
			.containsEntry("fonte", "ultimas4:gasolina-etanol")
			.containsEntry("lidas", 6L)
			.containsEntry("aceitas", 2L)
			.containsEntry("descartadas", 2L)
			.containsEntry("duplicadas", 1L)
			.containsEntry("rejeitadas", 1L)
			.containsEntry("inseridos", 2L)
			.containsEntry("atualizados", 0L)
			.containsEntry("sem_mudanca", 0L);
		assertThat(execucao.get("periodo_min").toString()).isEqualTo("2026-10-06");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM fato_preco", Long.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM stg_preco", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT motivo FROM rejeito", String.class)).isEqualTo("PARSE_INVALIDO");
		assertThat(jdbc.queryForObject("SELECT n_registro FROM rejeito", Long.class)).isEqualTo(5);
	}

	@Test
	void dimensoesSaemNormalizadasEComChavesUnicas() {
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS), false);

		assertThat(jdbc.queryForMap("SELECT cnpj, razao_social, visto_em::text FROM dim_revenda"))
			.containsEntry("cnpj", "07049692000101")
			.containsEntry("razao_social", "IMPERIAL COMÉRCIO DE COMBUSTÍVEIS LTDA.")
			.containsEntry("visto_em", "2026-10-06");
		assertThat(jdbc.queryForList("SELECT nome_normalizado FROM dim_municipio", String.class))
			.containsExactly("ANAPOLIS");
		assertThat(jdbc.queryForList("SELECT nome FROM dim_bandeira", String.class)).containsExactly("ALE");
		// 06/10/2026 é uma terça-feira da semana ISO 41.
		assertThat(jdbc.queryForMap("SELECT ano_iso, semana_iso, dia_semana FROM dim_data"))
			.containsEntry("ano_iso", 2026)
			.containsEntry("semana_iso", 41)
			.containsEntry("dia_semana", 2);
	}

	@Test
	void viewRespondeOndeOEtanolCompensa() {
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS, ETANOL_ANAPOLIS), false);

		// Etanol a 4,47 e gasolina a 6,77: razão 0,6603, abaixo do limite padrão de 0,70.
		Map<String, Object> posto = jdbc.queryForMap(
				"SELECT municipio, compensa_no_limite_padrao, round(razao, 4) AS razao, inicio_semana "
						+ "FROM vw_etanol_compensa_posto");
		assertThat(posto).containsEntry("municipio", "ANAPOLIS")
			.containsEntry("compensa_no_limite_padrao", true);
		assertThat(posto.get("razao").toString()).isEqualTo("0.6603");
		assertThat(posto.get("inicio_semana").toString()).isEqualTo("2026-10-05");

		Map<String, Object> municipio = jdbc.queryForMap("SELECT * FROM vw_etanol_compensa");
		assertThat(municipio).containsEntry("n_postos", 1L).containsEntry("n_postos_compensa", 1L);
		assertThat(municipio.get("mediana_razao").toString()).isEqualTo("0.6603");
	}

	@Test
	void arredondamentoNaoInverteOResultadoNaBordaDoLimite() {
		// Exemplo da revisão do Codex: etanol médio 2,1005 / gasolina 3,001 = 0,69993 (compensa). Se a média
		// fosse arredondada para 2,101 antes de dividir, daria 0,70010 (não compensaria).
		String gasolina = com(GASOLINA_ANAPOLIS, ColunaAnp.VALOR_VENDA, "3,001");
		String etanolTerca = com(ETANOL_ANAPOLIS, ColunaAnp.VALOR_VENDA, "2,100");
		String etanolQuarta = com(com(ETANOL_ANAPOLIS, ColunaAnp.VALOR_VENDA, "2,101"), ColunaAnp.DATA_COLETA,
				"07/10/2026");
		cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, gasolina, etanolTerca, etanolQuarta), false);

		assertThat(jdbc.queryForObject("SELECT compensa_no_limite_padrao FROM vw_etanol_compensa_posto", Boolean.class))
			.isTrue();
	}

}
