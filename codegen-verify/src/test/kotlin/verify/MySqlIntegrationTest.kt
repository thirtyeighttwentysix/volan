package verify

import com.example.sqlite.VolanClient
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MySqlDialect
import io.github.thirtyeighttwentysix.volan.dialect.mysql.MariaDbDialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import io.github.thirtyeighttwentysix.volan.runtime.VolanValidationException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIf
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.mariadb.MariaDBContainer
import org.testcontainers.containers.JdbcDatabaseContainer
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.TimeZone
import java.time.Instant
import java.math.BigDecimal
import com.example.mysql.VolanClient as MySqlClient
import kotlin.io.path.readText

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("verify.Docker#isAvailable")
abstract class MySqlIntegrationBase : EmbeddedIntegrationTest() {
    protected abstract fun container(): JdbcDatabaseContainer<*>
    protected abstract val dialect: DdlRenderer
    private val database by lazy { container().apply { start() } }
    private val urls = HashMap<Path, String>()
    override val temporalNanos: Int get() = 123456000
    override val supportsDistinctOn: Boolean get() = false
    override val memoryUrl: String get() = newDatabase()
    override fun fileUrl(path: Path): String = urls.getOrPut(path) { newDatabase() }

    private fun newDatabase(): String {
        val name = "db_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(database.jdbcUrl, database.username, database.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin") }
        }
        val base = database.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/$name"
        return "$base?user=${database.username}&password=${database.password}"
    }

    override fun initialize(database: VolanClient) {
        val text = Path.of("schema/sqlite.volan").readText().replace("\"sqlite\"", "\"${dialect.id}\"")
        val schema = SchemaLoader.load("mysql.volan", text).schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(dialect).forEach { database.rawExecute(it) }
    }

    @AfterAll
    fun stopDatabase() { database.stop() }

    @Test
    fun `follow up reads retain a row after its filter column changes`() {
        val first = client.user.create { email = "before@example.org" }
        val changed = client.user.update {
            where { email eq first.email }
            data { email = "after@example.org" }
        }
        changed.id shouldBe first.id
        changed.email shouldBe "after@example.org"
        client.user.findFirstOrThrow() shouldBe changed
    }

    @Test
    fun `ambiguous single row writes roll back without changing any match`() {
        client.user.create { email = "one@example.org" }
        client.user.create { email = "two@example.org" }
        shouldThrow<VolanValidationException> { client.user.update { data { name = "changed" } } }
        shouldThrow<VolanValidationException> { client.user.delete {} }
        client.user.count() shouldBe 2L
        client.user.findMany().all { it.name == null } shouldBe true
    }

    @Test
    fun `UTC instants retain their value in a non UTC application timezone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            val instant = Instant.parse("2026-10-03T00:20:30.123456Z")
            val row = client.user.create { email = "timezone@example.org"; createdAt = instant }
            row.createdAt shouldBe instant
            client.user.findFirstOrThrow().createdAt shouldBe instant
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `a MySQL generated client reads Decimal and database defaults with explicit mapped UUID keys`() {
        val text = Path.of("schema/mysql.volan").readText().replace("\"mysql\"", "\"${dialect.id}\"")
        val schema = SchemaLoader.load("mysql.volan", text).schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(dialect).forEach { client.rawExecute(it) }
        MySqlClient.builder().url(fileUrl(temporary.resolve("database"))).build().use { db ->
            val key = UUID.randomUUID()
            val value = BigDecimal("12345678901234567890.123456789012345678901234567890")
            val row = db.scalars.create { id = key; amount = value }
            row.id shouldBe key
            row.amount.compareTo(value) shouldBe 0
            row.label shouldBe "constant"
            val changed = db.scalars.update { where { id eq key }; data { label = "changed" } }
            changed.token shouldBe row.token
            db.scalars.delete { where { id eq key } } shouldBe changed
        }
    }
}

class MySqlIntegrationTest : MySqlIntegrationBase() {
    override val dialect: DdlRenderer get() = MySqlDialect
    override fun container(): JdbcDatabaseContainer<*> = MySQLContainer("mysql:8.4").withUsername("root").withPassword("test")
}

class MariaDbIntegrationTest : MySqlIntegrationBase() {
    override val dialect: DdlRenderer get() = MariaDbDialect
    override fun container(): JdbcDatabaseContainer<*> = MariaDBContainer("mariadb:11.4").withUsername("root").withPassword("test")
}
