package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.PrimaryKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition
import io.github.thirtyeighttwentysix.volan.ir.Cardinality
import io.github.thirtyeighttwentysix.volan.ir.DefaultValue
import io.github.thirtyeighttwentysix.volan.ir.FieldType
import io.github.thirtyeighttwentysix.volan.ir.IndexKind
import io.github.thirtyeighttwentysix.volan.ir.Model
import io.github.thirtyeighttwentysix.volan.ir.ReferentialAction
import io.github.thirtyeighttwentysix.volan.ir.Relation
import io.github.thirtyeighttwentysix.volan.ir.RelationKind
import io.github.thirtyeighttwentysix.volan.ir.ScalarField
import io.github.thirtyeighttwentysix.volan.ir.ScalarType
import io.github.thirtyeighttwentysix.volan.ir.Schema

/**
 * Reads a schema as the database it describes.
 *
 * This is the half of a migration that needs no database at all: it says what the tables, keys and
 * constraints of a `schema.volan` file are, so that comparing them with what a database actually holds
 * is a comparison of two values.
 *
 * Constraints and indexes are named by rule rather than left to the database, because a diff has to
 * recognise the same constraint twice. `@map`ped names are honoured; everything else follows
 * `table_column_suffix`, which is what most tools already produce and what most databases already have.
 */
public object SchemaMapper {
    /** The database [schema] describes. */
    @JvmStatic
    public fun map(schema: Schema): DatabaseSchema = map(schema, NativeTypeTable.forProvider(schema.datasource.provider))

    /** The database [schema] describes, with [types] deciding what its `@db.…` types mean. */
    @JvmStatic
    public fun map(schema: Schema, types: NativeTypeTable): DatabaseSchema {
        val enums = schema.enums.map { EnumDefinition(it.dbName, it.values.map { value -> value.dbName }) }
        val tables = schema.models.map { table(schema, types, it) } + joinTables(schema, types)
        return DatabaseSchema(enums.sortedBy { it.name }, tables.sortedBy { it.name })
    }

    private fun table(schema: Schema, types: NativeTypeTable, model: Model): TableDefinition {
        val columns = model.fields.map { column(schema, types, model, it) }
        return TableDefinition(
            name = model.dbName,
            columns = columns,
            primaryKey = primaryKey(model),
            uniques = uniques(model).sortedBy { it.name },
            indexes = indexes(model).sortedBy { it.name },
            foreignKeys = foreignKeys(schema, model).sortedBy { it.name },
        )
    }

    private fun column(schema: Schema, types: NativeTypeTable, model: Model, field: ScalarField): ColumnDefinition = ColumnDefinition(
        name = field.dbName,
        type = columnType(schema, types, field),
        nullable = field.cardinality == Cardinality.OPTIONAL,
        default = columnDefault(schema, model, field),
        autoIncrement = field.default == DefaultValue.AutoIncrement,
    )

    /**
     * What a column holds, in the names the database knows.
     *
     * An enum column names the type by its `@@map`ped name, because that is the type the migration
     * creates — the schema-language name never reaches the database at all.
     */
    private fun columnType(schema: Schema, types: NativeTypeTable, field: ScalarField): ColumnType {
        val element = when (val type = field.type) {
            is FieldType.Scalar -> field.nativeType?.let { types.canonical(type.type, it) } ?: ColumnType.Scalar(sqlType(type.type))
            is FieldType.EnumRef -> ColumnType.Enumeration(enumTable(schema, type.enumName))
        }
        return if (field.cardinality == Cardinality.LIST) ColumnType.Array(element) else element
    }

    private fun enumTable(schema: Schema, name: String): String = schema.enumType(name)?.dbName ?: throw VolanMigrationException(
        "a field is declared as `$name`, which this schema does not declare as an enum.",
    )

    /** What a Volan scalar becomes in SQL's vocabulary; what that is called is the dialect's business. */
    private fun sqlType(type: ScalarType): SqlType = when (type) {
        ScalarType.STRING -> SqlType.TEXT
        ScalarType.INT -> SqlType.INTEGER
        ScalarType.LONG -> SqlType.BIGINT
        ScalarType.FLOAT -> SqlType.REAL
        ScalarType.DOUBLE -> SqlType.DOUBLE
        ScalarType.DECIMAL -> SqlType.NUMERIC
        ScalarType.BOOLEAN -> SqlType.BOOLEAN
        ScalarType.DATE_TIME -> SqlType.TIMESTAMP
        ScalarType.DATE -> SqlType.DATE
        ScalarType.TIME -> SqlType.TIME
        ScalarType.JSON -> SqlType.JSON
        ScalarType.BYTES -> SqlType.BLOB
        ScalarType.UUID -> SqlType.UUID
    }

