# ADR-0008: Build toolchain and quality gates

- **Status:** accepted
- **Date:** 2026-07-26

## Context

The project has thirteen-plus modules with identical needs (Kotlin settings, test wiring, linting,
coverage, ABI validation). Repeating that configuration per module guarantees drift. The build also
has to run on JDK 17 and 21 in CI, on Linux, macOS and Windows, while contributors may be on any
recent JDK.

## Decision

- **Gradle 9.x** with the Kotlin DSL, a version catalog (`gradle/libs.versions.toml`) and the
  configuration cache enabled.
- All shared configuration lives in **convention plugins** inside the `build-logic` included build
  (`volan.kotlin-library`). Module build files declare only their description and their dependencies.
- **Kotlin 2.4.x**, `explicitApi()`, `allWarningsAsErrors`, JVM target 17 via `-Xjdk-release=17` and
  `-release 17` rather than a toolchain download, so the build works on any JDK ≥ 17 without provisioning.
- Quality gates wired into `check`: **ktlint**, **detekt** (with `TODO`/`FIXME`/`STOPSHIP` comments and
  `NotImplementedError` configured as build errors), **Kover**, and Kotlin's built-in **ABI
  validation** exposed under the conventional task names `apiCheck` / `apiDump`.

## Consequences

- A change to the Kotlin settings is one edit in one file for the whole repository.
- The detekt rules mechanically enforce the project rule "no stubs in the main branch": a `TODO:`
  comment or a `TODO()` call fails the build rather than surviving review.
- `allWarningsAsErrors` will occasionally block on a deprecation from a dependency upgrade. That is
  intentional friction on dependency bumps, and it is where Renovate/Dependabot pull requests are
  expected to be reviewed rather than auto-merged.
- The configuration cache constrains how build scripts may access `Project` at execution time. New
  build logic must be written cache-safe from the start, which is cheaper than retrofitting.

## Alternatives considered

- **`buildSrc`.** Equivalent in capability, but any change to it invalidates the whole build's
  configuration cache; an included build is more granular.
- **Java toolchains with auto-provisioning.** Cleaner in principle, but it makes an offline or
  restricted-network build download a JDK. Compiling with `-release 17` gives the same guarantee about
  which APIs are visible.
- **BCV (`binary-compatibility-validator`) plugin.** Superseded by Kotlin's built-in ABI validation,
  which needs no extra plugin; the conventional task names are preserved with aliases.
