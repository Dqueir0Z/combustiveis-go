package dev.diogoqueiroz.combustiveis.carga;

import static dev.diogoqueiroz.combustiveis.LinhasReais.GASOLINA_ANAPOLIS;
import static dev.diogoqueiroz.combustiveis.LinhasReais.com;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.diogoqueiroz.combustiveis.fonte.Fonte;
import dev.diogoqueiroz.combustiveis.leitura.ColunaAnp;

/**
 * SPEC seção 9: cada view sobre um fixture com o resultado calculado à mão. O fixture é inserido direto no
 * modelo, sem passar pela carga, para que cada número tenha uma origem óbvia.
 */
class ViewsTest extends IntegracaoBase {

	private long execucao;

	@BeforeEach
	void prepararDimensoes() {
		execucao = jdbc.queryForObject("""
				INSERT INTO execucao (fonte, url, sha256, obtido_em, status)
				VALUES ('fixture', 'fixture', repeat('0', 64), now(), 'PUBLICADA') RETURNING id
				""", Long.class);
		jdbc.update("INSERT INTO dim_municipio (uf, nome_normalizado) VALUES ('GO', 'GOIANIA'), ('GO', 'ANAPOLIS')");
		jdbc.update("INSERT INTO dim_bandeira (nome) VALUES ('BRANCA')");
		for (String posto : List.of("A", "B", "C", "D")) {
			jdbc.update("INSERT INTO dim_revenda (cnpj, razao_social, visto_em) VALUES (?, ?, '2026-10-01')",
					posto.repeat(14), "POSTO " + posto);
		}
	}

	@Test
	void precoSemanalEmGoEEmGoianiaComAMediaDoPostoNaSemana() {
		// Semana ISO 2026-41 (05 a 11/10). O posto A coletou duas vezes: o representativo dele é 6,10.
		preco("A", "GOIANIA", "GASOLINA", "2026-10-05", "6.00");
		preco("A", "GOIANIA", "GASOLINA", "2026-10-07", "6.20");
		preco("B", "GOIANIA", "GASOLINA", "2026-10-06", "6.50");
		preco("C", "ANAPOLIS", "GASOLINA", "2026-10-06", "7.00");

		// GO: (6,10 + 6,50 + 7,00) / 3 = 6,5333. Goiânia: (6,10 + 6,50) / 2 = 6,30.
		assertThat(semanal("GO")).containsEntry("preco_medio", "6.533").containsEntry("n_postos", 3L);
		assertThat(semanal("GOIANIA")).containsEntry("preco_medio", "6.300").containsEntry("n_postos", 2L);
	}

	@Test
	void variacaoUsaSoOsPostosPresentesNasDuasSemanas() {
		// Semana 40: A a 5,90 e D a 6,00 (D some na 41). Semana 41: A a 6,10 (média de duas coletas) e B a 6,50
		// (B não estava na 40). Interseção = {A}: 5,90 → 6,10, variação 0,20 (3,39%).
		preco("A", "GOIANIA", "GASOLINA", "2026-09-30", "5.90");
		preco("D", "GOIANIA", "GASOLINA", "2026-09-30", "6.00");
		preco("A", "GOIANIA", "GASOLINA", "2026-10-05", "6.00");
		preco("A", "GOIANIA", "GASOLINA", "2026-10-07", "6.20");
		preco("B", "GOIANIA", "GASOLINA", "2026-10-06", "6.50");

		Map<String, Object> variacao = jdbc.queryForMap("""
				SELECT semana_iso, n_postos_comuns, media_semana_anterior::text, media_semana::text, variacao::text,
				       variacao_pct::text
				FROM vw_variacao_semanal
				""");

		assertThat(variacao).containsEntry("semana_iso", 41)
			.containsEntry("n_postos_comuns", 1L)
			.containsEntry("media_semana_anterior", "5.900")
			.containsEntry("media_semana", "6.100")
			.containsEntry("variacao", "0.200")
			.containsEntry("variacao_pct", "3.39");
	}

	@Test
	void semanaAusenteNaoViraComparacaoComUmaSemanaMaisAntiga() {
		// Dados nas semanas 41 e 43; a 42 está ausente. A 43 não tem "semana anterior": nenhuma linha.
		preco("A", "GOIANIA", "GASOLINA", "2026-10-06", "6.00");
		preco("A", "GOIANIA", "GASOLINA", "2026-10-20", "6.50");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM vw_variacao_semanal", Long.class)).isZero();
	}

	@Test
	void variacaoAtravessaAViradaDoAnoIso() {
		// 22/12/2025 é da semana 2025-52; 29/12/2025 já é da semana 2026-01 (ano ISO diferente do ano civil).
		preco("A", "GOIANIA", "ETANOL", "2025-12-22", "4.00");
		preco("A", "GOIANIA", "ETANOL", "2025-12-29", "4.20");

		Map<String, Object> variacao = jdbc.queryForMap(
				"SELECT ano_iso, semana_iso, inicio_semana::text, variacao::text, variacao_pct::text FROM vw_variacao_semanal");

		assertThat(variacao).containsEntry("ano_iso", 2026)
			.containsEntry("semana_iso", 1)
			.containsEntry("inicio_semana", "2025-12-29")
			.containsEntry("variacao", "0.200")
			.containsEntry("variacao_pct", "5.00");
	}

