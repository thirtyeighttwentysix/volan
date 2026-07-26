package io.github.thirtyeighttwentysix.volan.schema

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import org.junit.jupiter.api.Test

/**
 * Properties the parser and the formatter must hold for arbitrary schemas, not just the ones a human
 * thought to write down.
 */
class SchemaFormatterPropertyTest {
    private val random = RandomSource.seeded(SEED)

    private val modelNames = Arb.of("User", "Post", "Tag", "Profile", "Comment", "Session")
    private val fieldNames = Arb.of("id", "email", "name", "title", "body", "createdAt", "authorId", "veryLongFieldName")
    private val typeNames = Arb.of("Int", "String", "DateTime", "Boolean", "Decimal", "Post", "Tag")
    private val arityMarkers = Arb.of("", "?", "[]")
    private val fieldAttributes = Arb.of("", " @id", " @unique", " @default(now())", " @map(\"column\")", " @db.VarChar(200)")
    private val blockAttributes = Arb.of("", "@@map(\"t\")", "@@index([id])", "@@unique([id, name])")
    private val counts = Arb.int(1..4)

    @Test
    fun `formatting any schema twice changes nothing the second time`() {
        repeat(ITERATIONS) {
            val schema = randomSchema()
            val once = SchemaFormatter.format("schema.volan", schema)
            val twice = SchemaFormatter.format("schema.volan", once)
            withClue(schema) { twice shouldBe once }
        }
    }

    @Test
    fun `horizontal whitespace never changes the formatted result`() {
        repeat(ITERATIONS) {
            val canonical = SchemaFormatter.format("schema.volan", randomSchema())
            val stretched = canonical.lines().joinToString("\n") { line -> line.replace(" ", "   ") + "  " }
            withClue(canonical) { SchemaFormatter.format("schema.volan", stretched) shouldBe canonical }
        }
    }

    @Test
    fun `every generated schema parses cleanly`() {
        repeat(ITERATIONS) {
            val schema = randomSchema()
            withClue(schema) { SchemaParser.parse("schema.volan", schema).render() shouldBe "" }
        }
    }

    private fun randomSchema(): String = buildString {
        repeat(counts.next(random)) {
            append("model ").append(modelNames.next(random)).append(" {\n")
            repeat(counts.next(random)) {
                append("  ").append(fieldNames.next(random)).append(' ')
                append(typeNames.next(random)).append(arityMarkers.next(random))
                append(fieldAttributes.next(random)).append('\n')
            }
            val blockAttribute = blockAttributes.next(random)
            if (blockAttribute.isNotEmpty()) append('\n').append("  ").append(blockAttribute).append('\n')
            append("}\n\n")
        }
        append("enum Role {\n  USER\n  ADMIN\n}\n")
    }

    private companion object {
        private const val SEED = 20_260_726L
        private const val ITERATIONS = 200
    }
}
