# ADR-0006: The Java API is a designed artefact, not a by-product

- **Status:** accepted
- **Date:** 2026-07-26

## Context

Kotlin libraries are routinely "callable from Java" in the sense that the bytecode exists. In
practice, Java users meet `Continuation` parameters, `DefaultConstructorMarker`, mangled names from
value classes, `Result`, `Sequence`, and default arguments that do not exist.

Volan targets JVM teams, a large share of which write Java. A second-class Java API would make the
project a Kotlin-only ORM with a compatibility footnote.

## Decision

Java support is a hard constraint on the public API surface, enforced mechanically:

1. No `suspend` function is public. Asynchronous access is `*Async(...)` returning
   `CompletableFuture`.
2. No Kotlin-only type appears in a public signature: no `Result`, `UInt`, unsigned types, value
   classes, `Sequence`, `KClass`, or function types with receivers. Only `List`, `Map`, `Optional`,
   `Stream`, JSR-310 types, `java.util.function.*` and generated Volan types.
3. Default arguments in public API carry `@JvmOverloads`; no `inline`/`reified` function is part of the
   public contract.
4. `@JvmStatic`, `@JvmName` and `@JvmField` are used wherever they improve the Java call site.
5. Generated entities expose getters, `equals`/`hashCode`/`toString`, a builder, and a Jackson-friendly
   shape.
6. Nullability is declared with JSpecify annotations on every public signature.
7. `:java-compat-tests` is written in Java and covers every public scenario; a signature check task
   fails the build when a forbidden type appears in the public ABI dump.

## Consequences

- Some Kotlin-idiomatic shapes are unavailable in the core API (for example returning `Result<T>` or
  taking a `Sequence`). Where a Kotlin-only convenience is genuinely valuable it lives in an extension
  function outside the public core contract.
- Two builder styles must be generated and kept in sync: Kotlin trailing-lambda receivers and Java
  `Function`-based builders. Both are generated from the same IR, so they cannot drift silently.
- The ABI dump becomes doubly useful: it is both the compatibility record and the input to the
  Java-purity check.

## Alternatives considered

- **A separate `volan-java` adapter module maintained by hand.** Drifts from the Kotlin API within one
  release; doubles the review burden.
- **Kotlin-only, "Java works if you try".** Rejected: it contradicts the project's stated mission.
