package io.github.thirtyeighttwentysix.volan.runtime

/** Where the relation loader gets its rows. The executor supplies both, so nested loads reuse it. */
internal interface RelationSource {
    /** Reads rows of another model, relations of its own included. */
    fun read(spec: QuerySpec, reader: EntityReader<*>): List<Any?>

    /**
     * Reads the pairs of a many-to-many join table for the given local keys.
     *
     * Returned as (local key, target key), in no particular order.
     */
    fun joinPairs(
        table: String,
        localColumns: List<String>,
        targetColumns: List<String>,
        keys: List<List<Any?>>,
    ): List<Pair<List<Any?>, List<Any?>>>
}

/**
 * Loads the relations a query asked for, one statement per relation rather than one per row.
 *
 * Given the rows already read, it collects their keys, fetches everything on the far side in a single
 * query, and hands each parent its own share. The number of statements therefore depends on how deep
 * the `include` goes, never on how many rows came back — which is the whole of Volan's answer to N+1.
 *
 * A many-to-many relation costs two statements per level rather than one: the pairs, then the rows
 * they point at. Nothing about that grows with the size of the result either.
 */
internal class RelationLoader(
    private val registry: TableRegistry,
    private val readers: Map<String, EntityReader<*>>,
    private val chunkSize: Int,
) {
    fun <T> load(parents: List<T>, reader: EntityReader<T>, requests: List<RelationRequest>, source: RelationSource): List<T> {
        if (parents.isEmpty() || requests.isEmpty()) return parents
        var result = parents
        requests.forEach { request -> result = loadOne(result, reader, request, source) }
        return result
    }

    private fun <T> loadOne(parents: List<T>, reader: EntityReader<T>, request: RelationRequest, source: RelationSource): List<T> {
        val relation = registry.requireRelation(reader.model, request.relation)
        val childReader = readerFor(relation.target)
        val parentColumns = parentKeyColumns(relation)
        val keys = parents.map { reader.key(it, parentColumns) }.filterNot { key -> key.any { it == null } }.distinct()
        if (keys.isEmpty()) return parents.map { reader.withRelation(it, request.relation, empty(relation)) }

        val shares = if (relation.joinTable == null) {
            direct(relation, request, keys, childReader, source)
        } else {
            throughJoinTable(relation, request, keys, childReader, source)
        }
        return parents.map { parent ->
            val share = shares[reader.key(parent, parentColumns)].orEmpty()
            reader.withRelation(parent, request.relation, take(relation, share))
        }
    }

    /** The columns of this model that the relation is keyed on. */
    private fun parentKeyColumns(relation: RelationMetadata): List<String> = when {
        relation.joinTable != null -> relation.referencedColumns
        relation.ownsForeignKey -> relation.foreignKeyColumns
        else -> relation.referencedColumns
    }

    /** One query: the rows on the far side, matched on the key one of the two sides holds. */
    private fun direct(
        relation: RelationMetadata,
        request: RelationRequest,
        keys: List<List<Any?>>,
        childReader: EntityReader<*>,
        source: RelationSource,
    ): Map<List<Any?>, List<Any?>> {
        val childColumns = if (relation.ownsForeignKey) relation.referencedColumns else relation.foreignKeyColumns
        val children = keys.chunked(chunkSize).flatMap { chunk ->
            source.read(request.spec.copy(filter = restrict(request.spec.filter, childColumns, chunk)), childReader)
        }
        return group(children, childReader, childColumns)
    }

    /**
     * Two queries: which rows are paired with which, then the rows themselves.
     *
     * Reading the pairs first is what keeps this to a fixed number of statements — the alternative,
     * joining in one statement, would duplicate every parent column across its matches.
     */
    private fun throughJoinTable(
        relation: RelationMetadata,
        request: RelationRequest,
        keys: List<List<Any?>>,
        childReader: EntityReader<*>,
        source: RelationSource,
    ): Map<List<Any?>, List<Any?>> {
        val table = requireNotNull(relation.joinTable)
        val pairs = keys.chunked(chunkSize).flatMap { chunk ->
            source.joinPairs(table, relation.joinLocalColumns, relation.joinTargetColumns, chunk)
        }
        if (pairs.isEmpty()) return emptyMap()

        val targetKeys = pairs.map { it.second }.distinct()
        val children = targetKeys.chunked(chunkSize).flatMap { chunk ->
            source.read(request.spec.copy(filter = restrict(request.spec.filter, relation.foreignKeyColumns, chunk)), childReader)
        }
        // Walking the children rather than the pairs is what keeps the `orderBy` of the include: the
        // pairs come back in whatever order the join table felt like, and the children in the asked-for one.
        val keyed = keyEach(children, childReader, relation.foreignKeyColumns)
        val wantedByParent = pairs.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        return wantedByParent.mapValues { (_, wanted) -> keyed.filter { it.first in wanted }.map { it.second } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun keyEach(children: List<Any?>, reader: EntityReader<*>, columns: List<String>): List<Pair<List<Any?>, Any?>> {
        val typed = reader as EntityReader<Any?>
        return children.map { typed.key(it, columns) to it }
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
}
