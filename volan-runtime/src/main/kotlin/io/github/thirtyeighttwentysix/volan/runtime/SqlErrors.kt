package io.github.thirtyeighttwentysix.volan.runtime

import io.github.thirtyeighttwentysix.volan.VolanException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.sql.SQLTransientConnectionException

/**
 * Turns a driver's [SQLException] into the Volan exception that says what actually happened.
 *
 * The classification is driven by `SQLState`, which is standardised, rather than by vendor error
 * numbers. That is what lets an application catch [VolanUniqueConstraintException] once and have it
 * mean the same thing on PostgreSQL and on MySQL.
 */
internal object SqlErrors {
    private const val UNIQUE_VIOLATION = "23505"
    private const val FOREIGN_KEY_VIOLATION = "23503"
    private const val NOT_NULL_VIOLATION = "23502"
    private const val CHECK_VIOLATION = "23514"
    private const val INTEGRITY_VIOLATION = "23000"
    private const val SERIALIZATION_FAILURE = "40001"
    private const val DEADLOCK_DETECTED = "40P01"
    private const val DEADLOCK_GENERIC = "40P02"
    private const val STATEMENT_CANCELLED = "57014"
    private const val TOO_MANY_CONNECTIONS = "53300"
    private const val CONNECTION_CLASS = "08"
    private const val TRANSACTION_ROLLBACK_CLASS = "40"

    /** The name of the constraint a database mentioned, when its message follows the usual shape. */
    private val CONSTRAINT_NAME = Regex("constraint \"([^\"]+)\"")

    /**
     * Classifies [exception], which was thrown while running [context].
     *
     * @param context a short description of what was being done, used to make the message specific.
     */
    fun translate(exception: SQLException, context: String): VolanException {
        val state = exception.sqlState.orEmpty()
        val constraint = CONSTRAINT_NAME.find(exception.message.orEmpty())?.groupValues?.get(1)
        return when {
            state == UNIQUE_VIOLATION || (state == INTEGRITY_VIOLATION && mentionsDuplicate(exception)) ->
                VolanUniqueConstraintException(
                    constraint,
                    "$context would create a duplicate of an existing row" + constraintSuffix(constraint) + ".",
                    exception,
                )
            state == FOREIGN_KEY_VIOLATION ->
                VolanForeignKeyException(
                    constraint,
                    "$context refers to a row that does not exist, or would orphan one that does" +
                        constraintSuffix(constraint) + ".",
                    exception,
                )
            state == NOT_NULL_VIOLATION || state == CHECK_VIOLATION || state == INTEGRITY_VIOLATION ->
                VolanConstraintException("$context breaks a constraint the database enforces: ${exception.message}", exception)
            state == SERIALIZATION_FAILURE || state == DEADLOCK_DETECTED || state == DEADLOCK_GENERIC ->
                VolanTransactionException(
                    retryable = true,
                    message = "$context could not be serialised against a concurrent transaction. Running it again may succeed.",
                    cause = exception,
                )
            state.startsWith(TRANSACTION_ROLLBACK_CLASS) ->
                VolanTransactionException(retryable = false, message = "$context was rolled back: ${exception.message}", cause = exception)
            state == STATEMENT_CANCELLED || exception is SQLTimeoutException ->
                VolanTimeoutException("$context took longer than it was allowed to.", exception)
            state == TOO_MANY_CONNECTIONS || state.startsWith(CONNECTION_CLASS) || exception is SQLTransientConnectionException ->
                VolanConnectionException("$context could not reach the database: ${exception.message}", exception)
            else -> VolanQueryException("$context failed: ${exception.message}", exception)
        }
    }

    /**
     * MySQL reports a duplicate key under the generic integrity class, so the state alone does not
     * separate it from a null violation.
     */
    private fun mentionsDuplicate(exception: SQLException): Boolean = exception.message.orEmpty().contains("uplicate", ignoreCase = false)

    private fun constraintSuffix(constraint: String?): String = if (constraint == null) "" else " (`$constraint`)"
}
