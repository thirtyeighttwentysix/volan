package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.VolanException
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.ForeignKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.IndexDefinition
import io.github.thirtyeighttwentysix.volan.dialect.PrimaryKeyDefinition
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition

/**
 * What a database holds, or what a schema says it should hold.
 *
 * The same shape describes both sides of a migration: the state read out of a live database and the
 * state a `schema.volan` file asks for. A diff is then a comparison of two values rather than a
 * conversation with a database, which is what makes it testable without one.
 *
 * @property enums the enum types, ordered by name.
 * @property tables the tables, ordered by name.
 */
public data class DatabaseSchema(
    public val enums: List<EnumDefinition> = emptyList(),
    public val tables: List<TableDefinition> = emptyList(),
) {
    private val tablesByName: Map<String, TableDefinition> = tables.associateBy { it.name }
    private val enumsByName: Map<String, EnumDefinition> = enums.associateBy { it.name }

    /** Returns the table called [name], or `null` when there is none. */
    public fun table(name: String): TableDefinition? = tablesByName[name]

    /** Returns the enum type called [name], or `null` when there is none. */
    public fun enumType(name: String): EnumDefinition? = enumsByName[name]
}

/**
 * An enum type in the database.
 *
 * @property name the type name.
 * @property values its values, in declaration order. Order matters: some databases compare enum values
 *   by it, and none of them let a value be inserted in the middle after the fact.
 */
public data class EnumDefinition(public val name: String, public val values: List<String>)

/**
 * One table.
 *
 * @property name the table name.
 * @property columns its columns, in order.
 * @property primaryKey its primary key, when it has one.
 * @property uniques its unique constraints, ordered by name.
 * @property indexes its indexes, ordered by name.
 * @property foreignKeys its foreign keys, ordered by name.
 */
public data class TableDefinition(
    public val name: String,
    public val columns: List<ColumnDefinition>,
    public val primaryKey: PrimaryKeyDefinition? = null,
    public val uniques: List<UniqueDefinition> = emptyList(),
    public val indexes: List<IndexDefinition> = emptyList(),
    public val foreignKeys: List<ForeignKeyDefinition> = emptyList(),
) {
    private val columnsByName: Map<String, ColumnDefinition> = columns.associateBy { it.name }

    /** Returns the column called [name], or `null` when there is none. */
    public fun column(name: String): ColumnDefinition? = columnsByName[name]
}

/**
 * Thrown when a schema cannot be turned into a database, or a database into a migration.
 *
 * The message names what stood in the way and what to write instead: every one of these is answered
 * by an edit to the schema file or to the migration, never by a retry.
 */
public class VolanMigrationException(message: String, cause: Throwable? = null) : VolanException(message, cause)
