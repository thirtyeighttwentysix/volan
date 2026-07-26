# Volan architecture

Volan is a **schema-first, code-generating ORM for the JVM**. You describe your data model once in
`schema.volan`; Volan generates a fully type-safe Kotlin client (plus a Java-facing layer) and keeps
your database in sync through declarative migrations.

This document describes how the pieces fit together. Individual decisions and their trade-offs are
recorded as [Architecture Decision Records](docs/adr/).

---

## 1. Goals and non-goals

### Goals

1. **Prisma-grade DX on the JVM.** A single, readable schema file; one command to generate a client;
   one command to migrate. Autocomplete and compiler errors instead of runtime surprises.
2. **Type safety without reflection.** Everything the compiler can prove, the compiler proves. The
   result-mapping hot path contains no reflection, no annotation scanning and no proxies.
3. **First-class Java.** The Java API is designed, not accidental. Every scenario expressible in
   Kotlin is expressible in idiomatic Java (see [ADR-0006](docs/adr/0006-java-facing-api.md)).
4. **Predictable SQL.** Every query the user writes maps to SQL they could have written themselves.
   No implicit lazy loading, no session-attached entities, no surprise N+1.
5. **Boring, auditable runtime.** Plain JDBC, plain connection pool, plain prepared statements,
   always parameterized.

### Non-goals

- Being a general-purpose SQL query builder. Volan targets the 95 % of application queries that are
  CRUD-with-relations; for the rest there is typed raw SQL.
- A stateful persistence context / first-level cache / dirty checking (the JPA model). Volan entities
  are immutable data carriers, not managed proxies.
- Runtime schema discovery. The schema is a build-time input, not something read from the database at
  startup.

---

## 2. The two pipelines

Volan has a **build-time pipeline** (schema → generated sources) and a **runtime pipeline**
(generated call → SQL → objects). They meet only through generated code and a small stable runtime
SPI, which is what keeps reflection out of the hot path.

### 2.1 Build-time pipeline

```
schema.volan
   │
   ▼  volan-schema      Lexer → Parser → AST (+ source spans on every node)
 AST
   │
   ▼  volan-ir          Name resolution, type checking, relation pairing,
 IR                     attribute validation, defaults, naming (@map) resolution
   │
   ├──────────────▶  volan-codegen   KotlinPoet → entity types, repositories,
   │                                 where/orderBy/select/include DSLs, projections,
   │                                 Java-facing façade, metadata tables
   │
   └──────────────▶  volan-migrate   IR ⟷ DatabaseSchema diff → ordered DDL steps
```

The AST keeps **exact source positions** for every token, which is what makes Rust-style diagnostics
possible (`code frame` + caret + explanation). The IR is deliberately a *different* data structure:
the AST mirrors the file, the IR mirrors the resolved model (both directions of every relation are
linked, `@map` names are resolved, defaults are typed).

### 2.2 Runtime pipeline

```
db.user.findMany { … }            generated, fully typed
   │
   ▼  builds a QuerySpec           immutable, dialect-independent description
 QuerySpec
   │
   ▼  volan-runtime planner        splits into a root query + batched relation loads
 QueryPlan
   │
   ▼  volan-dialect-*              renders SQL text + ordered parameter list
 SqlStatement
   │
   ▼  volan-runtime executor       JDBC: prepare → bind → execute
 ResultSet
   │
   ▼  generated RowMapper          positional reads, no reflection
 List<User>
```

`QuerySpec` never contains SQL strings and never contains user values inlined into text: values live
in a parameter list from the moment they enter the DSL until `PreparedStatement.setObject`. That is
the structural reason Volan cannot be SQL-injected (see [ADR-0004](docs/adr/0004-query-ir-and-sql-rendering.md)).

---

## 3. Modules

