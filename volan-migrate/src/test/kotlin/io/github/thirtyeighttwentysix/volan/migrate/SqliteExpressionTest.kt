package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.sqlite.SqliteDialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class SqliteExpressionTest {
    @Test
    fun `generated defaults preserve operators numbers BLOBs and constant expressions`() {
        val schema = Fixtures.schema(
            """
            datasource db {
              provider = "sqlite"
              url = env("DATABASE_URL")
            }
            model Expression {
              id Int @id @default(autoincrement())
              title String @default(dbgenerated("'a' || ',' || 'b'"))
              ratio Double @default(dbgenerated("round(1.25e1, 1)"))
              data Bytes @default(dbgenerated("X'0102'"))
              truth Boolean @default(dbgenerated("1 <= 2"))
              literal String @default(dbgenerated("'literal'"))
              number Int @default(dbgenerated("0x10"))
            }
            """.trimIndent(),
        )
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val sync = DatabaseSync(SqliteReader(), SqliteDialect)
            sync.push(connection, schema)
            connection.createStatement().use { it.execute("INSERT INTO Expression DEFAULT VALUES") }
            connection.createStatement().use { sql ->
                sql.executeQuery("SELECT * FROM Expression").use {
                    it.next()
                    it.getString("title") shouldBe "a,b"
                    it.getDouble("ratio") shouldBe 12.5
                    it.getBytes("data").toList() shouldBe listOf(1.toByte(), 2.toByte())
                    it.getInt("truth") shouldBe 1
                    it.getString("literal") shouldBe "literal"
                    it.getInt("number") shouldBe 16
                }
            }
            sync.push(connection, schema).isEmpty shouldBe true
            SchemaMapper.map(Fixtures.schema(sync.pull(connection))) shouldBe SchemaMapper.map(schema)
        }
        SqliteSql.normalize("1>=.5 AND 1!=2 AND 1<>2 AND 1==1 AND '{}'->>'x'='x'") shouldBe
            "1 >= .5 and 1 != 2 and 1 <> 2 and 1 == 1 and '{}' ->> 'x' = 'x'"
        SqliteSql.normalize("'{}'->'x'") shouldBe "'{}' -> 'x'"
    }
}
