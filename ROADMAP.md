# Roadmap

Status legend: ✅ done · 🚧 in progress · ⬜ not started

## Road to 1.0

| Milestone | Scope | Definition of done | Status |
|---|---|---|---|
| **M0** | Project skeleton: multi-module Gradle build, version catalog, ktlint/detekt/Kover/ABI validation, CI, licence, docs skeleton | `./gradlew build` green | ✅ |
| **M1** | Schema language: lexer, parser, AST, Rust-style diagnostics, formatter | The reference schema parses; 34 negative fixtures assert exact diagnostics | ✅ |
| **M2** | IR: name resolution, type checking, relation pairing, composite keys, cycle detection | IR snapshot tests | ✅ |
| **M3** | Kotlin code generation: entities, repositories, where/orderBy/select/include DSLs, projections, plus the query description layer they compile against | Golden-file tests, and `codegen-verify` generates a client during the build, compiles it and exercises it | ✅ |
| **M4** | Runtime + PostgreSQL: query planning, SQL rendering, mapping, pooling, transactions, full CRUD and filters, raw SQL | Testcontainers PostgreSQL integration suite covering every must-have operation | ✅ |
| **M5** | Relations, nested writes and summaries: arbitrary `include`/`select` nesting, batched loading, implicit and explicit N:M, nested writes from `create` and `update`, `aggregate`, `groupBy`/`having`, `distinct` | Statement-count assertions prove the absence of N+1 | ✅ |
| **M6** | Migrations: introspection, diff, SQL generation, journal, checksums, drift detection, `db pull` / `db push` | Round-trip test: schema → migration → database → introspection → schema | ✅ |
| **M7** | Java-facing API: generated Java-friendly layer, `*Async`, JSpecify nullability | `:java-compat-tests` green; Java-visible generated signatures and runtime ABI checked (compiler enum accessors excluded) | ✅ |
| **M8** | Dialects: MySQL, MariaDB, SQLite, H2 + feature-support matrix in the docs | The common integration suite passes on all five databases; supported differences documented | ✅ |
| **M9** | CLI and build plugins: Clikt CLI, Gradle plugin, Maven plugin | An example project builds through the plugin alone, with no manual steps | ✅ |
| **M10** | Coroutines, interceptors, Micrometer metrics | `suspend` API covered by tests; cancellation cancels the in-flight statement | ✅ |
| **M11** | Examples and documentation site: `kotlin-basic`, `java-basic`, `spring-boot`, `ktor`; Getting Started (Kotlin/Java), references, migration guides | Every example runs from its own README and has a CI smoke test | ✅ |
| **M12** | Benchmark extensions and the 1.0 release: broader workloads, Maven Central publication, changelog | Artifacts install into a clean project from staging and Maven Central | ✅ |

## Deliberately different from the original specification

The initial PostgreSQL JMH read suite was brought forward from M12 alongside M6. Maven Central
publication followed M7 with `0.1.0-alpha.1`; the 1.0 release and remaining benchmark workloads stay
in M12. The initial CLI was also brought forward from M9
for `db pull` and `db push`.

Three names and one shape differ from the brief, each because the brief's version cannot be built on
the JVM without giving up something the brief also asks for.

| Brief | Volan | Why |
|---|---|---|
| `select { … }` returns `UserEmailNameProjection` | returns `UserProjection`, whose unselected fields refuse to be read | A named type per select shape means one generated type per subset of fields. Without seeing the call site — which only a compiler plugin could — the generator would have to emit all of them. Prisma gets this from TypeScript conditional types, which the JVM has no equivalent of |
| `include`d relations typed into the result | relation properties throw `VolanRelationNotLoadedException` naming the query to change | Same reason, made worse by nesting: the type of an included `Post` depends on `Post`'s own includes, so the set of types is not merely exponential but unbounded. The alternative, generic slots, produces `User<List<Post<NotLoaded, NotLoaded>>, NotLoaded>` in Java signatures, which contradicts ADR-0006 |
| `in`, `notIn` | `oneOf`, `notOneOf` | `in` is a hard keyword in Kotlin; `` `in` `` at every call site is worse than a different word |
| `is`, `isNot` on to-one relation filters | `matches`, `notMatches` | Same reason |

## M8 progress

- SQLite runtime: provider discovery, CRUD, heterogeneous bulk writes with atomic batching, relations,
  nested writes, summaries, composite cursors, async operations and savepoint transactions are tested
  against real SQLite on every CI OS. [Feature matrix and limitations](docs/dialects.md).
