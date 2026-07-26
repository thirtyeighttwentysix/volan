# ADR-0002: Build-time code generation, no runtime reflection

- **Status:** accepted
- **Date:** 2026-07-26

## Context

Type-safe queries need model knowledge somewhere. The options are: reflection at startup (Hibernate),
annotation processing (KSP/kapt over user classes), or generating sources from an external schema.

We also promise "no surprise cost at runtime": mapping a `ResultSet` row should be roughly what a
hand-written `rs.getString(1)` loop costs.

## Decision

`volan-codegen` generates Kotlin sources from the IR at build time using KotlinPoet. The generated
code contains explicit, positional row mappers and explicit column metadata. The runtime does not
reflect over entity classes, does not scan the classpath and does not build proxies.

## Consequences

- Mapping is a straight-line function per model. No `Method.invoke`, no `setAccessible`, no megamorphic
  call sites.
- Errors surface at compile time: renaming a field in the schema breaks compilation at every use site
  rather than at the first request in production.
- GraalVM native image support becomes almost free, because there is nothing to register for
  reflection.
- The generated client must be regenerated when the schema changes. This is handled by the Gradle and
  Maven plugins (M9) so that it is not a manual step; until they land, `volan generate` is run
  explicitly.
- Generated sources are build outputs. They are not checked in, and editing them is not supported.

## Alternatives considered

- **KSP over annotated classes.** Would remove the separate schema file, but re-introduces the
  "classes define the database" inversion rejected in [ADR-0001](0001-schema-language-and-parser.md),
  and KSP cannot see anything the annotations do not say (native types, indexes, relation actions).
- **Runtime reflection.** Simplest to build, and the reason Hibernate startup takes seconds on large
  models. Rejected on both the performance goal and the type-safety goal — a reflective API cannot give
  you `where { email endsWith "@acme.com" }` with compile-time checking of `email`.
- **Compiler plugin.** Most powerful, least portable: it would tie us to exact Kotlin compiler
  versions and give Java users nothing.
