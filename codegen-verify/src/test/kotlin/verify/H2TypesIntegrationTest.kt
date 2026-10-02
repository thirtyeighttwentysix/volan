package verify

import com.example.h2.Role
import com.example.h2.VolanClient
import io.github.thirtyeighttwentysix.volan.Json
import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSchema
import io.github.thirtyeighttwentysix.volan.migrate.SchemaDiffer
import io.github.thirtyeighttwentysix.volan.migrate.SchemaMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.io.path.readText

/** Generates from an H2 schema and uses real H2 native values, including every scalar array type. */
class H2TypesIntegrationTest {
    private lateinit var client: VolanClient

    @BeforeEach
    fun start() {
        client = VolanClient.builder().url("jdbc:h2:mem:${UUID.randomUUID()}").maxPoolSize(1).build()
        val schema = SchemaLoader.load("h2.volan", Path.of("schema/h2.volan").readText()).schemaOrThrow()
        SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(H2Dialect).forEach { client.rawExecute(it) }
    }

    @AfterEach
    fun stop() {
        client.close()
    }

    @Test
    fun `decimal and all array element types round trip as their declared Kotlin types`() {
        client.rawExecute("SET TIME ZONE 'Asia/Novosibirsk'")
        val amount = BigDecimal("12345678901234567890.123456789012345678901234567890")
        val moment = Instant.parse("2026-10-01T12:34:56.123456789Z")
        val date = LocalDate.of(2026, 10, 1)
        val time = LocalTime.of(12, 34, 56, 123456789)
        val token = UUID.randomUUID()
        val inserted = client.scalars.create {
            this.amount = amount
            words = listOf("a", "quote'\\☃")
            integers = listOf(Int.MIN_VALUE, Int.MAX_VALUE)
            bigNumbers = listOf(Long.MIN_VALUE, Long.MAX_VALUE)
            ratios = listOf(1.25f)
            doubles = listOf(2.5)
            decimals = listOf(amount)
            flags = listOf(true, false)
            moments = listOf(moment)
            dates = listOf(date)
            times = listOf(time)
            tokens = listOf(token)
            documents = listOf(Json.of("{\"h2\":true}"), Json.of("null"))
            blobs = listOf(byteArrayOf(0, 1, -1))
            roles = listOf(Role.ADMIN, Role.USER)
        }
        val read = client.scalars.findUniqueOrThrow { where { id eq inserted.id } }
        read shouldBe inserted
        read.hashCode() shouldBe inserted.hashCode()
        read.toString() shouldBe inserted.toString()
        listOf(inserted, read).forEach { row ->
            row.amount shouldBe amount
            row.words shouldContainExactly listOf("a", "quote'\\☃")
            row.integers shouldContainExactly listOf(Int.MIN_VALUE, Int.MAX_VALUE)
            row.bigNumbers shouldContainExactly listOf(Long.MIN_VALUE, Long.MAX_VALUE)
            row.ratios shouldContainExactly listOf(1.25f)
            row.doubles shouldContainExactly listOf(2.5)
            row.decimals shouldContainExactly listOf(amount)
            row.flags shouldContainExactly listOf(true, false)
            row.moments shouldContainExactly listOf(moment)
            row.dates shouldContainExactly listOf(date)
            row.times shouldContainExactly listOf(time)
            row.tokens shouldContainExactly listOf(token)
            row.documents.map { it.raw } shouldContainExactly listOf("{\"h2\":true}", "null")
            row.blobs.single() shouldBe byteArrayOf(0, 1, -1)
            row.roles shouldContainExactly listOf(Role.ADMIN, Role.USER)
        }
    }

    @Test
    fun `empty arrays defaults and uuid defaults are returned after the write`() {
        val row = client.scalars.create { amount = BigDecimal.ZERO }
        row.words shouldBe emptyList()
        row.moments shouldBe emptyList()
        row.documents shouldBe emptyList()
        row.roles shouldBe emptyList()
        val changed = client.scalars.update {
            where { id eq row.id }
            data { words = listOf("changed"); roles = listOf(Role.ADMIN) }
        }
        changed.roles shouldContainExactly listOf(Role.ADMIN)
        client.scalars.delete { where { id eq row.id } }.words shouldContainExactly listOf("changed")
        client.scalars.count() shouldBe 0
    }

    @Test
    fun `default only inserts and bulk writes return generated identities and timestamps`() {
        val first = client.defaults.create { }
        client.defaults.createMany { row { }; row { } } shouldBe 2
        val rows = client.defaults.findMany { orderBy { id.asc() } }
        rows.map { it.id } shouldContainExactly listOf(first.id, first.id + 1, first.id + 2)
        rows.first().createdAt shouldBe first.createdAt
    }

    @Test
    fun `decimal comparisons and aggregates keep exact precision`() {
        client.scalars.createMany {
            row { amount = BigDecimal("0.1") }
            row { amount = BigDecimal("0.2") }
        }
        client.scalars.count { where { amount gt BigDecimal("0.15") } } shouldBe 1
        client.scalars.aggregate { sum { amount } }.sumOfAmount!!.compareTo(BigDecimal("0.3")) shouldBe 0
    }
}
