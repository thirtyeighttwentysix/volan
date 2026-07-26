# ADR-0010: Static analysis has to run on the JDK contributors actually have

- **Status:** accepted (amends [ADR-0008](0008-build-toolchain.md))
- **Date:** 2026-07-26

## Context

ADR-0008 chose detekt as the static analysis gate and stated that the build must work on any JDK ≥ 17
without provisioning a second one. The first module with real Kotlin in it showed those two statements
to be in conflict.

detekt 1.23.8 — the last release of the `io.gitlab.arturbosch.detekt` line — runs inside the Gradle
daemon and builds a Kotlin compiler environment from the running JVM's version string. On JDK 25 that
fails outright:

```text
Execution failed for task ':volan-core:detekt'.
> 25.0.3
  Caused by: java.lang.IllegalArgumentException: 25.0.3
      at io.gitlab.arturbosch.detekt.core.settings.EnvironmentFacade…
```

The failure is unconditional and cannot be configured away, because detekt runs in-process and there
is no second JDK to fork to. A quality gate that crashes on a current JDK is not a quality gate; it is
a reason for contributors to skip `./gradlew check`.

Separately, ktlint's `ktlint_official` code style — the default when no style is declared — enforces
a signature layout (every multi-line class signature puts its supertype on its own line, every method
chain breaks at every dot) that does not match how this codebase, or most Kotlin codebases, is written.

## Decision

1. Use detekt from the maintained `dev.detekt` coordinates, version `2.0.0-alpha.5`, applied as the
   `dev.detekt` plugin. It runs correctly on JDK 25.
2. Declare `ktlint_code_style = intellij_idea` in `.editorconfig`, with
   `ktlint_class_signature_rule_force_multiline_when_parameter_count_greater_or_equal_than = 3`.

## Consequences

- The gate runs everywhere: JDK 17, 21 and 25, on all three CI operating systems.
- We depend on an alpha for a build-time tool. This is a deliberate, bounded risk: detekt never ships
  in a published artifact, the version is pinned in the version catalog, and the failure mode of a bad
  upgrade is a red build, not a broken release. Dependabot and Renovate will offer later alphas and
  the eventual 2.0.0; each is reviewed, not auto-merged.
- The detekt configuration is written against detekt 2's property names, which differ from 1.x
  (`allowedFunctionsPerClass` rather than `thresholdInClasses`, no `build > maxIssues` — any finding
  fails the build).
- Two rules are tuned rather than obeyed, with the reasoning recorded in `config/detekt/detekt.yml`:
  `CyclomaticComplexMethod` ignores simple `when` entries, because a token-to-result table is not
  branching logic, and `LoopWithTooManyJumpStatements` allows three jumps, because a scanner loop
  legitimately exits at end-of-input, end-of-line and at its delimiter.
- `Parser` carries `@Suppress("LargeClass", "TooManyFunctions")` with a comment: a recursive-descent
  parser is one state machine over one cursor, and splitting it would mean threading that state
  through constructors to make a metric happy.

## Alternatives considered

- **Stay on detekt 1.23.8 and require JDK 17 or 21 locally.** Contradicts ADR-0008's "any JDK ≥ 17"
  promise and makes the local gate diverge from CI — the exact situation where a rule stops being run.
- **Fork detekt into a separate JVM on a provisioned JDK 17.** Needs toolchain auto-provisioning,
  which ADR-0008 rejected because it turns a restricted-network build into a JDK download.
- **Drop detekt and enforce only the "no stubs" rule with a hand-written task.** Cheapest, but it
  throws away every other rule detekt brings, and the hand-written check would be one more thing to
  maintain.
