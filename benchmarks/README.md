# PostgreSQL ORM benchmarks

This is an end-to-end latency comparison for specific operations, not an overall ORM ranking.
JMH measures query construction, Hikari connection checkout, a read-committed transaction,
PostgreSQL execution, result mapping where applicable, and commit. Initialization and correctness
checks happen before warmup. Compare confidence intervals before interpreting small differences.

## Workloads

Every workload runs with Volan, Hibernate, Exposed, jOOQ and a JDBC baseline, for 1 and 100 rows:

| Workload | Measured operation | Threads |
|---|---|---:|
| Read | `WHERE id >= ? ORDER BY id LIMIT ?`, materializing all four fields | 1 |
| Concurrent read | The same query; four callers share one adapter and four-connection pool | 4 |
| Update | Set `score` in an ID range, return affected-row count and commit | 1 |
| Insert/delete | Insert rows with explicit IDs, delete them in the same transaction, return deleted count and commit | 1 |

There are 40 cases. Four-thread results are average time per caller's operation, not the wall time
of four serial queries and not a separate throughput score. Each thread advances its own deterministic
cursor through 9,800 start positions. A write invocation also constructs its input rows when needed.

The read table has 10,000 rows with an integer primary key, email, name and score. Writes use a
separate table with the same shape and seed data. Before each write trial it is truncated, reseeded
and analyzed. Insert/delete uses IDs starting at 20,000, leaving no extra live rows after commit.
Updates reuse existing rows. PostgreSQL still creates dead tuples and may run autovacuum during
measurement; this is part of the tested workload. The dataset does not grow without bound.

The harness checks exact row order, all four field values, affected counts, update boundaries and
database state after insert/delete. Write checks read the inserted rows before deleting them, then
verify that only the original seed remains. Hibernate's verification clears its first-level cache
before reading. CI additionally exercises simultaneous reads through every shared adapter.

## Adapter choices

All adapters use PostgreSQL JDBC with `prepareThreshold=5`, Hikari min/max 4 and a fresh
read-committed transaction per operation. Each case runs in separate JVM forks; adapters never
share a pool with another library. There are no cross-invocation entity or result caches.

- Volan uses a generated client from [bench.volan](schema/bench.volan), `findMany`, `updateMany`,
  `createMany` and `deleteMany`. Bulk inserts use multi-row SQL.
- Hibernate reads through a fresh read-only session, with second-level and query caches disabled.
  Updates use an HQL bulk mutation. Insert/delete uses `persist`, explicit `flush`, JDBC batching
  with batch size 100, and an HQL bulk delete. Assigned IDs avoid generated-key round trips.
- Exposed uses the JDBC DSL with explicit DTO mapping, `update`, `batchInsert` without returning
  generated values, and `deleteWhere`.
- jOOQ uses its typed SQL DSL with explicit field mapping and multi-row inserts.
- JDBC uses prepared statements, explicit field mapping and multi-row inserts.

The PostgreSQL driver's `reWriteBatchedInserts` option is left at its default (false), including
Hibernate and Exposed. These are specified adapter strategies; the comparison does not establish
the best performance achievable with every library configuration. Update results are counts,
not returned entities. Insert/delete is a complete transaction containing both operations, not
an insert-only score. It does not exercise generated keys, relations or nested writes.

## Measurement and provenance

Default JMH settings: 2 fresh JVM forks per case, 3 × 2 s warmup, 5 × 2 s measurement,
512 MiB heap, average microseconds per operation and 99.9% confidence intervals. No allocation
profiler is enabled. Run on an otherwise idle machine without competing builds or tests.

The October 2026 run pins Hibernate 7.4.11.Final, Exposed 1.5.0, jOOQ 3.19.39 (the Java 17
open-source line), PostgreSQL JDBC 42.7.13, Hikari 7.1.0 and JMH 1.37. The earlier September
read-only run used Hibernate 7.4.7.Final and jOOQ 3.19.38; do not combine their measurements.

[results/](results/) contains dated raw JSON, machine metadata and compressed run logs.
`metadata.py` records dependency versions, JVM settings, database version, Docker image ID,
source digest and results checksum. `report.py` generates tables and SVGs directly from JSON;
it refuses missing/duplicate cases, incompatible configurations, invalid confidence intervals
and smoke settings. The original ten-case September report remains reproducible.

The 5 October write measurements have broad, heavily overlapping intervals. They are retained
as measured, but do not support a reliable ordering of libraries. Read intervals are narrower;
Volan has higher mean read latency in this run. Chart whiskers with a negative statistical lower
bound are clipped at zero; tables retain the full JMH error value.

This suite does not measure relation loading, nested writes, migrations, generated IDs, cold start,
allocation rates or sustained concurrent writes. PostgreSQL over Docker/WSL2 loopback includes
network and transaction latency. Results do not transfer directly to embedded or remote databases.

## Reproduce

Requires JDK 17+, Python 3 and Docker. Use a dedicated database: write trials replace the entire
`bench_write_people` table. The provided Compose service creates an isolated seed database.

```powershell
docker compose -p volan-bench -f benchmarks/compose.yaml up -d --wait
$env:VOLAN_BENCH_URL = 'jdbc:postgresql://localhost:55432/volan_bench'
.\gradlew.bat :benchmarks:verifyAdapters
.\gradlew.bat :benchmarks:jmh
python benchmarks/report.py benchmarks/build/results/jmh.json
python benchmarks/metadata.py benchmarks/build/results/jmh.json --container volan-bench-postgres-1 --output benchmarks/results/machine-local.json
docker compose -p volan-bench -f benchmarks/compose.yaml down
```

On Linux/macOS, use `export VOLAN_BENCH_URL=...` and `./gradlew` instead. Credentials default
to `volan_bench`; override `VOLAN_BENCH_USER` and `VOLAN_BENCH_PASSWORD` for a separately
provisioned benchmark database. Do not point this suite at an application database.

For a quick harness check (not publishable measurements):

```powershell
.\gradlew.bat :benchmarks:jmh '-PbenchmarkArgs=-f 1 -wi 0 -i 1 -r 100ms'
python -m unittest discover -s benchmarks -p test_report.py
```

The `verifyAdapters` task performs only correctness checks and does not need a timed JMH run.
