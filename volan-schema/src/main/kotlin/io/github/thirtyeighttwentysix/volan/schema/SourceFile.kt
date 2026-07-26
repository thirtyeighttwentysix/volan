package io.github.thirtyeighttwentysix.volan.schema

/**
 * A schema document together with the name it should be reported under in diagnostics.
 *
 * The file is read once and indexed by line, so that any character offset can be converted into a
 * human-readable line and column in constant time.
 *
 * @property name the name shown in diagnostics, conventionally `schema.volan`.
 * @property text the full text of the document.
 */
public class SourceFile(public val name: String, public val text: String) {
    private val lineStartOffsets: IntArray = computeLineStartOffsets(text)

    /** The number of lines in the document. A document always has at least one line. */
    public val lineCount: Int
        get() = lineStartOffsets.size

    /**
     * Converts a character [offset] into a one-based line and column.
     *
     * Offsets outside the document are clamped to its boundaries, so this never fails: a diagnostic
     * pointing just past the last character reports the end of the last line.
     */
    public fun positionOf(offset: Int): SourcePosition {
        val clamped = offset.coerceIn(0, text.length)
        var low = 0
        var high = lineStartOffsets.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (lineStartOffsets[middle] <= clamped) low = middle else high = middle - 1
        }
        return SourcePosition(line = low + 1, column = clamped - lineStartOffsets[low] + 1, offset = clamped)
    }

    /**
     * Returns the text of the given one-based [line], without its line terminator.
     *
     * @throws IndexOutOfBoundsException if [line] is not in `1..lineCount`.
     */
    public fun lineText(line: Int): String {
        require(line in 1..lineCount) { "line $line is out of range 1..$lineCount in $name" }
        val start = lineStartOffsets[line - 1]
        val end = if (line < lineCount) lineStartOffsets[line] else text.length
        return text.substring(start, end).trimEnd('\n', '\r')
    }

    override fun toString(): String = "SourceFile($name, ${text.length} chars)"

    private companion object {
        private fun computeLineStartOffsets(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts.add(0)
            var index = 0
            while (index < text.length) {
                if (text[index] == '\n') starts.add(index + 1)
                index++
            }
            return starts.toIntArray()
        }
    }
}

/**
 * A one-based line and column within a [SourceFile], together with the raw character [offset].
 *
 * @property line the one-based line number.
 * @property column the one-based column number, counted in characters.
 * @property offset the zero-based character offset in the document.
 */
public data class SourcePosition(
    public val line: Int,
    public val column: Int,
    public val offset: Int,
) {
    override fun toString(): String = "$line:$column"
}

/**
 * A half-open range of characters `[start, end)` within a [SourceFile].
 *
 * Every AST node carries one, which is what allows diagnostics to underline exactly the text the user
 * wrote rather than approximating it.
 *
 * @property start the zero-based offset of the first character.
 * @property end the zero-based offset just past the last character.
 */
public data class SourceSpan(public val start: Int, public val end: Int) {
    init {
        require(start >= 0) { "span start must not be negative, was $start" }
        require(end >= start) { "span end $end must not precede its start $start" }
    }

    /** The number of characters covered by this span. */
    public val length: Int
        get() = end - start

    /** Returns the smallest span covering both this span and [other]. */
    public fun union(other: SourceSpan): SourceSpan = SourceSpan(minOf(start, other.start), maxOf(end, other.end))

    override fun toString(): String = "$start..$end"
}
