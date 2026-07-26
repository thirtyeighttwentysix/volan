package io.github.thirtyeighttwentysix.volan.ir

/**
 * The checks that only make sense once every model and relation is known: names colliding in the
 * database, and delete cascades that go round in a circle.
 */
internal class IntegrityAnalyzer(private val sink: DiagnosticSink) {
    fun analyze(models: List<ModelDraft>, enums: List<EnumType>, relations: List<Relation>) {
        reportTableCollisions(models)
        reportEnumCollisions(enums)
        reportCascadeCycles(models, relations)
    }

    private fun reportTableCollisions(models: List<ModelDraft>) {
        models.groupBy { it.dbName }.values.filter { it.size > 1 }.forEach { collisions ->
            collisions.drop(1).forEach { model ->
                sink.error(
                    code = SemanticCode.DUPLICATE_MAPPED_NAME,
                    span = model.nameSpan,
                    message = "two models map to the table `${model.dbName}`",
                    label = "`${collisions.first().name}` already uses it",
                    help = "give one of them a different `@@map(\"…\")`",
                )
            }
        }
    }

    private fun reportEnumCollisions(enums: List<EnumType>) {
        enums.groupBy { it.dbName }.values.filter { it.size > 1 }.forEach { collisions ->
            collisions.drop(1).forEach { enumType ->
                sink.error(
                    code = SemanticCode.DUPLICATE_MAPPED_NAME,
                    span = enumType.span,
                    message = "two enums map to `${enumType.dbName}` in the database",
                    label = "`${collisions.first().name}` already uses it",
                    help = "give one of them a different `@@map(\"…\")`",
                )
            }
        }
    }

    /**
     * Reports delete cascades that form a loop.
     *
     * Deleting one row would then cascade back to the model it started from. PostgreSQL and MySQL
     * cope, SQL Server refuses the schema outright, and in every database it makes the effect of a
     * single `delete` hard to predict — which is why this is worth saying out loud, as a warning
     * rather than an error.
     */
    private fun reportCascadeCycles(models: List<ModelDraft>, relations: List<Relation>) {
        val edges = HashMap<String, MutableSet<String>>()
        relations.filter { it.onDelete == ReferentialAction.CASCADE }.forEach { relation ->
            edges.getOrPut(relation.to.model) { LinkedHashSet() }.add(relation.from.model)
        }
        val reported = HashSet<String>()
        models.map { it.name }.forEach { start ->
            val cycle = findCycle(start, edges)
            if (cycle != null && reported.add(cycle.toSortedSet().joinToString(","))) {
                reportCycle(models, cycle)
            }
        }
    }

    /** Returns the models on a cascade cycle reachable from [start] and returning to it, or `null`. */
    private fun findCycle(start: String, edges: Map<String, Set<String>>): List<String>? {
        val path = ArrayList<String>()
        val visiting = HashSet<String>()

        fun walk(node: String): List<String>? {
            if (node == start && path.isNotEmpty()) return path.toList()
            if (!visiting.add(node)) return null
            path.add(node)
            edges[node].orEmpty().forEach { next ->
                walk(next)?.let { return it }
            }
            path.removeAt(path.size - 1)
            return null
        }

        return walk(start)
    }

    private fun reportCycle(models: List<ModelDraft>, cycle: List<String>) {
        val head = models.first { it.name == cycle.first() }
        val route = (cycle + cycle.first()).joinToString(" → ")
        sink.warning(
            code = SemanticCode.CASCADE_CYCLE,
            span = head.nameSpan,
            message = "deleting a `${head.name}` cascades back to `${head.name}`",
            label = "the cascade goes $route",
            help = "set `onDelete: Restrict` or `onDelete: SetNull` on one relation in the loop to break it",
        )
    }
}
