package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.VolanException

/**
 * Thrown when code reads a relation that the query did not ask for.
 *
 * Volan never loads a relation behind your back, so this is always a question about the query, not
 * about the data. The message names the query to change and the line to add.
 *
 * @property model the model the relation is on.
 * @property relation the relation field that was read.
 */
public class VolanRelationNotLoadedException internal constructor(public val model: String, public val relation: String) :
    VolanException(
        "`$model.$relation` was not loaded by the query that produced this row.\n" +
            "  Add it to the query:  include { $relation { } }\n" +
            "  Or check first:       row.is${relation.replaceFirstChar { it.uppercase() }}Loaded",
    )

/**
 * Thrown when code reads a field that a partial `select` left out.
 *
 * @property model the model the field is on.
 * @property field the field that was read.
 */
public class VolanFieldNotSelectedException internal constructor(public val model: String, public val field: String) :
    VolanException(
        "`$model.$field` was not selected by the query that produced this row.\n" +
            "  Add it to the query:  select { $field }\n" +
            "  Or check first:       row.is${field.replaceFirstChar { it.uppercase() }}Selected",
    )

/**
 * Thrown by the `…OrThrow` operations when nothing matched.
 *
 * @property model the model that was queried.
 */
public class VolanNotFoundException(public val model: String, message: String) : VolanException(message)

/**
 * Thrown when a write is missing something the schema requires, before any statement is sent.
 *
 * Catching this at the boundary is the difference between a clear message about a field that was
 * never set and a constraint violation from the database three layers down.
 */
public class VolanValidationException(message: String) : VolanException(message)

/**
 * Thrown when a value in the database cannot be turned into what the schema says it is.
 *
 * The usual cause is a value that predates a schema change: an enum column holding a name the enum no
 * longer has, for example. The message says which column and which value, because that is what a
 * migration to fix it needs.
 */
public class VolanMappingException(message: String) : VolanException(message)

/**
 * The set of fields a partial `select` asked for.
 *
 * A projection carries one, so that reading a field the query left out fails with a message naming the
 * query to change rather than with a silent null.
 */
public class SelectedFields(private val fields: Set<String>) {
    /** Whether [field] was selected. */
    public fun contains(field: String): Boolean = fields.contains(field)

    /**
     * Returns [value] when [field] was selected.
     *
     * @throws VolanFieldNotSelectedException if it was not, naming [model] and [field] in the message.
     */
    public fun <T> require(model: String, field: String, value: T?): T {
        if (!fields.contains(field)) throw VolanFieldNotSelectedException(model, field)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    override fun equals(other: Any?): Boolean = this === other || (other is SelectedFields && fields == other.fields)

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = fields.joinToString(", ", prefix = "[", postfix = "]")

    public companion object {
        /** A selection naming exactly [fields]. */
        @JvmStatic
        public fun of(fields: Set<String>): SelectedFields = SelectedFields(fields)
    }
}

/**
 * Holds a value that a query may or may not have loaded.
 *
 * This is what makes "not loaded" different from "loaded and null": a `Profile?` relation that was
 * fetched and turned out to be absent is a loaded slot holding null, while one that was never
 * requested is an empty slot that refuses to be read.
 */
public class RelationSlot<T> private constructor(
    private val value: T?,
    /** Whether the query loaded this relation. */
    public val isLoaded: Boolean,
) {
    /**
     * Returns the loaded value.
     *
     * @throws VolanRelationNotLoadedException if the query did not load it. [model] and [relation]
     *   name it in the message.
     */
    public fun get(model: String, relation: String): T {
        if (!isLoaded) throw VolanRelationNotLoadedException(model, relation)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    /** Returns the loaded value, or `null` when the query did not load it. */
    public fun orNull(): T? = value

    override fun equals(other: Any?): Boolean =
        this === other || (other is RelationSlot<*> && isLoaded == other.isLoaded && value == other.value)

    override fun hashCode(): Int = 31 * (if (isLoaded) 1 else 0) + (value?.hashCode() ?: 0)

    override fun toString(): String = if (isLoaded) value.toString() else "<not loaded>"

    public companion object {
        private val EMPTY = RelationSlot<Any?>(null, isLoaded = false)

        /** A slot holding [value], which the query loaded. */
        @JvmStatic
        public fun <T> loaded(value: T): RelationSlot<T> = RelationSlot(value, isLoaded = true)

        /** An empty slot: the query did not ask for this relation. */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        public fun <T> notLoaded(): RelationSlot<T> = EMPTY as RelationSlot<T>
    }
}
