package io.github.thirtyeighttwentysix.volan.ir

import io.github.thirtyeighttwentysix.volan.schema.SourceSpan
import io.github.thirtyeighttwentysix.volan.schema.Suggestions
import io.github.thirtyeighttwentysix.volan.schema.ast.Expression
import io.github.thirtyeighttwentysix.volan.schema.ast.FieldDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.Identifier
import io.github.thirtyeighttwentysix.volan.schema.ast.ModelDeclaration
import io.github.thirtyeighttwentysix.volan.schema.ast.NumberLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.StringLiteral
import io.github.thirtyeighttwentysix.volan.schema.ast.TypeArity
import io.github.thirtyeighttwentysix.volan.schema.ast.TypeReference

/**
 * Turns one `model` block into a [ModelDraft]: resolves every field's type, applies its attributes and
 * checks the constraints that can be decided without looking at other models.
 *
 * Relations are only recorded here, not validated: whether a relation makes sense depends on the model
 * at its other end, which may not have been read yet.
 */
@Suppress("TooManyFunctions")
internal class ModelAnalyzer(
    private val sink: DiagnosticSink,
    private val enums: Map<String, EnumType>,
    private val modelNames: Set<String>,
) {
    private val defaults = DefaultValueAnalyzer(sink, enums)

    fun analyze(declaration: ModelDeclaration): ModelDraft {
        val blockAttributes = declaration.attributes.map { it.toUse() }
        validateNames(blockAttributes, BLOCK_ATTRIBUTES, "@@", declaration.name.text)
        reportDuplicates(blockAttributes, SINGLE_USE_BLOCK_ATTRIBUTES, "@@")

        val scalars = ArrayList<ScalarField>()
        val relations = ArrayList<RelationFieldDraft>()
        readFields(declaration, scalars, relations)

        val resolution = resolvePrimaryKey(declaration, blockAttributes, scalars)
        val primaryKey = resolution.key
        val isIgnored = blockAttributes.any { it.qualifiedName == "ignore" }
        // Saying "no primary key" on top of "this key is broken" would just be noise.
        if (primaryKey == null && !resolution.reported && !isIgnored) reportMissingPrimaryKey(declaration)
        reportColumnCollisions(scalars)

        return ModelDraft(
            name = declaration.name.text,
            nameSpan = declaration.name.span,
            dbName = mappedName(blockAttributes, "@@") ?: declaration.name.text,
            fields = scalars,
            relationFields = relations,
            primaryKey = primaryKey,
            uniques = resolveConstraints(blockAttributes, "unique", scalars),
            indexes = resolveIndexes(blockAttributes, scalars),
            isIgnored = isIgnored,
            documentation = documentationOf(declaration.leadingComments),
            span = declaration.span,
        )
    }

    private fun readFields(declaration: ModelDeclaration, scalars: MutableList<ScalarField>, relations: MutableList<RelationFieldDraft>) {
        val seen = HashMap<String, SourceSpan>()
        declaration.fields.forEach { field ->
            val previous = seen.put(field.name.text, field.name.span)
            if (previous != null) {
                sink.error(
                    code = SemanticCode.DUPLICATE_MEMBER,
                    span = field.name.span,
                    message = "`${declaration.name.text}` already has a field called `${field.name.text}`",
                    label = "declared twice",
                )
                return@forEach
            }
            when (val resolution = resolveType(field.type)) {
                is TypeResolution.Data -> scalars.add(readScalarField(field, resolution.type))
                is TypeResolution.Relation -> relations.add(readRelationField(field, resolution.model))
                TypeResolution.Unknown -> Unit
            }
        }
    }

    private fun resolveType(type: TypeReference): TypeResolution {
        val name = type.name.text
        ScalarType.fromSchemaName(name)?.let { return TypeResolution.Data(FieldType.Scalar(it)) }
        if (name in enums) return TypeResolution.Data(FieldType.EnumRef(name))
        if (name in modelNames) return TypeResolution.Relation(name)
        val candidates = ScalarType.names() + enums.keys + modelNames
        sink.error(
            code = SemanticCode.UNKNOWN_TYPE,
            span = type.name.span,
            message = "unknown type `$name`",
            label = "no such scalar type, enum or model",
            help = Suggestions.closest(name, candidates)?.let { "did you mean `$it`?" }
                ?: "declare a `model $name { … }` or an `enum $name { … }`, or use a scalar type",
        )
        return TypeResolution.Unknown
    }

    private fun readScalarField(field: FieldDeclaration, type: FieldType): ScalarField {
        val uses = field.attributes.map { it.toUse() }
        validateNames(uses, FIELD_ATTRIBUTES, "@", field.name.text)
        reportDuplicates(uses, FIELD_ATTRIBUTES, "@")
        val cardinality = cardinalityOf(field.type.arity)
        val description = FieldDescription(field.name.text, type, cardinality)

        val isId = uses.any { it.qualifiedName == "id" }
        val isUnique = uses.any { it.qualifiedName == "unique" }
        val isUpdatedAt = uses.any { it.qualifiedName == "updatedAt" }
        val default = uses.firstOrNull { it.qualifiedName == "default" }?.let { defaults.resolve(it, description) }

        validateIdField(uses, field, cardinality)
        validateUniqueField(uses, field, cardinality)
        validateUpdatedAt(uses, field, type, cardinality)
        uses.filter { it.qualifiedName == "id" || it.qualifiedName == "unique" }
            .forEach { sink.rejectUnknownArguments(it, listOf("map"), allowedPositional = 0) }

        return ScalarField(
            name = field.name.text,
            dbName = mappedName(uses, "@") ?: field.name.text,
            type = type,
            cardinality = cardinality,
            isId = isId,
            isUnique = isUnique,
            isUpdatedAt = isUpdatedAt,
            isIgnored = uses.any { it.qualifiedName == "ignore" },
            default = default,
            nativeType = readNativeType(uses),
            documentation = documentationOf(field.leadingComments),
            span = field.span,
        )
    }

    private fun readRelationField(field: FieldDeclaration, targetModel: String): RelationFieldDraft {
        val uses = field.attributes.map { it.toUse() }
        validateNames(uses, RELATION_FIELD_ATTRIBUTES, "@", field.name.text)
        reportDuplicates(uses, RELATION_FIELD_ATTRIBUTES, "@")
        val relation = uses.firstOrNull { it.qualifiedName == "relation" }
        relation?.let { sink.rejectUnknownArguments(it, RELATION_ARGUMENTS, allowedPositional = 1) }

        val fields = relation?.named("fields")?.let { sink.readNameList(it, "relation") }.orEmpty()
        val references = relation?.named("references")?.let { sink.readNameList(it, "relation") }.orEmpty()
        val onDelete = relation?.named("onDelete")?.let { readAction(it.value.span, sink.readName(it, "relation")) }
        val onUpdate = relation?.named("onUpdate")?.let { readAction(it.value.span, sink.readName(it, "relation")) }

        return RelationFieldDraft(
            name = field.name.text,
            nameSpan = field.name.span,
            targetModel = targetModel,
            cardinality = cardinalityOf(field.type.arity),
            explicitRelationName = relation?.let { readRelationName(it) },
            fields = fields,
            references = references,
            onDelete = onDelete,
            onUpdate = onUpdate,
            onDeleteSpan = relation?.named("onDelete")?.span,
            isIgnored = uses.any { it.qualifiedName == "ignore" },
            documentation = documentationOf(field.leadingComments),
            span = field.span,
            fieldsArgumentSpan = relation?.named("fields")?.span,
            referencesArgumentSpan = relation?.named("references")?.span,
        )
    }

    private fun readRelationName(relation: AttributeUse): String? {
        val argument = relation.positional.firstOrNull() ?: relation.named("name") ?: return null
        return sink.readString(argument, "relation")
    }

    private fun readAction(span: SourceSpan, name: Identifier?): ReferentialAction? {
        if (name == null) return null
        val action = ReferentialAction.fromId(name.text)
        if (action == null) {
            sink.error(
                code = SemanticCode.INVALID_REFERENTIAL_ACTION,
                span = span,
                message = "`${name.text}` is not a referential action",
                label = "unknown action",
                help = Suggestions.closest(name.text, ReferentialAction.ids())?.let { "did you mean `$it`?" }
                    ?: "the actions are ${ReferentialAction.ids().joinToString(", ") { "`$it`" }}",
            )
        }
        return action
    }

    private fun readNativeType(uses: List<AttributeUse>): NativeType? {
        val use = uses.firstOrNull { it.namespace != null } ?: return null
        return NativeType(use.simpleName, use.arguments.map { argumentText(it.value) })
    }

    private fun argumentText(expression: Expression): String = when (expression) {
        is NumberLiteral -> expression.text
        is StringLiteral -> expression.value
        else -> expression.toString()
    }

    private fun validateIdField(uses: List<AttributeUse>, field: FieldDeclaration, cardinality: Cardinality) {
        val use = uses.firstOrNull { it.qualifiedName == "id" } ?: return
        if (cardinality == Cardinality.REQUIRED) return
        sink.error(
            code = SemanticCode.INVALID_ID_FIELD,
            span = use.span,
            message = "`${field.name.text}` cannot be the primary key",
            label = if (cardinality.isList) "a primary key holds one value, not a list" else "a primary key cannot be null",
            help = "remove the `${if (cardinality.isList) "[]" else "?"}` from its type, or choose another field",
        )
    }

    private fun validateUniqueField(uses: List<AttributeUse>, field: FieldDeclaration, cardinality: Cardinality) {
        val use = uses.firstOrNull { it.qualifiedName == "unique" } ?: return
        if (!cardinality.isList) return
        sink.error(
            code = SemanticCode.INVALID_UNIQUE_FIELD,
            span = use.span,
            message = "`${field.name.text}` holds many values, so it cannot be unique",
            label = "`@unique` applies to a single column",
        )
    }

    private fun validateUpdatedAt(uses: List<AttributeUse>, field: FieldDeclaration, type: FieldType, cardinality: Cardinality) {
        val use = uses.firstOrNull { it.qualifiedName == "updatedAt" } ?: return
        val isTimestamp = type is FieldType.Scalar && type.type == ScalarType.DATE_TIME
        if (isTimestamp && !cardinality.isList) return
        sink.error(
            code = SemanticCode.INVALID_UPDATED_AT,
            span = use.span,
            message = "`@updatedAt` needs a single `DateTime` field",
            label = "`${field.name.text}` is ${field.type}",
            help = "declare it as `${field.name.text} DateTime @updatedAt`",
        )
    }

    /**
     * The primary key of a model, and whether resolving it already reported a problem — so that the
     * caller does not add "this model has no primary key" to an error that already explains why.
     */
    private class PrimaryKeyResult(val key: PrimaryKey?, val reported: Boolean)

    private fun resolvePrimaryKey(
        declaration: ModelDeclaration,
        blockAttributes: List<AttributeUse>,
        scalars: List<ScalarField>,
    ): PrimaryKeyResult {
        val idFields = scalars.filter { it.isId }
        val blockId = blockAttributes.firstOrNull { it.qualifiedName == "id" }
        idFields.drop(1).forEach { extra ->
            sink.error(
                code = SemanticCode.DUPLICATE_PRIMARY_KEY,
                span = extra.span,
                message = "`${declaration.name.text}` declares more than one `@id` field",
                label = "`${idFields.first().name}` is already the primary key",
                help = "for a key over several columns write `@@id([${idFields.joinToString(", ") { it.name }}])`",
            )
        }
        if (blockId != null && idFields.isNotEmpty()) {
            sink.error(
                code = SemanticCode.DUPLICATE_PRIMARY_KEY,
                span = blockId.span,
                message = "`${declaration.name.text}` declares its primary key twice",
                label = "`${idFields.first().name}` already carries `@id`",
                help = "keep either the field attribute or `@@id`, not both",
            )
            return PrimaryKeyResult(null, reported = true)
        }
        if (blockId != null) {
            val key = resolveBlockPrimaryKey(blockId, scalars)
            return PrimaryKeyResult(key, reported = key == null)
        }
        return PrimaryKeyResult(idFields.firstOrNull()?.let { PrimaryKey(listOf(it.name), null) }, reported = false)
    }

    private fun resolveBlockPrimaryKey(use: AttributeUse, scalars: List<ScalarField>): PrimaryKey? {
        sink.rejectUnknownArguments(use, listOf("map"), allowedPositional = 1)
        val names = readFieldList(use, "@@id", scalars) ?: return null
        names.forEach { name ->
            val field = scalars.first { it.name == name }
            if (field.cardinality != Cardinality.REQUIRED) {
                sink.error(
                    code = SemanticCode.INVALID_ID_FIELD,
                    span = use.span,
                    message = "`$name` cannot be part of the primary key",
                    label = if (field.cardinality.isList) "it holds a list" else "it can be null",
                )
            }
        }
        return PrimaryKey(names, mappedNameArgument(use))
    }

    private fun resolveConstraints(
        blockAttributes: List<AttributeUse>,
        attributeName: String,
        scalars: List<ScalarField>,
    ): List<UniqueConstraint> = blockAttributes
        .filter { it.qualifiedName == attributeName }
        .mapNotNull { use ->
            sink.rejectUnknownArguments(use, listOf("map"), allowedPositional = 1)
            readFieldList(use, "@@$attributeName", scalars)?.let { UniqueConstraint(it, mappedNameArgument(use)) }
        }

    private fun resolveIndexes(blockAttributes: List<AttributeUse>, scalars: List<ScalarField>): List<Index> =
        blockAttributes.mapNotNull { use ->
            val kind = when (use.qualifiedName) {
                "index" -> IndexKind.BTREE
                "fulltext" -> IndexKind.FULLTEXT
                else -> return@mapNotNull null
            }
            sink.rejectUnknownArguments(use, listOf("map"), allowedPositional = 1)
            readFieldList(use, "@@${use.qualifiedName}", scalars)?.let { Index(it, mappedNameArgument(use), kind) }
        }

    /** Reads the `[a, b]` argument of a block attribute and checks that every name is a field of the model. */
    private fun readFieldList(use: AttributeUse, attribute: String, scalars: List<ScalarField>): List<String>? {
        val argument = use.positional.firstOrNull()
        if (argument == null) {
            sink.error(
                code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
                span = use.span,
                message = "`$attribute` needs the fields it applies to",
                label = "no fields given",
                help = "write `$attribute([fieldA, fieldB])`",
            )
            return null
        }
        val names = sink.readNameList(argument, use.qualifiedName) ?: return null
        if (names.isEmpty()) {
            sink.error(
                code = SemanticCode.EMPTY_FIELD_LIST,
                span = argument.span,
                message = "`$attribute` was given no fields",
                label = "an empty list constrains nothing",
            )
            return null
        }
        val resolved = ArrayList<String>(names.size)
        names.forEach { name ->
            when {
                scalars.none { it.name == name.text } -> reportUnknownFieldReference(name, attribute, scalars)
                name.text in resolved -> sink.error(
                    code = SemanticCode.DUPLICATE_MEMBER,
                    span = name.span,
                    message = "`$attribute` lists `${name.text}` twice",
                    label = "already listed",
                )
                else -> resolved.add(name.text)
            }
        }
        return resolved.takeIf { it.size == names.size }
    }

    private fun reportUnknownFieldReference(name: Identifier, attribute: String, scalars: List<ScalarField>) {
        sink.error(
            code = SemanticCode.UNKNOWN_FIELD_REFERENCE,
            span = name.span,
            message = "`$attribute` refers to `${name.text}`, which is not a field of this model",
            label = "unknown field",
            help = Suggestions.closest(name.text, scalars.map { it.name })?.let { "did you mean `$it`?" }
                ?: "only the model's own scalar fields can be listed here",
        )
    }

    private fun reportMissingPrimaryKey(declaration: ModelDeclaration) {
        sink.error(
            code = SemanticCode.MISSING_PRIMARY_KEY,
            span = declaration.name.span,
            message = "`${declaration.name.text}` has no primary key",
            label = "Volan cannot address a row of this model",
            help = "add `@id` to a field, or `@@id([a, b])` for a key over several columns",
        )
    }

    private fun reportColumnCollisions(scalars: List<ScalarField>) {
        val byColumn = scalars.groupBy { it.dbName }
        byColumn.values.filter { it.size > 1 }.forEach { collisions ->
            collisions.drop(1).forEach { field ->
                sink.error(
                    code = SemanticCode.DUPLICATE_MAPPED_NAME,
                    span = field.span,
                    message = "two fields map to the column `${field.dbName}`",
                    label = "`${collisions.first().name}` already uses it",
                    help = "give one of them a different `@map(\"…\")`",
                )
            }
        }
    }

    private fun validateNames(uses: List<AttributeUse>, allowed: Set<String>, marker: String, target: String) {
        uses.forEach { use ->
            if (use.namespace != null) {
                validateNamespace(use, marker)
                return@forEach
            }
            if (use.simpleName in allowed) return@forEach
            sink.error(
                code = SemanticCode.UNKNOWN_ATTRIBUTE,
                span = use.span,
                message = "`$marker${use.simpleName}` cannot be used on `$target`",
                label = "unknown attribute here",
                help = Suggestions.closest(use.simpleName, allowed.toList())?.let { "did you mean `$marker$it`?" }
                    ?: "the attributes allowed here are ${allowed.sorted().joinToString(", ") { "`$marker$it`" }}",
            )
        }
    }

    private fun validateNamespace(use: AttributeUse, marker: String) {
        if (use.namespace == "db" && marker == "@") return
        sink.error(
            code = SemanticCode.UNKNOWN_ATTRIBUTE_NAMESPACE,
            span = use.span,
            message = "`$marker${use.qualifiedName}` is not an attribute Volan knows",
            label = "unknown namespace `${use.namespace}`",
            help = "native database types are written on a field as `@db.VarChar(200)`",
        )
    }

    private fun reportDuplicates(uses: List<AttributeUse>, singleUse: Set<String>, marker: String) {
        val seen = HashSet<String>()
        uses.forEach { use ->
            val name = if (use.namespace != null) "db" else use.simpleName
            if (use.namespace == null && name !in singleUse) return@forEach
            if (!seen.add(name)) {
                sink.error(
                    code = SemanticCode.DUPLICATE_ATTRIBUTE,
                    span = use.span,
                    message = "`$marker${use.qualifiedName}` is applied twice",
                    label = "already applied above",
                )
            }
        }
    }

    private fun mappedName(uses: List<AttributeUse>, marker: String): String? {
        val use = uses.firstOrNull { it.qualifiedName == "map" } ?: return null
        sink.rejectUnknownArguments(use, listOf("name"), allowedPositional = 1)
        val argument = use.positional.firstOrNull() ?: use.named("name")
        if (argument == null) {
            sink.error(
                code = SemanticCode.MISSING_ATTRIBUTE_ARGUMENT,
                span = use.span,
                message = "`${marker}map` needs the name to use in the database",
                label = "no name given",
                help = "write `${marker}map(\"table_name\")`",
            )
            return null
        }
        return sink.readString(argument, "map")
    }

    private fun mappedNameArgument(use: AttributeUse): String? = use.named("map")?.let { sink.readString(it, use.qualifiedName) }

    private fun cardinalityOf(arity: TypeArity): Cardinality = when (arity) {
        TypeArity.REQUIRED -> Cardinality.REQUIRED
        TypeArity.OPTIONAL -> Cardinality.OPTIONAL
        TypeArity.LIST -> Cardinality.LIST
    }

    private sealed interface TypeResolution {
        data class Data(val type: FieldType) : TypeResolution

        data class Relation(val model: String) : TypeResolution

        data object Unknown : TypeResolution
    }

    private companion object {
        private val FIELD_ATTRIBUTES = setOf("id", "unique", "default", "map", "updatedAt", "ignore")
        private val RELATION_FIELD_ATTRIBUTES = setOf("relation", "ignore")
        private val BLOCK_ATTRIBUTES = setOf("id", "unique", "index", "fulltext", "map", "ignore")
        private val SINGLE_USE_BLOCK_ATTRIBUTES = setOf("id", "map", "ignore")
        private val RELATION_ARGUMENTS = listOf("name", "fields", "references", "onDelete", "onUpdate")
    }
}
