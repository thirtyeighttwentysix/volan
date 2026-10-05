# Database support

`1.0.0` supports PostgreSQL, SQLite, H2, MySQL and MariaDB, including runtime operations and migrations.
Alpha.2 remains available with PostgreSQL and SQLite; alpha.1 supports PostgreSQL only.

| Capability | PostgreSQL | SQLite | H2 | MySQL / MariaDB |
|---|---|---|---|---|
| Generated Kotlin and Java clients | Yes | Yes | Yes | Yes |
| CRUD, upsert, bulk writes, raw SQL | Yes | Yes | Yes | Yes; transactional row read-back |
| Relations, batched includes, nested writes | Yes | Yes | Yes | Yes |
| Aggregate, groupBy, having | Yes | Yes, supported numeric types | Yes | Yes |
| Composite keys and cursors | Yes | Yes | Yes | Yes |
| Transactions, savepoints, async operations | Yes | Yes | Yes | Yes, InnoDB |
| Distinct | DISTINCT ON | Selected distinct columns only | DISTINCT ON | Selected distinct columns only |
| Scalar arrays | Yes | Rejected during schema analysis | Yes | Rejected during schema analysis |
| Decimal | Yes | Rejected during schema analysis | NUMERIC(65,30) | DECIMAL(65,30) |
| Provider-specific @db types | Yes | Rejected during schema analysis | Rejected during schema analysis | Rejected during schema analysis |
| Initial DDL and SQL change plans | Yes | Yes | Yes | Yes |
| Pull and structural drift detection | Yes | Yes, ordinary main tables | Yes, ordinary current-schema tables | Yes, canonical current-database tables |
| Automatic schema push | Yes, transactional | Yes, transactional | Yes, durable progress and manual repair | Yes, durable progress and manual repair |
| Versioned migrations and journal | Yes, transactional | Yes, transactional | Yes, durable progress and manual repair | Yes, durable progress and manual repair |
| Full-text index DDL | Yes | Rejected | Rejected | Yes |
| Typed full-text search | Planned | Planned | Planned | Planned |

## H2 setup

Use Maven Central and the 1.0.0 BOM:

```kotlin
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:1.0.0"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-h2")
    implementation("io.github.thirtyeighttwentysix:volan-migrate") // Schema inspection, plans and versioned migrations.
    runtimeOnly("com.h2database:h2:2.5.252")
}
```

Use `provider = "h2"` in the schema and generate the client as in [codegen-verify](../codegen-verify/).
Use `jdbc:h2:file:./data` for persistence, `jdbc:h2:mem:app` for a named in-memory database or
`jdbc:h2:mem:` for a private one. Named databases can use multiple pooled connections; private
memory pools use one connection because every connection otherwise holds a different database.
Volan disables connection retirement for memory pools, retaining their contents until the client
closes. External data sources keep their own connection lifecycle.

Create tables with reviewed SQL or `DatabaseSync.push` before using repositories. Initial DDL can be generated with
`SchemaDiffer.diff(DatabaseSchema(), SchemaMapper.map(schema)).render(H2Dialect)` from `volan-migrate`.
`DatabaseSync(H2Reader(), H2Dialect)` reads the connection's current schema, exports it with `pull`,
detects structural drift and generates SQL with `plan`. The CLI bundles the H2 driver:

```shell
volan db pull --url jdbc:h2:file:./data --stdout
volan db push --schema schema.volan --url jdbc:h2:file:./data --dry-run
```

