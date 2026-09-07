# 0011. Transactional synchronization with verified schema export

Status: accepted

## Context

The migration differ and PostgreSQL reader already share `DatabaseSchema`. Completing M6 needs a
schema-language export, a callable pull/push path and a clear distinction between migration history
and actual database drift.

## Decision

`SchemaWriter` emits canonical schema text, validates it, and maps it back to `DatabaseSchema`.
Export succeeds only when that reconstructed value equals the introspected value. Irrecoverable
client metadata is documented; unsupported database definitions fail explicitly.

`DatabaseSync` owns pull, plan, structural drift and push. It accepts a caller-owned JDBC connection
and a dialect renderer. Push requires auto-commit on entry, executes in one transaction, verifies
the result before commit and does not write migration history. PostgreSQL advisory locks serialize
push and journaled migrations by current database and schema.

The initial Clikt `volan-cli` module lands in M6 with `db pull` and `db push`. M9 will add the remaining
commands and build-tool plugins. Benchmarks are a separate non-published module, brought forward from
M12 to make performance claims reproducible during development.

## Consequences

A failed push rolls back rather than leaving a partially synchronized database. Migration history
checks and structural drift remain separate APIs: a valid checksum does not imply a pristine database.
Original model aliases and implicit relation syntax cannot be recovered from table metadata, so pull
preserves database structure, not the original generated client API.
