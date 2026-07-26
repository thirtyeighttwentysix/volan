package io.github.thirtyeighttwentysix.volan.schema

/**
 * Renders [Diagnostic]s as a code frame: the offending line, a caret under the exact text, and a
 * suggested fix.
 *
 * ```text
 * error[E0104]: expected a field type
 *  ┌─ schema.volan:5:9
 *  │
 * 5│   email @unique
 *  │         ^ a field is written as `name Type`
 *  │
 *  = help: add a type, for example `email String`
 * ```
 *
 * The output is plain text with no escape sequences, so it is equally readable in a terminal, in a
 * build log and in an exception message.
 */
public object DiagnosticRenderer {
    private const val TAB_WIDTH = 4
    private const val TAB_REPLACEMENT = "    "

    /**
     * Renders a single [diagnostic] against the [source] it was produced from.
     */
    @JvmStatic
    public fun render(diagnostic: Diagnostic, source: SourceFile): String {
        val start = source.positionOf(diagnostic.span.start)
        val gutter = start.line.toString().length
        val pad = " ".repeat(gutter)
        val rawLine = source.lineText(start.line)
        val displayLine = rawLine.replace("\t", TAB_REPLACEMENT)

        val builder = StringBuilder()
        builder.append(diagnostic.severity.label).append('[').append(diagnostic.code.id).append("]: ")
        builder.append(diagnostic.message).append('\n')
        builder.append(pad).append("┌─ ").append(source.name).append(':').append(start.line).append(':')
        builder.append(displayColumn(rawLine, start.column)).append('\n')
        builder.append(pad).append("│").append('\n')
        builder.append(start.line).append("│").append(displayLine).append('\n')
        builder.append(pad).append("│").append(underline(rawLine, diagnostic, source, start))
        diagnostic.label?.let { builder.append(' ').append(it) }
        builder.append('\n')
        diagnostic.help?.let {
            builder.append(pad).append("│").append('\n')
            builder.append(pad).append("= help: ").append(it).append('\n')
        }
        return builder.toString()
    }

    /**
     * Renders every diagnostic in [diagnostics], separated by blank lines, and appends a summary line.
     *
     * Returns an empty string when [diagnostics] is empty.
     */
    @JvmStatic
    public fun renderAll(diagnostics: List<Diagnostic>, source: SourceFile): String {
        if (diagnostics.isEmpty()) return ""
        val builder = StringBuilder()
        diagnostics.forEach { builder.append(render(it, source)).append('\n') }
        val errors = diagnostics.count { it.isError }
        val warnings = diagnostics.size - errors
        builder.append(summary(errors, warnings)).append(" in ").append(source.name).append('\n')
        return builder.toString()
    }

    private fun summary(errors: Int, warnings: Int): String = when {
        warnings == 0 -> "${plural(errors, "error")} found"
        errors == 0 -> "${plural(warnings, "warning")} found"
        else -> "${plural(errors, "error")} and ${plural(warnings, "warning")} found"
    }

    private fun plural(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"

    /**
     * Converts a character column into the column it occupies once tabs are expanded for display.
     */
    private fun displayColumn(rawLine: String, column: Int): Int {
        var display = 1
        for (index in 0 until minOf(column - 1, rawLine.length)) {
            display += if (rawLine[index] == '\t') TAB_WIDTH else 1
        }
        return display
    }

    private fun underline(rawLine: String, diagnostic: Diagnostic, source: SourceFile, start: SourcePosition): String {
        val lineEndOffset = start.offset - (start.column - 1) + rawLine.length
        val endOffset = minOf(diagnostic.span.end, lineEndOffset)
        val endColumn = source.positionOf(maxOf(endOffset, diagnostic.span.start)).column
        val startDisplay = displayColumn(rawLine, start.column)
        val endDisplay = displayColumn(rawLine, endColumn)
        val caretCount = maxOf(1, endDisplay - startDisplay)
        return " ".repeat(startDisplay - 1) + "^".repeat(caretCount)
    }
}
