# PostgreSQL ORM benchmarks

This is an end-to-end **read benchmark**, not an overall ORM ranking. It includes query construction,
borrowing a HikariCP connection, a read-committed transaction, PostgreSQL execution, mapping every
selected row and commit. Pool/ORM initialization and correctness checks run outside measured time.

## Reproduce

Requires JDK 17+, Docker and Python 3 for the optional report generator. From the repository root:

```bash
docker compose -p volan-bench -f benchmarks/compose.yaml up -d --wait
export VOLAN_BENCH_URL='jdbc:postgresql://localhost:55432/volan_bench'
./gradlew :benchmarks:jmh
python benchmarks/report.py benchmarks/build/results/jmh.json
docker compose -p volan-bench -f benchmarks/compose.yaml down
```

PowerShell uses `$env:VOLAN_BENCH_URL = 'jdbc:postgresql://localhost:55432/volan_bench'` and
`.\gradlew.bat`. Credentials default to the dedicated Compose database's `volan_bench` user/password.
`VOLAN_BENCH_USER` and `VOLAN_BENCH_PASSWORD` override them. The harness only reads data; the Compose
seed script creates the dataset in a separate database. Do not point the fixture at application data.

The default run uses 2 fresh JVM forks per case, 3 × 2-second warmup iterations, 5 × 2-second
measurement iterations, 1 thread and a fixed 512 MiB JVM heap. JMH 1.37 reports mean microseconds per
operation and its 99.9% confidence interval. Lower latency is better. No allocation profiler is
enabled in the published run.

A quick harness check, **not** a publishable result:

```bash
./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 -wi 1 -i 1 -w 1s -r 1s"
```

## Workload and fairness

| Property | Every adapter |
|---|---|
| Database | Same PostgreSQL server and table |
| Dataset | 10,000 rows, integer primary key, email, name, score |
| Query | `WHERE id >= ? ORDER BY id LIMIT ?` |
| Cases | 1 row and 100 rows |
| Parameters | Deterministically rotate through 9,800 start positions |
| Pool | HikariCP, min/max 4 connections |
| JDBC | Same PostgreSQL driver, prepare threshold 5 |
| Transaction | New read-committed transaction per invocation |
| Mapping | All four fields, full materialized list, returned to JMH |
| Validation | Exact row count, order and all field values checked before warmup |

Volan uses a client generated from [bench.volan](schema/bench.volan). Hibernate uses a fresh read-only
session for each query, with second-level and query caches disabled. Exposed uses its JDBC DSL and
explicit DTO mapping. jOOQ uses typed DSL fields and explicit mapping, not reflection-based `into()`.
JDBC is the hand-written SQL baseline. Their SQL may differ in aliases and pagination syntax while
requesting identical rows and fields.

The versions are pinned in [the catalog](../gradle/libs.versions.toml): Hibernate 7.4.7.Final,
Exposed 1.5.0, jOOQ 3.19.38, PostgreSQL JDBC 42.7.13 and HikariCP 7.1.0. jOOQ 3.19 is used because
its open-source distribution supports this project's Java 17 baseline; newer open-source lines
require newer JDKs. See [jOOQ's JDK matrix](https://www.jooq.org/download/support-matrix-jdk).

This suite does not measure writes, joins/includes, nested writes, migrations, bulk ingestion,
concurrent throughput, cold starts or cache-heavy application workloads. Loopback Docker networking
and transaction round trips are part of the measurements. Large overlapping confidence intervals
mean the run does not establish a reliable ranking. Results from one workstation are observations,
not universal performance claims.

The published raw JMH JSON, run log and machine metadata live in [results](results/). The generated
table and SVG are derived directly from the JSON by [report.py](report.py).
