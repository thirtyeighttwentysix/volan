# ADR-0003: A synchronous core with a thin coroutine layer

- **Status:** accepted
- **Date:** 2026-07-26

## Context

Volan must offer `suspend` variants of every operation for Kotlin users, and `CompletableFuture`
variants for Java users, while its only transport in 1.0 is JDBC — which is a blocking API.

The core can either be written as `suspend` functions with blocking calls inside, or be written
synchronously with a coroutine façade on top.

## Decision

`volan-runtime` is entirely synchronous and coroutine-agnostic — it does not depend on
`kotlinx-coroutines` at all. `volan-coroutines` wraps it, dispatching each call to a bounded IO
dispatcher, and the Java layer wraps the same core in `CompletableFuture`s backed by a configurable
`Executor`.

## Consequences

- The core is usable from plain Java, from Spring's blocking stack, and from tests without any
  coroutine machinery on the classpath.
- Thread-pool sizing stays honest: JDBC work runs on a pool the user can size against the connection
  pool, instead of silently occupying whatever dispatcher happened to call in.
- Cancellation is explicit: the coroutine layer registers an invocation handler that calls
  `Statement.cancel()` and returns the connection, so a cancelled coroutine does not leak a connection.
- A future R2DBC backend cannot simply reuse the synchronous core; it will need its own executor path
  behind the same generated API. That is accepted and recorded in the roadmap as post-1.0 work.

## Alternatives considered

- **`suspend`-first core.** Would look modern but would make every blocking JDBC call invisible inside
  a coroutine, which is exactly how coroutine dispatchers get starved. It would also force
  `kotlinx-coroutines` onto Java-only users.
- **Two independent implementations.** Guarantees divergence between the blocking and the suspending
  behaviour, and doubles the integration test matrix.
