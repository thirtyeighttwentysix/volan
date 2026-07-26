package io.github.thirtyeighttwentysix.volan.runtime

/**
 * The few decisions generated write blocks need at run time.
 *
 * Everything else about a nested write is decided when the client is generated; these are the two
 * things that depend on what the caller actually filled in.
 */
public object NestedWrites {
    /**
     * Builds the condition a `connectOrCreate` looks its row up by.
     *
     * The block gives the values to write if nothing matches; what to match on is the first unique key
     * whose every column that block set. Looking up by anything else would risk attaching the wrong row.
     *
     * @throws VolanValidationException when nothing the block set identifies a row.
     */
    @JvmStatic
    public fun uniqueFilter(model: String, values: Map<String, Any?>, uniqueKeys: List<List<String>>): Filter {
        val usable = uniqueKeys.firstOrNull { key -> key.all { values[it] != null } } ?: throw VolanValidationException(
            "`connectOrCreate` on `$model` cannot tell which row to look for: the block set " +
                "${describe(values.keys)}, and none of the unique keys of `$model` " +
                "(${uniqueKeys.joinToString(", ") { key -> key.joinToString("+") }}) is covered by that.\n" +
                "  Set the columns of one of them, or use `connect` with a condition of your own.",
        )
        val conditions = usable.map { Filter.Compare(it, ComparisonOperator.EQUAL, values[it]) }
        return if (conditions.size == 1) conditions.single() else Filter.And(conditions)
    }

    /**
     * Refuses a write that reached into relations where only a flat one can be applied.
     *
     * @param reason why this operation cannot carry them, as a clause following the operation's name.
     * @param instead what to use instead, as a whole sentence.
     */
    @JvmStatic
    public fun requireFlat(operation: String, nested: List<NestedWrite>, reason: String, instead: String) {
        if (nested.isEmpty()) return
        val relations = nested.joinToString(", ") { "`${it.relation}`" }
        throw VolanValidationException(
            "`$operation` $reason, so it cannot also reach into $relations.\n  $instead",
        )
    }

    private fun describe(columns: Set<String>): String = if (columns.isEmpty()) "nothing" else columns.joinToString(", ")
}
