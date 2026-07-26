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

    /**
     * Changes one row and returns it, together with the key other rows point at.
     *
     * The counterpart of [insertPlain]: it applies exactly the change it is given, and leaves what that
     * row asked to do to its relations to the writer.
     */
    fun changePlain(spec: UpdateSpec, keyColumns: List<String>): WrittenRow

    /** Finds the row a filter selects, or `null`, returning the values of [keyColumns]. */
    fun findKey(model: String, filter: Filter?, keyColumns: List<String>): List<Any?>?

    /** Finds every row a filter selects, returning the values of [keyColumns] for each. */
    fun findKeys(model: String, filter: Filter?, keyColumns: List<String>): List<List<Any?>>

    /** Changes every row a filter selects, returning how many changed. */
    fun changeRows(model: String, filter: Filter?, values: Map<String, Any?>): Long

    /** Deletes every row a filter selects, returning how many were removed. */
    fun removeRows(model: String, filter: Filter?): Long

    /** Records pairs in a join table. */
    fun pair(table: String, localColumns: List<String>, targetColumns: List<String>, local: List<Any?>, targets: List<List<Any?>>)

    /** Removes pairs from a join table: those naming [targets], or every pair of [local] when it is null. */
    fun unpair(table: String, localColumns: List<String>, targetColumns: List<String>, local: List<Any?>, targets: List<List<Any?>>?)

    /** The far keys [local] is paired with in a join table. */
    fun pairedKeys(table: String, localColumns: List<String>, targetColumns: List<String>, local: List<Any?>): List<List<Any?>>
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
     * Changes [spec] and applies everything it asked to do to its relations.
     *
     * The same two phases as a create, for the same reason. What is different is that a change can also
     * detach, replace, alter and delete rows that already exist, and all of those need the key of the
     * row being changed — so they happen after it, against the row that came back.
     */
    fun change(spec: UpdateSpec): WrittenRow {
        val keyColumns = registry.require(spec.model).primaryKey
        val (owning, dependent) = spec.nested.partition { owns(spec.model, it.relation) }
        val (before, after) = owning.partition { decidesForeignKey(it) }

        var values = spec.values
        before.forEach { write -> values = values + resolveOwningColumns(spec.model, write) }

        val written = target.changePlain(spec.copy(values = values, nested = emptyList()), keyColumns)
        after.forEach { write -> applyOwning(spec.model, write, written) }
        dependent.forEach { write -> applyDependent(spec.model, write, written.key) }
        return written
    }

    /** Whether this write decides which row the parent points at, and so has to happen before it changes. */
    private fun decidesForeignKey(write: NestedWrite): Boolean = when (write) {
        is NestedWrite.CreateRows, is NestedWrite.ConnectRows, is NestedWrite.ConnectOrCreateRows -> true
        is NestedWrite.DisconnectRows -> true
        is NestedWrite.UpdateRows, is NestedWrite.SetRows, is NestedWrite.DeleteRows -> false
    }

    private fun resolveOwningColumns(model: String, write: NestedWrite): Map<String, Any?> {
        val relation = registry.requireRelation(model, write.relation)
        if (write is NestedWrite.DisconnectRows) {
            return relation.foreignKeyColumns.associateWith { null }
        }
        return resolveOwning(model, write)
    }

    /**
     * Applies a write against the row the parent points at, which is only known once it has been read
     * back — the foreign key is on the parent, so the returned row is what says where it points.
     */
    private fun applyOwning(model: String, write: NestedWrite, parent: WrittenRow) {
        val relation = registry.requireRelation(model, write.relation)
        val key = target.findKey(model, keyOf(registry.require(model).primaryKey, parent.key), relation.foreignKeyColumns)
        if (key == null || key.all { it == null }) return
        val condition = keyOf(relation.referencedColumns, key)
        when (write) {
            is NestedWrite.UpdateRows -> target.changeRows(relation.target, allOf(condition, write.filter), write.values)
            else -> throw unsupported(model, write)
        }
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
        else -> throw unsupported(model, write)
    }

    private fun applyDependent(model: String, write: NestedWrite, parentKey: List<Any?>) {
        val relation = registry.requireRelation(model, write.relation)
        if (relation.joinTable != null) {
            applyThroughJoinTable(model, write, relation, parentKey)
            return
        }
        val attached = keyOf(relation.foreignKeyColumns, parentKey)
        val detached = relation.foreignKeyColumns.associateWith { null }
        when (write) {
            is NestedWrite.CreateRows -> write.rows.forEach { row ->
                write(row.copy(values = row.values + relation.foreignKeyColumns.zip(parentKey)), relation.referencedColumns)
            }
            is NestedWrite.ConnectRows -> write.filters.forEach { filter ->
                val changed = target.changeRows(relation.target, filter, relation.foreignKeyColumns.zip(parentKey).toMap())
                if (changed == 0L) throw notFound(model, write.relation)
            }
            is NestedWrite.ConnectOrCreateRows -> write.entries.forEach { entry ->
                val existing = target.findKey(relation.target, entry.filter, relation.referencedColumns)
                if (existing == null) {
                    write(entry.row.copy(values = entry.row.values + relation.foreignKeyColumns.zip(parentKey)), relation.referencedColumns)
                } else {
                    target.changeRows(relation.target, entry.filter, relation.foreignKeyColumns.zip(parentKey).toMap())
                }
            }
            is NestedWrite.DisconnectRows -> detach(relation, attached, write.filters, detached)
            is NestedWrite.SetRows -> {
                target.changeRows(relation.target, attached, detached)
                write.filters.forEach { filter ->
                    val changed = target.changeRows(relation.target, filter, relation.foreignKeyColumns.zip(parentKey).toMap())
                    if (changed == 0L) throw notFound(model, write.relation)
                }
            }
            is NestedWrite.UpdateRows -> target.changeRows(relation.target, allOf(attached, write.filter), write.values)
            is NestedWrite.DeleteRows -> target.removeRows(relation.target, allOf(attached, write.filter))
        }
    }

    /**
     * Detaches rows by clearing the foreign key that attached them.
     *
     * With no conditions this detaches everything attached, which is what `disconnect()` means on a
     * relation holding one row; with conditions it detaches only the rows they select and only among
     * the rows already attached.
     */
    private fun detach(relation: RelationMetadata, attached: Filter, filters: List<Filter>, cleared: Map<String, Any?>) {
        if (filters.isEmpty()) {
            target.changeRows(relation.target, attached, cleared)
            return
        }
        filters.forEach { filter -> target.changeRows(relation.target, allOf(attached, filter), cleared) }
    }

    private fun applyThroughJoinTable(model: String, write: NestedWrite, relation: RelationMetadata, parentKey: List<Any?>) {
        val table = requireNotNull(relation.joinTable)
        val local = relation.joinLocalColumns
        val far = relation.joinTargetColumns
        when (write) {
            is NestedWrite.CreateRows -> attach(relation, parentKey, write.rows.map { write(it, relation.foreignKeyColumns).key })
            is NestedWrite.ConnectRows ->
                attach(relation, parentKey, write.filters.map { resolvePaired(model, write.relation, relation, it) })
            is NestedWrite.ConnectOrCreateRows ->
                attach(relation, parentKey, write.entries.map { findOrCreate(it, relation.target, relation.foreignKeyColumns) })
            is NestedWrite.DisconnectRows -> target.unpair(
                table,
                local,
                far,
                parentKey,
                write.filters.takeIf { it.isNotEmpty() }?.map { resolvePaired(model, write.relation, relation, it) },
            )
            is NestedWrite.SetRows -> {
                target.unpair(table, local, far, parentKey, null)
                attach(relation, parentKey, write.filters.map { resolvePaired(model, write.relation, relation, it) })
            }
            is NestedWrite.UpdateRows ->
                target.changeRows(relation.target, allOf(paired(relation, parentKey), write.filter), write.values)
            is NestedWrite.DeleteRows -> {
                val doomed = target.findKeys(relation.target, allOf(paired(relation, parentKey), write.filter), relation.foreignKeyColumns)
                if (doomed.isNotEmpty()) {
                    target.unpair(table, local, far, parentKey, doomed)
                    target.removeRows(relation.target, anyOf(relation.foreignKeyColumns, doomed))
                }
            }
        }
    }

    private fun attach(relation: RelationMetadata, parentKey: List<Any?>, keys: List<List<Any?>>) {
        if (keys.isEmpty()) return
        target.pair(requireNotNull(relation.joinTable), relation.joinLocalColumns, relation.joinTargetColumns, parentKey, keys)
    }

    private fun resolvePaired(model: String, field: String, relation: RelationMetadata, filter: Filter): List<Any?> =
        requireFound(model, field, target.findKey(relation.target, filter, relation.foreignKeyColumns))

    /**
     * The rows currently paired with a parent through a join table.
     *
     * A join table cannot be reached from a condition on the far table, so the pairs are read first and
     * turned into a condition on the far table's own key.
     */
    private fun paired(relation: RelationMetadata, parentKey: List<Any?>): Filter {
        val table = requireNotNull(relation.joinTable)
        val keys = target.pairedKeys(table, relation.joinLocalColumns, relation.joinTargetColumns, parentKey)
        return anyOf(relation.foreignKeyColumns, keys)
    }

    private fun findOrCreate(entry: ConnectOrCreateEntry, model: String, keyColumns: List<String>): List<Any?> =
        target.findKey(model, entry.filter, keyColumns) ?: write(entry.row, keyColumns).key

    private fun requireFound(model: String, relation: String, key: List<Any?>?): List<Any?> = key ?: throw notFound(model, relation)

    /** The condition that picks out the row whose [columns] hold [key]. */
    private fun keyOf(columns: List<String>, key: List<Any?>): Filter = requireNotNull(
        Filter.all(columns.zip(key).map { (column, value) -> Filter.Compare(column, ComparisonOperator.EQUAL, value) }),
    ) { "a key with no columns cannot identify a row" }

    /** The condition that picks out every row whose [columns] hold one of [keys]; nothing when there are none. */
    private fun anyOf(columns: List<String>, keys: List<List<Any?>>): Filter = when {
        keys.isEmpty() -> Filter.InList(columns.first(), emptyList())
        columns.size == 1 -> Filter.InList(columns.single(), keys.map { it.single() })
        else -> requireNotNull(Filter.any(keys.map { keyOf(columns, it) }))
    }

    private fun allOf(scope: Filter, filter: Filter?): Filter = Filter.all(listOfNotNull(scope, filter)) ?: scope

    private fun notFound(model: String, relation: String): VolanException = VolanNotFoundException(
        model,
        "the row `$model.$relation` was told to connect to does not exist, so nothing was written.",
    )

    private fun tooMany(model: String, relation: String, count: Int): VolanException = VolanValidationException(
        "`$model.$relation` holds one row, but $count were given to write with it.",
    )

    private fun unsupported(model: String, write: NestedWrite): VolanException = VolanUnsupportedException(
        "`${write::class.simpleName}` is not something `$model.${write.relation}` can be asked for, " +
            "which means the generated client and this runtime disagree about that relation.",
    )
}
