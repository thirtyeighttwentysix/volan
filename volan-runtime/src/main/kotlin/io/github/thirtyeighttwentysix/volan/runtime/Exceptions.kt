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
 * Thrown when code reads a field the query never asked for: one a partial `select` left out, or one a
 * `groupBy` did not group by.
 *
 * @property model the model the field is on.
 * @property field the field that was read.
 */
public class VolanFieldNotSelectedException internal constructor(
    public val model: String,
    public val field: String,
    block: String,
) : VolanException(
    "`$model.$field` was not selected by the query that produced this row.\n" +
        "  Add it to the query:  $block { $field }\n" +
        "  Or check first:       row.is${field.replaceFirstChar { it.uppercase() }}Selected",
)

/**
 * Thrown by the `…OrThrow` operations when nothing matched.
 *
 * @property model the model that was queried.
 */
public class VolanNotFoundException(public val model: String, message: String) : VolanException(message)

/**
 * Thrown when the client and the database it is pointed at do not fit together.
 *
 * A bad JDBC URL, a dialect nothing on the classpath can handle, or a generated client asking about a
 * model its registry has never heard of all land here — problems of assembly rather than of data.
 */
public class VolanConfigurationException(message: String, cause: Throwable? = null) : VolanException(message, cause)

/**
 * Thrown when a feature exists in the API but not yet behind it.
 *
 * Volan would rather say so than answer a question it cannot answer correctly. The message names the
 * milestone the capability is scheduled for, so the answer is checkable rather than open-ended.
 */
public class VolanUnsupportedException(message: String) : VolanException(message)

/**
 * Thrown when a write would break a unique constraint.
 *
 * @property constraint the constraint the database named, when it named one.
 */
public class VolanUniqueConstraintException(
    public val constraint: String?,
    message: String,
    cause: Throwable?,
) : VolanException(message, cause)

/**
 * Thrown when a write would leave a foreign key pointing at nothing, or would orphan a row.
 *
 * @property constraint the constraint the database named, when it named one.
 */
public class VolanForeignKeyException(
    public val constraint: String?,
    message: String,
    cause: Throwable?,
) : VolanException(message, cause)

/** Thrown when a write breaks a check constraint or a not-null column. */
public class VolanConstraintException(message: String, cause: Throwable?) : VolanException(message, cause)

/** Thrown when Volan cannot reach the database, or the pool has nothing left to hand out. */
public class VolanConnectionException(message: String, cause: Throwable?) : VolanException(message, cause)

/** Thrown when a statement or a pool checkout took longer than it was allowed to. */
public class VolanTimeoutException(message: String, cause: Throwable?) : VolanException(message, cause)

/**
 * Thrown when a transaction cannot be completed: a deadlock, a serialization failure, or a block that
 * asked for a rollback.
 *
 * @property retryable whether running the same block again could succeed. Serialization failures and
 *   deadlocks are the cases where it can.
 */
public class VolanTransactionException(
    public val retryable: Boolean,
    message: String,
    cause: Throwable?,
) : VolanException(message, cause)

/** Thrown when a statement failed for a reason Volan could not classify further. */
public class VolanQueryException(message: String, cause: Throwable?) : VolanException(message, cause)

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
public class SelectedFields private constructor(private val fields: Set<String>, private val block: String) {
    /** Whether [field] was selected. */
    public fun contains(field: String): Boolean = fields.contains(field)

    /**
     * Returns [value] when [field] was selected.
     *
     * @throws VolanFieldNotSelectedException if it was not, naming [model] and [field] in the message.
     */
    public fun <T> require(model: String, field: String, value: T?): T {
        if (!fields.contains(field)) throw VolanFieldNotSelectedException(model, field, block)
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    override fun equals(other: Any?): Boolean = this === other || (other is SelectedFields && fields == other.fields)

    override fun hashCode(): Int = fields.hashCode()

    override fun toString(): String = fields.joinToString(", ", prefix = "[", postfix = "]")

    public companion object {
        /** A selection naming exactly [fields], as a `select` block would. */
        @JvmStatic
        public fun of(fields: Set<String>): SelectedFields = SelectedFields(fields, "select")

        /**
         * A selection naming exactly [fields], as the `by` block of a `groupBy` would.
         *
         * A group has values only for the columns it was grouped by, so reading anything else is the
         * same mistake as reading a field a `select` left out — and it deserves the same message,
         * pointing at the block that would add it.
         */
        @JvmStatic
        public fun groupedBy(fields: Set<String>): SelectedFields = SelectedFields(fields, "by")
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
