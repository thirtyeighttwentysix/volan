package io.github.thirtyeighttwentysix.volan.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class ConnectionSourceTest {
    @Test
    fun `default transactions leave JDBC isolation alone`() {
        val jdbc = TestConnection()
        jdbc.source.transaction(Isolation.DEFAULT, RetryPolicy.NONE) {
            jdbc.source.use { assertSame(jdbc.connection, it) }
            assertFalse(jdbc.connection.autoCommit)
        }
        assertEquals(0, jdbc.isolationReads)
        assertEquals(emptyList<Int>(), jdbc.isolationChanges)
        assertEquals(1, jdbc.commits)
        assertTrue(jdbc.autoCommit)
        assertTrue(jdbc.closed)
    }

    @Test
    fun `explicit isolation is restored after rollback`() {
        val jdbc = TestConnection()
        val error = IllegalStateException("rollback")
        assertSame(
            error,
            assertThrows<IllegalStateException> {
                jdbc.source.transaction(Isolation.SERIALIZABLE, RetryPolicy.NONE) {
                    assertEquals(Connection.TRANSACTION_SERIALIZABLE, jdbc.isolation)
                    throw error
                }
            },
        )
        assertEquals(listOf(Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_COMMITTED), jdbc.isolationChanges)
        assertEquals(1, jdbc.rollbacks)
        assertTrue(jdbc.autoCommit)
        assertTrue(jdbc.closed)
    }

    @Test
    fun `explicit isolation already in use needs no setter`() {
        val jdbc = TestConnection()
        jdbc.source.transaction(Isolation.READ_COMMITTED, RetryPolicy.NONE) {}
        assertEquals(1, jdbc.isolationReads)
        assertEquals(emptyList<Int>(), jdbc.isolationChanges)
        assertEquals(1, jdbc.commits)
    }

    @Test
    fun `a failing isolation read returns the connection`() {
        val jdbc = TestConnection(failIsolationRead = true)
        assertThrows<SQLException> { jdbc.source.transaction(Isolation.SERIALIZABLE, RetryPolicy.NONE) {} }
        assertTrue(jdbc.closed)
        assertTrue(jdbc.autoCommit)
    }

    private class TestConnection(failIsolationRead: Boolean = false) {
        var autoCommit = true
        var isolation = Connection.TRANSACTION_READ_COMMITTED
        var isolationReads = 0
        val isolationChanges = mutableListOf<Int>()
        var commits = 0
        var rollbacks = 0
        var closed = false

        val connection = proxy<Connection> { method, arguments ->
            when (method) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = arguments[0] as Boolean
                    null
                }
                "getTransactionIsolation" -> {
                    isolationReads++
                    if (failIsolationRead) throw SQLException("cannot read isolation")
                    isolation
                }
                "setTransactionIsolation" -> {
                    isolation = arguments[0] as Int
                    isolationChanges.add(isolation)
                    null
                }
                "commit" -> {
                    commits++
                    null
                }
                "rollback" -> {
                    rollbacks++
                    null
                }
                "close" -> {
                    closed = true
                    null
                }
                else -> error("Unexpected connection call: $method")
            }
        }
        val source = ConnectionSource(
            proxy<DataSource> { method, _ ->
                check(method == "getConnection")
                connection
            },
        )

        private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>) -> Any?): T = T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
                call(method.name, arguments.orEmpty())
            },
        )
    }
}