| Module | Depends on | Responsibility |
|---|---|---|
| `volan-schema` | — | Lexer, recursive-descent parser, AST, diagnostics, formatter |
| `volan-ir` | `schema` | Semantic analysis, validation, normalized IR |
| `volan-codegen` | `ir` | KotlinPoet generation of the client and the Java-facing layer |
| `volan-dialect-api` | — | SQL model, `Dialect` SPI, type mapping SPI, capability flags |
| `volan-dialect-postgres` | `dialect-api` | PostgreSQL rendering, native types, arrays, `RETURNING`, upsert |
| `volan-dialect-mysql` | `dialect-api` | MySQL and MariaDB rendering |
| `volan-dialect-sqlite` | `dialect-api` | SQLite rendering |
| `volan-dialect-h2` | `dialect-api` | H2 rendering (used heavily in fast tests) |
| `volan-runtime` | `dialect-api` | Query planning, execution, mapping, transactions, pooling, interceptors |
| `volan-migrate` | `ir`, `dialect-api`, `runtime` | Introspection, diff, migration files, journal, drift detection |
| `volan-coroutines` | `runtime` | `suspend` façade over the synchronous core |
| `volan-cli` | all of the above | `volan` command line (M9) |
| `volan-gradle-plugin` | `codegen` | `volanGenerate` task wired into compilation (M9) |
| `volan-maven-plugin` | `codegen` | `volan:generate` on `generate-sources` (M9) |
| `volan-bom` | — | Version alignment for consumers |
| `java-compat-tests` | generated client | Java-language tests over the public API (M7) |

Dependency rules enforced by review:

- Nothing depends on `volan-codegen` at runtime. Generated code depends only on `volan-runtime`.
- `volan-runtime` never depends on a concrete dialect; dialects are discovered from the JDBC URL and
  loaded through the `Dialect` SPI.
- `volan-coroutines` is a thin wrapper. The core is synchronous
  (see [ADR-0003](docs/adr/0003-sync-core-coroutine-wrapper.md)); this direction is not reversible
  without making blocking JDBC calls leak into coroutine dispatchers uncontrolled.

---

## 4. Generated code

For a model `User`, Volan generates:

| Artifact | Purpose |
|---|---|
| `User` | Immutable entity: `data class` in Kotlin, with getters/`equals`/`hashCode`/`toString`, a builder and Jackson compatibility for Java |
| `UserRepository` | `create/findMany/update/…`, exposed as `db.user` |
| `UserWhere`, `UserOrderBy`, `UserSelect`, `UserInclude` | Scoped DSL receivers |
| `User<Fields>Projection` | One projection type per distinct `select { … }` shape |
| `UserRowMapper` | Positional `ResultSet` → entity mapping |
| `UserTable` (`User_` for Java) | Column metadata constants used by the planner and by Java-side sorting/filters |
| `UserJava` façade methods on `db.user()` | `Function`-based builders and `*Async` variants |

Generated sources are written to the configured `output` directory and registered as a source set by
the build plugin. They are **not** meant to be edited or checked in.

---

## 5. Relation loading

`include` never produces one query per parent row. The planner groups a result page by relation and
issues **one batched query per relation level**:

```
SELECT … FROM users WHERE … LIMIT 20            -- root
SELECT … FROM posts WHERE author_id IN (?,…,?)  -- one query for all 20 users
SELECT … FROM tags  WHERE post_id  IN (?,…,?)   -- one query for all fetched posts
```

A `JOIN` strategy is available per relation for the cases where a single round trip matters more than
row duplication. Rationale and the exact selection rules are in
[ADR-0005](docs/adr/0005-relation-loading-strategy.md). Tests assert the executed statement count, so
an accidental N+1 fails the build.

---

## 6. Error model

```
VolanException
├── VolanSchemaException          parse / validation problems (build time)
├── VolanConfigurationException   bad datasource URL, missing generator options
├── VolanNotFoundException        findUniqueOrThrow / update on a missing row
├── VolanUniqueConstraintException
├── VolanForeignKeyException
├── VolanCheckConstraintException
├── VolanConnectionException      pool exhausted, network failure
├── VolanTimeoutException
├── VolanTransactionException     serialization failure, deadlock, rollback-only
└── VolanRawSqlException
```

