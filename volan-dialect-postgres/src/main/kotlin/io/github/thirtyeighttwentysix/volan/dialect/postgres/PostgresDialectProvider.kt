package io.github.thirtyeighttwentysix.volan.dialect.postgres

import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider

/** Makes [PostgresDialect] discoverable to a runtime that has this module on its classpath. */
public class PostgresDialectProvider : DialectProvider {
    override fun supports(jdbcUrl: String): Boolean = jdbcUrl.startsWith("jdbc:postgresql:")

    override fun dialect(): Dialect = PostgresDialect
}
