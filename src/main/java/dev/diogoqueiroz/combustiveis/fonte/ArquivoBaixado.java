package dev.diogoqueiroz.combustiveis.fonte;

import java.nio.file.Path;
import java.time.OffsetDateTime;

/**
 * Um arquivo já no disco, identificado pelo sha256 do que veio da ANP (o zip, no caso do semestral).
 *
 * @param csv o CSV a ler: o próprio arquivo baixado, ou o extraído do zip
 */
public record ArquivoBaixado(Fonte fonte, String sha256, OffsetDateTime obtidoEm, Path csv) {
}
