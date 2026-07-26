package io.github.thirtyeighttwentysix.volan.schema

import io.github.thirtyeighttwentysix.volan.VolanException
import io.github.thirtyeighttwentysix.volan.schema.ast.SchemaDocument

/**
 * Thrown when a schema cannot be used.
 *
 * The message is the fully rendered diagnostic output, so printing the exception is enough to show the
 * user exactly what is wrong and where. The individual [diagnostics] remain available for tooling that
 * wants to present them differently.
 *
 * @property diagnostics every problem found, in source order.
 */
public class VolanSchemaException internal constructor(message: String, public val diagnostics: List<Diagnostic>) : VolanException(message)

/**
 * The outcome of parsing one schema document.
 *
 * Parsing always produces a [document], even when the source contains errors: the parser recovers and
 * keeps going, so tooling can still offer completion or formatting for the parts that are well formed.
 * Check [hasErrors] before using the document for anything that must be correct.
 *
 * @property document the parsed schema, possibly missing declarations that could not be recovered.
 * @property diagnostics every problem found, in source order.
 * @property source the document that was parsed.
 */
public class ParseResult internal constructor(
    public val document: SchemaDocument,
    public val diagnostics: List<Diagnostic>,
    public val source: SourceFile,
) {
    /** True when at least one diagnostic is an error, meaning the schema cannot be used. */
    public val hasErrors: Boolean
        get() = diagnostics.any { it.isError }

    /** Only the error diagnostics, in source order. */
    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.isError }

    /** Only the warning diagnostics, in source order. */
    public val warnings: List<Diagnostic>
        get() = diagnostics.filterNot { it.isError }

    /**
     * Returns the parsed [document], or throws when the schema contains errors.
     *
     * @throws VolanSchemaException if [hasErrors] is true. Its message is the rendered diagnostics.
     */
    public fun documentOrThrow(): SchemaDocument {
        if (hasErrors) throw VolanSchemaException(render(), diagnostics)
        return document
    }

    /**
     * Renders every diagnostic as a code frame, ready to be printed.
     *
     * Returns an empty string when there is nothing to report.
     */
    public fun render(): String = DiagnosticRenderer.renderAll(diagnostics, source)

    override fun toString(): String =
        "ParseResult(${document.declarations.size} declarations, ${errors.size} errors, ${warnings.size} warnings)"
}

/**
 * Parses `schema.volan` documents.
 *
 * ```kotlin
 * val result = SchemaParser.parse("schema.volan", File("schema.volan").readText())
 * if (result.hasErrors) {
 *     print(result.render())
 * }
 * ```
 *
 * Parsing is purely syntactic: it checks that the document is well formed, not that the model it
 * describes makes sense. Whether a referenced type exists, whether a relation has a matching other
 * side and whether an attribute may appear where it does are all decided by semantic analysis.
 */
public object SchemaParser {
    /**
     * Parses [source] and returns everything that was understood together with everything that was not.
     */
    @JvmStatic
    public fun parse(source: SourceFile): ParseResult {
        val lexed = Lexer(source).tokenize()
        val parsed = Parser(source, lexed.tokens).parse()
        val diagnostics = (lexed.diagnostics + parsed.diagnostics).sortedBy { it.span.start }
        return ParseResult(parsed.document, diagnostics, source)
    }

    /**
     * Parses schema [text] reported under the given file [name].
     */
    @JvmStatic
    public fun parse(name: String, text: String): ParseResult = parse(SourceFile(name, text))

    /**
     * Parses [source] and returns the document, or throws if the schema contains errors.
     *
     * @throws VolanSchemaException if the schema cannot be used.
     */
    @JvmStatic
    public fun parseOrThrow(source: SourceFile): SchemaDocument = parse(source).documentOrThrow()
}
