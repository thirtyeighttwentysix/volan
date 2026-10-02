# Database support

`0.1.0-alpha.2` supports PostgreSQL and SQLite, including runtime operations and migrations.
H2 runtime support is on main for alpha.3. MySQL and MariaDB remain placeholders.

| Capability | PostgreSQL | SQLite | H2 on main | MySQL / MariaDB |
|---|---|---|---|---|
| Generated Kotlin and Java clients | Yes | Yes | Yes | Planned |
| CRUD, upsert, bulk writes, raw SQL | Yes | Yes | Yes | Planned |
| Relations, batched includes, nested writes | Yes | Yes | Yes | Planned |
| Aggregate, groupBy, having | Yes | Yes, supported numeric types | Yes | Planned |
| Composite keys and cursors | Yes | Yes | Yes | Planned |
| Transactions, savepoints, async operations | Yes | Yes | Yes | Planned |
| Distinct | DISTINCT ON | Selected distinct columns only | DISTINCT ON | Planned |
| Scalar arrays | Yes | Rejected during schema analysis | Yes | Planned |
| Decimal | Yes | Rejected during schema analysis | NUMERIC(65, 30) | Planned |
| Provider-specific @db types | Yes | Rejected during schema analysis | Rejected during schema analysis | Planned |
| Initial DDL and SQL change plans | Yes | Yes | Yes | Planned |
| Pull and structural drift detection | Yes | Yes, ordinary main tables | Yes, ordinary current-schema tables | Planned |
| Automatic push and migration journal | Yes | Yes | Planned | Planned |
| Typed full-text search | Planned | Planned; @@fulltext rejected | Planned; @@fulltext rejected | Planned |

## H2 setup on main

H2 is not included in alpha.2. To try the next candidate, stage the artifacts locally:

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=0.1.0-alpha.3 -PvolanUnsignedLocalPublication --no-configuration-cache
```

Point your consumer's Maven repository at `build/release-repository` and use:

```kotlin
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:0.1.0-alpha.3"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-h2")
    implementation("io.github.thirtyeighttwentysix:volan-migrate") // Schema inspection and SQL plans.
    runtimeOnly("com.h2database:h2:2.5.252")
}
```

Use `provider = "h2"` in the schema and generate the client as in [codegen-verify](../codegen-verify/).
Use `jdbc:h2:file:./data` for persistence, `jdbc:h2:mem:app` for a named in-memory database or
`jdbc:h2:mem:` for a private one. Named databases can use multiple pooled connections; private
memory pools use one connection because every connection otherwise holds a different database.
Volan disables connection retirement for memory pools, retaining their contents until the client
closes. External data sources keep their own connection lifecycle.

Create tables with reviewed SQL before using repositories. Initial DDL can be generated with
`SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(H2Dialect)` from `volan-migrate`.
`DatabaseSync(H2Reader(), H2Dialect)` reads the connection's current schema, exports it with `pull`,
detects structural drift and generates SQL with `plan`. The CLI bundles the H2 driver:

```shell
volan db pull --url jdbc:h2:file:./data --stdout
volan db push --schema schema.volan --url jdbc:h2:file:./data --dry-run
```

An H2 plan is a preview. Automatic `db push` and `Migrator` are refused before DDL or journal writes,
including with `--accept-data-loss`. H2 DDL can commit an existing transaction; applying reviewed SQL
manually can leave earlier statements applied if a later statement fails. Use a dedicated connection
and handle recovery explicitly. Pull, drift and planning execute only catalogue queries and do not
commit a caller's transaction.

Introspection preserves standard scalar types, arrays, defaults, normal BY DEFAULT identities,
ordered keys, constraints and ascending indexes. It rejects definitions the schema cannot preserve:
custom type sizes/precision, bounded or nested arrays, custom identity options, generated/invisible
columns, domains used as column types, CHECK constraints, views, triggers, standalone sequences,
synonyms, linked/temporary tables, cross-schema foreign keys and custom index ordering or methods.
Only regular mode with default collation/null ordering is supported. The default migration journal
table is excluded; `H2Reader(journalTable)` selects a custom journal name, using its exact database case.

H2 reports NO ACTION foreign keys as RESTRICT; schema mapping normalizes this equivalent immediate
check to avoid repeated changes. Enum columns export as String, and implicit join tables export as
explicit ignored models. A standalone unique index is read accurately but cannot be exported as a
unique constraint. Arbitrary database-generated expressions use H2's reported SQL spelling; when
planning against handwritten expressions, match that spelling to avoid textual default differences.

The implementation is tested with H2 2.5.252 in regular mode. Compatibility modes are not covered.
Writes return rows through [H2 data change delta tables](https://h2database.com/html/grammar.html#data_change_delta_table):
FINAL TABLE after insert/update, OLD TABLE for deleted rows. This preserves database-generated
defaults without a second select. Writes without returned rows use ordinary DML.

H2 stores DateTime as TIMESTAMP(9) WITH TIME ZONE, Time as TIME(9), Decimal as NUMERIC(65, 30),
Bytes as BINARY VARYING and Json as native JSON. Decimal values are constrained to 65 digits total
and 30 fractional digits. JSON is validated and normalized by H2, so whitespace can change.
Enums use CHARACTER VARYING and their mapped database values; the generated client validates them.
Native scalar arrays support every scalar type and enums, including empty defaults. Nullable array
elements cannot be represented by a generated non-null element type. List-column filters remain
deferred as described in the roadmap. `@db` and `@@fulltext` are rejected during schema analysis.

The shared embedded suite checks H2 and SQLite CRUD, bulk writes, queries, relations, nested writes,
summaries, cursors, constraints, asynchronous reads, file reopening and savepoint rollback. An H2
schema separately generates and compiles a client covering Decimal, UUID defaults and every scalar
array. The independent release consumer verifies H2 CRUD and schema round trips from staged Maven artifacts.

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