    /**
     * The column default, or `null` when the value does not come from the database.
     *
     * `autoincrement()` is a property of the column type rather than a default, and `@updatedAt` is
     * written by Volan on every update rather than left to the database, so neither appears here.
     */
    private fun columnDefault(schema: Schema, model: Model, field: ScalarField): ColumnDefault? = when (val default = field.default) {
        null, DefaultValue.AutoIncrement -> null
        is DefaultValue.StringValue -> ColumnDefault.Text(default.value)
        is DefaultValue.NumberValue -> ColumnDefault.Number(default.value)
        is DefaultValue.BooleanValue -> ColumnDefault.Boolean(default.value)
        is DefaultValue.EnumValueRef -> ColumnDefault.Text(enumValue(schema, default))
        DefaultValue.EmptyList -> ColumnDefault.EmptyArray
        DefaultValue.Now -> ColumnDefault.CurrentTimestamp
        DefaultValue.Uuid -> ColumnDefault.GeneratedUuid
        DefaultValue.Cuid -> throw VolanMigrationException(
            "`${model.name}.${field.name}` defaults to `cuid()`, which no database can produce and which " +
                "Volan does not yet generate on its way to one.\n" +
                "  Use `@default(uuid())`, or `@default(dbgenerated(\"…\"))` with an expression the database has.",
        )
        is DefaultValue.DatabaseGenerated -> default.expression?.let { ColumnDefault.Expression(it) }
            ?: throw VolanMigrationException(
                "`${model.name}.${field.name}` defaults to `dbgenerated()` with no expression, so there is " +
                    "nothing to write into the column definition.\n" +
                    "  Give it the expression the database should evaluate, as in `dbgenerated(\"now()\")`.",
            )
    }

    private fun enumValue(schema: Schema, reference: DefaultValue.EnumValueRef): String {
        val type = schema.enumType(reference.enumName) ?: throw VolanMigrationException(
            "a default refers to the enum `${reference.enumName}`, which this schema does not declare.",
        )
        val value = type.values.firstOrNull { it.name == reference.valueName } ?: throw VolanMigrationException(
            "a default refers to `${reference.enumName}.${reference.valueName}`, which that enum does not have.",
        )
        return value.dbName
    }

    private fun primaryKey(model: Model): PrimaryKeyDefinition? {
        val key = model.primaryKey ?: return null
        return PrimaryKeyDefinition(key.dbName ?: "${model.dbName}_pkey", key.fields.map { columnOf(model, it) })
    }

    private fun uniques(model: Model): List<UniqueDefinition> {
        val single = model.fields.filter { it.isUnique && !it.isId }.map { field ->
            UniqueDefinition(constraintName(model.dbName, listOf(field.dbName), "key"), listOf(field.dbName))
        }
        val composite = model.uniques.map { unique ->
            val columns = unique.fields.map { columnOf(model, it) }
            UniqueDefinition(unique.dbName ?: constraintName(model.dbName, columns, "key"), columns)
        }
        return single + composite
    }

    private fun indexes(model: Model): List<IndexDefinition> = model.indexes.map { index ->
        val columns = index.fields.map { columnOf(model, it) }
        IndexDefinition(
            name = index.dbName ?: constraintName(model.dbName, columns, "idx"),
            columns = columns,
            fullText = index.kind == IndexKind.FULLTEXT,
        )
    }

    private fun foreignKeys(schema: Schema, model: Model): List<ForeignKeyDefinition> = schema.relations
        .filter { it.joinTable == null && it.from.model == model.name }
        .map { relation -> foreignKey(schema, model, relation) }

    private fun foreignKey(schema: Schema, model: Model, relation: Relation): ForeignKeyDefinition {
        val target = schema.model(relation.to.model) ?: throw VolanMigrationException(
            "the relation `${relation.name}` points at `${relation.to.model}`, which this schema does not declare.",
        )
        val columns = relation.foreignKeyFields.map { columnOf(model, it) }
        return ForeignKeyDefinition(
            name = constraintName(model.dbName, columns, "fkey"),
            columns = columns,
            targetTable = target.dbName,
            targetColumns = relation.referencedFields.map { columnOf(target, it) },
            onDelete = action(relation.onDelete) ?: defaultOnDelete(relation, model),
            onUpdate = action(relation.onUpdate) ?: ForeignKeyAction.CASCADE,
        )
    }

