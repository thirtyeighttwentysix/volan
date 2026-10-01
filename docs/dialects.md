# Database support

`0.1.0-alpha.2` supports PostgreSQL and SQLite, including runtime operations and migrations.
MySQL, MariaDB and H2 modules are placeholders, not usable dialects.

| Capability | PostgreSQL | SQLite | MySQL / MariaDB / H2 |
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
| DDL, pull, push, migration journal, drift detection | Yes | Yes, ordinary main tables | Planned |
| Typed full-text search | Planned | Planned; @@fulltext rejected | Planned |

## SQLite setup

Use `provider = "sqlite"` in the datasource and a JDBC URL such as `jdbc:sqlite:./data.db` or
`jdbc:sqlite::memory:`. URLs beginning with `file:` alone are not JDBC URLs. Include both
`volan-dialect-sqlite` and the Xerial SQLite JDBC driver in the application's runtime classpath.
The dialect is discovered through `ServiceLoader`.

SQLite is available from alpha.2. Use Maven Central:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:0.1.0-alpha.2"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-sqlite")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
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

Create the tables before using repositories. The CLI supports SQLite:

```shell
volan db push --schema schema.volan --url jdbc:sqlite:./data.db --dry-run
volan db push --schema schema.volan --url jdbc:sqlite:./data.db
volan db pull --url jdbc:sqlite:./data.db --stdout
```

The library entry point is `DatabaseSync(SqliteReader(), SqliteDialect)`, using a dedicated JDBC
connection with auto-commit enabled. Review `sync.plan(connection, schema).toSql(SqliteDialect)` before
applying it with `sync.push`, or write that SQL to a `MigrationDirectory` and deploy it with `Migrator`.
[Migration setup and guarantees →](migrations.md#sqlite-migrations)

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
| Int, Long | INTEGER / BIGINT; autoincrement requires a single-column INTEGER PRIMARY KEY |
| Float, Double | FLOAT / DOUBLE declarations with REAL affinity; approximate binary floating point |
| Boolean | BOOLEAN declaration; stored as INTEGER, 0 or 1 |
| String, enum, Json | TEXT / JSON TEXT; enums use their mapped database values |
| DateTime | TIMESTAMP TEXT, UTC ISO-8601 with nine fractional digits, e.g. `2026-10-01T10:20:30.123456789Z` |
| Date, Time | DATE TEXT / TIME TEXT, ISO dates and local times |
| Uuid | UUID TEXT, canonical UUID string |
| Bytes | BLOB |

Keep external writes and SQL defaults consistent with these conventions. SQLite's native timestamp
strings or integer epochs are not Volan's DateTime storage. For `@default(now())`, a matching SQL
default is `strftime('%Y-%m-%dT%H:%M:%f000000Z', 'now')`; SQLite supplies millisecond precision while
application timestamps retain nanoseconds. `@updatedAt` is supplied by the runtime. Other schema
defaults are generated by Volan's DDL, including `uuid()`. For manually managed tables, supply
equivalent defaults or provide values in the application.

The declarations keep logical scalar types recognizable during pull without changing their storage
affinity. A Long autoincrement column must use INTEGER; Volan retains its Long type in a declaration
comment. Keep that comment when editing the table SQL. SQLite stores enums as TEXT, so pull exports
those fields as String; the original schema still generates enum-typed clients.

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
