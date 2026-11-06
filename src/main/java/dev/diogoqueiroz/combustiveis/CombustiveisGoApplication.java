package dev.diogoqueiroz.combustiveis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CombustiveisGoApplication {

	public static void main(String[] args) {
		// O código de saída da carga (CargaCli) vira o código de saída do processo.
		System.exit(SpringApplication.exit(SpringApplication.run(CombustiveisGoApplication.class, args)));
	}

}
