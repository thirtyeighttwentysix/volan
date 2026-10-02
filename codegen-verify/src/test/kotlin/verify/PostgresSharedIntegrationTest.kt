package verify

import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.readText

/** The common generated-client suite also runs on PostgreSQL, alongside its provider-specific tests. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("verify.Docker#isAvailable")
class PostgresSharedIntegrationTest : EmbeddedIntegrationTest() {
    private val database by lazy { PostgreSQLContainer("postgres:17-alpine").apply { start() } }
    private val urls = HashMap<Path, String>()
    override val supportsDistinctOn: Boolean get() = true
    override val temporalNanos: Int get() = 123000000
    override val memoryUrl: String get() = newDatabase()
    override fun fileUrl(path: Path): String = urls.getOrPut(path) { newDatabase() }

    private fun newDatabase(): String {
        val name = "db_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE $name") }
        }
        return database.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/$name" +
            "?user=${database.username}&password=${database.password}"
    }

    override fun initialize(database: VolanClient) {
        val text = Path.of("schema/sqlite.volan").readText().replace("\"sqlite\"", "\"postgresql\"")
        val schema = SchemaLoader.load("postgresql.volan", text).schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(PostgresDialect).forEach { database.rawExecute(it) }
    }

    @AfterAll fun stopDatabase() { database.stop() }
}
