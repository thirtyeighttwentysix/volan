package io.github.thirtyeighttwentysix.volan.runtime

/**
 * Runs the queries a generated repository describes.
 *
 * Generated code never touches JDBC: it builds a [QuerySpec] or a write spec and hands it to an
 * executor. That boundary is what lets the same generated client run against a real database, inside
 * a transaction, or against a test double, without the generated code knowing the difference.
 *
 * It has one function per thing a repository can ask for. Splitting it up would only move the same
 * count behind a second interface that every implementation would have to implement anyway.
 */
@Suppress("TooManyFunctions")
public interface QueryExecutor {
    /** Reads every row [spec] selects. */
    public fun <T> findMany(spec: QuerySpec, mapper: RowMapper<T>): List<T>

    /** Reads the first row [spec] selects, or `null` when it selects none. */
    public fun <T> findFirst(spec: QuerySpec, mapper: RowMapper<T>): T?

    /** Counts the rows [spec] selects. */
    public fun count(spec: QuerySpec): Long

    /** Whether [spec] selects any row at all. */
    public fun exists(spec: QuerySpec): Boolean

    /** Works out the summaries [spec] asks for, keyed by the alias each was asked under. */
    public fun aggregate(spec: AggregateSpec): Map<String, Any?>

    /**
     * Folds rows into groups as [spec] describes, reading each group's key with [mapper].
     *
     * The key is read the same way a partial `select` is read, because that is what it is: the columns
     * the grouping asked for, and nothing else.
     */
    public fun <K> groupBy(spec: GroupSpec, mapper: RowMapper<K>): List<GroupRow<K>>

    /** Inserts one row and reads it back. */
    public fun <T> create(spec: CreateSpec, mapper: RowMapper<T>): T

    /** Inserts many rows, returning how many were written. */
    public fun createMany(specs: List<CreateSpec>): Long

    /** Updates the single row [spec] selects and reads it back. */
    public fun <T> update(spec: UpdateSpec, mapper: RowMapper<T>): T

    /** Updates every row [spec] selects, returning how many changed. */
    public fun updateMany(spec: UpdateSpec): Long

    /** Updates the row [spec] selects, or inserts one when it selects none, and reads it back. */
    public fun <T> upsert(spec: UpsertSpec, mapper: RowMapper<T>): T

    /** Deletes the single row [spec] selects and reads back what was deleted. */
    public fun <T> delete(spec: DeleteSpec, mapper: RowMapper<T>): T

    /** Deletes every row [spec] selects, returning how many were removed. */
    public fun deleteMany(spec: DeleteSpec): Long
}
