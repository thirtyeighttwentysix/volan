package io.github.thirtyeighttwentysix.volan.coroutines

import io.github.thirtyeighttwentysix.volan.runtime.AggregateSpec
import io.github.thirtyeighttwentysix.volan.runtime.CreateSpec
import io.github.thirtyeighttwentysix.volan.runtime.DeleteSpec
import io.github.thirtyeighttwentysix.volan.runtime.GroupRow
import io.github.thirtyeighttwentysix.volan.runtime.GroupSpec
import io.github.thirtyeighttwentysix.volan.runtime.QueryExecutor
import io.github.thirtyeighttwentysix.volan.runtime.QuerySpec
import io.github.thirtyeighttwentysix.volan.runtime.RowMapper
import io.github.thirtyeighttwentysix.volan.runtime.UpdateSpec
import io.github.thirtyeighttwentysix.volan.runtime.UpsertSpec

/** Suspend counterparts of every query operation, useful for consumers of the runtime description API. */
public class SuspendingQueryExecutor public constructor(
    private val delegate: QueryExecutor,
    private val access: CoroutineAccess = CoroutineAccess.DEFAULT,
) {
    /** Reads matching rows. */
    @JvmSynthetic
    public suspend fun <T> findMany(spec: QuerySpec, mapper: RowMapper<T>): List<T> = access.execute { delegate.findMany(spec, mapper) }

    /** Reads the first matching row. */
    @JvmSynthetic
    public suspend fun <T> findFirst(spec: QuerySpec, mapper: RowMapper<T>): T? = access.execute { delegate.findFirst(spec, mapper) }

    /** Counts matching rows. */
    @JvmSynthetic
    public suspend fun count(spec: QuerySpec): Long = access.execute { delegate.count(spec) }

    /** Checks whether rows match. */
    @JvmSynthetic
    public suspend fun exists(spec: QuerySpec): Boolean = access.execute { delegate.exists(spec) }

    /** Summarises rows. */
    @JvmSynthetic
    public suspend fun aggregate(spec: AggregateSpec): Map<String, Any?> = access.execute { delegate.aggregate(spec) }

    /** Groups rows and computes summaries. */
    @JvmSynthetic
    public suspend fun <K> groupBy(spec: GroupSpec, mapper: RowMapper<K>): List<GroupRow<K>> =
        access.execute { delegate.groupBy(spec, mapper) }

    /** Creates a row and its nested writes. */
    @JvmSynthetic
    public suspend fun <T> create(spec: CreateSpec, mapper: RowMapper<T>): T = access.execute { delegate.create(spec, mapper) }

    /** Creates rows atomically. */
    @JvmSynthetic
    public suspend fun createMany(specs: List<CreateSpec>): Long = access.execute { delegate.createMany(specs) }

    /** Updates a row and its nested writes. */
    @JvmSynthetic
    public suspend fun <T> update(spec: UpdateSpec, mapper: RowMapper<T>): T = access.execute { delegate.update(spec, mapper) }

    /** Updates matching rows. */
    @JvmSynthetic
    public suspend fun updateMany(spec: UpdateSpec): Long = access.execute { delegate.updateMany(spec) }

    /** Updates or creates a row. */
    @JvmSynthetic
    public suspend fun <T> upsert(spec: UpsertSpec, mapper: RowMapper<T>): T = access.execute { delegate.upsert(spec, mapper) }

    /** Deletes and returns a row. */
    @JvmSynthetic
    public suspend fun <T> delete(spec: DeleteSpec, mapper: RowMapper<T>): T = access.execute { delegate.delete(spec, mapper) }

    /** Deletes matching rows. */
    @JvmSynthetic
    public suspend fun deleteMany(spec: DeleteSpec): Long = access.execute { delegate.deleteMany(spec) }
}