Automatic `db push` applies changes with durable progress and final-schema verification. For deployments, save the
reviewed SQL in a `MigrationDirectory` and call `Migrator.apply` using a dedicated administrator
connection in auto-commit mode. H2 DDL commits immediately: failed migrations retain their completed
statements and unfinished journal entry. Further apply calls stop until manual repair and `markApplied`
with the original file. An interrupted push likewise blocks subsequent writes until manually repaired
and resolved against the original schema. [Migration and recovery details](migrations.md#nontransactional-push-recovery).
Pull, drift and planning execute only catalogue queries and do not commit a caller's transaction.

Introspection preserves standard scalar types, arrays, defaults, normal BY DEFAULT identities,
ordered keys, constraints and ascending indexes. It rejects definitions the schema cannot preserve:
custom type sizes/precision, bounded or nested arrays, custom identity options, generated/invisible
columns, domains used as column types, CHECK constraints, views, triggers, standalone sequences,
synonyms, linked/temporary tables, cross-schema foreign keys and custom index ordering or methods.
Only regular mode with default collation/null ordering is supported. The default migration journal
table and the private `_volan_push` journal are excluded; `H2Reader(journalTable)` selects a custom journal name, using its exact database case.

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

## MySQL and MariaDB setup

The shared `volan-dialect-mysql` module discovers both providers through ServiceLoader.
Resolve the module from Maven Central and select the matching JDBC driver:

```kotlin
dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:1.0.0"))
    implementation("io.github.thirtyeighttwentysix:volan-runtime")
    implementation("io.github.thirtyeighttwentysix:volan-dialect-mysql")
    implementation("io.github.thirtyeighttwentysix:volan-migrate")
    runtimeOnly("com.mysql:mysql-connector-j:26.7.0") // MySQL
    // runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.10") // MariaDB instead
}
```

Use `provider = "mysql"` with `jdbc:mysql://host:3306/database`, or `provider = "mariadb"`
with `jdbc:mariadb://host:3306/database`. Supply credentials through the client builder or
`DATABASE_USER` / `DATABASE_PASSWORD` for the CLI; the CLI bundles both drivers.

```shell
volan db push --schema schema.volan --url jdbc:mysql://localhost:3306/app --dry-run
volan db push --schema schema.volan --url jdbc:mysql://localhost:3306/app
volan db pull --url jdbc:mysql://localhost:3306/app --stdout
```

The library entry point is `DatabaseSync(MySqlReader(), MySqlDialect)` or
`DatabaseSync(MySqlReader(), MariaDbDialect)`. Use `DatabaseSync.plan` when generating migrations:
MySQL column modifications require the full target definition, and constraint removal requires
catalogue context. [Migration locking and recovery](migrations.md#mysql-and-mariadb-migrations).

The tested versions are MySQL 8.4 and MariaDB 11.4. Tables use InnoDB, utf8mb4 and utf8mb4_bin.
Each borrowed runtime connection uses UTC, ANSI_QUOTES, NO_BACKSLASH_ESCAPES and STRICT_TRANS_TABLES.
Text and enum fields use **VARCHAR(191)**: longer values fail, and larger/custom declarations are
currently refused by strict introspection. Uuid uses CHAR(36), Bytes LONGBLOB, Boolean TINYINT(1),
Decimal DECIMAL(65,30), DateTime UTC DATETIME(6), Date DATE and Time TIME(6). Temporal precision is
microseconds; finer input precision follows server rounding. Decimal is limited to 65 total digits
and 30 fractional digits. MySQL stores native JSON; MariaDB uses its validated JSON alias.

Create reads generated auto-increment keys through JDBC and selects the saved row inside the same
transaction. Explicit composite keys are supported. Supply UUID primary keys from the application:
`@default(uuid())` on a primary key is rejected because JDBC cannot return it reliably. Update and
delete lock the matching row with FOR UPDATE and use its primary key; a single-row write matching
multiple rows fails before changing any row. Transactions and nested writes retain rollback and
savepoint behavior. Upsert uses the existing find/create-or-update API and is not an atomic server upsert.

Case-sensitive comparisons follow utf8mb4_bin; insensitive searches use LOWER/LIKE. Search values
escape wildcards with `!` and remain bound parameters. Distinct projections must select exactly the
distinct columns. Arrays, `@db` overrides, SetDefault actions and unsupported auto-increment shapes
are rejected during schema analysis. Full-text indexes can be created with `@@fulltext`; a typed
full-text query API is deferred.

Introspection preserves canonical scalars, defaults, keys, foreign keys, ordinary ascending indexes
and full-text indexes. It refuses views, triggers, non-InnoDB tables, custom collations, unsigned or
generated/invisible columns, custom sizes or precision, other CHECK constraints, cross-database
foreign keys, expression/prefix/descending/invisible indexes and unsupported index methods.
Foreign-key supporting indexes are modeled explicitly, including their removal when the relation is dropped.
The migration and push journals are excluded. Enum columns export as String; generated client names,
relations and enum declarations still require retaining the source schema. Custom SQL expressions
may need their catalogue spelling to avoid textual default differences. Renames need reviewed SQL.

## SQLite setup

Use `provider = "sqlite"` in the datasource and a JDBC URL such as `jdbc:sqlite:./data.db` or
`jdbc:sqlite::memory:`. URLs beginning with `file:` alone are not JDBC URLs. Include both
`volan-dialect-sqlite` and the Xerial SQLite JDBC driver in the application's runtime classpath.
The dialect is discovered through `ServiceLoader`.

SQLite is available from alpha.2. Use Maven Central:

```kotlin
repositories { mavenCentral() }

dependencies {
    implementation(platform("io.github.thirtyeighttwentysix:volan-bom:1.0.0"))
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
macOS and Windows without Docker. PostgreSQL, MySQL and MariaDB integration tests use Testcontainers on Docker-enabled
runners. The same shared runtime suite runs on all five providers, alongside provider-specific tests. The release consumer separately verifies SQLite discovery from the published JAR and BOM.
