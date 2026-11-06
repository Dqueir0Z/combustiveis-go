package dev.diogoqueiroz.combustiveis.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.diogoqueiroz.combustiveis.limpeza.Limpador;

/**
 * O relógio é um bean para que "hoje" seja controlável nos testes (data futura, obtido_em).
 */
@Configuration(proxyBeanMethods = false)
class RelogioConfig {

	@Bean
	Clock relogio() {
		return Clock.system(ZoneId.of("America/Sao_Paulo"));
	}

	@Bean
	Limpador limpador(Clock relogio) {
		return new Limpador(relogio);
	}

}
