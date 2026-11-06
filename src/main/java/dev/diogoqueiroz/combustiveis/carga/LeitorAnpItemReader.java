package dev.diogoqueiroz.combustiveis.carga;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamException;
import org.springframework.batch.infrastructure.item.ItemStreamReader;

import dev.diogoqueiroz.combustiveis.leitura.LeitorAnp;
import dev.diogoqueiroz.combustiveis.leitura.RegistroBruto;

/**
 * Adapta o {@link LeitorAnp} ao Spring Batch, com checkpoint.
 *
 * <p>A cada commit de chunk, o Batch chama {@link #update} e grava quantos registros já foram lidos no
 * ExecutionContext do step, na mesma transação do chunk. Num reinício, {@link #open} encontra esse número e
 * pula os registros já gravados no staging. É isso que impede o reinício de duplicar o staging.
 */
public class LeitorAnpItemReader implements ItemStreamReader<RegistroBruto> {

	static final String LIDOS = "registrosLidos";

	private final Path arquivo;

	private LeitorAnp leitor;

	public LeitorAnpItemReader(Path arquivo) {
		this.arquivo = arquivo;
	}

	@Override
	public void open(ExecutionContext contexto) {
		try {
			leitor = new LeitorAnp(Files.newInputStream(arquivo));
			if (contexto.containsKey(LIDOS)) {
				leitor.pular(contexto.getLong(LIDOS));
			}
		}
		catch (IOException e) {
			throw new ItemStreamException("não foi possível abrir " + arquivo, e);
		}
	}

	@Override
	public RegistroBruto read() {
		try {
			return leitor.proximo();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void update(ExecutionContext contexto) {
		contexto.putLong(LIDOS, leitor.registrosLidos());
	}

	@Override
	public void close() {
		if (leitor != null) {
			try {
				leitor.close();
			}
			catch (IOException e) {
				throw new ItemStreamException(e);
			}
		}
	}

}
