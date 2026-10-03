package io.github.thirtyeighttwentysix.volan.micrometer

import io.github.thirtyeighttwentysix.volan.runtime.QueryContext
import io.github.thirtyeighttwentysix.volan.runtime.QueryOperation
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CancellationException

class MicrometerQueryInterceptorTest {
    @Test
    fun `records all outcomes without SQL or parameters in labels`() {
        SimpleMeterRegistry().let { registry ->
            val interceptor = MicrometerQueryInterceptor(registry)
            val query = QueryContext("SELECT 'secret'", QueryOperation.QUERY, "postgresql")
            assertEquals(42, interceptor.intercept(query) { 42 })
            assertThrows<IllegalArgumentException> { interceptor.intercept(query) { throw IllegalArgumentException("secret") } }
            assertThrows<CancellationException> { interceptor.intercept(query) { throw CancellationException("secret") } }
            for (outcome in listOf("success", "error", "cancelled")) {
                assertEquals(1L, registry.get("volan.query").tag("outcome", outcome).timer().count())
            }
            registry.meters.forEach { meter ->
                assertEquals(setOf("dialect", "operation", "outcome"), meter.id.tags.map { it.key }.toSet())
            }
        }
    }
}
