package io.github.thirtyeighttwentysix.volan.micrometer

import io.github.thirtyeighttwentysix.volan.runtime.QueryContext
import io.github.thirtyeighttwentysix.volan.runtime.QueryInterceptor
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.function.Supplier

/**
 * Records duration and count of physical statements, including failures and cancellations.
 * Tags are bounded: dialect, operation, outcome. SQL, bound values and exception messages are never tags.
 * Registry ownership stays with the application. Timing includes connection checkout and row mapping.
 */
public class MicrometerQueryInterceptor @JvmOverloads public constructor(
    private val registry: MeterRegistry,
    private val name: String = "volan.query",
) : QueryInterceptor {
    override fun <T> intercept(query: QueryContext, next: Supplier<T>): T {
        val sample = Timer.start(registry)
        var outcome = "error"
        try {
            return next.get().also { outcome = "success" }
        } catch (failure: CancellationException) {
            outcome = "cancelled"
            throw failure
        } finally {
            sample.stop(
                Timer.builder(name)
                    .description("Volan physical JDBC statement duration and count")
                    .tags("dialect", query.dialect, "operation", query.operation.name.lowercase(Locale.ROOT), "outcome", outcome)
                    .register(registry),
            )
        }
    }
}
