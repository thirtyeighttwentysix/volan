package io.github.thirtyeighttwentysix.volan.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.github.thirtyeighttwentysix.volan.ir.ConnectionUrl
import io.github.thirtyeighttwentysix.volan.ir.Schema
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync
import io.github.thirtyeighttwentysix.volan.migrate.PostgresReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.DriverManager
import java.util.Properties
import kotlin.io.path.readText

/** Runs Volan's database synchronization commands. */
public fun main(args: Array<String>) {
    Root().subcommands(Database().subcommands(Pull(), Push())).main(args)
}

private class Root : CliktCommand(name = "volan") {
    override fun run(): Unit = Unit
}

private class Database : CliktCommand(name = "db") {
    override fun run(): Unit = Unit
}

private abstract class DatabaseCommand(name: String) : CliktCommand(name = name) {
    protected val schemaPath: String by option("--schema", help = "Schema file.").default("schema.volan")
    private val url: String? by option("--url", help = "PostgreSQL JDBC URL; defaults to DATABASE_URL or the schema datasource.")
    protected val sync: DatabaseSync = DatabaseSync(PostgresReader(), PostgresDialect)

    protected fun schema(): Schema = SchemaLoader.load(schemaPath, Path.of(schemaPath).readText()).schemaOrThrow()

    protected fun connect(schema: Schema? = null): java.sql.Connection {
        val configured = schema?.datasource?.url
        val schemaUrl = when (configured) {
            is ConnectionUrl.Literal -> configured.value
            is ConnectionUrl.Environment -> System.getenv(configured.variable)
            null -> null
        }
        val address = url ?: schemaUrl ?: System.getenv("DATABASE_URL")
        require(address?.startsWith("jdbc:postgresql:") == true) { "Set DATABASE_URL to a PostgreSQL JDBC URL." }
        val properties = Properties()
        System.getenv("DATABASE_USER")?.let { properties.setProperty("user", it) }
        System.getenv("DATABASE_PASSWORD")?.let { properties.setProperty("password", it) }
        return DriverManager.getConnection(address, properties)
    }
}

private class Pull : DatabaseCommand("pull") {
    private val stdout: Boolean by option("--stdout", help = "Print schema instead of writing a file.").flag()
    private val force: Boolean by option("--force", help = "Replace an existing schema file.").flag()

    override fun run() {
        val text = connect().use { sync.pull(it) }
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

    override fun run() {
        val wanted = schema()
        connect(wanted).use { connection ->
            if (dryRun) {
                val plan = sync.plan(connection, wanted)
                plan.warnings.forEach { echo("Warning: $it", err = true) }
                echo(if (plan.isEmpty) "Schema is up to date." else plan.toSql(PostgresDialect))
            } else {
                val plan = sync.push(connection, wanted, acceptWarnings)
                echo(if (plan.isEmpty) "Schema is up to date." else "Applied ${plan.steps.size} schema changes.")
            }
        }
    }
}
