# Database support

The released `0.1.0-alpha.1` supports PostgreSQL. SQLite runtime support is on `main`, targeting
the next alpha. MySQL, MariaDB and H2 modules are placeholders, not usable dialects.

| Capability | PostgreSQL | SQLite on main | MySQL / MariaDB / H2 |
|---|---|---|---|
| Generated Kotlin and Java clients | Yes | Yes | Planned |
| CRUD, upsert, bulk writes, raw SQL | Yes | Yes | Planned |
| Relations, batched includes, nested writes | Yes | Yes | Planned |
| Aggregate, groupBy, having | Yes | Yes, supported numeric types | Planned |
| Composite keys and cursors | Yes | Yes | Planned |
| Transactions, savepoints, async operations | Yes | Yes | Planned |
| Distinct | DISTINCT ON | Selected distinct columns only | Planned |
| Scalar arrays | Yes | Rejected during schema analysis | Planned |
| Decimal | Yes | Rejected during schema analysis | Planned |
| Provider-specific @db types | Yes | Rejected during schema analysis | Planned |
| Pull, push, migration journal, drift detection | Yes | Planned | Planned |
| Typed full-text search | Planned | Planned; @@fulltext rejected | Planned |

## SQLite setup

Use `provider = "sqlite"` in the datasource and a JDBC URL such as `jdbc:sqlite:./data.db` or
`jdbc:sqlite::memory:`. URLs beginning with `file:` alone are not JDBC URLs. Include both
`volan-dialect-sqlite` and the Xerial SQLite JDBC driver in the application's runtime classpath.
The dialect is discovered through `ServiceLoader`.

SQLite is not available in alpha.1. To consume the next candidate locally, stage it from this checkout:

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=0.1.0-alpha.2 -PvolanUnsignedLocalPublication --no-configuration-cache
```

Then point the consumer at `build/release-repository` and use:

```kotlin
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:0.1.0-alpha.2"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-sqlite")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.2.1")
}
```

Generate a client as shown in [codegen-verify](../codegen-verify/) or the
[independent consumer](../release-smoke/). The generated client has the same API:

```kotlin
VolanClient.builder().url("jdbc:sqlite:./data.db").build().use { db ->
    val user = db.user.create { email = "player@example.org" }
    db.user.findUnique { where { id eq user.id } }
}
```

Create tables with reviewed SQL before using repositories. SQLite DDL generation and introspection
are not implemented, so `db push`, `db pull` and Volan-managed SQLite migrations are unavailable.
The [integration fixture](../codegen-verify/src/test/resources/sqlite-schema.sql) demonstrates matching
tables, defaults, mapped enums, foreign keys and implicit join tables for the
[test schema](../codegen-verify/schema/sqlite.volan).

## SQLite storage and behavior

Use SQLite 3.35 or newer: writes read their rows back through
[RETURNING](https://www.sqlite.org/lang_returning.html). The tested JDBC driver bundles SQLite 3.53.
Bulk writes omit unspecified columns, preserving database defaults. Consecutive rows with the same
supplied columns are batched with at most 999 parameters, and every batch belongs to one transaction.

Volan's SQLite pool uses one connection, even if a larger `maxPoolSize` is requested. This keeps a
private in-memory database alive and serializes work within that pool. Separate clients can still
contend for the same file. Automatic connection retirement is disabled so it cannot destroy a private
memory database during the client's lifetime. An externally supplied `DataSource` owns its connection count and lifetime;
use a single persistent connection for private in-memory databases.

Foreign keys are enabled and checked on every borrowed connection. Supply connections outside an
active transaction: SQLite cannot enable foreign keys after a transaction has started. Constraint
failures use the same Volan exception classes as PostgreSQL. Busy/locked failures are translated as
serialization failures; an explicit transaction retry policy can retry the entire block. Isolation
defaults to SQLite's setting; `SERIALIZABLE` and `READ_UNCOMMITTED` are the JDBC driver's supported
explicit levels. Async operations use the configured executor and still perform blocking JDBC work.

| Schema type | SQLite storage convention |
|---|---|
| Int, Long | INTEGER; autoincrement requires a single-column INTEGER PRIMARY KEY |
| Float, Double | REAL; approximate binary floating point |
| Boolean | INTEGER, 0 or 1 |
| String, enum, Json | TEXT; enums use their mapped database values |
| DateTime | TEXT, UTC ISO-8601 with nine fractional digits, e.g. `2026-10-01T10:20:30.123456789Z` |
| Date, Time | TEXT, ISO dates and local times |
| Uuid | TEXT, canonical UUID string |
| Bytes | BLOB |

Keep external writes and SQL defaults consistent with these conventions. SQLite's native timestamp
strings or integer epochs are not Volan's DateTime storage. For `@default(now())`, a matching SQL
default is `strftime('%Y-%m-%dT%H:%M:%f000000Z', 'now')`; SQLite supplies millisecond precision while
application timestamps retain nanoseconds. `@updatedAt` is supplied by the runtime. Other schema
defaults must also be implemented in the manually managed table or supplied by the application.

Decimal is rejected because storing it as REAL loses precision, while storing it as TEXT changes
numeric filters and ordering. Use a scaled Long for exact amounts. Arrays, native `@db` overrides,
`@@fulltext` and invalid autoincrement shapes likewise produce E0235 before generation. Manage
specialized FTS tables explicitly with raw SQL.

Case-sensitive string searches use escaped GLOB patterns; case-insensitive searches use LOWER/LIKE.
SQLite's built-in case conversion handles ASCII, not full Unicode case folding. Wildcards in search
values are escaped and all values are bound parameters.

SQLite cannot choose an arbitrary representative entity for DISTINCT ON. Use
`projectMany { select { field }; distinct { field } }`, selecting exactly the distinct fields, or
`groupBy`. A distinct query selecting additional entity fields fails with an explanation.

## Verification

`codegen-verify` generates and compiles a SQLite client, then exercises a file database, reopening,
private memory databases, heterogeneous bulk inserts, relations, composite cursors, filters, summaries,
supported scalars, asynchronous calls, savepoints, rollback and constraints. These tests run on Linux,
macOS and Windows without Docker. PostgreSQL integration tests use Testcontainers on Docker-enabled
runners. The release consumer separately verifies SQLite discovery from the published JAR and BOM.
