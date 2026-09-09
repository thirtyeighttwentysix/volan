package io.github.thirtyeighttwentysix.volan.runtime

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.function.Supplier

/**
 * Dispatches blocking JDBC work. Applications may supply a dedicated executor and retain its ownership.
 *
 * Cancelling a future prevents queued work from starting, but does not interrupt an in-flight JDBC
 * statement. Statement cancellation belongs to the coroutine/interceptor milestone.
 */
@NullMarked
public class AsyncAccess @JvmOverloads public constructor(
    private val executor: Executor = ForkJoinPool.commonPool(),
    private val beforeDispatch: Runnable = Runnable {},
) {
    /** Failures, including executor rejection and transaction guard failures, complete the future exceptionally. */
    @Suppress("TooGenericExceptionCaught") // Adapt caller-supplied executors and guards to the future's failure channel.
    public fun <T : @Nullable Any?> submit(operation: Supplier<T>): CompletableFuture<T> = try {
        beforeDispatch.run()
        CompletableFuture.supplyAsync({
            beforeDispatch.run()
            operation.get()
        }, executor)
    } catch (failure: Exception) {
        CompletableFuture.failedFuture(failure)
    }
}
