# Migrations

M6 provides SQL plans, migration files, a checksum journal, database introspection and schema
synchronization. Alpha.2 supports PostgreSQL and SQLite; alpha.1 supports PostgreSQL only.
Main adds H2, MySQL and MariaDB runtime, introspection, pull/push and versioned migrations for alpha.3.
Their DDL commits immediately, so interrupted changes require manual repair. See the
[database matrix](dialects.md). PostgreSQL examples address the current schema; SQLite addresses main;
MySQL and MariaDB address the selected database. Multi-schema models remain deferred.

## Pull and push

Build the command line distribution with `./gradlew :volan-cli:installDist`. Its launcher is
`volan-cli/build/install/volan/bin/volan` (`volan.bat` on Windows).

```bash
export DATABASE_URL='jdbc:postgresql://localhost:5432/my_database'
export DATABASE_USER='my_user'
export DATABASE_PASSWORD='my_password'

volan db pull --stdout                         # inspect the database as schema text
volan db pull --schema introspected.volan       # create a file; refuses to overwrite
volan db push --schema schema.volan --dry-run   # inspect SQL and warnings
volan db push --schema schema.volan             # apply the desired schema
```

`db push` reads the datasource URL from the schema, unless `--url` overrides it. `db pull` uses
`DATABASE_URL` or `--url`. URLs must use JDBC syntax. `--force` allows pull to replace an existing
file. Generated schema text always uses `env("DATABASE_URL")` and does not contain credentials.

