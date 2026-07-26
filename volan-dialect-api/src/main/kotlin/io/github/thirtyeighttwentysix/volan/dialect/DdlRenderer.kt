package io.github.thirtyeighttwentysix.volan.dialect

/**
 * Renders Volan's DDL model as standard SQL, leaving each database to override only what it does
 * differently.
 *
 * Unlike a query, a statement here binds nothing: a default belongs to the table rather than to any
 * one execution of a statement, so there is nowhere to bind it to. Everything that ends up in the text
 * comes from the schema file, which is the only reason writing it into the text is safe.
 */
public abstract class DdlRenderer(capabilities: DialectCapabilities) : SqlRenderer(capabilities) {
    /**
     * Turns one described change into the statements this database needs for it.
     *
     * It is a list because some databases have no single way to say some of these; where one statement
     * is enough, the list holds one.
     */
    public open fun render(ddl: DdlStatement): List<SqlStatement> = when (ddl) {
        is DdlStatement.CreateTable -> one(createTable(ddl))
        is DdlStatement.DropTable -> one("DROP TABLE ${quote(ddl.table)}")
        is DdlStatement.AddColumn -> one("ALTER TABLE ${quote(ddl.table)} ADD COLUMN ${column(ddl.column)}")
        is DdlStatement.DropColumn -> one("ALTER TABLE ${quote(ddl.table)} DROP COLUMN ${quote(ddl.column)}")
        is DdlStatement.AlterColumn -> alterColumn(ddl)
        is DdlStatement.AddPrimaryKey -> one(
            "ALTER TABLE ${quote(ddl.table)} ADD ${named(ddl.key.name)}PRIMARY KEY ${columns(ddl.key.columns)}",
        )
        is DdlStatement.AddUnique -> one(
            "ALTER TABLE ${quote(ddl.table)} ADD CONSTRAINT ${quote(ddl.constraint.name)} " +
                "UNIQUE ${columns(ddl.constraint.columns)}",
        )
        is DdlStatement.AddForeignKey -> one("ALTER TABLE ${quote(ddl.table)} ADD ${foreignKey(ddl.key)}")
        is DdlStatement.DropConstraint -> one("ALTER TABLE ${quote(ddl.table)} DROP CONSTRAINT ${quote(ddl.name)}")
        is DdlStatement.CreateIndex -> one(createIndex(ddl.table, ddl.index))
        is DdlStatement.DropIndex -> one("DROP INDEX ${quote(ddl.name)}")
        is DdlStatement.CreateEnum -> createEnum(ddl)
        is DdlStatement.DropEnum -> dropEnum(ddl)
        is DdlStatement.AddEnumValue -> addEnumValue(ddl)
    }

    /** The type name this database gives one of Volan's own types. */
    protected abstract fun typeName(type: SqlType): String

    /** Whether this database has enum types of its own, rather than emulating them with a check. */
    protected open val hasEnumTypes: Boolean get() = false

    /** How this database spells an auto-incrementing column of the given type. */
    protected open fun autoIncrementType(type: ColumnType): String? = null

    /** The expression this database uses for the moment a row is written. */
    protected open val currentTimestamp: String get() = "CURRENT_TIMESTAMP"

    /** The expression this database uses to generate a UUID, when it has one. */
    protected open val generatedUuid: String? get() = null

    protected open fun createTable(create: DdlStatement.CreateTable): String {
        val parts = create.columns.map { column(it) } +
            listOfNotNull(create.primaryKey?.let { "${named(it.name)}PRIMARY KEY ${columns(it.columns)}" })
        return "CREATE TABLE ${quote(create.table)} (\n  " + parts.joinToString(",\n  ") + "\n)"
    }

    protected open fun column(column: ColumnDefinition): String {
        val declared = if (column.autoIncrement) autoIncrementType(column.type) ?: render(column.type) else render(column.type)
        val builder = StringBuilder(quote(column.name)).append(' ').append(declared)
        if (!column.nullable) builder.append(" NOT NULL")
        column.default?.let { builder.append(" DEFAULT ").append(render(it)) }
        return builder.toString()
    }

