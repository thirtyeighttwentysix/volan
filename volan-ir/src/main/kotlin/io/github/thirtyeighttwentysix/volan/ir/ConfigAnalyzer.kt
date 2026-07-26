package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.github.thirtyeighttwentysix.volan.schema.Suggestions
import io.github.thirtyeighttwentysix.volan.schema.ast.ConfigEntry
import io.github.thirtyeighttwentysix.volan.schema.ast.DatasourceDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.FunctionCall
import io.github.thirtyeighttwentysix.volan.schema.ast.GeneratorDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.SchemaDocument
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral

/**
 * Resolves the `datasource` and `generator` blocks.
 *
 * These are the only places in a schema where a typo costs you a whole build rather than one field,
 * so unknown properties are errors with suggestions rather than something quietly ignored.
 */
internal class ConfigAnalyzer(private val sink: DiagnosticSink) {
    fun analyzeDatasource(document: SchemaDocument): DatasourceConfig? {
        val blocks = document.datasources
        if (blocks.isEmpty()) {
            sink.error(
                code = SemanticCode.MISSING_DATASOURCE,
                span = SourceSpan(0, 0),
                message = "the schema has no `datasource` block",
                label = "Volan needs to know which database this schema targets",
                help = "add a block such as `datasource db { provider = \"postgresql\" url = env(\"DATABASE_URL\") }`",
            )
            return null
        }
        blocks.drop(1).forEach { extra ->
            sink.error(
                code = SemanticCode.DUPLICATE_DATASOURCE,
                span = extra.name.span,
                message = "a schema may declare only one `datasource`",
                label = "`${blocks.first().name.text}` is already declared above",
                help = "targeting several databases from one schema is not supported; use one schema per database",
            )
        }
        return resolveDatasource(blocks.first())
    }

    fun analyzeGenerators(document: SchemaDocument): List<GeneratorConfig> = document.generators.mapNotNull { resolveGenerator(it) }

    private fun resolveDatasource(block: DatasourceDeclaration): DatasourceConfig? {
        val entries = indexEntries(block.entries, DATASOURCE_OPTIONS, "datasource")
        val provider = requiredString(entries, "provider", block.name.span, "datasource")?.let { resolveProvider(it, entries) }
        val url = entries["url"]?.let { resolveUrl(it) } ?: run {
            reportMissing("url", block.name.span, "datasource", "url = env(\"DATABASE_URL\")")
            null
        }
        if (provider == null || url == null) return null
        return DatasourceConfig(block.name.text, provider, url, block.span)
    }

    private fun resolveProvider(value: String, entries: Map<String, ConfigEntry>): Provider? {
        val provider = Provider.fromId(value)
        if (provider == null) {
            val entry = entries.getValue("provider")
            sink.error(
                code = SemanticCode.UNKNOWN_PROVIDER,
                span = entry.value.span,
                message = "`$value` is not a database Volan supports",
                label = "unknown provider",
                help = Suggestions.closest(value, Provider.ids())?.let { "did you mean `$it`?" }
                    ?: "the supported providers are ${Provider.ids().joinToString(", ") { "`$it`" }}",
            )
        }
        return provider
    }

    private fun resolveUrl(entry: ConfigEntry): ConnectionUrl? = when (val value = entry.value) {
        is StringLiteral -> {
            warnOnUrlInSchema(value)
            ConnectionUrl.Literal(value.value)
        }
        is FunctionCall -> resolveEnvCall(value)
        else -> {
            sink.error(
                code = SemanticCode.INVALID_OPTION_VALUE,
                span = value.span,
                message = "`url` must be a connection string or `env(\"NAME\")`",
                label = "this is neither",
            )
            null
        }
    }

    /**
     * A connection string written into the schema ends up in version control, and if it carries
     * credentials it ends up wherever the repository ends up. This is a warning rather than an error
     * because a local development URL without a password is a legitimate thing to check in.
     */
    private fun warnOnUrlInSchema(value: StringLiteral) {
        sink.warning(
            code = SemanticCode.CONNECTION_URL_IN_SCHEMA,
            span = value.span,
            message = "the connection URL is written into the schema",
            label = "this value will be committed with the file",
            help = "read it from the environment instead: `url = env(\"DATABASE_URL\")`",
        )
    }