Push is for bringing a development database into the desired shape. It does **not** create migration
files or update the journal. Review `--dry-run` first; changes carrying warnings require
`--accept-data-loss`, including changes that may fail on existing rows. PostgreSQL and SQLite push are
transactional and check the resulting structure before commit. H2, MySQL and MariaDB push retain
durable progress across implicit DDL commits and require manual repair after interruption; see
[nontransactional push recovery](#nontransactional-push-recovery). Repeating a successful push makes no changes.

## Versioned migrations

Keep reviewed migration SQL in source control and use `Migrator` for deployment:

```kotlin
val schema = SchemaLoader.load("schema.volan", schemaText).schemaOrThrow()
val sync = DatabaseSync(PostgresReader(), PostgresDialect)
val directory = MigrationDirectory(Path.of("migrations"))

dataSource.connection.use { connection ->
    val plan = sync.plan(connection, schema)
    if (!plan.isEmpty) {
        println(plan.warnings)
        directory.write(Instant.now(), "add_posts", plan.toSql(PostgresDialect))
    }
}

// After reviewing and committing the generated SQL:
dataSource.connection.use { connection ->
    Migrator(directory).apply(connection)
}
```

Each directory is named `yyyyMMddHHmmss_description` and contains `migration.sql`. A duplicate name
cannot overwrite a migration. SHA-256 checksums normalize CRLF to LF. Each migration and its journal
entry commit together on PostgreSQL and SQLite; a failed migration rolls back and can be fixed and retried. PostgreSQL advisory
locks serialize Volan migration writers in the same database schema. Connections must start in
auto-commit mode, so the migrator cannot commit unrelated work in a caller's transaction.

The SQL script reader handles quoted identifiers, escaped strings, dollar-quoted function bodies and
nested block comments. Scripts must not contain their own transaction control or operations PostgreSQL
forbids inside a transaction, such as `CREATE INDEX CONCURRENTLY`. Adding an enum value and using that
new value may require separate migrations because PostgreSQL requires a commit between those steps.

## History and structural drift

`Migrator.status(connection)` reports applied, pending, edited, missing and unfinished migrations.
Apply refuses edited, missing or unfinished history. `markApplied` records a reviewed baseline without
executing its SQL; it is intended for a database that already has that migration's structure.

History checks do not prove that nobody changed the database manually. To check the structure, retain
the expected schema for the deployed version and compare it with the live database:

```kotlin
val expected = SchemaMapper.map(deployedSchema)
val drift = sync.drift(connection, expected) // expected -> actual
check(drift.isEmpty) { drift.toSql(PostgresDialect) }
```

`sync.plan(connection, nextSchema)` produces the opposite kind of plan: actual -> desired. These
operations are read-only. They compare structures, not the contents of application tables.

## What pull can preserve

Tables, column order, scalar and native types, enums, defaults, mapped names, keys, supported indexes
and referential actions are reconstructed and validated by mapping the output back to the input.
Names that are invalid or collide in the language get deterministic aliases and mapping attributes.
Any definition that cannot be represented exactly fails export instead of being silently omitted.

Comments, generators, original model/field aliases and client-side `@updatedAt` are not stored in
PostgreSQL, so pull cannot recover them. Implicit many-to-many join tables are exported as explicit
models; keyless tables use `@@ignore`. Keep the original schema to preserve its client API.

Custom foreign-key names, unique indexes that are not constraints, partial indexes, included columns,
custom index ordering and arbitrary expression indexes are currently unsupported. Volan's own GIN
full-text index shape is supported. Column renames are represented as drop/add, with a warning: write
an explicit `ALTER TABLE ... RENAME COLUMN ...` migration to preserve the data. Removing/reordering
enum values, inserting values in the middle, and changing auto-increment sequences also require
explicit migration SQL.

## SQLite migrations

Use `provider = "sqlite"` and a `jdbc:sqlite:` datasource URL. The CLI selects the database from the URL;
the library uses `DatabaseSync(SqliteReader(), SqliteDialect)`. Add `volan-migrate` and
`volan-dialect-sqlite` to your dependencies alongside the SQLite JDBC driver.

```kotlin
val sync = DatabaseSync(SqliteReader(), SqliteDialect)
DriverManager.getConnection("jdbc:sqlite:./data.db").use { connection ->
    val plan = sync.plan(connection, schema)
    println(plan.toSql(SqliteDialect))
    sync.push(connection, schema) // warning-bearing changes require explicit acceptance
}
```

For deployments, write the reviewed plan to a `MigrationDirectory` and use `Migrator`, as in the
PostgreSQL example, with `SqliteDialect` when rendering. Use `DatabaseSync.plan` rather than the generic
`SchemaDiffer.diff`: SQLite constraints must be included when creating or rebuilding a table.
Do not add BEGIN/COMMIT/ROLLBACK or other transaction control to migration scripts.

Push acquires SQLite's write lock before introspection with BEGIN IMMEDIATE. Migrations acquire it
for each file and recheck history after acquiring it. Separate connections and processes therefore
cannot apply the same migration twice. SQLite's configured busy timeout controls how long a competing
writer waits; an expired timeout reports a failed migration.

Column or constraint changes rebuild a table atomically: create a replacement, copy surviving columns,
drop the old table, rename the replacement and recreate indexes. Added columns get their defaults or
NULL; required columns without defaults fail on populated tables. Surviving values and the
AUTOINCREMENT high-water mark are retained. Index-only changes avoid rebuilding.

Foreign keys are disabled before the transaction to prevent cascading deletes when replacing a parent.
Every migration checks foreign keys before commit and restores the connection's original setting,
including on failure. Invalid references, uniqueness violations and NOT NULL failures roll back both
the changes and the journal record. Use dedicated connections starting in auto-commit mode.

Introspection retains supported scalar declarations, defaults, keys, constraint names, ordinary indexes,
and referential actions. Pull validates its output by mapping it back to the database shape. Application
enum declarations are not stored in SQLite: pull exposes their columns as String. Original client aliases,
generators and updatedAt also require keeping the source schema.

Schema synchronization refuses views, triggers, virtual tables, CHECK constraints, generated columns,
custom collations, STRICT/WITHOUT ROWID tables, partial/expression/descending indexes, temporary objects
and attached databases. It does not silently discard them during a rebuild. Unique indexes can be read
and compared, but pull cannot export them as different unique constraints. Use explicit SQL for shapes
outside this supported subset, and column renames or changes to autoincrement.

## H2 versioned migrations

This support is on main for alpha.3; alpha.2 does not include H2. Generate a plan with
`DatabaseSync(H2Reader(), H2Dialect)`, review the SQL, and save it with `MigrationDirectory.write`.
Apply it using the same `Migrator` API as PostgreSQL:

```kotlin
val directory = MigrationDirectory(Path.of("migrations"))
DriverManager.getConnection("jdbc:h2:file:./data", "sa", password).use { connection ->
    Migrator(directory).apply(connection)
}
```

Use a dedicated administrator connection starting in auto-commit mode, with no caller-owned exclusive
mode. The migrator acquires [H2 exclusive mode](https://h2database.com/html/commands.html#set_exclusive)
before reading history or creating the journal. Existing connections stay open, but their operations
pause; new connections are rejected during migration. Concurrent migration writers may wait or report
an exclusive-access conflict; retry the apply call after the other writer finishes. Use a maintenance
window and avoid outstanding application transactions. Exclusive mode is released on success, failure
or owner disconnect, including for H2 server connections in separate processes.

[H2 DDL commits the current transaction](https://h2database.com/html/advanced.html#transaction_isolation),
so H2 migrations cannot be rolled back as a unit. Volan commits a start record before the first
statement, acknowledges each completed statement in `applied_steps`, and sets `finished_at` only after
the whole script completes. A failed migration leaves earlier statements committed and the record
unfinished. Later migrations stop, and the partial script is never automatically replayed.
After a crash, the last statement may have committed before its progress update: the count is a lower
bound, not proof that the next statement did not run. Inspect the actual database before any repair.

To recover, retain the original migration file and checksum. Inspect `MigrationJournal.read` and
`Migrator.status`, compare the database with the intended final state, and manually complete or repair
the migration. Once the database is in that final state, explicitly finish the existing record:

```kotlin
val original = directory.read().single { it.id == failedMigrationId }
DriverManager.getConnection("jdbc:h2:file:./data", "sa", password).use { connection ->
    val migrator = Migrator(directory)
    // After reviewing and repairing the database to the migration's intended final state:
    migrator.markApplied(connection, original)
    migrator.apply(connection) // runs subsequent migrations, without replaying the repaired script
}
```

`markApplied` does not execute or verify SQL; it records the caller's reviewed repair, retaining the
original checksum, start timestamp and acknowledged progress. It refuses changed scripts and already
finished records. Restoring a consistent database backup is another recovery option. Do not edit the
failed file to make it replayable or delete its record while partial changes remain.

Scripts must not change transaction/session settings, schemas or exclusive mode, run nested scripts
or shut down the database. Direct BEGIN/START/COMMIT/ROLLBACK/SAVEPOINT/RELEASE/PREPARE, SET, USE,
RUNSCRIPT, EXECUTE and SHUTDOWN commands are refused before any statement from that file runs. SQL
aliases, procedures and triggers must not perform these operations indirectly or modify the journal.
Malformed quoted SQL is also refused before the start record; an unstarted script can be corrected.
The journal resides in the connection's current schema; use the same custom table name in
`MigrationJournal(name)` and `H2Reader(name)` when changing the default.

## MySQL and MariaDB migrations

Use `MySqlDialect` / `MariaDbDialect` and `MySqlReader`, both backed by the shared
`volan-dialect-mysql` module. Generate plans with `DatabaseSync.plan`, review them, write them to a
`MigrationDirectory` and apply with `Migrator` on a dedicated connection starting in auto-commit mode.
No active application writes should run during schema changes. Connections must select a database.

Writers acquire a database-specific named session lock before inspecting schema/history. The lock
survives implicit DDL commits and is released on completion, failure or disconnect. It coordinates
Volan writers on the same server; application SQL and other migration tools must be coordinated
separately. Acquisition waits up to 60 seconds. See [MySQL named locks](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)
and [MariaDB implicit commits](https://mariadb.com/docs/server/reference/sql-statements/transactions/sql-statements-that-cause-an-implicit-commit).

Versioned migrations use the same durable start/progress/finish protocol and `markApplied` recovery
as H2 above. No partial script is automatically replayed. Scripts must not change session/transaction
settings, databases or migration locks, including indirectly through routines or triggers. LOCK/UNLOCK
and the direct control commands listed above are refused before any statement runs. Backtick identifiers
and ordinary MySQL comments are parsed without splitting quoted semicolons. Executable version
comments (`/*! ... */` / `/*M! ... */`) are refused; write ordinary reviewed SQL instead. These guarantees
require keeping the dedicated connection open for the whole operation.

## Nontransactional push recovery

H2, MySQL and MariaDB `DatabaseSync.push` / CLI `db push` use a separate `_volan_push` journal. Review
`--dry-run` first. Warning-bearing changes require `--accept-data-loss`, as on the other providers.
MySQL/MariaDB additionally refuse a required column without a default on a populated table, preventing
implicit empty-value backfills; use a reviewed migration to backfill explicitly. Push verifies the
resulting schema before recording completion. Successful repeated pushes make no changes and never
add entries to versioned migration history.

Failure can leave earlier DDL committed. An unfinished push blocks both another push and versioned
migration apply. An unfinished default versioned journal similarly blocks push. Inspect the database
and the acknowledged statement count, retain the original target schema and manually complete the
repair. The count is a lower bound: a crash can happen after DDL commits but before its progress is saved.
Once the database exactly matches that original target, resolve it:

```shell
volan db push --schema schema.volan --url jdbc:h2:file:./data --resolve
# The same command supports jdbc:mysql: and jdbc:mariadb: URLs.
```

The library equivalent is `sync.resolvePush(connection, originalSchema)`. Resolution compares the
structural target fingerprint and actual database schema; a different target or incomplete repair
is refused. It executes no repair DDL itself. Do not delete unfinished journal rows to force a retry.
Reserve `_volan_push` and `_volan_migrations` for Volan. Readers can select a custom versioned journal
name; automatic push interlocks with the default versioned journal, so deployments using a custom
journal must coordinate its unfinished state themselves. Plan/pull/drift only read catalogue data.
