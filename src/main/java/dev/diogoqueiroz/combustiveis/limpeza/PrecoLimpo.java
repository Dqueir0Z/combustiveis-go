package dev.diogoqueiroz.combustiveis.limpeza;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Um preço aceito pela limpeza, pronto para o staging.
 *
 * @param numero        posição do registro no arquivo
 * @param uf            sempre "GO" na v1
 * @param municipio     maiúsculas, sem acento
 * @param revenda       razão social como veio (só aparada); pode ser {@code null}
 * @param cnpj          exatamente 14 dígitos
 * @param valorCompra   {@code null} quando vazio (o normal na ANP)
 * @param bandeira      maiúsculas, sem acento; "NAO INFORMADA" quando vazia
 * @param conteudoBruto a linha original, para mover à quarentena se houver conflito
 */
public record PrecoLimpo(long numero, String uf, String municipio, String revenda, String cnpj, String bairro,
		String cep, Produto produto, LocalDate dataColeta, BigDecimal valorVenda, BigDecimal valorCompra,
		String unidade, String bandeira, String conteudoBruto) {
}
