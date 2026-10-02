package verify

import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException
import io.kotest.matchers.shouldBe
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

class H2IntegrationTest : EmbeddedIntegrationTest() {
    override fun fileUrl(path: Path): String = "jdbc:h2:file:$path"

    override val memoryUrl: String get() = "jdbc:h2:mem:${UUID.randomUUID()}"

    override val supportsDistinctOn: Boolean get() = true

    override fun initialize(database: VolanClient) {
        val text = Path.of("schema/sqlite.volan").readText().replace("\"sqlite\"", "\"h2\"")
        val schema = SchemaLoader.load("embedded-h2.volan", text).schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(H2Dialect).forEach { database.rawExecute(it) }
    }

    @Test
    fun `private memory database survives asynchronous borrows`() {
        VolanClient.builder().url("jdbc:h2:mem:").maxPoolSize(8).build().use { memory ->
            initialize(memory)
            memory.user.createAsync { email = "private@example.org" }.get(10, TimeUnit.SECONDS).email shouldBe "private@example.org"
            memory.user.countAsync().get(10, TimeUnit.SECONDS) shouldBe 1
        }
    }

    @Test
    fun `external data source remains caller owned`() {
        val source = JdbcDataSource().apply { setURL(fileUrl(temporary.resolve("external"))) }
        Volan.builder().url(source.getURL()).dataSource(source).build().use { database ->
            database.rawQuery("SELECT 1 AS \"value\"", emptyList()) { it.getInt("value") }.single() shouldBe 1
        }
        source.connection.use { it.isClosed shouldBe false }
    }

    @Test
    fun `bulk inserts cross the H2 parameter ceiling and rollback earlier batches on failure`() {
        // Each User supplies email plus the managed updatedAt column.
        val rows = H2Dialect.capabilities.maximumParameters / 2 + 1
        client.user.createMany { repeat(rows) { index -> row { email = "batch$index@example.org" } } } shouldBe rows.toLong()
        assertThrows<VolanUniqueConstraintException> {
            client.user.createMany {
                repeat(rows - 1) { index -> row { email = "rollback$index@example.org" } }
                row { email = "batch0@example.org" }
            }
        }
        client.user.count() shouldBe rows.toLong()
    }
}
