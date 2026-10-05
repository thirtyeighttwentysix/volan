package bench

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.impl.DSL
import java.sql.Connection

private object WritePeople : Table("bench_write_people") {
    val id = integer("id")
    val email = text("email")
    val name = text("name")
    val score = integer("score")
}

/** Explicit IDs avoid comparing different generated-key strategies. Each operation commits. */
class WriteAdapter(private val orm: String) : AutoCloseable {
    private val resources = ReadAdapter(orm)
    private val exposed = if (orm == "Exposed") Database.connect(resources.pool) else null
    private val id = DSL.field(DSL.name("id"), Int::class.javaObjectType)
    private val email = DSL.field(DSL.name("email"), String::class.java)
    private val name = DSL.field(DSL.name("name"), String::class.java)
    private val score = DSL.field(DSL.name("score"), Int::class.javaObjectType)
    private val table = DSL.table(DSL.name("bench_write_people"))
    private val settings = Settings().withExecuteLogging(false)

    init {
        resources.inTransaction { connection ->
            connection.createStatement().use {
                it.execute("create table if not exists bench_write_people (like bench_people including all)")
            }
        }
        reset()
    }

    private fun reset() {
        resources.inTransaction { connection ->
            connection.createStatement().use {
                it.execute("truncate bench_write_people")
                it.execute("insert into bench_write_people select * from bench_people")
                it.execute("analyze bench_write_people")
            }
        }
    }

