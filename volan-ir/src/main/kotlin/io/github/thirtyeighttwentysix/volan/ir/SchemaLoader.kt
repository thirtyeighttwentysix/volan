package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.Diagnostic
import io.github.thirtyeighttwentysix.volan.schema.DiagnosticRenderer
import io.github.thirtyeighttwentysix.volan.schema.ParseResult
import io.github.thirtyeighttwentysix.volan.schema.SchemaParser
import io.github.thirtyeighttwentysix.volan.schema.SourceFile
import io.github.thirtyeighttwentysix.volan.schema.VolanSchemaException
import io.github.thirtyeighttwentysix.volan.schema.ast.Declaration
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.ModelDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.SchemaDocument

/**
 * The outcome of analysing one schema document.
 *
 * A [schema] is produced only when nothing is wrong with the document: a partially valid model would
 * generate code that does not compile, or migrations that corrupt data, so analysis either hands over
 * a schema it stands behind or none at all. Warnings do not prevent one.
 *
 * @property schema the validated schema, or `null` when [diagnostics] contains an error.
 * @property diagnostics every problem found, syntactic and semantic, in source order.
 * @property source the document that was analysed.
 */
public class AnalysisResult internal constructor(
    public val schema: Schema?,
    public val diagnostics: List<Diagnostic>,
    public val source: SourceFile,
) {
    /** True when at least one diagnostic is an error, meaning no schema was produced. */
    public val hasErrors: Boolean
        get() = diagnostics.any { it.isError }

    /** Only the error diagnostics, in source order. */
    public val errors: List<Diagnostic>
        get() = diagnostics.filter { it.isError }

    /** Only the warning diagnostics, in source order. */
    public val warnings: List<Diagnostic>
        get() = diagnostics.filterNot { it.isError }

    /**
     * Returns the validated [schema], or throws when the document could not produce one.
     *
     * @throws VolanSchemaException if the schema contains errors. Its message is the rendered diagnostics.
     */
    public fun schemaOrThrow(): Schema {
        val validated = schema
        if (validated == null) throw VolanSchemaException(render(), diagnostics)
        return validated
    }

    /** Renders every diagnostic as a code frame, ready to be printed. Empty when there is nothing to report. */
    public fun render(): String = DiagnosticRenderer.renderAll(diagnostics, source)

    override fun toString(): String =
        "AnalysisResult(${if (schema == null) "no schema" else "valid"}, ${errors.size} errors, ${warnings.size} warnings)"
}

/**
 * Reads a `schema.volan` document and validates it into a [Schema].
 *
 * ```kotlin
 * val schema = SchemaLoader.load("schema.volan", File("schema.volan").readText()).schemaOrThrow()
 * ```
 *
 * Loading is parsing followed by semantic analysis. Analysis only runs on a document that parsed
 * cleanly: reporting "unknown type `Strng`" for a field whose type could not even be read would be
 * guesswork presented as fact.
 */
public object SchemaLoader {
    /** Parses and analyses [source]. */
    @JvmStatic
    public fun load(source: SourceFile): AnalysisResult = analyze(SchemaParser.parse(source))

    /** Parses and analyses schema [text] reported under the given file [name]. */
    @JvmStatic
    public fun load(name: String, text: String): AnalysisResult = load(SourceFile(name, text))

    /**
     * Parses and analyses [source], returning the schema or throwing.
     *
     * @throws VolanSchemaException if the document is not a valid schema.
     */
    @JvmStatic
    public fun loadOrThrow(source: SourceFile): Schema = load(source).schemaOrThrow()

    /**
     * Analyses an already parsed document.
     *
     * When [parse] reported syntax errors, its diagnostics are passed through unchanged and no
     * semantic analysis is attempted.
     */
    @JvmStatic
    public fun analyze(parse: ParseResult): AnalysisResult {
        if (parse.hasErrors) return AnalysisResult(null, parse.diagnostics, parse.source)
        val sink = DiagnosticSink()
        val schema = SemanticAnalyzer(sink).analyze(parse.document)
        return AnalysisResult(schema, parse.diagnostics + sink.toList(), parse.source)
    }
}

/** Runs the analysis passes in order and assembles the [Schema] when they all agree. */
internal class SemanticAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(document: SchemaDocument): Schema? {
        reportDuplicateDeclarations(document)

        val datasource = ConfigAnalyzer(sink).analyzeDatasource(document)
        val generators = ConfigAnalyzer(sink).analyzeGenerators(document)

        val enumAnalyzer = EnumAnalyzer(sink)
        val enums = document.enums.map { enumAnalyzer.analyze(it) }
        val enumsByName = enums.associateBy { it.name }

        val modelNames = document.models.map { it.name.text }.toSet()
        val modelAnalyzer = ModelAnalyzer(sink, enumsByName, modelNames)
        val drafts = document.models.map { modelAnalyzer.analyze(it) }
        val draftsByName = drafts.associateBy { it.name }

        val relations = RelationAnalyzer(sink, draftsByName).analyze()
        IntegrityAnalyzer(sink).analyze(drafts, enums, relations)

        if (sink.hasErrors || datasource == null) return null
        return Schema(
            datasource = datasource,
            generators = generators,
            enums = enums,
            models = drafts.map { it.toModel() },
            relations = relations,
        )
    }

    private fun reportDuplicateDeclarations(document: SchemaDocument) {
        val seen = HashMap<String, Declaration>()
        document.declarations.filter { it is ModelDeclaration || it is EnumDeclaration }.forEach { declaration ->
            val previous = seen.put(declaration.name.text, declaration)
            if (previous != null) {
                sink.error(
                    code = SemanticCode.DUPLICATE_DECLARATION,
                    span = declaration.name.span,
                    message = "`${declaration.name.text}` is declared twice",
                    label = "${kindOf(declaration)} with a name already taken",
                    help = "models and enums share one namespace, so each name may be used once",
                )
            }
        }
    }

    private fun kindOf(declaration: Declaration): String = when (declaration) {
        is ModelDeclaration -> "a model"
        is EnumDeclaration -> "an enum"
        else -> "a declaration"
    }
}
