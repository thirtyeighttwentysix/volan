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
| **M5** | Relations, nested writes and summaries: arbitrary `include`/`select` nesting, batched loading, implicit and explicit N:M, `aggregate`, `groupBy`/`having`, `distinct` | Statement-count assertions prove the absence of N+1 | 🚧 |
| **M6** | Migrations: introspection, diff, SQL generation, journal, checksums, drift detection, `db pull` / `db push` | Round-trip test: schema → migration → database → introspection → schema | ⬜ |
| **M7** | Java-facing API: generated Java-friendly layer, `*Async`, JSpecify nullability | `:java-compat-tests` green; signature check finds no Kotlin-only types in public API | ⬜ |
| **M8** | Dialects: MySQL, MariaDB, SQLite, H2 + feature-support matrix in the docs | The same integration suite passes on every dialect | ⬜ |
| **M9** | CLI and build plugins: Clikt CLI, Gradle plugin, Maven plugin | An example project builds through the plugin alone, with no manual steps | ⬜ |
| **M10** | Coroutines, interceptors, Micrometer metrics | `suspend` API covered by tests; cancellation cancels the in-flight statement | ⬜ |
| **M11** | Examples and documentation site: `kotlin-basic`, `java-basic`, `spring-boot`, `ktor`; Getting Started (Kotlin/Java), references, migration guides | Every example runs from its own README and has a CI smoke test | ⬜ |
| **M12** | Benchmarks and the 1.0 release: JMH suite, Maven Central publication, changelog | Artifacts install into a clean project from a Central staging repository | ⬜ |

## Deliberately different from the original specification

Three names and one shape differ from the brief, each because the brief's version cannot be built on
the JVM without giving up something the brief also asks for.

| Brief | Volan | Why |
|---|---|---|
| `select { … }` returns `UserEmailNameProjection` | returns `UserProjection`, whose unselected fields refuse to be read | A named type per select shape means one generated type per subset of fields. Without seeing the call site — which only a compiler plugin could — the generator would have to emit all of them. Prisma gets this from TypeScript conditional types, which the JVM has no equivalent of |
| `include`d relations typed into the result | relation properties throw `VolanRelationNotLoadedException` naming the query to change | Same reason, made worse by nesting: the type of an included `Post` depends on `Post`'s own includes, so the set of types is not merely exponential but unbounded. The alternative, generic slots, produces `User<List<Post<NotLoaded, NotLoaded>>, NotLoaded>` in Java signatures, which contradicts ADR-0006 |
| `in`, `notIn` | `oneOf`, `notOneOf` | `in` is a hard keyword in Kotlin; `` `in` `` at every call site is worse than a different word |
| `is`, `isNot` on to-one relation filters | `matches`, `notMatches` | Same reason |

## Deliberately deferred

Items below are **not** in 1.0. They are listed so that nothing has to be left half-finished in the
main branch.

### Deferred within the road to 1.0

- **Coverage gate.** The ≥ 85 % Kover verification rule is on for `volan-schema` and `volan-ir` as of
  M2. It is added to `volan-runtime` and `volan-migrate` in the milestone that fills them, because
  enforcing a coverage floor on an empty module measures nothing.
- **Provider-specific native types.** `@db.…` is parsed, validated for shape and carried into the IR,
  but whether `@db.VarChar(200)` exists for the configured database is a question only a dialect can
  answer. That check lands with the dialects in M8.
- **Nested writes from an update.** A `create` can write, attach, or find-or-write the rows on the far
  side of any of its relations, in one transaction. The operations that only make sense against rows
  that already exist — `disconnect`, `set`, nested `update` and nested `delete` — are written from an
  `update`, which is what remains of M5.
- **A field named `count`.** The result of `aggregate` and of `groupBy` reads the row count as `count`,
  so a model with a scalar field of that name generates two properties with one name and the generated
  code does not compile. The generator should reject the schema with a diagnostic instead; until it
  does, the failure is loud but points at generated code rather than at the schema.
- **Writing a grandchild that needs its grandparent's key.** A nested write supplies the foreign key of
  the row it is nested under, so a shape reaching two levels down works whenever the deeper row's other
  required columns are already known. A composite key that needs a key from two levels up — a comment
  needing both its post and its author while both are being written — cannot be expressed yet, and
  fails with the missing column named rather than writing half a shape.
- **Cursors combined with an explicit `orderBy`.** Resuming after a row requires knowing that row's
  position in that order, which the key alone does not give. A cursor on its own pages by primary key;
  combining the two is refused with an explanation until keyset paging over arbitrary orderings lands.
- **Reading a written row back without `RETURNING`.** PostgreSQL has it, so `create`, `update` and
  `delete` read the row back in one statement. The follow-up-select fallback the other databases need
  arrives with them in M8.
- **Filters and ordering on list columns.** A `String[]` column is read and written, but has no filter
  handle: what `contains` means for an array is a dialect question, answered in M8.
- **The Java-facing layer.** Generated entities are already Java-shaped — getters, builders, no Kotlin-only
  types — but the `*Async` methods and the `Function`-based builders are M7.
- **Cycles of required relations across models.** A self-relation that requires itself is rejected in
  M2. Two models that require each other are not yet detected; the check needs the same traversal as
  the cascade-cycle pass and is scheduled with the migration ordering work in M6.
- **Publishing configuration.** Signing, POM metadata and the Central Portal release job land in M12.
- **`volan-cli`, `volan-gradle-plugin`, `volan-maven-plugin`, `java-compat-tests` modules.** Created
  in the milestone that first fills them (M9 / M7), so that the main branch never contains an empty
  module pretending to be a feature.
- **The `volan format` and `volan validate` commands.** Both capabilities exist as library API from
  M1 (`SchemaFormatter` and `SchemaParser`, which reports every syntax problem); wrapping them in a
  command line is part of M9, where the CLI is built. `validate` gains semantic checks in M2.

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
