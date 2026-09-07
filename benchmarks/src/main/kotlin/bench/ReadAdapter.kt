package bench

import bench.generated.Person
import bench.generated.VolanClient
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.github.thirtyeighttwentysix.volan.runtime.Volan
import org.hibernate.SessionFactory
import org.hibernate.boot.MetadataSources
import org.hibernate.boot.registry.StandardServiceRegistryBuilder
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.sql.Connection

data class BenchRow(val id: Int, val email: String, val name: String, val score: Int)

private object People : Table("bench_people") {
    val id = integer("id")
    val email = text("email")
    val name = text("name")
    val score = integer("score")
}

class ReadAdapter(orm: String) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        jdbcUrl = requireNotNull(System.getenv("VOLAN_BENCH_URL")) { "Set VOLAN_BENCH_URL to a dedicated seeded PostgreSQL database." }
        username = System.getenv("VOLAN_BENCH_USER") ?: "volan_bench"
        password = System.getenv("VOLAN_BENCH_PASSWORD") ?: "volan_bench"
        maximumPoolSize = 4
        minimumIdle = 4
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
        addDataSourceProperty("prepareThreshold", "5")
    })
    private var hibernate: SessionFactory? = null
    private var volan: VolanClient? = null
    private val query: (Int, Int) -> List<*>

    init {
        query = when (orm) {
            "Volan" -> volanQuery()
            "Hibernate" -> hibernateQuery()
            "Exposed" -> exposedQuery()
            "jOOQ" -> jooqQuery()
            "JDBC" -> ::jdbcQuery
            else -> error("Unknown ORM: $orm")
        }
    }

    fun read(start: Int, size: Int): List<*> = query(start, size)

    /** Full field equality and ordering are checked before JMH starts its timers. */
    fun verify(size: Int) {
        for (start in listOf(1, 4999, 9800)) {
            val actual = read(start, size).map {
                when (it) {
                    is BenchRow -> it
                    is Person -> BenchRow(it.id, it.email, it.name, it.score)
                    is HibernatePerson -> BenchRow(it.id, it.email, it.name, it.score)
                    else -> error("Unexpected mapped row: $it")
                }
            }
            val expected = (start until start + size).map { BenchRow(it, "person$it@example.com", "Person $it", it % 100) }
            check(actual == expected) { "Benchmark adapter returned different rows or fields." }
        }
    }

    private fun volanQuery(): (Int, Int) -> List<*> {
        val client = VolanClient(
            Volan.builder().dataSource(pool).dialect(PostgresDialect)
                .tables(VolanClient.TABLES).readers(VolanClient.READERS).build(),
        )
        volan = client
        return { start, size ->
            client.transaction { db ->
                db.person.findMany {
                    where { id gte start }
                    orderBy { id.asc() }
                    take = size
                }
            }
        }
    }

    private fun hibernateQuery(): (Int, Int) -> List<*> {
        val registry = StandardServiceRegistryBuilder()
            .applySetting("hibernate.connection.datasource", pool)
            .applySetting("hibernate.hbm2ddl.auto", "none")
            .applySetting("hibernate.show_sql", false)
            .applySetting("hibernate.cache.use_second_level_cache", false)
            .applySetting("hibernate.cache.use_query_cache", false)
            .build()
        val factory = MetadataSources(registry).addAnnotatedClass(HibernatePerson::class.java)
            .buildMetadata().buildSessionFactory()
        hibernate = factory
        return { start, size ->
            factory.openSession().use { session ->
                session.isDefaultReadOnly = true
                val tx = session.beginTransaction()
                val result = session.createSelectionQuery("from BenchPerson p where p.id >= :start order by p.id", HibernatePerson::class.java)
                    .setParameter("start", start).setMaxResults(size).resultList
                tx.commit()
                result
            }
        }
    }

    private fun exposedQuery(): (Int, Int) -> List<*> {
        val db = Database.connect(pool)
        return { start, size ->
            transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = db) {
                People.selectAll().where { People.id greaterEq start }.orderBy(People.id to SortOrder.ASC).limit(size)
                    .map { BenchRow(it[People.id], it[People.email], it[People.name], it[People.score]) }
            }
        }
    }

    private fun jooqQuery(): (Int, Int) -> List<*> {
        val id = DSL.field(DSL.name("id"), Int::class.javaObjectType)
        val email = DSL.field(DSL.name("email"), String::class.java)
        val name = DSL.field(DSL.name("name"), String::class.java)
        val score = DSL.field(DSL.name("score"), Int::class.javaObjectType)
        val table = DSL.table(DSL.name("bench_people"))
        val settings = Settings().withExecuteLogging(false)
        return { start, size ->
            inTransaction { connection ->
                DSL.using(connection, SQLDialect.POSTGRES, settings).select(id, email, name, score).from(table)
                    .where(id.ge(start)).orderBy(id.asc()).limit(size)
                    .fetch { BenchRow(it.value1(), it.value2(), it.value3(), it.value4()) }
            }
        }
    }

    private fun jdbcQuery(start: Int, size: Int): List<BenchRow> = inTransaction { connection ->
        connection.prepareStatement("select id, email, name, score from bench_people where id >= ? order by id limit ?").use { statement ->
            statement.setInt(1, start)
            statement.setInt(2, size)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(BenchRow(rows.getInt(1), rows.getString(2), rows.getString(3), rows.getInt(4)))
                }
            }
        }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = pool.connection.use { connection ->
        connection.autoCommit = false
        val result = block(connection)
        connection.commit()
        result
    }

    override fun close() {
        hibernate?.close()
        volan?.close()
        pool.close()
    }
}
