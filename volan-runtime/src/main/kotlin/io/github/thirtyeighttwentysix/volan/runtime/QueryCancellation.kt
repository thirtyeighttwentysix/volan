package io.github.thirtyeighttwentysix.volan.runtime

import org.jspecify.annotations.NullMarked
import org.jspecify.annotations.Nullable
import java.sql.Statement
import java.util.concurrent.CancellationException
import java.util.function.Supplier

/**
 * Cancellation for one dispatched operation, including all its statements and nested writes.
 * Cancellation requests JDBC cancellation; it cannot undo a statement already committed.
 * The worker retains ownership of its statement and connection until their ordinary cleanup finishes.
 */
@NullMarked
public class QueryCancellation {
    private val lock = Any()
    private var statement: Statement? = null

    @Volatile private var cancelled = false

    /** Idempotently cancels the active statement. Driver cancellation failures do not replace cancellation. */
    public fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            runCatching { statement?.cancel() }
        }
    }

    /** Runs synchronous work in this cancellation scope; the scope must not be shared by workers. */
    public fun <T : @Nullable Any?> execute(block: Supplier<T>): T {
        check(current.get() == null) { "A cancellation scope is already active on this worker." }
        current.set(this)
        try {
            checkCancelled()
            return block.get().also { checkCancelled() }
        } finally {
            current.remove()
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw CancellationException("Volan operation was cancelled.")
    }

    private fun <T> statement(statement: Statement, block: () -> T): T {
        val previous = synchronized(lock) {
            checkCancelled()
            this.statement.also { this.statement = statement }
        }
        try {
            return block().also { checkCancelled() }
        } finally {
            // A cancelling thread must finish using the statement before its owner closes it.
            synchronized(lock) { this.statement = previous }
        }
    }

    public companion object {
        private val transactions = ThreadLocal<Int>()

        /** Rejects dispatch from a transaction or dispatched operation instead of losing its thread-bound scope. */
        @JvmStatic
        public fun requireDispatchable() {
            check(current.get() == null && transactions.get() == null) {
                "Dispatch the entire transaction and use synchronous operations inside its callback."
            }
        }

        internal fun enterTransaction() {
            transactions.set((transactions.get() ?: 0) + 1)
        }
        internal fun leaveTransaction() {
            val depth = requireNotNull(transactions.get()) - 1
            if (depth == 0) transactions.remove() else transactions.set(depth)
        }
        private val current = ThreadLocal<QueryCancellation>()

        internal fun check() {
            current.get()?.checkCancelled()
        }

        internal fun <T> withStatement(statement: Statement, block: () -> T): T = current.get()?.statement(statement, block) ?: block()
    }
}
