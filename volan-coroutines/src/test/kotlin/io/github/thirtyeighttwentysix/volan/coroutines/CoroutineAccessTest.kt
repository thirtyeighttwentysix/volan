package io.github.thirtyeighttwentysix.volan.coroutines

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CoroutineAccessTest {
    @Test
    fun `blocking operations run on the supplied worker and propagate mapper failures`() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val access = CoroutineAccess(dispatcher)
            val caller = Thread.currentThread().name
            assertNotEquals(caller, access.execute { Thread.currentThread().name })
            val failure = IllegalArgumentException("mapper")
            assertEquals(failure.message, assertThrows<IllegalArgumentException> { access.execute { throw failure } }.message)
            assertEquals(42, access.execute { 42 })
        }
    }

    @Test
    fun `cancellation does not finish until worker cleanup finishes and skips queued callbacks`() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val access = CoroutineAccess(dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cleaned = AtomicBoolean()
            val queuedCalled = AtomicBoolean()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                access.execute {
                    try {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                    } finally {
                        cleaned.set(true)
                    }
                }
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val queued = async(start = CoroutineStart.UNDISPATCHED) { access.execute { queuedCalled.set(true) } }
                active.cancel()
                queued.cancel()
                assertFalse(active.isCompleted)
                assertFalse(cleaned.get())
                release.countDown()
                active.join()
                queued.join()
                assertTrue(cleaned.get())
                assertFalse(queuedCalled.get())
                assertEquals(7, access.execute { 7 })
            } finally {
                release.countDown()
                active.cancelAndJoin()
            }
        }
    }

    @Test
    fun `typed extension preserves the receiver and rejects nested dispatch`() = runBlocking<Unit> {
        assertEquals(3, "abc".suspendQuery { length })
        assertThrows<IllegalStateException> {
            CoroutineAccess.DEFAULT.execute { runBlocking { CoroutineAccess.DEFAULT.execute {} } }
        }
    }
}
