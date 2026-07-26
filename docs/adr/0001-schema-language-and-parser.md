# ADR-0001: A dedicated schema language with a hand-written parser

- **Status:** accepted
- **Date:** 2026-07-26

## Context

Volan is schema-first: a single file is the source of truth for the generated client *and* for
migrations. The format has to be

- readable and diff-friendly for humans reviewing a pull request,
- familiar to the audience we are targeting (developers who know or have heard of Prisma),
- rich enough to express relations, composite keys, native column types and generator options,
- and, above all, capable of producing **excellent error messages**, since the schema file is the
  single place where most user mistakes will happen.

The candidate formats were: a Prisma-like DSL, YAML/TOML, Kotlin DSL (a `.kts` file evaluated at
build time), and annotated Kotlin classes (the JPA model).

## Decision

Volan defines its own textual DSL, stored in `schema.volan`, with syntax intentionally close to
Prisma's. It is processed by a **hand-written lexer and recursive-descent parser** in `volan-schema`
with no parser-generator dependency. Every AST node carries an exact source span (offset, line,
column, length).

## Consequences

- Diagnostics can render a Rust-style code frame with a caret under the offending token, an
  explanation and a `help:` suggestion. This is the single biggest DX lever we have, and it is only
  achievable with full control over the token stream and error recovery.
- The parser has no third-party dependency, so `volan-schema` stays tiny and starts instantly — which
  matters for a CLI and for an incremental Gradle task.
- Error recovery (continue parsing after an error to report several problems at once) is implemented
  by hand rather than inherited from a framework. That is real work, but it is work we would have had
  to do anyway to get good messages out of a generated parser.
- We own a language. It needs its own formatter (`volan format`), its own documentation and, later,
  editor support. This is accepted: the format is small and deliberately closed to expression
  evaluation.

## Alternatives considered

- **ANTLR4.** Gives a grammar file and a parser for free, but its default error messages are poor and
  overriding them means writing most of the diagnostics layer anyway; adds a runtime dependency and a
  code-generation step to our own build.
- **YAML/TOML.** No new language to learn, mature parsers — but relations and attributes become deeply
  nested key/value soup, and column-accurate errors are limited to what the YAML parser reports.
- **Kotlin DSL (`schema.kts`).** Type-safe and IDE-friendly, but evaluating it requires a Kotlin script
  engine (slow startup, heavyweight dependency), and an arbitrary-code schema cannot be reliably
  round-tripped by `volan db pull` or `volan format`.
- **Annotated Kotlin/Java classes (JPA-style).** Familiar, but it inverts the model: the database
  schema becomes a side effect of application classes, migrations become guesswork, and the "one
  readable file describing the whole data model" property is lost.
