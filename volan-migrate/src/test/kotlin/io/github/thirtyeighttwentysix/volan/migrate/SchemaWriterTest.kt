package io.github.thirtyeighttwentysix.volan.migrate

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SchemaWriterTest {
    @Test
    fun `the reference schema including mapped names and join tables exports without data loss`() {
        val database = SchemaMapper.map(Fixtures.blog())
        val text = SchemaWriter.write(database)

        SchemaMapper.map(Fixtures.schema(text)) shouldBe database
        SchemaWriter.write(SchemaMapper.map(Fixtures.schema(text))) shouldBe text
    }

    @Test
    fun `an empty database is a valid schema`() {
        SchemaMapper.map(Fixtures.schema(SchemaWriter.write(DatabaseSchema()))) shouldBe DatabaseSchema()
    }
}
