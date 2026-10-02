package verify

import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync
import io.github.thirtyeighttwentysix.volan.migrate.SqliteReader
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.readText

class SqliteIntegrationTest : EmbeddedIntegrationTest() {
    override fun fileUrl(path: Path): String = "jdbc:sqlite:$path"

    override val memoryUrl: String get() = "jdbc:sqlite::memory:"

    override val supportsDistinctOn: Boolean get() = false

    override fun initialize(database: VolanClient) {
        val schema = SchemaLoader.load("sqlite.volan", Path.of("schema/sqlite.volan").readText()).schemaOrThrow()
        val statements = DriverManager.getConnection("jdbc:sqlite::memory:").use {
            DatabaseSync(SqliteReader(), SqliteDialect).plan(it, schema).render(SqliteDialect)
        }
        statements.forEach { database.rawExecute(it) }
    }

    @Test
    fun `supplied data source connections also enforce foreign keys`() {
        val source = SQLiteDataSource().apply { url = fileUrl(temporary.resolve("external.sqlite")) }
        Volan.builder().url(source.url).dataSource(source).build().use { database ->
            database.rawQuery("pragma foreign_keys", emptyList()) { it.getInt("foreign_keys") }.single() shouldBe 1
        }
        source.connection.use { it.isClosed shouldBe false }
        client.rawQuery("pragma foreign_keys", emptyList()) { it.getInt("foreign_keys") }.single() shouldBe 1
    }
}
