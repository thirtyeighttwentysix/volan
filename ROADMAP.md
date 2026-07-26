# Roadmap

Status legend: ✅ done · 🚧 in progress · ⬜ not started

## Road to 1.0

| Milestone | Scope | Definition of done | Status |
|---|---|---|---|
| **M0** | Project skeleton: multi-module Gradle build, version catalog, ktlint/detekt/Kover/ABI validation, CI, licence, docs skeleton | `./gradlew build` green | ✅ |
| **M1** | Schema language: lexer, parser, AST, Rust-style diagnostics, `format`, `validate` | The reference schema parses; ≥ 30 negative fixtures assert exact diagnostics | 🚧 |
| **M2** | IR: name resolution, type checking, relation pairing, composite keys, cycle detection | IR snapshot tests | ⬜ |
| **M3** | Kotlin code generation: entities, repositories, where/orderBy/select/include DSLs, projections | Golden-file tests + the generated sources actually compile | ⬜ |
| **M4** | Runtime + PostgreSQL: query planning, SQL rendering, mapping, pooling, transactions, full CRUD and filters | Testcontainers PostgreSQL integration suite covering every must-have operation | ⬜ |
| **M5** | Relations and nested writes: arbitrary `include`/`select` nesting, batched loading, implicit and explicit N:M | Statement-count assertions prove the absence of N+1 | ⬜ |
| **M6** | Migrations: introspection, diff, SQL generation, journal, checksums, drift detection, `db pull` / `db push` | Round-trip test: schema → migration → database → introspection → schema | ⬜ |
| **M7** | Java-facing API: generated Java-friendly layer, `*Async`, JSpecify nullability | `:java-compat-tests` green; signature check finds no Kotlin-only types in public API | ⬜ |
| **M8** | Dialects: MySQL, MariaDB, SQLite, H2 + feature-support matrix in the docs | The same integration suite passes on every dialect | ⬜ |
| **M9** | CLI and build plugins: Clikt CLI, Gradle plugin, Maven plugin | An example project builds through the plugin alone, with no manual steps | ⬜ |
| **M10** | Coroutines, interceptors, Micrometer metrics | `suspend` API covered by tests; cancellation cancels the in-flight statement | ⬜ |
| **M11** | Examples and documentation site: `kotlin-basic`, `java-basic`, `spring-boot`, `ktor`; Getting Started (Kotlin/Java), references, migration guides | Every example runs from its own README and has a CI smoke test | ⬜ |
| **M12** | Benchmarks and the 1.0 release: JMH suite, Maven Central publication, changelog | Artifacts install into a clean project from a Central staging repository | ⬜ |

## Deliberately deferred

Items below are **not** in 1.0. They are listed so that nothing has to be left half-finished in the
main branch.

### Deferred within the road to 1.0

- **Coverage gate.** The ≥ 85 % Kover verification rule is switched on together with the first real
  runtime code in M4; enforcing it against empty modules is meaningless.
- **Publishing configuration.** Signing, POM metadata and the Central Portal release job land in M12.
- **`volan-cli`, `volan-gradle-plugin`, `volan-maven-plugin`, `java-compat-tests` modules.** Created
  in the milestone that first fills them (M9 / M7), so that the main branch never contains an empty
  module pretending to be a feature.

### Post-1.0

| Feature | Notes |
|---|---|
| Optimistic locking (`@version`) | Needs a story for retry and for conflict reporting in nested writes |
| Multi-schema support | PostgreSQL search-path and cross-schema relations |
| Read replicas | Routing policy per query, replica lag handling |
| Sharding | Depends on read replicas landing first |
| R2DBC driver | A genuinely reactive backend, not a wrapper around blocking JDBC |
| `volan studio` | Local web UI for browsing and editing data |
| Microsoft SQL Server and Oracle dialects | |
| Full-text search API | `@@fulltext` currently parses and generates the index; a typed search API comes later |
| PostGIS / geometry types | |
| GraalVM native image for the CLI | Reflection-free by design, so mostly a build-configuration task |
