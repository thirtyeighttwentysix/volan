package io.github.thirtyeighttwentysix.volan.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MariaDbDialect
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MySqlDialect
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.github.thirtyeighttwentysix.volan.ir.ConnectionUrl
import io.github.thirtyeighttwentysix.volan.ir.Provider
import io.github.thirtyeighttwentysix.volan.ir.Schema
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseReader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.readText

/** Runs Volan's database synchronization commands. */
public fun main(args: Array<String>) {
    command().main(args)
}

internal fun command(): CliktCommand = Root().subcommands(Database().subcommands(Pull(), Push()))

private class Root : CliktCommand(name = "volan") {
    override fun run(): Unit = Unit
}

private class Database : CliktCommand(name = "db") {
    override fun run(): Unit = Unit
}

private abstract class DatabaseCommand(name: String) : CliktCommand(name = name) {
    protected val schemaPath: String by option("--schema", help = "Schema file.").default("schema.volan")
    private val url: String? by option(
        "--url",
        help = "PostgreSQL, SQLite, H2, MySQL or MariaDB JDBC URL; defaults to DATABASE_URL or the schema datasource.",
    )

    protected fun dialect(connection: java.sql.Connection): DdlRenderer = when (provider(connection)) {
        Provider.POSTGRESQL -> PostgresDialect
        Provider.SQLITE -> SqliteDialect
        Provider.H2 -> H2Dialect
        Provider.MYSQL -> MySqlDialect
        Provider.MARIADB -> MariaDbDialect
    }

    private fun provider(connection: java.sql.Connection): Provider = when (connection.metaData.databaseProductName) {
        "PostgreSQL" -> Provider.POSTGRESQL
        "SQLite" -> Provider.SQLITE
        "H2" -> Provider.H2
        "MySQL" -> Provider.MYSQL
        "MariaDB" -> Provider.MARIADB
        else -> error("Unsupported database: ${connection.metaData.databaseProductName}")
    }

    protected fun sync(connection: java.sql.Connection): DatabaseSync =
        DatabaseSync(DatabaseReader.forProvider(provider(connection)), dialect(connection))

    protected fun schema(): Schema = SchemaLoader.load(schemaPath, Path.of(schemaPath).readText()).schemaOrThrow()

    protected fun connect(schema: Schema? = null): java.sql.Connection {
        val configured = schema?.datasource?.url
        val schemaUrl = when (configured) {
            is ConnectionUrl.Literal -> configured.value
            is ConnectionUrl.Environment -> System.getenv(configured.variable)
            null -> null
        }
        val address = url ?: schemaUrl ?: System.getenv("DATABASE_URL")
        require(
            listOf("jdbc:postgresql:", "jdbc:sqlite:", "jdbc:h2:", "jdbc:mysql:", "jdbc:mariadb:").any {
                address?.startsWith(it) == true
            },
        ) {
            "Set DATABASE_URL to a PostgreSQL, SQLite, H2, MySQL or MariaDB JDBC URL."
        }
        val properties = Properties()
        System.getenv("DATABASE_USER")?.let { properties.setProperty("user", it) }
        System.getenv("DATABASE_PASSWORD")?.let { properties.setProperty("password", it) }
        return DriverManager.getConnection(address, properties).also { dialect(it).initialize(it) }
    }
}

private class Pull : DatabaseCommand("pull") {
    private val stdout: Boolean by option("--stdout", help = "Print schema instead of writing a file.").flag()
    private val force: Boolean by option("--force", help = "Replace an existing schema file.").flag()

    override fun run() {
        val text = connect().use { sync(it).pull(it) }
        if (stdout) {
            echo(text, trailingNewline = false)
        } else {
            val options = if (force) {
                arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            } else {
                arrayOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
            }
            Files.writeString(Path.of(schemaPath), text, *options)
            echo("Wrote $schemaPath")
        }
    }
}

private class Push : DatabaseCommand("push") {
    private val dryRun: Boolean by option("--dry-run", help = "Print SQL and warnings without applying changes.").flag()
    private val acceptWarnings: Boolean by option("--accept-data-loss", help = "Apply reviewed warning-bearing changes.").flag()
    private val resolve: Boolean by option(
        "--resolve",
        help = "Verify a manually repaired nontransactional push against its original schema.",
    ).flag()

    override fun run() {
        val wanted = schema()
        require(!(resolve && dryRun)) { "--resolve and --dry-run cannot be used together." }
        connect(wanted).use { connection ->
            val sync = sync(connection)
            if (resolve) {
                sync.resolvePush(connection, wanted)
                echo("Verified and resolved the repaired database push.")
            } else if (dryRun) {
                val plan = sync.plan(connection, wanted)
                plan.warnings.forEach { echo("Warning: $it", err = true) }
                echo(if (plan.isEmpty) "Schema is up to date." else plan.toSql(dialect(connection)))
            } else {
                val plan = sync.push(connection, wanted, acceptWarnings)
                echo(if (plan.isEmpty) "Schema is up to date." else "Applied ${plan.steps.size} schema changes.")
            }
        }
    }
}