    private fun resolveEnvCall(call: FunctionCall): ConnectionUrl? {
        if (call.name.text != "env") {
            sink.error(
                code = SemanticCode.INVALID_OPTION_VALUE,
                span = call.span,
                message = "`${call.name.text}()` cannot produce a connection URL",
                label = "unknown function",
                help = "read the URL from the environment with `env(\"DATABASE_URL\")`",
            )
            return null
        }
        val argument = call.arguments.singleOrNull()?.value as? StringLiteral
        if (argument == null) {
            sink.error(
                code = SemanticCode.INVALID_OPTION_VALUE,
                span = call.span,
                message = "`env()` takes the name of one environment variable",
                label = "expected exactly one quoted name",
                help = "write `env(\"DATABASE_URL\")`",
            )
            return null
        }
        return ConnectionUrl.Environment(argument.value)
    }

    private fun resolveGenerator(block: GeneratorDeclaration): GeneratorConfig? {
        val entries = indexEntries(block.entries, GENERATOR_OPTIONS, "generator")
        val provider = requiredString(entries, "provider", block.name.span, "generator")
        val packageName = requiredString(entries, "package", block.name.span, "generator")
        val output = entries["output"]?.let { stringValue(it) } ?: DEFAULT_OUTPUT
        val javaFriendly = entries["javaFriendly"]?.let { booleanValue(it) } ?: false
        if (provider != null && provider !in KNOWN_GENERATORS) {
            sink.error(
                code = SemanticCode.INVALID_OPTION_VALUE,
                span = entries.getValue("provider").value.span,
                message = "`$provider` is not a generator Volan knows",
                label = "unknown generator",
                help = Suggestions.closest(provider, KNOWN_GENERATORS)?.let { "did you mean `$it`?" }
                    ?: "the available generators are ${KNOWN_GENERATORS.joinToString(", ") { "`$it`" }}",
            )
            return null
        }
        if (provider == null || packageName == null) return null
        return GeneratorConfig(block.name.text, provider, packageName, output, javaFriendly, block.span)
    }

    /** Indexes a block's properties by name, reporting duplicates and names the block does not accept. */
    private fun indexEntries(entries: List<ConfigEntry>, allowed: List<String>, kind: String): Map<String, ConfigEntry> {
        val byName = LinkedHashMap<String, ConfigEntry>()
        entries.forEach { entry ->
            val name = entry.key.text
            if (name !in allowed) {
                sink.error(
                    code = SemanticCode.UNKNOWN_OPTION,
                    span = entry.key.span,
                    message = "a `$kind` block has no property `$name`",
                    label = "unknown property",
                    help = Suggestions.closest(name, allowed)?.let { "did you mean `$it`?" }
                        ?: "it accepts ${allowed.joinToString(", ") { "`$it`" }}",
                )
                return@forEach
            }
            val previous = byName.put(name, entry)
            if (previous != null) {
                sink.error(
                    code = SemanticCode.DUPLICATE_MEMBER,
                    span = entry.key.span,
                    message = "`$name` is set twice in this `$kind` block",
                    label = "already set above",
                )
            }
        }
        return byName
    }

    private fun requiredString(entries: Map<String, ConfigEntry>, name: String, blockSpan: SourceSpan, kind: String): String? {
        val entry = entries[name]
        if (entry == null) {
            reportMissing(name, blockSpan, kind, "$name = \"…\"")
            return null
        }
        return stringValue(entry)
    }

    private fun stringValue(entry: ConfigEntry): String? {
        val value = entry.value
        if (value is StringLiteral) return value.value
        sink.error(
            code = SemanticCode.INVALID_OPTION_VALUE,
            span = value.span,
            message = "`${entry.key.text}` must be a quoted string",
            label = "this is not a string",
        )
        return null
    }

    private fun booleanValue(entry: ConfigEntry): Boolean? {
        val value = entry.value
        if (value is io.github.thirtyeighttwentysix.volan.schema.ast.BooleanLiteral) return value.value
        sink.error(
            code = SemanticCode.INVALID_OPTION_VALUE,
            span = value.span,
            message = "`${entry.key.text}` must be `true` or `false`",
            label = "this is not a boolean",
        )
        return null
    }

    private fun reportMissing(name: String, span: SourceSpan, kind: String, example: String) {
        sink.error(
            code = SemanticCode.MISSING_OPTION,
            span = span,
            message = "this `$kind` block does not set `$name`",
            label = "`$name` is required",
            help = "add `$example` inside the block",
        )
    }

    private companion object {
        private val DATASOURCE_OPTIONS = listOf("provider", "url")
        private val GENERATOR_OPTIONS = listOf("provider", "package", "output", "javaFriendly")
        private val KNOWN_GENERATORS = listOf("volan-kotlin")
        private const val DEFAULT_OUTPUT = "build/generated/volan"
    }
}
