package io.github.thirtyeighttwentysix.volan.coroutines

import io.github.thirtyeighttwentysix.volan.runtime.QueryCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs blocking JDBC on a bounded dispatcher. A cancelled call waits for statement/transaction cleanup.
 * Blocks are synchronous, including transaction callbacks: no coroutine may inherit a thread-bound transaction.
 * A supplied dispatcher belongs to the caller and must support dispatching blocking work.
 */
public class CoroutineAccess public constructor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM),
) {
    /** Dispatches one operation, which may include a whole synchronous transaction. */
    @Suppress("TooGenericExceptionCaught") // Worker and dispatcher failures must reach the suspended caller.
    @JvmSynthetic
    public suspend fun <T> execute(block: () -> T): T {
        QueryCancellation.requireDispatchable()
        val cancellation = QueryCancellation()
        val finished = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancellation.cancel() }
                try {
                    dispatcher.dispatch(continuation.context) {
                        try {
                            continuation.resume(cancellation.execute { block() })
                        } catch (failure: Throwable) {
                            continuation.resumeWithException(failure)
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                } catch (failure: Throwable) {
                    finished.complete(Unit)
                    continuation.resumeWithException(failure)
                }
            }
        } finally {
            withContext(NonCancellable) { finished.await() }
        }
    }

    public companion object {
        private const val DEFAULT_PARALLELISM = 16

        /** Shared bounded access; no coroutine dependency is required by the synchronous runtime. */
        public val DEFAULT: CoroutineAccess = CoroutineAccess()
    }
}

/**
 * Typed suspend access to any generated client or repository, without additional generated classes.
 * For example, `db.suspendQuery { user.findMany { take = 10 } }`.
 * Put a whole `transaction { ... }` inside this block; use synchronous calls inside that transaction.
 */
@JvmSynthetic
public suspend fun <C, T> C.suspendQuery(access: CoroutineAccess = CoroutineAccess.DEFAULT, block: C.() -> T): T =
    access.execute { block() }
