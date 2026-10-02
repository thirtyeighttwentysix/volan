package io.github.thirtyeighttwentysix.volan.dialect.mysql

import io.github.thirtyeighttwentysix.volan.dialect.Dialect
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider

public class MySqlDialectProvider : DialectProvider {
    override fun supports(jdbcUrl: String): Boolean = jdbcUrl.startsWith("jdbc:mysql:")
    override fun dialect(): Dialect = MySqlDialect
}

public class MariaDbDialectProvider : DialectProvider {
    override fun supports(jdbcUrl: String): Boolean = jdbcUrl.startsWith("jdbc:mariadb:")
    override fun dialect(): Dialect = MariaDbDialect
}
