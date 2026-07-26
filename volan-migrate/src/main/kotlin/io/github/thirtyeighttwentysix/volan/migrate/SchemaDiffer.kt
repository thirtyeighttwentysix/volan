package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.ColumnDefinition
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement
import io.github.thirtyeighttwentysix.volan.dialect.UniqueDefinition

/**
 * Works out what has to happen for one database to become another.
 *
 * The order is the whole point. A foreign key cannot outlive the column it points at, an index cannot
 * be created before the column it covers, and a table cannot be dropped while something references it —
 * so the plan is built in phases, each of which leaves the database in a state the next one can work
 * against.
 *
 * Nothing here decides whether a change is a good idea. Steps that lose data carry a warning and the
 * caller decides; a differ that quietly refused would only be a differ that lies about the difference.
 */
public object SchemaDiffer {
    /** What takes [from] to [to]. */
    @JvmStatic
    public fun diff(from: DatabaseSchema, to: DatabaseSchema): MigrationPlan {
        val steps = ArrayList<MigrationStep>()
        steps += dropForeignKeys(from, to)
        steps += dropIndexesAndConstraints(from, to)
        steps += dropTables(from, to)
        steps += enumChanges(from, to)
        steps += createTables(from, to)
        steps += columnChanges(from, to)
        steps += addConstraintsAndIndexes(from, to)
        steps += addForeignKeys(from, to)
        steps += dropEnums(from, to)
        return MigrationPlan(steps)
    }

