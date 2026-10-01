package io.github.thirtyeighttwentysix.volan.migrate

import io.github.thirtyeighttwentysix.volan.dialect.DdlStatement

/** Complete table definitions are required because SQLite cannot add constraints with ALTER TABLE. */
internal object SqliteDiffer {
    fun diff(from: DatabaseSchema, to: DatabaseSchema): MigrationPlan {
        val warnings = SchemaDiffer.diff(from, to).warnings
        val steps = ArrayList<MigrationStep>()
        from.tables.filter { to.table(it.name) == null }.forEach {
            steps += MigrationStep(DdlStatement.DropTable(it.name))
        }
        to.tables.forEach { target ->
            val existing = from.table(target.name)
            val definition = DdlStatement.CreateConstrainedTable(
                DdlStatement.CreateTable(target.name, target.columns, target.primaryKey),
                target.uniques,
                target.foreignKeys,
            )
            when {
                existing == null -> {
                    steps += MigrationStep(definition)
                    target.indexes.forEach { steps += MigrationStep(DdlStatement.CreateIndex(target.name, it)) }
                }
                existing.copy(indexes = emptyList()) != target.copy(indexes = emptyList()) -> {
                    if (from.table("__volan_rebuild_${target.name}") != null || to.table("__volan_rebuild_${target.name}") != null) {
                        throw VolanMigrationException("The temporary rebuild table for ${target.name} already exists.")
                    }
                    val retained = target.columns.filter { existing.column(it.name) != null }.map { it.name }
                    steps += MigrationStep(DdlStatement.RebuildTable(definition, retained, target.indexes))
                }
                else -> {
                    existing.indexes.filter { it !in target.indexes }.forEach {
                        steps += MigrationStep(DdlStatement.DropIndex(target.name, it.name))
                    }
                    target.indexes.filter { it !in existing.indexes }.forEach {
                        steps += MigrationStep(DdlStatement.CreateIndex(target.name, it))
                    }
                }
            }
        }
        // Keep every warning even though several column/constraint changes coalesce into one rebuild.
        return MigrationPlan(
            steps.mapIndexed { index, step ->
                if (index == 0 && warnings.isNotEmpty()) step.copy(warning = warnings.joinToString("\n")) else step
            },
        )
    }
}