- SQLite publication and its BOM entry are included in alpha.2; alpha.1 remains PostgreSQL-only.
- SQLite schema management: DDL, strict introspection, pull/push, drift detection and migration journals
  are tested. Table rebuilds retain rows, indexes and AUTOINCREMENT history; concurrent writers serialize.
- H2 runtime and initial DDL: generated clients, scalar arrays, Decimal, relations, nested writes,
  summaries, DISTINCT ON, cursors, async operations and transactions run against real H2 on every CI OS.
  Its Maven module and BOM entry ship in 1.0.0; alpha.2 does not include H2.
- H2 schema inspection: strict current-schema introspection, `db pull`, structural drift and SQL plans
  (`db push --dry-run`) are tested, including scalar arrays, composite foreign keys and identity columns.
- H2 versioned migrations: administrator connections acquire exclusive access across DDL commits.
  Durable start records and acknowledged statement counts survive failure; unfinished migrations block
  replay until manually repaired and marked applied with the original checksum. File reopening,
  concurrency and interrupted progress are tested.
- H2 automatic push verifies the resulting schema and journals partial DDL in `_volan_push`.
  Manual repair must match the original target before `db push --resolve` clears the block.
- MySQL 8.4 and MariaDB 11.4: provider discovery, generated CRUD, row read-back without RETURNING,
  relations, nested writes, summaries, cursors, savepoints and async operations pass the shared suite.
  Strict InnoDB introspection, pull/push, full-text index DDL, structural drift and versioned migrations
  use named session locks and durable progress. Unsupported catalogue shapes are refused.
- H2 and the shared MySQL/MariaDB dialect module are included in the next release BOM and tested
  by an independent Java consumer of staged Maven artifacts. M8 is complete.

## M9 progress

- Clikt commands `init`, `generate`, `format` / `format --check` and semantic `validate`, with source
  diagnostics and database-independent generation. Existing `db pull` / `db push` remain explicit.
- Generated-source manifests remove deleted models and renamed packages, retain unrelated files
  and refuse filesystem collisions, escaping paths and links inside the generated output.
- Gradle Kotlin/JVM main generation is wired into compilation, adds the matching runtime and supports
  incremental builds, build cache and configuration cache. Plugin marker and implementation are staged
  together for Maven Central; no Plugin Portal publication is required.
- Maven generates during `generate-sources` and registers its sources for the ordinary Kotlin compiler;
  Java consumers are exercised with incremental compilation, including deleted-model bytecode cleanup.
- Independent Gradle Kotlin/Java and Maven Java examples build through the plugins without generator
  programs or manual source-directory/task wiring. CI and release workflows verify staged publications
  and ordinary builds, schema changes, source/bytecode removal and cache reuse. [Setup](docs/build-plugins.md).
- M9 is complete. Build plugins ship in 1.0.0; alpha.2 does not contain them.

## M10 progress

- Optional published `volan-coroutines` adds typed `suspendQuery` access to every generated client
  operation and a `SuspendingQueryExecutor` for the description API. Blocking JDBC runs on a bounded
  IO dispatcher; whole transactions stay on one worker with synchronous callbacks and savepoints.
- Cancellation skips queued callbacks, calls `Statement.cancel()` for active JDBC work, prevents
  subsequent statements and rolls back before commit. Coroutine completion awaits worker cleanup;
  Java futures request the same JDBC cancellation but become cancelled immediately.
- Tests cancel long-running queries on PostgreSQL, SQLite, H2, MySQL and MariaDB, verify rollback and
  reuse of the only pooled connection. Unit tests cover queued work, cancellation/close races,
  worker reuse, failure propagation and refusal of coroutine dispatch from a transaction.
- Thread-safe statement interceptors wrap checkout, execution, mapping and cleanup for generated SQL,
  raw SQL, relation reads and generated-key writes. Registration order is deterministic; duplicate
  downstream execution and cross-thread invocation are refused.
- Optional published `volan-micrometer` records attempted-statement count and duration with bounded
  dialect, operation and outcome tags, excluding SQL and parameters. Both optional modules are BOM
  constraints and are exercised by an independent Gradle consumer of staged Maven publications.
  M10 is complete. These APIs ship in 1.0.0; alpha.2 does not contain them.
  [Usage, cancellation guarantees and limits](docs/coroutines-and-observability.md).

## M11 progress

