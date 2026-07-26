package io.github.thirtyeighttwentysix.volan.runtime

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** How much a transaction is protected from what other transactions are doing. */
public enum class Isolation(internal val jdbcLevel: Int) {
    /** Reads may see rows other transactions have written but not committed. */
    READ_UNCOMMITTED(Connection.TRANSACTION_READ_UNCOMMITTED),

    /** Reads see only committed rows, but two reads in one transaction may disagree. */
    READ_COMMITTED(Connection.TRANSACTION_READ_COMMITTED),

    /** Rows already read do not change under the transaction's feet. */
    REPEATABLE_READ(Connection.TRANSACTION_REPEATABLE_READ),

    /** The result is as if transactions ran one after another. */
    SERIALIZABLE(Connection.TRANSACTION_SERIALIZABLE),

    /** Whatever the database is configured to do. */
    DEFAULT(-1),
}

/**
 * How often a transaction that lost a race should be tried again.
 *
 * Serialization failures and deadlocks are not bugs: they are the database telling two transactions to
 * sort out an order between them. Retrying is the intended response — but only for a block that can be
 * run twice, which is why this is off unless asked for.
 *
 * @property attempts how many times to run the block in total, including the first.
 * @property initialDelay how long to wait before the second attempt, in milliseconds.
 * @property multiplier how much longer to wait before each further attempt.
 */
public data class RetryPolicy(
    public val attempts: Int,
    public val initialDelay: Long = DEFAULT_DELAY_MILLIS,
    public val multiplier: Double = DEFAULT_MULTIPLIER,
) {
    init {
        require(attempts >= 1) { "a transaction has to be attempted at least once, not $attempts times" }
    }

    public companion object {
        private const val DEFAULT_DELAY_MILLIS = 20L
        private const val DEFAULT_MULTIPLIER = 2.0

        /** Run once, and let a serialization failure surface. */
        @JvmField
        public val NONE: RetryPolicy = RetryPolicy(attempts = 1)

        /** Run up to three times, backing off between attempts. */
        @JvmField
        public val DEFAULT: RetryPolicy = RetryPolicy(attempts = 3)
    }
}

/**
 * Hands out the connection a statement should run on.
 *
 * Outside a transaction that is a fresh one from the pool, returned as soon as the statement is done.
 * Inside one it is the transaction's own connection, which is what makes several statements part of
 * the same unit of work — and what makes a transaction confined to the thread that opened it.
 */
internal class ConnectionSource(private val dataSource: DataSource) {
    private val active = ThreadLocal<Transaction?>()

    /** Whether the calling thread is inside a transaction. */
    val inTransaction: Boolean get() = active.get() != null

    fun <T> use(block: (Connection) -> T): T {
        active.get()?.let { return block(it.connection) }
        return borrow().use(block)
    }

    fun <T> transaction(isolation: Isolation, retry: RetryPolicy, block: () -> T): T {
        active.get()?.let { return nested(it, block) }
        return outermost(isolation, retry, block)
    }

    private fun <T> outermost(isolation: Isolation, retry: RetryPolicy, block: () -> T): T {
        var delay = retry.initialDelay
        var attempt = 1
        while (true) {
            try {
                return runOnce(isolation, block)
            } catch (failure: VolanTransactionException) {
                if (!failure.retryable || attempt >= retry.attempts) throw failure
                Thread.sleep(delay)
                delay = (delay * retry.multiplier).toLong()
                attempt++
            }
        }
    }

    private fun <T> runOnce(isolation: Isolation, block: () -> T): T {
        val connection = borrow()
        val previousAutoCommit = connection.autoCommit
        val previousIsolation = connection.transactionIsolation
        val transaction = Transaction(connection)
        try {
            connection.autoCommit = false
            if (isolation != Isolation.DEFAULT) connection.transactionIsolation = isolation.jdbcLevel
            active.set(transaction)
            val result = runCatching { block() }
            result.exceptionOrNull()?.let { failure ->
                rollback(connection, failure)
                throw failure
            }
            commit(connection)
            return result.getOrThrow()
        } finally {
            active.remove()
            restore(connection, previousAutoCommit, previousIsolation)
            connection.close()
        }
    }

    /**
     * A transaction inside a transaction becomes a savepoint.
     *
     * Committing the inner block then means "keep what it did"; failing means undoing only its part,
     * which is the behaviour that makes nesting worth having at all.
     */
    // Anything at all thrown by the block has to undo the savepoint, including errors the block did not
    // intend to be caught anywhere. Narrowing this would leave a half-applied nested block behind.
    @Suppress("TooGenericExceptionCaught")
    private fun <T> nested(transaction: Transaction, block: () -> T): T {
        val savepoint = transaction.connection.setSavepoint("volan_${transaction.nextSavepoint()}")
        try {
            val result = block()
            transaction.connection.releaseSavepoint(savepoint)
            return result
        } catch (failure: Throwable) {
            runCatching { transaction.connection.rollback(savepoint) }
            throw failure
        }
    }

    private fun borrow(): Connection = try {
        dataSource.connection
    } catch (failure: SQLException) {
        throw SqlErrors.translate(failure, "checking out a connection")
    }

    private fun commit(connection: Connection) {
        try {
            connection.commit()
        } catch (failure: SQLException) {
            throw SqlErrors.translate(failure, "committing the transaction")
        }
    }

    private fun rollback(connection: Connection, cause: Throwable) {
        runCatching { connection.rollback() }.onFailure { cause.addSuppressed(it) }
    }

    private fun restore(connection: Connection, autoCommit: Boolean, isolation: Int) {
        runCatching { connection.autoCommit = autoCommit }
        runCatching { connection.transactionIsolation = isolation }
    }

    /** The connection one transaction is pinned to, and the savepoints opened inside it. */
    private class Transaction(val connection: Connection) {
        private var savepoints = 0

        fun nextSavepoint(): Int = ++savepoints
    }
}
