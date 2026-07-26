package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.VolanException

/**
 * What the nested writer needs from the executor: the same operations, one level down.
 *
 * Keeping this an interface rather than a back-reference is what lets the ordering logic below be read
 * on its own, and tested without a database.
 */
internal interface NestedWriteTarget {
    /**
     * Inserts one row and returns it, together with the key other rows will point at.
     *
     * This is the primitive: it writes exactly the row it is given. Anything that row asked to write
     * alongside it is the writer's business, not the executor's.
     */
    fun insertPlain(spec: CreateSpec, keyColumns: List<String>): WrittenRow

    /** Finds the row a filter selects, or `null`, returning the values of [keyColumns]. */
    fun findKey(model: String, filter: Filter?, keyColumns: List<String>): List<Any?>?

    /** Points existing rows at a parent by writing the foreign key. */
    fun pointAt(model: String, filter: Filter?, columns: List<String>, key: List<Any?>): Long

    /** Records pairs in a join table. */
    fun pair(table: String, localColumns: List<String>, targetColumns: List<String>, local: List<Any?>, targets: List<List<Any?>>)
}

/** A row that was just written, and the key by which other rows refer to it. */
internal class WrittenRow(val row: Any?, val key: List<Any?>)

/**
 * Applies the writes a create asked to make on the other side of its relations.
 *
 * The ordering is the whole point. A row that holds a foreign key cannot be written before the row it
 * points at exists, and a row that is pointed at cannot know its own key until it has been written.
 * So relations the parent owns are resolved first and folded into the parent's own values, and
 * everything else is applied afterwards against the key the parent came back with.
 */
internal class NestedWriter(private val registry: TableRegistry, private val target: NestedWriteTarget) {
    /**
     * Writes [spec] and everything it asked to write with it, returning the row and its [keyColumns].
     *
     * Nested rows go back through this same function, so a shape three levels deep is written by the
     * same rules as one level.
     */
    fun write(spec: CreateSpec, keyColumns: List<String> = registry.require(spec.model).primaryKey): WrittenRow {
        val (owning, dependent) = spec.nested.partition { owns(spec.model, it.relation) }

        var values = spec.values
        owning.forEach { write -> values = values + resolveOwning(spec.model, write) }

        val written = target.insertPlain(spec.copy(values = values, nested = emptyList()), keyColumns)
        dependent.forEach { write -> applyDependent(spec.model, write, written.key) }
        return written
    }

    private fun owns(model: String, relation: String): Boolean {
        val metadata = registry.requireRelation(model, relation)
        return metadata.joinTable == null && metadata.ownsForeignKey
    }

    /**
     * Resolves a relation whose foreign key this row holds, producing the columns to write with it.
     *
     * There is exactly one row on the far side, so anything that would attach several is a mistake
     * worth naming rather than silently taking the first.
     */
    private fun resolveOwning(model: String, write: NestedWrite): Map<String, Any?> {
        val relation = registry.requireRelation(model, write.relation)
        val key = singleKey(model, write, relation)
        return relation.foreignKeyColumns.zip(key).toMap()
    }

    private fun singleKey(model: String, write: NestedWrite, relation: RelationMetadata): List<Any?> = when (write) {
        is NestedWrite.CreateRows -> {
            val row = write.rows.singleOrNull() ?: throw tooMany(model, write.relation, write.rows.size)
            write(row, relation.referencedColumns).key
        }
        is NestedWrite.ConnectRows -> {
            val filter = write.filters.singleOrNull() ?: throw tooMany(model, write.relation, write.filters.size)
            requireFound(model, write.relation, target.findKey(relation.target, filter, relation.referencedColumns))
        }
        is NestedWrite.ConnectOrCreateRows -> {
            val entry = write.entries.singleOrNull() ?: throw tooMany(model, write.relation, write.entries.size)
            findOrCreate(entry, relation.target, relation.referencedColumns)
        }
    }

    private fun applyDependent(model: String, write: NestedWrite, parentKey: List<Any?>) {
        val relation = registry.requireRelation(model, write.relation)
        if (relation.joinTable != null) {
            applyThroughJoinTable(model, write, relation, parentKey)
            return
        }
        when (write) {
            is NestedWrite.CreateRows -> write.rows.forEach { row ->
                write(row.copy(values = row.values + relation.foreignKeyColumns.zip(parentKey)), relation.referencedColumns)
            }
            is NestedWrite.ConnectRows -> write.filters.forEach { filter ->
                val changed = target.pointAt(relation.target, filter, relation.foreignKeyColumns, parentKey)
                if (changed == 0L) throw notFound(model, write.relation)
            }
            is NestedWrite.ConnectOrCreateRows -> write.entries.forEach { entry ->
                val existing = target.findKey(relation.target, entry.filter, relation.referencedColumns)
                if (existing == null) {
                    write(entry.row.copy(values = entry.row.values + relation.foreignKeyColumns.zip(parentKey)), relation.referencedColumns)
                } else {
                    target.pointAt(relation.target, entry.filter, relation.foreignKeyColumns, parentKey)
                }
            }
        }
    }

    private fun applyThroughJoinTable(model: String, write: NestedWrite, relation: RelationMetadata, parentKey: List<Any?>) {
        val table = requireNotNull(relation.joinTable)
        val keys = when (write) {
            is NestedWrite.CreateRows -> write.rows.map { write(it, relation.foreignKeyColumns).key }
            is NestedWrite.ConnectRows -> write.filters.map { filter ->
                requireFound(model, write.relation, target.findKey(relation.target, filter, relation.foreignKeyColumns))
            }
            is NestedWrite.ConnectOrCreateRows -> write.entries.map { entry ->
                findOrCreate(entry, relation.target, relation.foreignKeyColumns)
            }
        }
        if (keys.isNotEmpty()) {
            target.pair(table, relation.joinLocalColumns, relation.joinTargetColumns, parentKey, keys)
        }
    }

    private fun findOrCreate(entry: ConnectOrCreateEntry, model: String, keyColumns: List<String>): List<Any?> =
        target.findKey(model, entry.filter, keyColumns) ?: write(entry.row, keyColumns).key

    private fun requireFound(model: String, relation: String, key: List<Any?>?): List<Any?> = key ?: throw notFound(model, relation)

    private fun notFound(model: String, relation: String): VolanException = VolanNotFoundException(
        model,
        "the row `$model.$relation` was told to connect to does not exist, so nothing was written.",
    )

    private fun tooMany(model: String, relation: String, count: Int): VolanException = VolanValidationException(
        "`$model.$relation` holds one row, but $count were given to write with it.",
    )
}