    /**
     * What happens to a row whose parent is deleted, when the schema does not say.
     *
     * A required relation cannot be left dangling, so refusing the delete is the only answer that keeps
     * the schema true; an optional one can simply forget its parent.
     */
    private fun defaultOnDelete(relation: Relation, model: Model): ForeignKeyAction {
        val required = relation.foreignKeyFields.all { model.field(it)?.cardinality != Cardinality.OPTIONAL }
        return if (required) ForeignKeyAction.RESTRICT else ForeignKeyAction.SET_NULL
    }

    private fun action(action: ReferentialAction?): ForeignKeyAction? = when (action) {
        null -> null
        ReferentialAction.CASCADE -> ForeignKeyAction.CASCADE
        ReferentialAction.RESTRICT -> ForeignKeyAction.RESTRICT
        ReferentialAction.NO_ACTION -> ForeignKeyAction.NO_ACTION
        ReferentialAction.SET_NULL -> ForeignKeyAction.SET_NULL
        ReferentialAction.SET_DEFAULT -> ForeignKeyAction.SET_DEFAULT
    }

    /**
     * The tables that hold a many-to-many relation.
     *
     * Neither model owns a key, so the relation lives in a table of its own with one column per side.
     * `A` is the end that comes first, which is how both sides agree on which column is theirs without
     * either being told.
     */
    private fun joinTables(schema: Schema, types: NativeTypeTable): List<TableDefinition> = schema.relations
        .filter { it.kind == RelationKind.MANY_TO_MANY }
        .map { relation -> joinTable(schema, types, relation) }

    private fun joinTable(schema: Schema, types: NativeTypeTable, relation: Relation): TableDefinition {
        val table = requireNotNull(relation.joinTable) { "a many-to-many relation always has a join table" }
        val first = requireModel(schema, relation.from.model)
        val second = requireModel(schema, relation.to.model)
        return TableDefinition(
            name = table,
            columns = listOf(
                ColumnDefinition(JOIN_FIRST, keyType(schema, types, first), nullable = false),
                ColumnDefinition(JOIN_SECOND, keyType(schema, types, second), nullable = false),
            ),
            uniques = listOf(UniqueDefinition(constraintName(table, listOf(JOIN_FIRST, JOIN_SECOND), "key"), JOIN_COLUMNS)),
            indexes = listOf(IndexDefinition(constraintName(table, listOf(JOIN_SECOND), "idx"), listOf(JOIN_SECOND))),
            foreignKeys = listOf(
                joinForeignKey(table, JOIN_FIRST, first),
                joinForeignKey(table, JOIN_SECOND, second),
            ),
        )
    }

    private fun joinForeignKey(table: String, column: String, target: Model): ForeignKeyDefinition = ForeignKeyDefinition(
        name = constraintName(table, listOf(column), "fkey"),
        columns = listOf(column),
        targetTable = target.dbName,
        targetColumns = listOf(singleKeyColumn(target)),
        onDelete = ForeignKeyAction.CASCADE,
        onUpdate = ForeignKeyAction.CASCADE,
    )

    /**
     * The type of a join column, taken from the key it points at.
     *
     * A join table has no schema of its own to read a type from, and a column that does not match the
     * key it references is a foreign key the database will refuse.
     */
    private fun keyType(schema: Schema, types: NativeTypeTable, model: Model): ColumnType {
        val field = model.primaryKeyFields.singleOrNull() ?: throw VolanMigrationException(
            "`${model.name}` takes part in a many-to-many relation, which joins on a single-column primary " +
                "key, but its primary key has ${model.primaryKeyFields.size} columns.\n" +
                "  Give it a single-column key, or model the join as a table of its own.",
        )
        return columnType(schema, types, field).let { if (it is ColumnType.Array) it.element else it }
    }

    private fun singleKeyColumn(model: Model): String = model.primaryKeyFields.single().dbName

    private fun requireModel(schema: Schema, name: String): Model = schema.model(name) ?: throw VolanMigrationException(
        "a relation points at `$name`, which this schema does not declare.",
    )

    private fun columnOf(model: Model, field: String): String = model.field(field)?.dbName ?: throw VolanMigrationException(
        "`${model.name}` has no field called `$field` to build a key or an index from.",
    )

    private fun constraintName(table: String, columns: List<String>, suffix: String): String =
        (listOf(table) + columns + suffix).joinToString("_")

    private const val JOIN_FIRST = "A"
    private const val JOIN_SECOND = "B"
    private val JOIN_COLUMNS = listOf(JOIN_FIRST, JOIN_SECOND)
}
