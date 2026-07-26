# ADR-0005: Batched relation loading by default, JOIN opt-in

- **Status:** accepted
- **Date:** 2026-07-26

## Context

`include { posts { … } }` has to fetch a collection for every row of the parent page. The classic
implementations are: one query per parent (N+1), a single `JOIN`ed query, or one batched query per
relation level.

Nested `include` with its own `where`, `orderBy`, `take` and `skip` at each level makes this harder:
per-parent limits cannot be expressed by a plain `JOIN` without window functions, and window functions
are not available everywhere we target.

## Decision

The default strategy is **one batched query per relation level**, keyed by the parent ids collected
from the previous level (`WHERE fk IN (?, ?, …)`). Per-relation, the user may request `JOIN` loading
where a single round trip is preferable. Volan never issues a query per parent row.

Very large id sets are chunked (default 1000 ids per statement, configurable) so that no dialect's
parameter limit is exceeded.

## Consequences

- Query count is a function of `include` *depth*, not of result size: a 20-row page with two nested
  relations is always 3 statements. Tests assert this count, so an accidental N+1 fails CI.
- Per-relation `take`/`skip`/`orderBy` work uniformly on every dialect, because each relation level is
  its own statement with its own `ORDER BY`/`LIMIT` applied per parent through a lateral or ranked
  subquery only where the dialect supports it, and through chunked per-parent statements otherwise —
  the choice is a dialect capability, not a behavioural difference the user sees.
- Row duplication is avoided: parent columns are fetched once regardless of child cardinality. This is
  the main reason batching usually beats `JOIN` on wide parents.
- Extra round trips compared to a single `JOIN`. For the "one parent, one small relation" case the
  `JOIN` strategy exists precisely to avoid that, and it is a per-relation switch rather than a global
  mode.

## Alternatives considered

- **Always `JOIN`.** One round trip, but it duplicates parent columns across child rows, breaks
  per-relation pagination, and makes `take` on the root ambiguous.
- **Lazy loading on property access.** The JPA model. Requires proxies and a session bound to the
  entity, which contradicts [ADR-0002](0002-build-time-codegen.md) and turns every read into a
  potential surprise query.
