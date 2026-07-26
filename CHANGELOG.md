# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `aggregate { … }`: `count`, `sum`, `average`, `minimum` and `maximum` over the matching rows, in one
  statement. A summary is only offered where it means something — there is no total of a boolean column
  — and a summary the query never asked for refuses to be read rather than reading as zero.
- `groupBy { by { … } … having { … } }`: rows folded into groups, each summarised, with `having`
  written over the summaries. A group is read the same way a partial `select` is read, so a field the
  `by` block left out fails with the message that names the block which would add it.
- `distinct { … }` on a read, naming fields the way every other block does.
- Nested writes from a `create`: `create`, `connect` and `connectOrCreate` on any relation, applied in
  one transaction with the row they belong to. Relations the row owns the key of are resolved first and
  folded into its own values; everything else is applied against the key it came back with.
- Many-to-many relations load through their join table, from either side, at two statements per level.
- Batched relation loading for one-to-one and one-to-many relations: `include` costs one statement per
  relation level, whatever the number of rows, and includes nest to any depth. Generated mappers hand
  the loader the two things it needs — a row's key, and a copy of that row with a relation filled in.
- `Volan.Builder.dataSource(…)`, for fitting into a container that owns connection management.
- `volan-dialect-api` and `volan-dialect-postgres`: Volan's own SQL model, the `Dialect` interface, a
  capability record the planner reads instead of naming databases, and a renderer that implements
  standard SQL once. Dialects are discovered through `ServiceLoader`, so the runtime depends on no
  database in particular.
- The execution core: a planner that turns a query description into that SQL model, a JDBC executor,
  a HikariCP pool, transactions with isolation levels and savepoint nesting, and `SQLState`
  translation into the Volan exception hierarchy.
- `Volan.builder()`, and a generated `VolanClient.builder()` that carries its own schema's tables, so
  connecting reads the way the documentation shows.
- Raw SQL: `rawQuery` and `rawExecute`, where the statement text is yours and the values are still
  bound as parameters.
- `volan-codegen`: the generator. From a validated schema it produces entities with fluent builders,
  table metadata, row mappers, the `where`/`orderBy`/`select`/`include` DSLs, relation filters, write
  payloads that know a null from an unset field, repositories and the client that hands them out.
  Models and fields marked `@ignore` are left out entirely.
- `codegen-verify`: a module that generates a client during the build, compiles it and exercises it
  against a recording executor — the same sequence a user's project will run through the Gradle plugin
  in M9.
- `volan-runtime`: the query description layer generated clients are built on — filter, ordering,
  paging and selection trees, table metadata, the `Row`/`RowMapper` reading contract, and the
  `QueryExecutor` boundary that keeps JDBC out of generated code. No SQL is produced here: values stay
  values from the moment they enter the DSL.
- `RelationSlot` and `SelectedFields`: reading a relation that was not included, or a field a partial
  `select` left out, fails with a message naming the query to change instead of returning a silent null.
- `Json`, the value type of a `Json` column, in `volan-core`.
- `volan-ir`: semantic analysis and the normalized schema model. Resolves every type, pairs up both
  ends of every relation, applies `@map`, and validates keys, defaults, constraints and referential
  actions. `SchemaLoader.load(…)` turns schema text into a `Schema` or into diagnostics.
- Validation warnings that do not fail a build: a connection URL written into the schema instead of
  read with `env()`, and a chain of `onDelete: Cascade` that loops back on itself.
- Kover coverage gate of 85 % on `volan-schema` and `volan-ir`.
- `volan-schema`: the `schema.volan` language. Hand-written lexer and recursive-descent parser, a
  fully spanned AST, and a canonical formatter that preserves comments and the blank lines used to
  group fields.
- Rust-style diagnostics with stable codes: a code frame, a caret under the exact text, an
  explanation and a suggested fix. The parser recovers after every error, so one run reports every
  mistake in a file. Documented in [docs/schema-language.md](docs/schema-language.md).
- `volan-core`: the `VolanException` root shared by every module.
- Multi-module Gradle build with a version catalog and shared convention plugins (`build-logic`).
- Quality gates wired into `check`: ktlint, detekt (with `TODO`/`FIXME` and `NotImplementedError` as
  build errors), Kover coverage reporting and Kotlin ABI validation exposed as `apiCheck` / `apiDump`.
- Continuous integration across JDK 17/21 and Linux/macOS/Windows.
- Project documentation: `ARCHITECTURE.md`, `ROADMAP.md`, the schema language reference — including
  every diagnostic code — and the first ten architecture decision records.

### Changed

- Whether a write supplies everything a row requires is now decided by the runtime, just before the
  statement is built, rather than by the generated payload. A foreign key that a nested write is about
  to supply is absent when the block is written and present by the time the row is; checking early
  made writing a row together with its parent impossible.
- Diagnostic codes are now an interface with one enum per layer (`SyntaxCode`, `SemanticCode`) instead
  of a single enum, so no module has to enumerate the failures of a module above it.

[Unreleased]: https://github.com/thirtyeighttwentysix/volan/commits/main
