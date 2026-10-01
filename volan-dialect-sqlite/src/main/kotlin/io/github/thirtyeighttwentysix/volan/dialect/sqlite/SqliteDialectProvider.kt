package io.github.thirtyeighttwentysix.volan.dialect.sqlite

import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider

/** Discovers SQLite from a `jdbc:sqlite:` URL. */
public class SqliteDialectProvider : DialectProvider {
    override fun supports(jdbcUrl: String): Boolean = jdbcUrl.startsWith("jdbc:sqlite:")

    override fun dialect(): Dialect = SqliteDialect
}
