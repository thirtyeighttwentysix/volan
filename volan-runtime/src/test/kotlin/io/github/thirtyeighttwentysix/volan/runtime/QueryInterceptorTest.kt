package io.github.thirtyeighttwentysix.volan.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

class QueryInterceptorTest {
    private val query = QueryContext("INSERT INTO example VALUES (?)", QueryOperation.EXECUTE, "h2")

    @Test
    fun `an interceptor cannot repeat a write`() {
        var writes = 0
        val interceptor = object : QueryInterceptor {
            override fun <T> intercept(query: QueryContext, next: Supplier<T>): T {
                next.get()
                return next.get()
            }
        }
        assertThrows<IllegalStateException> { QueryInterceptors(listOf(interceptor)).execute(query) { writes++ } }
        assertEquals(1, writes)
    }

    @Test
    fun `an interceptor cannot move a transaction statement onto another thread`() {
        val worker = Executors.newSingleThreadExecutor()
        try {
            var writes = 0
            val interceptor = object : QueryInterceptor {
                override fun <T> intercept(query: QueryContext, next: Supplier<T>): T =
                    worker.submit<T> { next.get() }.get(5, TimeUnit.SECONDS)
            }
            val failure = assertThrows<java.util.concurrent.ExecutionException> {
                QueryInterceptors(listOf(interceptor)).execute(query) { writes++ }
            }
            assertEquals(IllegalStateException::class.java, failure.cause?.javaClass)
            assertEquals(0, writes)
        } finally {
            worker.shutdownNow()
        }
    }
}