Vendor `SQLState` codes are translated to this hierarchy by the dialect, so application code catches
the same exception type on PostgreSQL and MySQL. Every message names the model, the field and — where
possible — the fix. Schema diagnostics render as:

```
error: unknown field `authorID` referenced in @relation
  ┌─ schema.volan:24:32
  │
24│   author   User @relation(fields: [authorID], references: [id])
  │                                    ^^^^^^^^ no such field on model `Post`
  │
  = help: did you mean `authorId`?
```

---

## 7. Concurrency and transactions

- A `Volan` instance owns a HikariCP pool and is thread-safe; it is meant to be a singleton.
- A transaction pins exactly one connection for its duration; the transactional `tx` receiver is
  confined to the transaction block and is not safe to leak out of it.
- Nested `transaction { }` calls map to `SAVEPOINT`s rather than to new connections.
- Serialization failures and deadlocks are retried with bounded exponential backoff when the block is
  declared retryable; the retry policy is configurable and off by default for non-idempotent blocks.
- `volan-coroutines` dispatches blocking JDBC work to a bounded `Dispatchers.IO` view; cancellation
  cancels the in-flight statement (`Statement.cancel`) and releases the connection.

---

## 8. Extension points

| SPI | Used for |
|---|---|
| `Dialect` | Adding a database backend |
| `TypeCodec<T, J>` | Custom column ↔ Kotlin type conversion (e.g. a domain wrapper over `Json`) |
| `QueryInterceptor` | Logging, tracing, metrics, soft delete, multi-tenancy filters |
| `NamingStrategy` | Default table/column naming when `@map` is absent |
| `ConnectionProvider` | Replacing HikariCP with an externally managed `DataSource` |

---

## 9. Testing strategy

| Layer | Approach |
|---|---|
| Parser | Golden AST snapshots + ~30 negative fixtures asserting exact diagnostics; Kotest property tests for round-tripping `format` |
| IR | Snapshot tests of the normalized model |
| Codegen | Golden-file tests over generated sources **plus** a compilation test that actually compiles the output |
| SQL rendering | Golden SQL per dialect for a fixed catalogue of `QuerySpec`s |
| Runtime | H2/SQLite in-memory for fast feedback; Testcontainers PostgreSQL/MySQL/MariaDB for the real matrix |
| Migrations | Round-trip: schema → migration → database → introspection → schema, asserted equal |
| Java API | `java-compat-tests`, written in Java, plus a signature check that fails on Kotlin-only types |
| Performance | JMH against JOOQ/Exposed/Hibernate/JDBI, results tracked in `benchmarks/RESULTS.md` |

---

## 10. Repository layout

```
volan/
├── build-logic/            convention plugins (single source of build truth)
├── config/detekt/          static analysis configuration
├── gradle/libs.versions.toml
├── volan-*/                the modules from §3
│   └── api/*.api           checked-in public ABI dumps
├── docs/
│   ├── adr/                architecture decision records
│   └── site/               documentation site sources (M11)
├── examples/               kotlin-basic, java-basic, spring-boot, ktor (M11)
├── benchmarks/             JMH suite (M12)
└── .github/workflows/      CI
```

---

## 11. Toolchain

| Item | Choice | Why |
|---|---|---|
| Language | Kotlin 2.4.x | Coroutines, expressive DSLs, `explicitApi()` |
| Bytecode target | JVM 17 | Long-term-support floor; compiled with `-release 17` so a newer JDK cannot leak newer APIs in |
| Build | Gradle 9.x, Kotlin DSL, version catalog, configuration cache | Reproducible, incremental, plugin-friendly |
| Public API control | `explicitApi()` + Kotlin ABI validation (`apiCheck`) | A dump of the public ABI is checked in; unintended API changes fail CI |
| Static analysis | ktlint + detekt (`TODO`/`FIXME` are build errors) | Enforces the "no stubs in main" rule mechanically |
| Coverage | Kover | ≥ 85 % on `runtime`, `ir`, `migrate` |
