package io.github.thirtyeighttwentysix.volan.ir

/**
 * Renders a [Schema] as deterministic text.
 *
 * This exists so that the IR can be snapshot-tested: a golden file makes every consequence of a change
 * visible in review, including the ones nobody thought to assert. It is not a serialisation format and
 * nothing reads it back.
 */
internal object IrPrinter {
    fun print(schema: Schema): String = buildString {
        appendDatasource(schema.datasource)
        schema.generators.forEach { appendGenerator(it) }
        schema.enums.forEach { appendEnum(it) }
        schema.models.forEach { appendModel(it) }
        schema.relations.forEach { appendRelation(it) }
    }.trimEnd('\n') + "\n"

    private fun StringBuilder.appendDatasource(datasource: DatasourceConfig) {
        append("datasource ").append(datasource.name).append('\n')
        append("  provider = ").append(datasource.provider.id).append('\n')
        append("  url = ").append(describe(datasource.url)).append("\n\n")
    }

    private fun describe(url: ConnectionUrl): String = when (url) {
        is ConnectionUrl.Literal -> "\"${url.value}\""
        is ConnectionUrl.Environment -> "env(${url.variable})"
    }

    private fun StringBuilder.appendGenerator(generator: GeneratorConfig) {
        append("generator ").append(generator.name).append('\n')
        append("  provider = ").append(generator.provider).append('\n')
        append("  package = ").append(generator.packageName).append('\n')
        append("  output = ").append(generator.outputDirectory).append('\n')
        append("  javaFriendly = ").append(generator.javaFriendly).append("\n\n")
    }

    private fun StringBuilder.appendEnum(enumType: EnumType) {
        append("enum ").append(enumType.name).append(mapping(enumType.name, enumType.dbName)).append('\n')
        enumType.values.forEach { value ->
            append("  ").append(value.name).append(mapping(value.name, value.dbName)).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendModel(model: Model) {
        append("model ").append(model.name).append(mapping(model.name, model.dbName))
        if (model.isIgnored) append(" ignored")
        append('\n')
        model.fields.forEach { appendField(it) }
        model.relationFields.forEach { appendRelationField(it) }
        val primaryKey = model.primaryKey
        if (primaryKey == null) {
            append("  no primary key\n")
        } else {
            append("  primary key (").append(primaryKey.fields.joinToString(", ")).append(')')
            primaryKey.dbName?.let { append(" -> \"").append(it).append('"') }
            append('\n')
        }
        model.uniques.forEach { unique ->
            append("  unique (").append(unique.fields.joinToString(", ")).append(')')
            unique.dbName?.let { append(" -> \"").append(it).append('"') }
            append('\n')
        }
        model.indexes.forEach { index ->
            append("  index (").append(index.fields.joinToString(", ")).append(") ")
            append(index.kind.name.lowercase())
            index.dbName?.let { append(" -> \"").append(it).append('"') }
            append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendField(field: ScalarField) {
        append("  ").append(field.name).append(": ").append(typeName(field.type))
        append(' ').append(field.cardinality.name.lowercase())
        append(mapping(field.name, field.dbName))
        val flags = buildList {
            if (field.isId) add("id")
            if (field.isUnique) add("unique")
            if (field.isUpdatedAt) add("updatedAt")
            if (field.isIgnored) add("ignored")
            field.default?.let { add("default=${describe(it)}") }
            field.nativeType?.let { add("db.${it.name}(${it.arguments.joinToString(", ")})") }
        }
        if (flags.isNotEmpty()) append(" [").append(flags.joinToString(", ")).append(']')
        append('\n')
    }

    private fun StringBuilder.appendRelationField(field: RelationField) {
        append("  ~ ").append(field.name).append(": ").append(field.targetModel)
        append(' ').append(field.cardinality.name.lowercase())
        append(" (relation ").append(field.relationName).append(')')
        if (field.isIgnored) append(" ignored")
        append('\n')
    }

    private fun StringBuilder.appendRelation(relation: Relation) {
        append("relation ").append(relation.name).append(' ').append(kindName(relation.kind)).append('\n')
        append("  from ").append(relation.from.model).append('.').append(relation.from.field)
        append(" (").append(relation.from.cardinality.name.lowercase()).append(')')
        if (relation.foreignKeyFields.isNotEmpty()) {
            append(" fk (").append(relation.foreignKeyFields.joinToString(", ")).append(')')
        }
        append('\n')
        append("  to ").append(relation.to.model).append('.').append(relation.to.field)
        append(" (").append(relation.to.cardinality.name.lowercase()).append(')')
        if (relation.referencedFields.isNotEmpty()) {
            append(" references (").append(relation.referencedFields.joinToString(", ")).append(')')
        }
        append('\n')
        relation.onDelete?.let { append("  onDelete ").append(it.id).append('\n') }
        relation.onUpdate?.let { append("  onUpdate ").append(it.id).append('\n') }
        relation.joinTable?.let { append("  join table \"").append(it).append("\"\n") }
        append('\n')
    }

    private fun kindName(kind: RelationKind): String = when (kind) {
        RelationKind.ONE_TO_ONE -> "one-to-one"
        RelationKind.ONE_TO_MANY -> "one-to-many"
        RelationKind.MANY_TO_MANY -> "many-to-many"
    }

    private fun typeName(type: FieldType): String = when (type) {
        is FieldType.Scalar -> type.type.schemaName
        is FieldType.EnumRef -> type.enumName
    }

    /** Renders `-> "column"` only when the database name differs from the schema name. */
    private fun mapping(name: String, dbName: String): String = if (name == dbName) "" else " -> \"$dbName\""

    private fun describe(default: DefaultValue): String = when (default) {
        is DefaultValue.StringValue -> "\"${default.value}\""
        is DefaultValue.NumberValue -> default.value
        is DefaultValue.BooleanValue -> default.value.toString()
        is DefaultValue.EnumValueRef -> "${default.enumName}.${default.valueName}"
        DefaultValue.EmptyList -> "[]"
        DefaultValue.AutoIncrement -> "autoincrement()"
        DefaultValue.Now -> "now()"
        DefaultValue.Uuid -> "uuid()"
        DefaultValue.Cuid -> "cuid()"
        is DefaultValue.DatabaseGenerated -> "dbgenerated(${default.expression?.let { "\"$it\"" }.orEmpty()})"
    }
}
