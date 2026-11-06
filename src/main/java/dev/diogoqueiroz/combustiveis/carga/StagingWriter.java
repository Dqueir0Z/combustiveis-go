package dev.diogoqueiroz.combustiveis.carga;

import java.util.ArrayList;
import java.util.List;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

import dev.diogoqueiroz.combustiveis.limpeza.PrecoLimpo;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Aceito;
import dev.diogoqueiroz.combustiveis.limpeza.ResultadoLimpeza.Rejeitado;

/**
 * Grava cada chunk no staging (aceitos) e na quarentena (rejeitados), isolado pela execução.
 * Os descartados nem chegam aqui: o processador os filtra, e o Batch os conta como "filtrados".
 */
public class StagingWriter implements ItemWriter<ResultadoLimpeza> {

	private static final String INSERIR_STAGING = """
			INSERT INTO stg_preco (execucao_id, n_registro, uf, municipio, revenda, cnpj, bairro, cep, produto,
			                       data_coleta, valor_venda, valor_compra, unidade, bandeira, conteudo_bruto)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String INSERIR_REJEITO = """
			INSERT INTO rejeito (execucao_id, arquivo, n_registro, motivo, detalhe, conteudo_bruto)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;

	private final long execucaoId;

	private final String arquivo;

	public StagingWriter(JdbcTemplate jdbc, long execucaoId, String arquivo) {
		this.jdbc = jdbc;
		this.execucaoId = execucaoId;
		this.arquivo = arquivo;
	}

	@Override
	public void write(Chunk<? extends ResultadoLimpeza> chunk) {
		List<Object[]> aceitos = new ArrayList<>();
		List<Object[]> rejeitados = new ArrayList<>();
		for (ResultadoLimpeza resultado : chunk) {
			switch (resultado) {
				case Aceito(PrecoLimpo p) -> aceitos.add(new Object[] { execucaoId, p.numero(), p.uf(), p.municipio(),
						p.revenda(), p.cnpj(), p.bairro(), p.cep(), p.produto().nomeAnp(), p.dataColeta(),
						p.valorVenda(), p.valorCompra(), p.unidade(), p.bandeira(), p.conteudoBruto() });
				case Rejeitado r -> rejeitados.add(new Object[] { execucaoId, arquivo, r.numero(), r.motivo().name(),
						r.detalhe(), r.conteudoBruto() });
				case ResultadoLimpeza.Descartado d -> throw new IllegalStateException(
						"descartado chegou ao writer: registro " + d.numero());
			}
		}
		if (!aceitos.isEmpty()) {
			jdbc.batchUpdate(INSERIR_STAGING, aceitos);
		}
		if (!rejeitados.isEmpty()) {
			jdbc.batchUpdate(INSERIR_REJEITO, rejeitados);
		}
	}

}