    private fun dropForeignKeys(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = from.tables.flatMap { table ->
        val wanted = to.table(table.name)
        table.foreignKeys
            .filter { key -> wanted == null || wanted.foreignKeys.none { it == key } }
            .filter { wanted != null }
            .map { MigrationStep(DdlStatement.DropConstraint(table.name, it.name)) }
    }

    private fun dropIndexesAndConstraints(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = from.tables.flatMap { table ->
        val wanted = to.table(table.name) ?: return@flatMap emptyList()
        val indexes = table.indexes
            .filter { index -> wanted.indexes.none { it == index } }
            .map { MigrationStep(DdlStatement.DropIndex(table.name, it.name)) }
        val uniques = table.uniques
            .filter { unique -> wanted.uniques.none { it == unique } }
            .map { MigrationStep(DdlStatement.DropConstraint(table.name, it.name)) }
        val key = table.primaryKey
            ?.takeIf { it != wanted.primaryKey }
            ?.name
            ?.let { listOf(MigrationStep(DdlStatement.DropConstraint(table.name, it))) }
            .orEmpty()
        indexes + uniques + key
    }

    private fun dropTables(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = from.tables
        .filter { to.table(it.name) == null }
        .map {
            MigrationStep(
                DdlStatement.DropTable(it.name),
                "the table `${it.name}` is dropped, and every row in it with it",
            )
        }

    private fun enumChanges(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = to.enums.flatMap { wanted ->
        val existing = from.enumType(wanted.name)
            ?: return@flatMap listOf(MigrationStep(DdlStatement.CreateEnum(wanted.name, wanted.values)))
        addedValues(existing, wanted)
    }

    /**
     * Adds the values an enum has gained.
     *
     * Only additions are expressed. Removing a value would orphan every row holding it, and reordering
     * is not something a database will do at all, so both are reported as something to write by hand
     * rather than guessed at.
     */
    private fun addedValues(existing: EnumDefinition, wanted: EnumDefinition): List<MigrationStep> {
        val removed = existing.values - wanted.values.toSet()
        if (removed.isNotEmpty()) {
            throw VolanMigrationException(
                "the enum `${wanted.name}` no longer has ${removed.joinToString(", ") { "`$it`" }}, and rows may " +
                    "still hold ${if (removed.size == 1) "that value" else "those values"}.\n" +
                    "  Removing an enum value is a data change as much as a schema change: write the migration " +
                    "that moves those rows first, then remove the value.",
            )
        }
        val kept = wanted.values.filter { existing.values.contains(it) }
        if (kept != existing.values) {
            throw VolanMigrationException(
                "the values of the enum `${wanted.name}` are in a different order than the database has them, " +
                    "and no database can reorder them.\n" +
                    "  Keep the existing values in their existing order and add new ones after them.",
            )
        }
        var previous = existing.values.lastOrNull()
        return wanted.values.filterNot { existing.values.contains(it) }.map { value ->
            MigrationStep(DdlStatement.AddEnumValue(wanted.name, value, previous)).also { previous = value }
        }
    }

    private fun createTables(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = to.tables
        .filter { from.table(it.name) == null }
        .map { MigrationStep(DdlStatement.CreateTable(it.name, it.columns, it.primaryKey)) }

    private fun columnChanges(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = to.tables.flatMap { wanted ->
        val existing = from.table(wanted.name) ?: return@flatMap emptyList()
        val added = wanted.columns
            .filter { existing.column(it.name) == null }
            .map { MigrationStep(DdlStatement.AddColumn(wanted.name, it), addedColumnWarning(wanted.name, it)) }
        val altered = wanted.columns.mapNotNull { column ->
            existing.column(column.name)?.let { alterColumn(wanted.name, it, column) }
        }.flatten()
        val dropped = existing.columns
            .filter { wanted.column(it.name) == null }
            .map {
                MigrationStep(
                    DdlStatement.DropColumn(wanted.name, it.name),
                    "the column `${wanted.name}.${it.name}` is dropped, and every value in it with it",
                )
            }
        added + altered + dropped
    }

    /**
     * A new column that is required and has no default has nothing to put in the rows already there.
     *
     * The database will refuse it rather than guess, which is the right outcome — but saying so before
     * the statement runs is more useful than the constraint violation that follows it.
     */
    private fun addedColumnWarning(table: String, column: ColumnDefinition): String? {
        if (column.nullable || column.default != null || column.autoIncrement) return null
        return "the column `$table.${column.name}` is required and has no default, so adding it fails " +
            "if `$table` already has rows"
    }

    private fun alterColumn(table: String, existing: ColumnDefinition, wanted: ColumnDefinition): List<MigrationStep> {
        val steps = ArrayList<MigrationStep>()
        if (existing.type != wanted.type) {
            steps += MigrationStep(
                DdlStatement.AlterColumn(table, wanted.name, ColumnChange.Type(wanted.type)),
                "the type of `$table.${wanted.name}` changes, and values that do not fit the new one are lost",
            )
        }
        if (existing.nullable != wanted.nullable) {
            val warning = "`$table.${wanted.name}` becomes required, so the change fails if any row holds null there"
            steps += MigrationStep(
                DdlStatement.AlterColumn(table, wanted.name, ColumnChange.Nullability(wanted.nullable)),
                if (wanted.nullable) null else warning,
            )
        }
        if (existing.default != wanted.default) {
            steps += MigrationStep(DdlStatement.AlterColumn(table, wanted.name, ColumnChange.Default(wanted.default)))
        }
        return steps
    }

    private fun addConstraintsAndIndexes(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = to.tables.flatMap { wanted ->
        val existing = from.table(wanted.name)
        val key = wanted.primaryKey
            ?.takeIf { existing != null && it != existing.primaryKey }
            ?.let { listOf(MigrationStep(DdlStatement.AddPrimaryKey(wanted.name, it))) }
            .orEmpty()
        val uniques = wanted.uniques
            .filter { unique -> existing == null || existing.uniques.none { it == unique } }
            .map { MigrationStep(DdlStatement.AddUnique(wanted.name, it), uniqueWarning(wanted.name, existing, it)) }
        val indexes = wanted.indexes
            .filter { index -> existing == null || existing.indexes.none { it == index } }
            .map { MigrationStep(DdlStatement.CreateIndex(wanted.name, it)) }
        key + uniques + indexes
    }

    /** A unique constraint on a table that already has rows fails if any two of them are alike. */
    private fun uniqueWarning(table: String, existing: TableDefinition?, unique: UniqueDefinition): String? {
        if (existing == null) return null
        return "`$table` gains a unique constraint on ${unique.columns.joinToString(", ") { "`$it`" }}, so the " +
            "change fails if two rows already share those values"
    }

    private fun addForeignKeys(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = to.tables.flatMap { wanted ->
        val existing = from.table(wanted.name)
        wanted.foreignKeys
            .filter { key -> existing == null || existing.foreignKeys.none { it == key } }
            .map { MigrationStep(DdlStatement.AddForeignKey(wanted.name, it)) }
    }

    private fun dropEnums(from: DatabaseSchema, to: DatabaseSchema): List<MigrationStep> = from.enums
        .filter { to.enumType(it.name) == null }
        .map { MigrationStep(DdlStatement.DropEnum(it.name), "the enum type `${it.name}` is dropped") }
}
