# Migrations

M6 provides SQL plans, migration files, a checksum journal, database introspection and schema
synchronization. Alpha.2 supports PostgreSQL and SQLite; alpha.1 supports PostgreSQL only.
The PostgreSQL examples below address the connection's current schema. SQLite addresses main.
Multi-schema models and MySQL/MariaDB/H2 remain scheduled separately.

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
`--accept-data-loss`, including changes that may fail on existing rows. The whole push is transactional.
Its resulting structure is checked before commit. Repeating a successful push makes no changes.

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
entry commit together; a failed migration rolls back and can be fixed and retried. PostgreSQL advisory
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
