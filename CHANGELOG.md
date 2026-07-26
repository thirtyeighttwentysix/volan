# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

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
- Project documentation: `ARCHITECTURE.md`, `ROADMAP.md`, the schema language reference and the first
  ten architecture decision records.

[Unreleased]: https://github.com/thirtyeighttwentysix/volan/commits/main
