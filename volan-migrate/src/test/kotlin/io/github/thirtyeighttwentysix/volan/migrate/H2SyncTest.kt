package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.h2.H2Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID

class H2SyncTest {
    @Test
    fun `plans generate executable H2 alterations without writing and detect external drift`() {
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
            val initial = schema("value Int? @default(1)")
            val desired = schema("value Long @default(2)\nadded String?\n@@index([value])")
            val sync = DatabaseSync(H2Reader(), H2Dialect)
            connection.createStatement().use { statement ->
                sync.plan(connection, initial).render(H2Dialect).forEach { statement.execute(it) }
                statement.execute("INSERT INTO \"Item\"(\"id\",\"value\") VALUES (1,3)")
            }
            val before = H2Reader().read(connection)
            val plan = sync.plan(connection, desired)
            plan.isDestructive shouldBe true
            H2Reader().read(connection) shouldBe before
            connection.createStatement().use { statement -> plan.render(H2Dialect).forEach { statement.execute(it) } }
            sync.plan(connection, desired).isEmpty shouldBe true
            sync.drift(connection, SchemaMapper.map(desired)).isEmpty shouldBe true
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT \"value\" FROM \"Item\" WHERE \"id\"=1").use {
                    it.next()
                    it.getLong(1) shouldBe 3L
                }
                statement.execute("ALTER TABLE \"Item\" ADD COLUMN \"external\" INTEGER")
            }
            sync.drift(connection, SchemaMapper.map(desired)).isEmpty shouldBe false
            sync.push(connection, desired, acceptWarnings = true).steps.size shouldBe 1
            sync.plan(connection, desired).isEmpty shouldBe true
            shouldThrow<VolanMigrationException> { sync.plan(connection, Fixtures.blog()) }
        }
    }

    @Test
    fun `pull and drift do not commit a caller transaction and push is refused before DDL`() {
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
            val model = schema("value Int?")
            val sync = DatabaseSync(H2Reader(), H2Dialect)
            connection.createStatement().use { statement ->
                sync.plan(connection, model).render(H2Dialect).forEach { statement.execute(it) }
            }
            connection.autoCommit = false
            connection.createStatement().use { it.execute("INSERT INTO \"Item\"(\"id\") VALUES (1)") }
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(model)
            sync.drift(connection, SchemaMapper.map(model)).isEmpty shouldBe true
            shouldThrow<VolanMigrationException> { sync.push(connection, model) }
            connection.autoCommit shouldBe false
            connection.rollback()
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM \"Item\"").use {
                    it.next()
                    it.getInt(1) shouldBe 0
                }
            }
        }
    }

    @Test
    fun `H2 column casts use SET DATA TYPE and honor an explicit conversion expression`() {
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE \"odd\" (\"a\" INTEGER)")
                statement.execute("INSERT INTO \"odd\" VALUES (3)")
                val ddl = DdlStatement.AlterColumn("odd", "a", ColumnChange.Type(ColumnType.Scalar(SqlType.BIGINT), "\"a\" * 2"))
                H2Dialect.render(ddl).forEach { statement.execute(it.sql) }
                statement.executeQuery("SELECT \"a\" FROM \"odd\"").use {
                    it.next()
                    it.getLong(1) shouldBe 6L
                }
            }
        }
    }

    @Test
    fun `explicit NoAction is normalized to H2 immediate Restrict without endless foreign key changes`() {
        val schema = Fixtures.schema(
            """
            datasource db {
              provider = "h2"
              url = env("DATABASE_URL")
            }
            model Parent {
              id Int @id
              children Child[]
            }
            model Child {
              id Int @id
              parentId Int
              parent Parent @relation(fields: [parentId], references: [id], onDelete: NoAction, onUpdate: NoAction)
            }
            """.trimIndent(),
        )
        DriverManager.getConnection("jdbc:h2:mem:${UUID.randomUUID()}").use { connection ->
            val sync = DatabaseSync(H2Reader(), H2Dialect)
            connection.createStatement().use { statement ->
                sync.plan(connection, schema).render(H2Dialect).forEach { statement.execute(it) }
            }
            sync.plan(connection, schema).isEmpty shouldBe true
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(schema)
        }
    }

    private fun schema(fields: String) = Fixtures.schema(
        """
        datasource db {
          provider = "h2"
          url = env("DATABASE_URL")
        }
        model Item {
          id Int @id
          $fields
        }
        """.trimIndent(),
    )
}
