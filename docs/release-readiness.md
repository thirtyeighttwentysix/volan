# 1.0 release preparation

The current public library release is **0.1.0-alpha.2**. The selected release version is
**1.0.0**; its exact coordinates, changelog and release notes are prepared for verification.
Unsigned rehearsals are validation candidates, not Maven Central publications.

## Verify the candidate

Run the [Release workflow](https://github.com/thirtyeighttwentysix/volan/actions/workflows/release.yml)
on the development branch with `candidate_version=1.0.0`. A successful run proves:

- The complete build, public API check and migration coverage gates pass.
- All seventeen Maven artifacts have the required metadata, sources, documentation, checksums,
  Java 17 bytecode and plugin descriptors. Branch rehearsals intentionally have no signatures.
- An independent generated Java client installs the staged artifacts and exercises PostgreSQL,
  SQLite, H2, MySQL and MariaDB.
- Independent Gradle and Maven consumers generate automatically, compile, run CRUD and remove
  deleted-model sources and bytecode; Gradle also exercises its build and configuration caches.
- Kotlin, Java, Spring Boot and Ktor examples build through staged plugins, run their smoke tests
  and package launchers. Console examples additionally run their documented application command.

The downloadable `candidate-repository-1.0.0` artifact contains the unsigned Maven repository.
It is retained for 14 days. A successful rehearsal proves consumption from that repository; it does
not prove Central Portal acceptance or propagation. Signed staging and Central downloads are
checked by the tag release workflow.

## Review before the tag

Use the [database matrix](dialects.md), [migration guarantees](migrations.md),
[coroutine cancellation contract](coroutines-and-observability.md) and
[documented limitations](../ROADMAP.md#deliberately-deferred) when evaluating 1.0.
In particular, the currently deferred schema names `count` and Kotlin keywords can cause generated
code compilation errors; use supported names and mapping until those generator cases are fixed.
The runtime coverage gate is also still deferred. A stable release must describe those limits
without claiming universal schema or SQL support.

The benchmark comparison covers specific PostgreSQL reads, updates and insert/delete transactions.
It establishes neither overall ORM superiority nor relation, nested-write or migration performance.
Keep its raw JSON, run log and machine metadata with the generated README tables.

## Publish

Select either a public release candidate or the final `1.0.0`. Set the exact version in
`gradle.properties`, add `docs/releases/<version>.md`, and move the corresponding changes from
`Unreleased` to a dated changelog entry. Wait for CI and a rehearsal of that exact version, then
follow [publishing](publishing.md#publish-a-release).

After the tag workflow verifies actual Central downloads, update README installation coordinates,
the CLI installer channel and the Mintlify version navigation to the version actually published.
Keep the alpha.2 guides available because its dialects and build setup differ from the new release.
M12 is complete only after the 1.0 artifacts are published and consumed successfully.
