package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefault
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ColumnType
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyAction
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.SqlType
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader
import io.github.thirtyeighttwentysix.volan.schema.SchemaFormatter

/** Writes introspected PostgreSQL tables as a validated, canonical schema.volan document. */
public object SchemaWriter {
    /**
     * Serializes [database], retaining database names through mapping attributes.
     * Keyless tables, including implicit join tables, are explicit models marked `@@ignore`.
     * Client-only information (comments, generators, updatedAt) cannot be recovered from a database.
     */
    @JvmStatic
    public fun write(database: DatabaseSchema): String = Writer(database).write()
}

private class Writer(private val database: DatabaseSchema) {
    private val names = Names()
    private val models = database.tables.associate { it.name to names.add(it.name) }
    private val enums = database.enums.associate { it.name to names.add(it.name) }
    private val fields = database.tables.associate { table ->
        val scope = Names()
        table.name to (scope to table.columns.associate { it.name to scope.add(it.name) })
    }
    private val values = database.enums.associate { type ->
        val scope = Names()
        type.name to type.values.associateWith { scope.add(it) }
    }
    private val relations = database.tables.associate { it.name to ArrayList<String>() }

    fun write(): String {
        database.tables.forEach { table -> table.foreignKeys.forEach { relation(table, it) } }
        val text = buildString {
            append("datasource db {\n  provider = \"postgresql\"\n  url = env(\"DATABASE_URL\")\n}\n\n")
            database.enums.forEach { type ->
                append("enum ${enums.getValue(type.name)} {\n")
                type.values.forEach { append("  ${values.getValue(type.name).getValue(it)} @map(${quoted(it)})\n") }
                append("  @@map(${quoted(type.name)})\n}\n\n")
            }
            database.tables.forEach { append(model(it)) }
        }
        val schema = SchemaLoader.load("introspected.volan", text).schemaOrThrow()
        val reconstructed = SchemaMapper.map(schema)
        if (reconstructed != database) {
            throw VolanMigrationException(
                "This database contains definitions schema.volan cannot preserve exactly; no schema was exported.",
            )
        }
        return SchemaFormatter.format("introspected.volan", text)
    }

    private fun model(table: TableDefinition): String = buildString {
        append("model ${models.getValue(table.name)} {\n")
        table.columns.forEach { append("  ${column(table, it)}\n") }
        relations.getValue(table.name).forEach { append("  $it\n") }
        table.primaryKey?.let { append("  @@id(${columns(table.name, it.columns)}, map: ${quoted(it.name ?: "${table.name}_pkey")})\n") }
        table.uniques.forEach { append("  @@unique(${columns(table.name, it.columns)}, map: ${quoted(it.name)})\n") }
        table.indexes.forEach {
            if (it.unique) throw VolanMigrationException("The unique index `${it.name}` is not a unique constraint; export is unsupported.")
            val kind = if (it.fullText) "fulltext" else "index"
            append("  @@$kind(${columns(table.name, it.columns)}, map: ${quoted(it.name)})\n")
        }
        if (table.primaryKey == null) append("  @@ignore\n")
        append("  @@map(${quoted(table.name)})\n}\n\n")
    }

    private fun column(table: TableDefinition, column: ColumnDefinition): String {
        val element = (column.type as? ColumnType.Array)?.element ?: column.type
        val cardinality = when {
            column.type is ColumnType.Array -> "[]"
            column.nullable -> "?"
            else -> ""
        }
        val native = (element as? ColumnType.Native)?.let {
            " @db.${it.name}" + if (it.arguments.isEmpty()) "" else it.arguments.joinToString(", ", "(", ")")
        }.orEmpty()
        val default = if (column.autoIncrement) {
            " @default(autoincrement())"
        } else {
            column.default?.let {
                " @default(${default(it, element)})"
            }.orEmpty()
        }
        return "${field(table.name, column.name)} ${type(element)}$cardinality$native$default @map(${quoted(column.name)})"
    }

    private fun type(type: ColumnType): String = when (type) {
        is ColumnType.Scalar -> when (type.type) {
            SqlType.TEXT -> "String"
            SqlType.INTEGER -> "Int"
            SqlType.BIGINT -> "Long"
            SqlType.REAL -> "Float"
            SqlType.DOUBLE -> "Double"
            SqlType.NUMERIC -> "Decimal"
            SqlType.BOOLEAN -> "Boolean"
            SqlType.TIMESTAMP -> "DateTime"
            SqlType.DATE -> "Date"
            SqlType.TIME -> "Time"
            SqlType.JSON -> "Json"
            SqlType.BLOB -> "Bytes"
            SqlType.UUID -> "Uuid"
        }
        is ColumnType.Enumeration -> enums.getValue(type.name)
        is ColumnType.Native -> nativeScalar(type.name)
        is ColumnType.Array -> throw VolanMigrationException("Nested array types cannot be exported to schema.volan.")
    }