	@Test
	void etanolCompensaUsaAMedianaDasRazoesPorPosto() {
		// Semana 41 em Goiânia. Razões por posto: A 4,00/6,00 = 0,6667; B 4,90/7,00 = 0,70; C 3,00/6,00 = 0,50.
		// Mediana = 0,6667. Compensam no limite de 0,70: A, B (igual ao limite) e C = 3.
		// D tem só gasolina: fica fora (precisa dos dois produtos na mesma semana).
		preco("A", "GOIANIA", "GASOLINA", "2026-10-05", "6.00");
		preco("A", "GOIANIA", "ETANOL", "2026-10-08", "4.00");
		preco("B", "GOIANIA", "GASOLINA", "2026-10-06", "7.00");
		preco("B", "GOIANIA", "ETANOL", "2026-10-06", "4.90");
		preco("C", "GOIANIA", "GASOLINA", "2026-10-06", "6.00");
		preco("C", "GOIANIA", "ETANOL", "2026-10-06", "3.00");
		preco("D", "GOIANIA", "GASOLINA", "2026-10-06", "6.00");

		Map<String, Object> municipio = jdbc.queryForMap(
				"SELECT n_postos, mediana_razao::text, n_postos_compensa FROM vw_etanol_compensa");

		assertThat(municipio).containsEntry("n_postos", 3L)
			.containsEntry("mediana_razao", "0.6667")
			.containsEntry("n_postos_compensa", 3L);
	}

	@Test
	void coberturaContaPostosPorProdutoEMunicipio() {
		preco("A", "GOIANIA", "GASOLINA", "2026-10-05", "6.00");
		preco("A", "GOIANIA", "GASOLINA", "2026-10-07", "6.20");
		preco("B", "GOIANIA", "GASOLINA", "2026-10-06", "6.50");

		assertThat(jdbc.queryForMap("SELECT n_postos, n_coletas FROM vw_cobertura"))
			.containsEntry("n_postos", 2L)
			.containsEntry("n_coletas", 3L);
	}

	@Test
	void cargaComSemanaFaltandoGravaOAvisoComOSemestralACarregar() {
		jdbc.execute("TRUNCATE fato_preco, dim_revenda, dim_municipio, dim_bandeira, execucao RESTART IDENTITY CASCADE");
		ResultadoCarga carga = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS,
				com(GASOLINA_ANAPOLIS, ColunaAnp.DATA_COLETA, "20/10/2026")), false);

		assertThat(execucao(carga.execucaoId()).get("aviso_cobertura"))
			.isEqualTo("Faltam 1 semana(s) sem dados (2026-S42). Carregue: semestral:2026-2.");
		assertThat(jdbc.queryForObject("SELECT aviso_cobertura FROM vw_atualizacao", String.class))
			.startsWith("Faltam 1 semana");
	}

	@Test
	void cargaSemLacunaNaoGravaAviso() {
		jdbc.execute("TRUNCATE fato_preco, dim_revenda, dim_municipio, dim_bandeira, execucao RESTART IDENTITY CASCADE");
		ResultadoCarga carga = cargas.carregar(arquivo(Fonte.ULTIMAS4_GASOLINA_ETANOL, GASOLINA_ANAPOLIS,
				com(GASOLINA_ANAPOLIS, ColunaAnp.DATA_COLETA, "13/10/2026")), false);

		assertThat(execucao(carga.execucaoId()).get("aviso_cobertura")).isNull();
	}

	private Map<String, Object> semanal(String nivel) {
		return jdbc.queryForMap("SELECT preco_medio::text, n_postos FROM vw_preco_semanal WHERE nivel = ?", nivel);
	}

	private void preco(String posto, String municipio, String produto, String data, String valor) {
		jdbc.update("""
				INSERT INTO dim_data (data, ano, mes, semana_iso, ano_iso, dia_semana)
				SELECT d, extract(year FROM d), extract(month FROM d), extract(week FROM d), extract(isoyear FROM d),
				       extract(isodow FROM d)
				FROM (SELECT ?::date AS d) x
				ON CONFLICT (data) DO NOTHING
				""", data);
		jdbc.update("""
				INSERT INTO fato_preco (data, revenda_id, municipio_id, produto_id, bandeira_id, valor_venda,
				                        fonte_prioridade, execucao_id)
				SELECT ?::date, r.id, m.id, p.id, b.id, ?::numeric, 1, ?
				FROM dim_revenda r, dim_municipio m, dim_produto p, dim_bandeira b
				WHERE r.cnpj = ? AND m.nome_normalizado = ? AND p.nome = ? AND b.nome = 'BRANCA'
				""", data, valor, execucao, posto.repeat(14), municipio, produto);
	}

}
