package io.github.thirtyeighttwentysix.volan.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.sql.Statement
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueryCancellationTest {
    @Test
    fun `cancelled scope never starts queued work and restores the worker`() {
        val cancellation = QueryCancellation()
        cancellation.cancel()
        var called = false
        assertThrows<CancellationException> { cancellation.execute { called = true } }
        assertFalse(called)
        assertEquals(42, QueryCancellation().execute { 42 })
    }

    @Test
    fun `cancellation waits for cancel to finish before the statement may close`() {
        val running = CountDownLatch(1)
        val finishStatement = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1)
        val finishCancel = CountDownLatch(1)
        val calls = AtomicInteger()
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    calls.incrementAndGet()
                    cancelEntered.countDown()
                    assertTrue(finishCancel.await(5, TimeUnit.SECONDS))
                    null
                }
                else -> error("Unexpected statement call: ${method.name}")
            }
        } as Statement
        val workers = Executors.newFixedThreadPool(2)
        try {
            val cancellation = QueryCancellation()
            val operation = workers.submit<Int> {
                cancellation.execute {
                    QueryCancellation.withStatement(statement) {
                        running.countDown()
                        assertTrue(finishStatement.await(5, TimeUnit.SECONDS))
                        1
                    }
                }
            }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            val cancel = workers.submit { cancellation.cancel() }
            assertTrue(cancelEntered.await(5, TimeUnit.SECONDS))
            finishStatement.countDown()
            assertFalse(operation.isDone)
            finishCancel.countDown()
            cancel.get(5, TimeUnit.SECONDS)
            assertTrue(assertThrows<ExecutionException> { operation.get(5, TimeUnit.SECONDS) }.cause is CancellationException)
            cancellation.cancel()
            assertEquals(1, calls.get())
        } finally {
            finishCancel.countDown()
            finishStatement.countDown()
            workers.shutdownNow()
        }
    }

    @Test
    fun `a finished statement is never cancelled and the next one is refused`() {
        var cancelled = false
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, _, _ ->
            cancelled = true
            null
        } as Statement
        val cancellation = QueryCancellation()
        assertThrows<CancellationException> {
            cancellation.execute {
                QueryCancellation.withStatement(statement) { 1 }
                cancellation.cancel()
                QueryCancellation.withStatement(statement) { error("Must not start") }
            }
        }
        assertFalse(cancelled)
    }

    @Test
    fun `Java future cancellation reaches a running statement and waits to reuse the worker`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val statement = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Statement::class.java)) { _, method, _ ->
            check(method.name == "cancel")
            cancelled.countDown()
            null
        } as Statement
        val worker = Executors.newSingleThreadExecutor()
        try {
            val access = AsyncAccess(worker)
            val future = access.submit {
                try {
                    QueryCancellation.withStatement(statement) {
                        started.countDown()
                        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
                    }
                } finally {
                    finished.countDown()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(future.cancel(false))
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(7, access.submit { 7 }.get(5, TimeUnit.SECONDS))
        } finally {
            cancelled.countDown()
            worker.shutdownNow()
        }
    }
}