    private fun nativeScalar(name: String): String = when (name) {
        "SmallInt", "Integer", "Oid" -> "Int"
        "BigInt" -> "Long"
        "Real" -> "Float"
        "DoublePrecision" -> "Double"
        "Decimal", "Money" -> "Decimal"
        "Boolean" -> "Boolean"
        "Timestamp", "Timestamptz" -> "DateTime"
        "Time", "Timetz" -> "Time"
        "Date" -> "Date"
        "Json", "JsonB" -> "Json"
        "ByteA" -> "Bytes"
        "Uuid" -> "Uuid"
        else -> "String"
    }

    private fun default(value: ColumnDefault, type: ColumnType): String = when (value) {
        is ColumnDefault.Text -> if (type is ColumnType.Enumeration) {
            values.getValue(type.name).getValue(value.value)
        } else {
            quoted(value.value)
        }
        is ColumnDefault.Number -> value.value
        is ColumnDefault.Boolean -> value.value.toString()
        ColumnDefault.EmptyArray -> "[]"
        ColumnDefault.CurrentTimestamp -> "now()"
        ColumnDefault.GeneratedUuid -> "uuid()"
        is ColumnDefault.Expression -> "dbgenerated(${quoted(value.sql)})"
    }

    private fun relation(table: TableDefinition, key: ForeignKeyDefinition) {
        val conventional = (listOf(table.name) + key.columns + "fkey").joinToString("_")
        if (key.name != conventional) {
            throw VolanMigrationException("Foreign key `${key.name}` needs a custom constraint name, which @relation cannot yet express.")
        }
        val target = database.table(key.targetTable)
            ?: throw VolanMigrationException("Foreign key `${key.name}` references a table outside the current schema.")
        val name = "${table.name}_${key.name}"
        val forward = fields.getValue(table.name).first.add("${key.targetTable}_relation")
        val backward = fields.getValue(target.name).first.add("${table.name}_rows")
        val optional = if (key.columns.any { table.column(it)?.nullable == true }) "?" else ""
        val unique = (table.uniques.map { it.columns } + listOfNotNull(table.primaryKey?.columns)).any {
            it.toSet() == key.columns.toSet()
        }
        val backType = if (unique) "?" else "[]"
        relations.getValue(table.name).add(
            "$forward ${models.getValue(target.name)}$optional @relation(${quoted(name)}, " +
                "fields: ${columns(table.name, key.columns)}, references: ${columns(target.name, key.targetColumns)}, " +
                "onDelete: ${action(key.onDelete)}, onUpdate: ${action(key.onUpdate)})",
        )
        relations.getValue(target.name).add("$backward ${models.getValue(table.name)}$backType @relation(${quoted(name)})")
    }

    private fun action(value: ForeignKeyAction): String = when (value) {
        ForeignKeyAction.CASCADE -> "Cascade"
        ForeignKeyAction.RESTRICT -> "Restrict"
        ForeignKeyAction.NO_ACTION -> "NoAction"
        ForeignKeyAction.SET_NULL -> "SetNull"
        ForeignKeyAction.SET_DEFAULT -> "SetDefault"
    }

    private fun field(table: String, column: String): String = fields.getValue(table).second.getValue(column)

    private fun columns(table: String, columns: List<String>): String = columns.joinToString(", ", "[", "]") { field(table, it) }
}

private class Names {
    private val used = HashSet<String>()

    fun add(original: String): String {
        val cleaned = original.replace(Regex("[^A-Za-z0-9_]"), "_")
        val base = if (cleaned.firstOrNull()?.isLetter() == true) cleaned else "v_$cleaned"
        var candidate = base
        var suffix = 0
        while (candidate in RESERVED || !used.add(candidate)) candidate = "${base}_${++suffix}"
        return candidate
    }

    private companion object {
        private val RESERVED = setOf(
            "model", "enum", "datasource", "generator", "true", "false", "String", "Int", "Long", "Float",
            "Double", "Decimal", "Boolean", "DateTime", "Date", "Time", "Json", "Bytes", "Uuid",
        )
    }
}

internal fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