    protected open fun render(type: ColumnType): String = when (type) {
        is ColumnType.Scalar -> typeName(type.type)
        is ColumnType.Native -> if (type.arguments.isEmpty()) type.name else "${type.name}(${type.arguments.joinToString(", ")})"
        is ColumnType.Enumeration -> if (hasEnumTypes) quote(type.name) else typeName(SqlType.TEXT)
        is ColumnType.Array -> "${render(type.element)}[]"
    }

    protected open fun render(default: ColumnDefault): String = when (default) {
        is ColumnDefault.Text -> literal(default.value)
        is ColumnDefault.Number -> default.value
        is ColumnDefault.Boolean -> if (default.value) "TRUE" else "FALSE"
        is ColumnDefault.EmptyArray -> "'{}'"
        is ColumnDefault.CurrentTimestamp -> currentTimestamp
        is ColumnDefault.GeneratedUuid -> generatedUuid ?: throw VolanDialectException(
            "$id cannot generate a UUID of its own, so `@default(uuid())` has nothing to become.\n" +
                "  Generate it in the application, or use `@default(dbgenerated(\"…\"))` with an expression it does have.",
        )
        is ColumnDefault.Expression -> default.sql
    }

    protected open fun alterColumn(alter: DdlStatement.AlterColumn): List<SqlStatement> {
        val prefix = "ALTER TABLE ${quote(alter.table)} ALTER COLUMN ${quote(alter.column)}"
        return when (val change = alter.change) {
            is ColumnChange.Type -> one("$prefix TYPE ${render(change.type)}" + (change.using?.let { " USING $it" }.orEmpty()))
            is ColumnChange.Nullability -> one(if (change.nullable) "$prefix DROP NOT NULL" else "$prefix SET NOT NULL")
            is ColumnChange.Default ->
                one(if (change.default == null) "$prefix DROP DEFAULT" else "$prefix SET DEFAULT ${render(change.default)}")
        }
    }

    protected open fun createIndex(table: String, index: IndexDefinition): String {
        val unique = if (index.unique) "UNIQUE " else ""
        return "CREATE ${unique}INDEX ${quote(index.name)} ON ${quote(table)} ${columns(index.columns)}"
    }

    protected open fun foreignKey(key: ForeignKeyDefinition): String =
        "CONSTRAINT ${quote(key.name)} FOREIGN KEY ${columns(key.columns)} " +
            "REFERENCES ${quote(key.targetTable)} ${columns(key.targetColumns)} " +
            "ON DELETE ${key.onDelete.sql} ON UPDATE ${key.onUpdate.sql}"

    /**
     * Declares an enum type.
     *
     * A database without enum types stores the values as text; the schema still knows what they are,
     * and the generated client still refuses anything else, so nothing is lost but the constraint.
     */
    protected open fun createEnum(create: DdlStatement.CreateEnum): List<SqlStatement> {
        if (!hasEnumTypes) return emptyList()
        return one("CREATE TYPE ${quote(create.name)} AS ENUM (${create.values.joinToString(", ") { literal(it) }})")
    }

    protected open fun dropEnum(drop: DdlStatement.DropEnum): List<SqlStatement> =
        if (hasEnumTypes) one("DROP TYPE ${quote(drop.name)}") else emptyList()

    protected open fun addEnumValue(add: DdlStatement.AddEnumValue): List<SqlStatement> {
        if (!hasEnumTypes) return emptyList()
        val position = add.after?.let { " AFTER ${literal(it)}" }.orEmpty()
        return one("ALTER TYPE ${quote(add.name)} ADD VALUE ${literal(add.value)}$position")
    }

    /** A text literal for DDL, with quotes doubled. Only schema-authored text ever reaches this. */
    protected fun literal(value: String): String = "'" + value.replace("'", "''") + "'"

    protected fun columns(names: List<String>): String = names.joinToString(", ", "(", ")") { quote(it) }

    private fun named(name: String?): String = name?.let { "CONSTRAINT ${quote(it)} " }.orEmpty()

    private fun one(sql: String): List<SqlStatement> = listOf(SqlStatement(sql, emptyList()))
}
