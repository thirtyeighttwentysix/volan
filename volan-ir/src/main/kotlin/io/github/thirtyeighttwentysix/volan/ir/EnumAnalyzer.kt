package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.Suggestions
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.EnumValueDeclaration

/** Turns one `enum` block into an [EnumType], applying `@map` and rejecting anything else. */
internal class EnumAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(declaration: EnumDeclaration): EnumType {
        val blockAttributes = declaration.attributes.map { it.toUse() }
        blockAttributes.forEach { use ->
            if (use.qualifiedName != "map") reportUnknown(use, "@@", declaration.name.text, BLOCK_ATTRIBUTES)
        }
        // An enum with no values never reaches analysis: the parser rejects it as E0118.
        val values = readValues(declaration)
        reportValueCollisions(values)
        return EnumType(
            name = declaration.name.text,
            dbName = blockAttributes.firstOrNull { it.qualifiedName == "map" }?.let { readMappedName(it, "@@") }
                ?: declaration.name.text,
            values = values,
            documentation = documentationOf(declaration.leadingComments),
            span = declaration.span,
        )
    }

    private fun readValues(declaration: EnumDeclaration): List<EnumValue> {
        val seen = HashSet<String>()
        return declaration.values.mapNotNull { value ->
            if (!seen.add(value.name.text)) {
                sink.error(
                    code = SemanticCode.DUPLICATE_MEMBER,
                    span = value.name.span,
                    message = "`${declaration.name.text}` already has a value called `${value.name.text}`",
                    label = "declared twice",
                )
                null
            } else {
                readValue(value, declaration.name.text)
            }
        }
    }

    private fun readValue(value: EnumValueDeclaration, enumName: String): EnumValue {
        val uses = value.attributes.map { it.toUse() }
        uses.forEach { use ->
            if (use.qualifiedName != "map") reportUnknown(use, "@", "$enumName.${value.name.text}", VALUE_ATTRIBUTES)
        }
        val mapped = uses.firstOrNull { it.qualifiedName == "map" }?.let { readMappedName(it, "@") }
        return EnumValue(
            name = value.name.text,
            dbName = mapped ?: value.name.text,
            documentation = documentationOf(value.leadingComments),
            span = value.span,
        )
    }

    private fun readMappedName(use: AttributeUse, marker: String): String? {
        sink.rejectUnknownArguments(use, listOf("name"), allowedPositional = 1)
        val argument = use.positional.firstOrNull() ?: use.named("name")
        if (argument == null) {
            sink.error(
                code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
                span = use.span,
                message = "`${marker}map` needs the name to use in the database",
                label = "no name given",
                help = "write `${marker}map(\"name\")`",
            )
            return null
        }
        return sink.readString(argument, "map")
    }

    private fun reportValueCollisions(values: List<EnumValue>) {
        values.groupBy { it.dbName }.values.filter { it.size > 1 }.forEach { collisions ->
            collisions.drop(1).forEach { value ->
                sink.error(
                    code = SemanticCode.DUPLICATE_MAPPED_NAME,
                    span = value.span,
                    message = "two values map to `${value.dbName}` in the database",
                    label = "`${collisions.first().name}` already uses it",
                )
            }
        }
    }

    private fun reportUnknown(use: AttributeUse, marker: String, target: String, allowed: Set<String>) {
        sink.error(
            code = SemanticCode.UNKNOWN_ATTRIBUTE,
            span = use.span,
            message = "`$marker${use.qualifiedName}` cannot be used on `$target`",
            label = "unknown attribute here",
            help = Suggestions.closest(use.simpleName, allowed.toList())?.let { "did you mean `$marker$it`?" }
                ?: "enums accept ${allowed.joinToString(", ") { "`$marker$it`" }}",
        )
    }

    private companion object {
        private val BLOCK_ATTRIBUTES = setOf("map")
        private val VALUE_ATTRIBUTES = setOf("map")
    }
}
