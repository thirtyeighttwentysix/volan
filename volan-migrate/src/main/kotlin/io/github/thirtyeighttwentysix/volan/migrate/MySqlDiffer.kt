package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.ColumnChange
import io.github.thirtyeighttwentysix.volan.dialect.ConstraintKind
import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement

/** MySQL MODIFY requires the entire target column, including nullability and unchanged defaults. */
internal object MySqlDiffer {
    fun diff(from: DatabaseSchema, to: DatabaseSchema): MigrationPlan {
        val generic = SchemaDiffer.diff(from, to)
        val modified = HashSet<Pair<String, String>>()
        val steps = generic.steps.mapNotNull { step ->
            when (val ddl = step.statement) {
                is DdlStatement.AlterColumn -> {
                    if ((ddl.change as? ColumnChange.Type)?.using != null) {
                        throw VolanMigrationException("MySQL explicit conversion expressions require reviewed migration SQL.")
                    }
                    if (!modified.add(ddl.table to ddl.column)) return@mapNotNull null
                    val warnings = generic.steps.filter {
                        val change = it.statement as? DdlStatement.AlterColumn
                        change?.table == ddl.table && change.column == ddl.column
                    }.mapNotNull { it.warning }
                    step.copy(
                        statement = DdlStatement.ModifyColumn(ddl.table, requireNotNull(to.table(ddl.table)?.column(ddl.column))),
                        warning = warnings.takeIf {
                            it.isNotEmpty()
                        }?.joinToString("\n"),
                    )
                }
                is DdlStatement.DropConstraint -> {
                    val table = requireNotNull(from.table(ddl.table))
                    val kind = when {
                        table.primaryKey?.name == ddl.name -> ConstraintKind.PRIMARY_KEY
                        table.uniques.any { it.name == ddl.name } -> ConstraintKind.UNIQUE
                        else -> ConstraintKind.FOREIGN_KEY
                    }
                    step.copy(statement = ddl.copy(kind = kind))
                }
                else -> step
            }
        }
        // InnoDB refuses to drop an index while a surviving foreign key still depends on it.
        val changedIndexes = steps.mapNotNull { it.statement as? DdlStatement.DropIndex }.map { it.table }.toSet()
        val temporaryKeys = from.tables.filter { it.name in changedIndexes }.flatMap { table ->
            table.foreignKeys.filter { key ->
                to.table(table.name)?.foreignKeys?.contains(key) == true && steps.none {
                    val drop = it.statement as? DdlStatement.DropConstraint
                    drop?.table == table.name && drop.name == key.name
                }
            }.map { table.name to it }
        }
        val dropKeys = temporaryKeys.map { (table, key) ->
            MigrationStep(DdlStatement.DropConstraint(table, key.name, ConstraintKind.FOREIGN_KEY))
        }
        val restoreKeys = temporaryKeys.map { (table, key) -> MigrationStep(DdlStatement.AddForeignKey(table, key)) }
        return MigrationPlan(dropKeys + steps + restoreKeys)
    }
}
