# Session reuse validation

The driver retains exclusive sessions for pure query-builder questions on fixed
compute. Reuse is opt-in and defaults to zero. Native SQL and native source stages
remain independent because their session state has no reset contract.

## Measured effect

Tested September 7, 2026 with Metabase **v0.61.2**, Java 21, SDK **0.1.4**, driver
main **e8d53d80185b530f06b5546383fd40007908b76e** plus this change, and backend main
**3892f2d6e7d29890df6d809ca986bc0e846eea82**. The final refresh kept driver main
unchanged and advanced backend main to **4f7fd31f0bc080bc09033e7210ce5ed4f0d9a527**,
which changed only dbt files. Both commits have the identical API tree
`3495107fac4b985bcce4513adda4bb84efc268c6`; the latest checkout also passed a final
Metabase smoke run after restarting the fixture.

The actual Metabase `/api/dataset` path ran count, grouped count/sum, and filtered
count/sum questions against a million-row DuckLake table. Two Metabase databases
used the same backend, catalog, credentials, and XS request setting, with session
pool sizes zero and four. Each run warmed all questions, alternated configuration
order, and checked every result against the generated dataset. Metabase result
caching was bypassed. No test suite or compiler ran during timing collection.

| Run | Workload | Samples per configuration | Disabled median | Reused median | Disabled p95 | Reused p95 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Initial | Sequential question | 60 | 36.19 ms | 25.30 ms | 43.03 ms | 29.26 ms |
| Initial | Four-question batch | 20 | 73.71 ms | 34.31 ms | 77.10 ms | 38.37 ms |
| Fresh Metabase process | Sequential question | 60 | 42.99 ms | 28.96 ms | 53.65 ms | 37.35 ms |
| Fresh Metabase process | Four-question batch | 20 | 99.34 ms | 43.65 ms | 111.60 ms | 48.79 ms |
| Final packaged driver | Sequential question | 60 | 38.00 ms | 27.15 ms | 44.01 ms | 32.43 ms |
| Final packaged driver | Four-question batch | 20 | 82.24 ms | 36.58 ms | 88.26 ms | 44.42 ms |

These are 840 measured questions across three runs, all with correct results.
[Paired timings](session-reuse-results.csv) retain each measurement; p95 uses
nearest rank. The final run used the rebuilt JAR after limiting the cancellation
wait to borrowed reusable sessions (SHA-256
`b58cb00c4260481a0aed2e37a7ecbcbf644b59fcc9c81b7ad2686fbe84860b04`).
The first run's backend trace recorded 140 different session IDs
with reuse disabled and four with reuse enabled. All 280 requests used the same
worker. A separate run of the published v0.3.0 JAR passed the same 140 correctness
checks, with medians of 45.04 ms per question and 97.52 ms per four-question batch.
That release control ran separately, so the alternating comparisons above are
the evidence for the optimization's effect.

## Correctness checks

The complete driver suite passes 92 tests and 514 assertions. Session tests drive
the real SDK through HTTP and cover exclusive ownership, overflow without waiting,
credential/database/catalog/schema/compute isolation, incomplete and malformed
streams, SQL failures without replay, empty results, failures before metadata,
explicit caller sessions, delayed cancellation, and invalidation during an active
query. Independent query cleanup also remains asynchronous when a cancellation
acknowledgement is delayed. The full suite exercises the repository's pinned mock
integration. See the [HTTP contract tests](../test/metabase/driver/altertable/lakehouse_contract_test.clj).

Additional checks against the actual latest-main backend and Metabase verified:

- Four expired sessions were replaced and then reused in exactly eight questions.
- 120 questions at concurrency 12 returned correct results. There were 62 pooled
  requests and 58 independent overflow requests, with no overlapping use of a
  pooled session in the recorded backend request intervals.
- Native questions and a native source stage bypassed the pool, including an
  attempted client-supplied compiler marker. Metabase discards that marker before
  compiling and only restores it for pure MBQL
  ([Metabase compiler](https://github.com/metabase/metabase/blob/0c64e27763e13123766434979ed3e41f0cd185bc/src/metabase/query_processor/compile.clj#L74)).
- Two Metabase databases with identical backend connection settings retained
  disjoint pools of four sessions.
- An invalid square-root expression failed once at the backend. Only that pool
  slot was retired; eight subsequent questions succeeded without replaying SQL.
- The driver cancelled an actively streaming ten-million-row query. The backend
  acknowledged `cancelled=true`, and subsequent queries used a new session.
- An idle backend process restart preserved the running Metabase configuration.
  Four stale IDs were replaced and the replacements reused in exactly eight
  successful questions.

## Reproduce

Create this disposable table in a test catalog using an authorized SQL client:

```sql
CREATE TABLE main.metabase_session_bench AS
SELECT i AS id, i % 10 AS bucket,
       CAST(i % 10000 AS DECIMAL(18,2)) AS amount
FROM range(1000000) AS t(i);
```

Install the built driver in Metabase v0.61.2. Add two databases for that catalog
with identical fixed-compute details, set reusable sessions to `0` and `4`, and
sync their metadata. Supply a Metabase session token through the environment:

```sh
python3 bin/benchmark-session-reuse.py \
  http://localhost:3000 BASELINE_DATABASE_ID REUSE_DATABASE_ID > timings.json
```

The runner reads `METABASE_SESSION`, performs 18 warmup questions and 280 measured
questions, asserts the configured modes and expected results, and prints timings
without credentials. It uses Python 3.9+ and the standard library. Run the normal
driver suite with the mock service using `clojure -X:test`.

## Limits

This was a local debug backend using the real API router, scheduler, PostgreSQL,
DuckLake, and embedded workers. The fixture advertised oversized workers and
limited the environment to two compute units to keep allocation equal. It used
one rollback database connection and a two-hour worker lease to accommodate the
long-lived test transaction. It did not model production XS hardware or network
latency. A local recording proxy buffered responses equally in both benchmark
modes; the running-query cancellation check used the backend directly.

Engine caches stayed warm at their defaults. Each timed run used a fresh Metabase
process. The backend was also restarted before the final run. Twenty batches per
configuration do not establish a production tail-latency bound. Production workloads, multi-pod
failover, permission changes during execution, and cancellation before SDK
metadata becomes available were not validated here.

The pool size bounds reusable slots, not total server session count. There is no
HTTP session-close API, so retired sessions depend on backend expiration,
currently four hours
([backend session manager](https://github.com/altertable-ai/backend/blob/3892f2d6e7d29890df6d809ca986bc0e846eea82/api/src/server/session/manager.rs#L32)).
Overflow remains independent. These checks support the
opt-in fixed-compute design; they do not prove an absence of all possible failures.
