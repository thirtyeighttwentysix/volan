# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Multi-module Gradle build with a version catalog and shared convention plugins (`build-logic`).
- Quality gates wired into `check`: ktlint, detekt (with `TODO`/`FIXME` and `NotImplementedError` as
  build errors), Kover coverage reporting and Kotlin ABI validation exposed as `apiCheck` / `apiDump`.
- Continuous integration across JDK 17/21 and Linux/macOS/Windows.
- Project documentation: `ARCHITECTURE.md`, `ROADMAP.md` and the first eight architecture decision
  records.

[Unreleased]: https://github.com/thirtyeighttwentysix/volan/commits/main
