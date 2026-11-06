package dev.diogoqueiroz.combustiveis.carga;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import dev.diogoqueiroz.combustiveis.fonte.Fonte;

/**
 * A linha de comando da carga. Sem argumentos, não faz nada (por isso os testes sobem o contexto em paz).
 *
 * <pre>
 *   --atualizar                       atualização semanal: as duas fontes ultimas4
 *   --carga=FONTE                     uma fonte: ultimas4:gasolina-etanol, ultimas4:diesel-gnv, semestral:2026-1
 *   --historico=2022-1:2026-1         os semestrais do intervalo, em ordem
 *   --reprocessar                     carrega de novo mesmo que o sha256 já tenha sido publicado
 *   --rebaixar                        baixa o semestral de novo, ignorando o cache
 * </pre>
 *
 * Código de saída: 0 tudo certo, 1 alguma fonte falhou, 2 outro executor está rodando, 3 argumento inválido.
 */
@Component
public class CargaCli implements ApplicationRunner, ExitCodeGenerator {

	private static final Logger log = LoggerFactory.getLogger(CargaCli.class);

	private static final Pattern INTERVALO = Pattern.compile("(\\d{4})-([12]):(\\d{4})-([12])");

	private final CargaService cargas;

	private final ExecutorUnico executorUnico;

	private int codigoDeSaida;

	public CargaCli(CargaService cargas, ExecutorUnico executorUnico) {
		this.cargas = cargas;
		this.executorUnico = executorUnico;
	}

	@Override
	public void run(ApplicationArguments argumentos) throws Exception {
		List<Fonte> fontes;
		try {
			fontes = fontes(argumentos);
		}
		catch (IllegalArgumentException e) {
			log.error(e.getMessage());
			codigoDeSaida = 3;
			return;
		}
		if (fontes.isEmpty()) {
			return;
		}
		boolean reprocessar = argumentos.containsOption("reprocessar");
		boolean rebaixar = argumentos.containsOption("rebaixar");

		try (ExecutorUnico.Lock lock = executorUnico.tentarAdquirir()) {
			if (lock == null) {
				log.error("Outra carga está rodando (lock do PostgreSQL ocupado). Nada foi feito.");
				codigoDeSaida = 2;
				return;
			}
			List<ResultadoCarga> resultados = new ArrayList<>();
			for (Fonte fonte : fontes) {
				ResultadoCarga resultado = cargas.carregar(fonte, reprocessar, rebaixar);
				resultados.add(resultado);
				if (resultado.falhou()) {
					codigoDeSaida = 1;
					if (fonte.semestral()) {
						break;  // o histórico vai em ordem; seguir depois de uma falha deixaria um buraco no meio
					}
				}
			}
			resultados.forEach(r -> log.info("{} → {} (execução {}): {}", r.fonte(), r.situacao(), r.execucaoId(),
					r.detalhe()));
		}
	}

	@Override
	public int getExitCode() {
		return codigoDeSaida;
	}

	static List<Fonte> fontes(ApplicationArguments argumentos) {
		List<Fonte> fontes = new ArrayList<>();
		if (argumentos.containsOption("atualizar")) {
			fontes.addAll(Fonte.ATUALIZACAO_SEMANAL);
		}
		if (argumentos.containsOption("carga")) {
			argumentos.getOptionValues("carga").forEach(id -> fontes.add(Fonte.doId(id)));
		}
		if (argumentos.containsOption("historico")) {
			for (String intervalo : argumentos.getOptionValues("historico")) {
				fontes.addAll(semestrais(intervalo));
			}
		}
		return fontes;
	}

	static List<Fonte> semestrais(String intervalo) {
		Matcher m = INTERVALO.matcher(intervalo);
		if (!m.matches()) {
			throw new IllegalArgumentException("intervalo inválido: " + intervalo + " (use 2022-1:2026-1)");
		}
		int de = Integer.parseInt(m.group(1)) * 2 + Integer.parseInt(m.group(2)) - 1;
		int ate = Integer.parseInt(m.group(3)) * 2 + Integer.parseInt(m.group(4)) - 1;
		if (de > ate) {
			throw new IllegalArgumentException("intervalo invertido: " + intervalo);
		}
		List<Fonte> fontes = new ArrayList<>();
		for (int s = de; s <= ate; s++) {
			fontes.add(Fonte.semestral(s / 2, s % 2 + 1));
		}
		return fontes;
	}

}
