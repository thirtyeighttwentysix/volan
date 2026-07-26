# ADR-0004: A dialect-independent query IR, rendered to SQL at execution time

- **Status:** accepted
- **Date:** 2026-07-26

## Context

The generated DSL has to produce something. It can produce SQL text directly, or an intermediate
description that a dialect turns into SQL.

We support four databases at 1.0 and more later, and we promise that user values are never
concatenated into SQL.

## Decision

The DSL builds an immutable `QuerySpec` — a description of *what* is being asked (target model,
filter tree, ordering, pagination, projection, requested relations, write payloads). A planner turns
it into a `QueryPlan` (a root statement plus batched relation statements). Only then does a `Dialect`
render each node into a `SqlStatement`: a text template plus an ordered list of bound parameters.

User values live in the parameter list from the moment they enter the DSL. There is no code path in
which a value reaches the SQL string.

## Consequences

- SQL injection is prevented structurally rather than by discipline: rendering has no access to a
  string-concatenation path for values, and identifiers come from the IR, not from user input.
- Adding a dialect means implementing rendering for a fixed, small node set plus declaring capability
  flags (`supportsReturning`, `supportsUpsert`, `supportsArrays`, …). The planner consults capabilities
  and degrades gracefully (for example, emulating `RETURNING` with a follow-up select).
- Golden-SQL tests can pin the exact statement produced for each `QuerySpec` per dialect, which makes
  performance regressions and accidental dialect drift visible in review.
- One extra layer between the DSL and the driver. Its cost is bounded: rendering is a pure function of
  the plan, and rendered statements are cached keyed by plan shape, so a repeated query renders once.

## Alternatives considered

- **Emit SQL straight from the DSL.** Fastest to write, impossible to keep portable, and it makes the
  "no values in text" guarantee a matter of reviewer vigilance.
- **Reuse an existing SQL builder (JOOQ, Exposed) internally.** Adds a large dependency with its own
  API surface, licensing considerations (JOOQ's commercial dialects) and its own opinions about
  transactions; and we would still need our own layer for nested writes and batched relation loading.
