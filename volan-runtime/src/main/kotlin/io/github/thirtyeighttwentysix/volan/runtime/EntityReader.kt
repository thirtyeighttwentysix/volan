package io.github.thirtyeighttwentysix.volan.runtime

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable

/**
 * What the runtime needs from a generated entity mapper in order to load relations.
 *
 * Reading a row is only half of it: to attach the rows on the far side of a relation, the loader has
 * to be able to ask an entity for its key and to hand back a copy with the relation filled in. Both
 * are generated, so neither costs a reflective call.
 */
@NullMarked
public interface EntityReader<T : @Nullable Any?> : RowMapper<T> {
    /** The model these rows belong to. */
    public val model: String

    /**
     * Reads the values of [columns] out of [entity], in order.
     *
     * The loader uses this to group related rows back against the rows they belong to, which is how
     * one query per relation replaces one query per row.
     */
    public fun key(entity: T, columns: List<String>): List<@Nullable Any?>

    /**
     * Returns a copy of [entity] with [relation] loaded to [value].
     *
     * Entities are immutable, so this is a copy rather than a mutation: two references to the same row
     * cannot disagree about what has been loaded.
     */
    public fun withRelation(entity: T, relation: String, value: @Nullable Any?): T
}
