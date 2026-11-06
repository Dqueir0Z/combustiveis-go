package dev.diogoqueiroz.combustiveis.carga;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.stereotype.Component;

/**
 * Garante um executor por vez, em qualquer máquina ou processo, com um lock consultivo do PostgreSQL
 * (SPEC seção 5). O lock é da sessão: vale enquanto a conexão do {@link Lock} estiver aberta, e o banco o
 * libera sozinho se o processo morrer.
 */
@Component
public class ExecutorUnico {

	/** Número arbitrário e fixo que identifica "a carga do combustiveis-go" entre os locks do banco. */
	static final long CHAVE_DO_LOCK = 2026_10_21L;

	private final DataSource dataSource;

	public ExecutorUnico(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** Tenta pegar o lock sem esperar; devolve {@code null} se outro executor já o tem. */
	public Lock tentarAdquirir() throws SQLException {
		Connection conexao = dataSource.getConnection();
		try (PreparedStatement sql = conexao.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
			sql.setLong(1, CHAVE_DO_LOCK);
			try (ResultSet resultado = sql.executeQuery()) {
				resultado.next();
				if (resultado.getBoolean(1)) {
					return new Lock(conexao);
				}
			}
		}
		catch (SQLException e) {
			conexao.close();
			throw e;
		}
		conexao.close();
		return null;
	}

	public static final class Lock implements AutoCloseable {

		private final Connection conexao;

		private Lock(Connection conexao) {
			this.conexao = conexao;
		}

		@Override
		public void close() throws SQLException {
			try (conexao; PreparedStatement sql = conexao.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				sql.setLong(1, CHAVE_DO_LOCK);
				sql.execute();
			}
		}

	}

}
