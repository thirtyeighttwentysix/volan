# Architecture Decision Records

Every decision that constrains future work gets a short record here, so that six months from now the
reasoning is available and does not have to be reconstructed from the code.

Use [0000-template.md](0000-template.md) for new records. Records are immutable once accepted: if a
decision changes, add a new record and mark the old one superseded.

| # | Decision | Status |
|---|---|---|
| [0001](0001-schema-language-and-parser.md) | A dedicated schema language with a hand-written parser | accepted |
| [0002](0002-build-time-codegen.md) | Build-time code generation, no runtime reflection | accepted |
| [0003](0003-sync-core-coroutine-wrapper.md) | A synchronous core with a thin coroutine layer | accepted |
| [0004](0004-query-ir-and-sql-rendering.md) | A dialect-independent query IR, rendered to SQL at execution time | accepted |
| [0005](0005-relation-loading-strategy.md) | Batched relation loading by default, JOIN opt-in | accepted |
| [0006](0006-java-facing-api.md) | The Java API is a designed artefact, not a by-product | accepted |
| [0007](0007-coordinates-and-versioning.md) | Coordinates, versioning and compatibility policy | accepted |
| [0008](0008-build-toolchain.md) | Build toolchain and quality gates | accepted |
| [0009](0009-foundation-module.md) | A foundation module for cross-cutting types | accepted |
| [0010](0010-static-analysis-on-modern-jdks.md) | Static analysis has to run on the JDK contributors actually have | accepted, amends 0008 |
