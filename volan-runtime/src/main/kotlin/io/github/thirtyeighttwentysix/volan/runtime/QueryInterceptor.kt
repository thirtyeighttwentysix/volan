package io.github.thirtyeighttwentysix.volan.runtime

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable
import java.util.function.Supplier

/** The JDBC operation, rather than a high-cardinality SQL label. */
@NullMarked
public enum class QueryOperation {
    /** A statement returning rows, including writes with RETURNING. */
    QUERY,

    /** A statement returning an affected-row count. */
    EXECUTE,
}

/** A physical statement. Bound values are deliberately excluded; SQL can still contain caller-written literals. */
@NullMarked
public data class QueryContext(
    public val sql: String,
    public val operation: QueryOperation,
    public val dialect: String,
)

/**
 * Wraps preparation, execution and result mapping of each physical statement, including raw SQL and relation queries.
 * Call the downstream supplier exactly once, on the calling thread. Implementations must be thread-safe;
 * registered order is outermost first.
 * Failure propagates to the caller, so a failure inside a transaction rolls it back.
 */
@NullMarked
public interface QueryInterceptor {
    /** Returns the downstream result, or propagates its failure. */
    public fun <T : @Nullable Any?> intercept(query: QueryContext, next: Supplier<T>): T
}

internal class QueryInterceptors(private val interceptors: List<QueryInterceptor>) {
    fun <T> execute(query: QueryContext, block: () -> T): T {
        if (interceptors.isEmpty()) return block()
        val owner = Thread.currentThread()
        val chain = interceptors.asReversed().fold(Supplier(block)) { next, interceptor ->
            Supplier {
                var called = false
                interceptor.intercept(query) {
                    check(Thread.currentThread() === owner) { "Interceptors must execute downstream work on the calling thread." }
                    check(!called) { "An interceptor may execute a statement only once." }
                    called = true
                    next.get()
                }.also { check(called) { "An interceptor must execute its downstream statement." } }
            }
        }
        return chain.get()
    }
}