- Russian and English versioned documentation is published on [Mintlify](https://volan.mintlify.app).
- Four independent [example projects](examples/README.md) use the build plugin, committed migrations
  and generated clients: Kotlin, Java, Spring Boot client ownership and Ktor coroutine request handling.
  CI and release verification build their copies against staged artifacts; tag releases repeat the
  examples against Central downloads.
- M11 is complete. All four examples passed locally against staged alpha.3 artifacts and in the
  CI consumer job. The published alpha.2 guides remain available separately from development APIs.

## M12 progress

- The benchmark suite now has 40 PostgreSQL cases: one/four-thread reads, range updates and
  insert/delete transactions for 1/100 rows, across Volan, Hibernate, Exposed, jOOQ and JDBC.
  Shared-pool concurrency and write effects are verified before timing and by a CI correctness job.
- Reports validate complete suites, consistent JVM/settings and publishable measurements;
  machine metadata records source and results digests, dependency versions and the Docker image.
- The [5 October measurements](benchmarks/results/jmh-2026-10-05.json) are reflected in README
  tables and four charts. Read latency is higher for Volan in this run; wide overlapping write
  intervals do not support a reliable write ranking. The original September results remain archived.
- Release rehearsals accept an explicit candidate version and validate seventeen Maven artifacts,
  independent consumers and build plugins without publishing. Successful rehearsals retain the
  unsigned repository as a downloadable CI artifact. Tags require release notes and a dated changelog.
- 1.0.0 is published to Maven Central. The [publication verification](https://github.com/thirtyeighttwentysix/volan/actions/runs/37348445496) verified all seventeen artifacts and clean consumers, both plugins and all four examples from actual Central downloads. CLI archives are attached to the GitHub release. M12 is complete.

## Deliberately deferred

Items below are **not** in 1.0. They are listed so that nothing has to be left half-finished in the
main branch.

### Deferred within the road to 1.0

- **Coverage gate.** The ≥ 85 % Kover verification rule is on for `volan-schema`, `volan-ir` and
  `volan-migrate` (M6, with PostgreSQL integration tests). The runtime coverage gate remains deferred.
- **Provider-specific native types beyond PostgreSQL.** `@db.…` is checked against the types
  PostgreSQL actually has, and a name it does not have is refused with the ones it does listed. The
  other dialects deliberately reject native overrides for now; custom sizes need reviewed SQL.
- **Reading back a database Volan did not create.** Introspection understands the shapes Volan writes.
  An index built from an expression Volan would not have written is reported as something it cannot
  describe, rather than being quietly dropped from the schema it reads — but that does mean a database
  with hand-written expression indexes cannot yet be introspected at all.
- **Deleting the row a required foreign key points at.** An `update` can detach, replace, change and
  delete the rows on the far side of its relations, except for one case: deleting the row that the row
  being changed points at. That needs the old key after the key has been cleared, and what it should
  do depends on `onDelete`. M6 now generates and verifies those actions; runtime support for deleting
  an owning-side relation remains deferred.
- **Nested writes more than one level deep from an update.** A nested `update` writes columns; a shape
  that reaches a third level down would need the key of a row nobody has read yet. It is refused where
  it was written rather than silently dropped.
- **A field named `count`.** The result of `aggregate` and of `groupBy` reads the row count as `count`,
  so a model with a scalar field of that name generates two properties with one name and the generated
  code does not compile. The generator should reject the schema with a diagnostic instead; until it
  does, the failure is loud but points at generated code rather than at the schema.
- **Kotlin keyword field names.** Some generated expression fragments do not escape names such as
  `when`. Use a different field name with `@map("when")` until the generator handles these consistently.
- **Writing a grandchild that needs its grandparent's key.** A nested write supplies the foreign key of
  the row it is nested under, so a shape reaching two levels down works whenever the deeper row's other
  required columns are already known. A composite key that needs a key from two levels up — a comment
  needing both its post and its author while both are being written — cannot be expressed yet, and
  fails with the missing column named rather than writing half a shape.
- **Cursors combined with an explicit `orderBy`.** Resuming after a row requires knowing that row's
  position in that order, which the key alone does not give. A cursor on its own pages by primary key;
  combining the two is refused with an explanation until keyset paging over arbitrary orderings lands.
- **Filters and ordering on list columns.** A `String[]` column is read and written, but has no filter
  handle. Array filter semantics remain deferred; PostgreSQL and H2 can store arrays.

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
