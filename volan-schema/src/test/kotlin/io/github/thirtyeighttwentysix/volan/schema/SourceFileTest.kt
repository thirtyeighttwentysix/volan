package io.github.thirtyeighttwentysix.volan.schema

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SourceFileTest {
    private val source = SourceFile("schema.volan", "model User {\n  id Int\n}\n")

    @Test
    fun `counts lines including the one after a trailing newline`() {
        source.lineCount shouldBe 4
    }

    @Test
    fun `maps the first character to line 1 column 1`() {
        source.positionOf(0) shouldBe SourcePosition(line = 1, column = 1, offset = 0)
    }

    @Test
    fun `maps an offset in the middle of a line`() {
        val offset = source.text.indexOf("Int")
        source.positionOf(offset) shouldBe SourcePosition(line = 2, column = 6, offset = offset)
    }

    @Test
    fun `clamps offsets past the end of the document`() {
        source.positionOf(Int.MAX_VALUE).offset shouldBe source.text.length
    }

    @Test
    fun `returns line text without the terminator`() {
        source.lineText(2) shouldBe "  id Int"
    }

    @Test
    fun `spans report their length and can be merged`() {
        val span = SourceSpan(3, 7)
        span.length shouldBe 4
        span.union(SourceSpan(10, 12)) shouldBe SourceSpan(3, 12)
    }
}
