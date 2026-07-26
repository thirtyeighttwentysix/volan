package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.postgres.PostgresDialect
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The SQL a fresh database gets from the reference schema.
 *
 * A golden file rather than a set of assertions because this is the artefact a person reviews before
 * running it: what matters is that the whole of it reads correctly, not that a few substrings appear.
 */
class PostgresDdlTest {
    @Test
    fun `the reference schema becomes the migration in its golden file`() {
        val sql = SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.blog())).toSql(PostgresDialect)

        sql shouldBe golden("blog.sql")
    }

    @Test
    fun `an autoincrementing key is spelled as the type postgres has for it`() {
        val sql = SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.blog())).toSql(PostgresDialect)

        sql.shouldContain("\"id\" bigserial NOT NULL")
        sql.shouldContain("\"id\" serial NOT NULL")
    }

    private fun golden(name: String): String {
        if (System.getProperty("volan.updateGolden") == "true") {
            val sql = SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(Fixtures.blog())).toSql(PostgresDialect)
            Path.of("src/test/resources/golden/$name").toFile().writeText(sql)
            return sql
        }
        return requireNotNull(javaClass.getResourceAsStream("/golden/$name")) { "missing golden $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
            .replace("\r\n", "\n")
    }
}
