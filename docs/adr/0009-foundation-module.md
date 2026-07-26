# ADR-0009: A foundation module for cross-cutting types

- **Status:** accepted
- **Date:** 2026-07-26

## Context

[Section 5 of the project brief](../../ROADMAP.md) requires a single exception hierarchy rooted at
`VolanException`, with subclasses raised from wherever the failure happens: schema parsing at build
time, query execution at run time, migration at deploy time.

Those modules have no dependency on each other, and must not acquire one. `volan-runtime` must not
depend on `volan-schema` (the runtime never parses a schema), and `volan-schema` must not depend on
`volan-runtime` (the parser never touches a database). There was no module below both to hold the
shared root.

## Decision

Add `volan-core`: a module with no dependencies of its own beyond JSpecify annotations, holding types
that genuinely belong to every layer. Today that is `VolanException`. Every other Volan module depends
on it.

The bar for adding something to `volan-core` is deliberately high: a type belongs here only if at
least two modules that cannot depend on each other both need it.

## Consequences

- One catch clause catches everything Volan raises, as the brief requires, without any module
  depending on a module it has no business knowing about.
- One extra artifact for consumers. It is tiny, and `volan-bom` plus the transitive dependency from
  every other module mean nobody has to add it by hand.
- A tempting place to dump utilities. The stated bar, and code review, are the defence.

## Alternatives considered

- **Root the hierarchy in `volan-schema`.** Would make the runtime depend on the parser, dragging a
  build-time concern into every production deployment.
- **Two unrelated hierarchies**, one for build time and one for run time. Simpler, but it breaks the
  "catch `VolanException`" promise and makes framework integrations enumerate types.
- **Put the root in `volan-dialect-api`.** It is currently the lowest runtime module, but a schema
  parser depending on a SQL dialect SPI is backwards, and it would strand build-time tooling.
