# Contributing to Volan

Thanks for taking the time. This document explains how to get a working build, what the quality gates
are, and what a reviewable change looks like.

By participating you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## Getting set up

You need JDK 17 or newer. Nothing else: the Gradle wrapper provisions Gradle itself.

```bash
git clone https://github.com/thirtyeighttwentysix/volan.git
cd volan
./gradlew build
```

Docker will be required for the Testcontainers-based integration tests (PostgreSQL, MySQL, MariaDB)
once they land in M4; the fast suites run on in-memory H2 and SQLite and need no Docker.

## The commands you will actually use

| Command | What it does |
|---|---|
| `./gradlew build` | Compile everything and run the unit tests |
| `./gradlew test` | Unit tests only |
| `./gradlew ktlintCheck` / `ktlintFormat` | Code style |
| `./gradlew detekt` | Static analysis |
| `./gradlew apiCheck` / `apiDump` | Public ABI verification / regeneration |
| `./gradlew koverHtmlReport` | Coverage report |

Before opening a pull request, the full local gate is:

```bash
./gradlew build test detekt ktlintCheck apiCheck
```

## Project rules

These are enforced, not aspirational:

1. **No stubs in `main`.** No `TODO`/`FIXME` comments, no `TODO()`, no `NotImplementedError`, no empty
   bodies standing in for behaviour, no commented-out code. detekt fails the build on all of these.
   Work that does not fit the current milestone goes into [ROADMAP.md](ROADMAP.md) as an explicit item.
2. **Tests ship with the code,** in the same commit. For a bug fix, the first commit is a failing test.
3. **Public API changes are deliberate.** If your change alters `<module>/api/*.api`, run
   `./gradlew apiDump`, commit the dump, and explain the change in the pull request description.
4. **Every public declaration has KDoc.** The module is compiled with `explicitApi()`, so visibility and
   return types are explicit too.
5. **Only parameterized SQL.** A user value must never reach a SQL string. If you find yourself
   building SQL with string interpolation over a value, the design is wrong.
6. **Architecture decisions get an ADR.** Anything that constrains future work belongs in
   [docs/adr/](docs/adr/), using [the template](docs/adr/0000-template.md).

## Commits and branches

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```
feat(schema): parse composite @@id attributes
fix(runtime): release the connection when a transaction body throws
docs(adr): record the relation loading strategy
```

Scopes are module names without the `volan-` prefix: `schema`, `ir`, `codegen`, `runtime`, `migrate`,
`dialect-postgres`, `cli`, `build`, `docs`.

One logical change per commit, and every commit should build.

## Pull requests

- Keep them focused; a reviewable PR is one that a reader can hold in their head.
- Describe *why*, not only *what*. The diff already says what.
- Note any behaviour visible to users, and update the documentation in the same PR.
- CI must be green: build and unit tests on JDK 17 and 21 across Linux/macOS/Windows, detekt, ktlint,
  `apiCheck` and coverage. Integration and Java-compatibility jobs join the matrix as those suites land
  (M4 and M7).

## Reporting bugs

Please include the schema fragment that reproduces the problem, the exact Volan version, the database
and driver versions, and the SQL Volan generated (enable query logging). A failing test is the fastest
possible bug report.

## Security

Do not open a public issue for a security problem. See [SECURITY.md](SECURITY.md).
