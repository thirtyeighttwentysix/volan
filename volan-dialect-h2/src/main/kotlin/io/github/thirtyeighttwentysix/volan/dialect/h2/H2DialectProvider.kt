package io.github.thirtyeighttwentysix.volan.dialect.h2

import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider

/** Discovers H2 from file, memory and server JDBC URLs. */
public class H2DialectProvider : DialectProvider {
    override fun supports(jdbcUrl: String): Boolean = jdbcUrl.startsWith("jdbc:h2:")

    override fun dialect(): Dialect = H2Dialect
}
