# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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

- Diagnostic codes are now an interface with one enum per layer (`SyntaxCode`, `SemanticCode`) instead
  of a single enum, so no module has to enumerate the failures of a module above it.

[Unreleased]: https://github.com/thirtyeighttwentysix/volan/commits/main
