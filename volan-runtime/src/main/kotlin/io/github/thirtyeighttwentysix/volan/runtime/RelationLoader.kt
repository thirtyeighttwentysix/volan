package io.github.thirtyeighttwentysix.volan.runtime

/**
 * Loads the relations a query asked for, one statement per relation rather than one per row.
 *
 * Given the rows already read, it collects their keys, fetches everything on the far side in a single
 * query, and hands each parent its own share. The number of statements therefore depends on how deep
 * the `include` goes, never on how many rows came back — which is the whole of Volan's answer to N+1.
 */
internal class RelationLoader(
    private val registry: TableRegistry,
    private val readers: Map<String, EntityReader<*>>,
    private val chunkSize: Int,
) {
    /**
     * Fills [requests] on [parents].
     *
     * @param run how to execute a child query; the executor passes its own read path in, so nested
     *   relations go through exactly the same machinery.
     */
    fun <T> load(
        parents: List<T>,
        reader: EntityReader<T>,
        requests: List<RelationRequest>,
        run: (QuerySpec, EntityReader<*>) -> List<Any?>,
    ): List<T> {
        if (parents.isEmpty() || requests.isEmpty()) return parents
        var result = parents
        requests.forEach { request ->
            result = loadOne(result, reader, request, run)
        }
        return result
    }

    private fun <T> loadOne(
        parents: List<T>,
        reader: EntityReader<T>,
        request: RelationRequest,
        run: (QuerySpec, EntityReader<*>) -> List<Any?>,
    ): List<T> {
        val relation = registry.requireRelation(reader.model, request.relation)
        rejectJoinTable(reader.model, relation)
        val childReader = readerFor(relation.target)
        val parentColumns = if (relation.ownsForeignKey) relation.foreignKeyColumns else relation.referencedColumns
        val childColumns = if (relation.ownsForeignKey) relation.referencedColumns else relation.foreignKeyColumns

        val keys = parents.map { reader.key(it, parentColumns) }.filterNot { key -> key.any { it == null } }.distinct()
        if (keys.isEmpty()) return parents.map { reader.withRelation(it, request.relation, empty(relation)) }

        val children = keys.chunked(chunkSize).flatMap { chunk ->
            run(request.spec.copy(filter = restrict(request.spec.filter, childColumns, chunk)), childReader)
        }
        val grouped = group(children, childReader, childColumns)
        return parents.map { parent ->
            val share = grouped[reader.key(parent, parentColumns)].orEmpty()
            reader.withRelation(parent, request.relation, take(relation, share))
        }
    }

    /**
     * Narrows a child query to the parents in hand.
     *
     * A single-column key becomes `IN (…)`; a composite one becomes a disjunction of matches, since
     * not every database compares tuples and the row counts here are bounded by the chunk size anyway.
     */
    private fun restrict(existing: Filter?, columns: List<String>, keys: List<List<Any?>>): Filter? {
        val restriction = if (columns.size == 1) {
            Filter.InList(columns.single(), keys.map { it.single() })
        } else {
            Filter.Or(
                keys.map { key ->
                    Filter.And(columns.zip(key).map { (column, value) -> Filter.Compare(column, ComparisonOperator.EQUAL, value) })
                },
            )
        }
        return Filter.all(listOfNotNull(existing, restriction))
    }

    @Suppress("UNCHECKED_CAST")
    private fun group(children: List<Any?>, reader: EntityReader<*>, columns: List<String>): Map<List<Any?>, List<Any?>> {
        val typed = reader as EntityReader<Any?>
        return children.groupBy { typed.key(it, columns) }
    }

    /** A to-one relation takes the single row it found; a to-many takes all of them. */
    private fun take(relation: RelationMetadata, share: List<Any?>): Any? = if (relation.isList) share else share.firstOrNull()

    private fun empty(relation: RelationMetadata): Any? = if (relation.isList) emptyList<Any?>() else null

    private fun readerFor(model: String): EntityReader<*> = readers[model] ?: throw VolanConfigurationException(
        "no reader was registered for `$model`, so its rows cannot be loaded.\n" +
            "  This means the generated client handed the runtime an incomplete set of models.",
    )

    private fun rejectJoinTable(model: String, relation: RelationMetadata) {
        if (relation.joinTable == null) return
        throw VolanUnsupportedException(
            "loading the many-to-many relation `$model.${relation.field}` is not available yet.\n" +
                "  Read the join with a second query for now; it arrives with nested writes in M5.",
        )
    }
}