    fun update(start: Int, size: Int, value: Int): Long = when (orm) {
        "Volan" -> requireNotNull(resources.volan).transaction { db ->
            db.writePerson.updateMany {
                where { id gte start; id lt start + size }
                data { score = value }
            }
        }
        "Hibernate" -> requireNotNull(resources.hibernate).openSession().use { session ->
            val tx = session.beginTransaction()
            val count = session.createMutationQuery("update BenchWritePerson p set p.score = :score where p.id >= :start and p.id < :end")
                .setParameter("score", value).setParameter("start", start).setParameter("end", start + size).executeUpdate()
            tx.commit()
            count.toLong()
        }
        "Exposed" -> transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = requireNotNull(exposed)) {
            WritePeople.update({ (WritePeople.id greaterEq start) and (WritePeople.id less start + size) }) {
                it[score] = value
            }.toLong()
        }
        "jOOQ" -> resources.inTransaction { connection ->
            DSL.using(connection, SQLDialect.POSTGRES, settings).update(table).set(score, value)
                .where(id.ge(start).and(id.lt(start + size))).execute().toLong()
        }
        "JDBC" -> resources.inTransaction { connection ->
            connection.prepareStatement("update bench_write_people set score = ? where id >= ? and id < ?").use {
                it.setInt(1, value)
                it.setInt(2, start)
                it.setInt(3, start + size)
                it.executeUpdate().toLong()
            }
        }
        else -> error("Unknown ORM: $orm")
    }

    /** Insertion and deletion share one transaction; the database remains bounded in size. */
    fun insertDelete(size: Int, verify: Boolean): Long {
        val rows = (20000 until 20000 + size).map { BenchRow(it, "person$it@example.com", "Person $it", it % 100) }
        return when (orm) {
            "Volan" -> requireNotNull(resources.volan).transaction { db ->
                val inserted = db.writePerson.createMany {
                    rows.forEach { source -> row { id = source.id; email = source.email; name = source.name; score = source.score } }
                }
                if (verify) {
                    val actual = db.writePerson.findMany { where { id gte 20000 }; orderBy { id.asc() } }
                        .map { BenchRow(it.id, it.email, it.name, it.score) }
                    check(actual == rows)
                }
                val deleted = db.writePerson.deleteMany { where { id gte 20000 } }
                check(inserted == size.toLong() && deleted == inserted)
                deleted
            }
            "Hibernate" -> requireNotNull(resources.hibernate).openSession().use { session ->
                val tx = session.beginTransaction()
                rows.forEach { source ->
                    session.persist(HibernateWritePerson().apply {
                        id = source.id; email = source.email; name = source.name; score = source.score
                    })
                }
                session.flush()
                if (verify) {
                    session.clear()
                    val actual = session.createSelectionQuery("from BenchWritePerson p where p.id >= 20000 order by p.id", HibernateWritePerson::class.java)
                        .resultList.map { BenchRow(it.id, it.email, it.name, it.score) }
                    check(actual == rows)
                }
                val deleted = session.createMutationQuery("delete from BenchWritePerson p where p.id >= 20000").executeUpdate()
                check(deleted == size)
                tx.commit()
                deleted.toLong()
            }
            "Exposed" -> transaction(transactionIsolation = Connection.TRANSACTION_READ_COMMITTED, db = requireNotNull(exposed)) {
                WritePeople.batchInsert(rows, shouldReturnGeneratedValues = false) { source ->
                    this[WritePeople.id] = source.id; this[WritePeople.email] = source.email
                    this[WritePeople.name] = source.name; this[WritePeople.score] = source.score
                }
                if (verify) {
                    val actual = WritePeople.selectAll().where { WritePeople.id greaterEq 20000 }.orderBy(WritePeople.id)
                        .map { BenchRow(it[WritePeople.id], it[WritePeople.email], it[WritePeople.name], it[WritePeople.score]) }
                    check(actual == rows)
                }
                val deleted = WritePeople.deleteWhere { id greaterEq 20000 }
                check(deleted == size)
                deleted.toLong()
            }
            "jOOQ" -> resources.inTransaction { connection ->
                val db = DSL.using(connection, SQLDialect.POSTGRES, settings)
                val insert = db.insertInto(table, id, email, name, score)
                rows.forEach { insert.values(it.id, it.email, it.name, it.score) }
                check(insert.execute() == size)
                if (verify) check(readRows(connection, 20000) == rows)
                val deleted = db.deleteFrom(table).where(id.ge(20000)).execute()
                check(deleted == size)
                deleted.toLong()
            }
            "JDBC" -> resources.inTransaction { connection ->
                val placeholders = List(size) { "(?, ?, ?, ?)" }.joinToString(", ")
                connection.prepareStatement("insert into bench_write_people (id, email, name, score) values $placeholders").use { statement ->
                    rows.forEachIndexed { index, row ->
                        statement.setInt(index * 4 + 1, row.id); statement.setString(index * 4 + 2, row.email)
                        statement.setString(index * 4 + 3, row.name); statement.setInt(index * 4 + 4, row.score)
                    }
                    check(statement.executeUpdate() == size)
                }
                if (verify) check(readRows(connection, 20000) == rows)
                connection.prepareStatement("delete from bench_write_people where id >= 20000").use {
                    val deleted = it.executeUpdate()
                    check(deleted == size)
                    deleted.toLong()
                }
            }
            else -> error("Unknown ORM: $orm")
        }
    }

    private fun readRows(connection: Connection, start: Int): List<BenchRow> =
        connection.prepareStatement("select id, email, name, score from bench_write_people where id >= ? order by id").use { statement ->
            statement.setInt(1, start)
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(BenchRow(result.getInt(1), result.getString(2), result.getString(3), result.getInt(4))) }
            }
        }

    fun verify(size: Int) {
        for (start in listOf(1, 4999, 9800)) {
            check(update(start, size, 12345) == size.toLong())
            resources.inTransaction { connection ->
                val expected = (1..10000).map {
                    BenchRow(it, "person$it@example.com", "Person $it", if (it in start until start + size) 12345 else it % 100)
                }
                check(readRows(connection, 1) == expected) { "Update changed the wrong rows or fields." }
            }
            reset()
        }
        check(insertDelete(size, true) == size.toLong())
        resources.inTransaction { connection ->
            val expected = (1..10000).map { BenchRow(it, "person$it@example.com", "Person $it", it % 100) }
            check(readRows(connection, 1) == expected) { "Insert/delete left rows behind or changed the baseline." }
        }
        reset()
    }

    override fun close() = resources.close()
}
