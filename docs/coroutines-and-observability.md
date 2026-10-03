# Coroutines, interception and metrics

These APIs are on main for alpha.3; Maven Central alpha.2 does not include them.
The JDBC core stays synchronous and has no coroutine or Micrometer dependencies.
Add only the optional modules your application uses, aligned with the generation plugin:

```kotlin
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:0.1.0-alpha.3"))
    implementation("io.github.thirtyeighttwentysix:volan-coroutines")
    implementation("io.github.thirtyeighttwentysix:volan-micrometer")
}
```

## Typed coroutine access

The `suspendQuery` extension preserves the generated receiver and return type. It covers the complete
generated API, including projections, summaries, nested writes, relations and raw SQL, without a second
client or new schema options:

```kotlin
import io.github.thirtyeighttwentysix.volan.coroutines.suspendQuery
import kotlinx.coroutines.withTimeout

val users = withTimeout(2_000) {
    db.suspendQuery {
        user.findMany {
            where { email endsWith "@example.org" }
            include { posts {} }
            take = 20
        }
    }
}

val created = db.user.suspendQuery { create { email = "new@example.org" } }
```

The default `CoroutineAccess` uses a shared `Dispatchers.IO.limitedParallelism(16)` view. Configure a
view suited to your application's connection budget and load when needed:

```kotlin
import io.github.thirtyeighttwentysix.volan.coroutines.CoroutineAccess
import kotlinx.coroutines.Dispatchers

val access = CoroutineAccess(Dispatchers.IO.limitedParallelism(4))
val count = db.suspendQuery(access) { user.count() }
```

A supplied dispatcher remains caller owned. Query configuration and mapper callbacks execute on the
worker; do not mutate their captured inputs concurrently. JDBC still occupies a worker while it runs;
this is not an R2DBC backend. Runtime-description consumers can instead use `SuspendingQueryExecutor`,
which exposes suspend counterparts of all `QueryExecutor` operations.

## Transactions and cancellation

Dispatch the entire transaction, and use synchronous operations inside its callback:

```kotlin
val user = db.suspendQuery {
    transaction { tx ->
        val created = tx.user.create { email = "transaction@example.org" }
        tx.post.create { title = "Welcome"; authorId = created.id }
        created
    }
}
```

The callback deliberately cannot suspend. The synchronous core binds a transaction to one thread and
one connection; a suspension or dispatch inside that callback would lose that context. Nested
synchronous transactions retain savepoint behavior. Coroutine dispatch from a transaction or another
dispatched operation is refused before work leaves the worker. Keep network calls outside transactions.

Cancellation marks the operation cancelled and calls `Statement.cancel()` on its active statement.
Work cancelled while queued never calls the application callback. Subsequent statements are refused;
a transaction cancelled before commit rolls back. The coroutine waits for its worker to close the
statement and return the connection before completing, including during timeout cancellation.

JDBC cancellation is a driver request, not a guaranteed hard deadline. Drivers or connection checkout
can take time to finish; configure pool/network/server timeouts as appropriate. Already committed
autocommit writes, or a commit which has already started, cannot be undone by cancellation.

Java `*Async` operations use the same cancellation scope. Cancelling their `CompletableFuture` requests
statement cancellation and skips queued work. The future becomes cancelled immediately; unlike the
coroutine call, future cancellation itself does not wait for worker cleanup. `mayInterruptIfRunning`
does not control JDBC cancellation and no worker-thread interrupt is needed.

## Statement interceptors

Register a thread-safe `QueryInterceptor` with the runtime or generated client builder:

```kotlin
import io.github.thirtyeighttwentysix.volan.runtime.QueryContext
import io.github.thirtyeighttwentysix.volan.runtime.QueryInterceptor
import java.util.function.Supplier

val interceptor = object : QueryInterceptor {
    override fun <T> intercept(query: QueryContext, next: Supplier<T>): T {
        logger.debug("Volan operation={}, dialect={}", query.operation, query.dialect)
        return next.get()
    }
}

val db = VolanClient.builder().url(url).interceptor(interceptor).build()
```

Interceptors wrap each physical statement's checkout, preparation, binding, execution, mapping and
cleanup. Raw SQL, generated-key inserts, relation queries and nested writes use the same path. Pool validation,
dialect initialization, transaction-control commands and migration SQL executed directly on JDBC are
outside these hooks. The
first registered interceptor is outermost. Downstream execution must happen once on the calling
thread; duplicate execution or moving it to another thread is refused. An interceptor failure
propagates, rolling back an enclosing transaction; an autocommit write already completed is not undone.

`QueryContext` contains SQL, QUERY/EXECUTE operation and dialect, but no bound parameter values. SQL can
still contain caller-written literals: logging it requires your application's redaction policy.
These are statement hooks for logging/tracing/metrics, not automatic soft-delete or tenant filters.

## Optional Micrometer metrics

```kotlin
import io.github.thirtyeighttwentysix.volan.micrometer.MicrometerQueryInterceptor

val db = VolanClient.builder()
    .url(url)
    .interceptor(MicrometerQueryInterceptor(registry))
    .build()
```

The `volan.query` timer records durations and attempted-statement counts, with three bounded tags:

| Tag | Values |
|---|---|
| `dialect` | Provider id, such as `postgresql` or `sqlite` |
| `operation` | `query` (rows, including RETURNING), `execute` (affected-row count) |
| `outcome` | `success`, `error`, `cancelled` |

SQL, parameter values and exception messages never become metric labels. Timing includes pool checkout
and mapping; relation loading contributes one sample per statement, not one sample for an entire
repository call. Successful statements inside a later rolled-back transaction remain successful
statement samples. Registry and exporter ownership stay with the application; closing Volan does not
close them. Install the Micrometer exporter your deployment uses separately.
