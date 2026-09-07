# Session reuse validation

Reuse remains opt-in for pure query-builder questions on fixed compute. Native
SQL, native source stages, AUTO, and metadata synchronization bypass the pool.
The default remains zero.

Before enabling reuse on workers that can recycle, include backend
[5f14a881b](https://github.com/altertable-ai/backend/commit/5f14a881b19d6d297c8a2b1be1460090ca7858bc). Without this recovery, a released worker lease
causes the next question using its cached session to fail once. The fix replaces
that session during acquisition, before SQL execution. It uses a typed error
marker and does not replay queries. It is a companion backend change, not part of
the driver JAR or the benchmarked main commit.

## Measured effect

Tested September 7, 2026 through real Metabase **v0.61.2** `/api/dataset`,
Java 21, SDK **0.1.4**, published driver **v0.3.0** (`e8d53d8`) and candidate
**109bd76**. The backend was built at **4f7fd31**. Main advanced to **6c96d18**
during the investigation; both commits have the identical API tree
`3495107fac4b985bcce4513adda4bb84efc268c6`.

The primary runs use persisted DuckLake with PostgreSQL metadata, dedicated local
MinIO buckets, the backend's default **50 API database connections**, and verified
worker thread counts. No compiler or test suite ran during timing. Driver pool
sizes were zero and four. Metabase result caching was bypassed and every result checked
against the generated data. Modes and sizes were interleaved after warmup.

The 20-million-row `SUM(amount * amount)` question gives the following medians
in milliseconds, **20 measured iterations per cell**. The two candidate modes
share one Metabase JVM; the published release runs in a separate JVM.

| Size | Threads / DuckDB limit | v0.3.0 | Candidate off | Pool 4 | Reduction vs off |
| --- | --- | ---: | ---: | ---: | ---: |
| XS | 2 / 2.8 GiB | 71.6 | 71.3 | 54.8 | 23.1% |
| S | 4 / 5.7 GiB | 58.5 | 57.4 | 42.2 | 26.5% |
| M | 8 / 11.4 GiB | 54.6 | 52.1 | 36.3 | 30.2% |
| L | 16 / 22.8 GiB* | 52.4 | 50.1 | 35.3 | 29.6% |
| XL | 32 / 45.7 GiB* | 51.0 | 50.2 | 34.6 | 31.2% |
| AUTO | Per query | 71.8 | 70.2 | 71.6 | Bypasses reuse |

\* L and XL oversubscribe the **14-core, 48 GiB** test host. These are configured
DuckDB limits, not measured memory requirements or production capacity results.
AUTO never sends a pooled session ID; its off/on timing differences are not a
session-reuse benefit.

The mixed sequential workload contains count and grouped count/sum over one
million rows plus the 20-million-row aggregate. With one M worker, medians were
**46.48 / 46.74 / 29.39 ms** for release / candidate off / pool four. Candidate p95
was **56.74 / 38.52 ms**, respectively, across 60 questions per mode. In the
multi-size fleet, candidate initialization medians fell from **17–18.5 ms to 2 ms**
across fixed sizes, consistent with removing session setup work.

Four simultaneous questions produced these local batch completion medians
(20 batches per cell). Mixed batches contain one count, one grouped aggregate,
and two large aggregates. The final control contains four large aggregates.

| Available workers / workload | v0.3.0 | Candidate off | Pool 4 | Reduction vs off |
| --- | ---: | ---: | ---: | ---: |
| One XS, mixed | 146.1 | 143.7 | 65.6 | 54.4% |
| One S, mixed | 125.3 | 128.5 | 51.1 | 60.2% |
| One M, mixed | 110.9 | 119.4 | 44.1 | 63.1% |
| One L, mixed | 121.2 | 101.8 | 44.7 | 56.1% |
| One XL, mixed | 118.1 | 116.4 | 44.2 | 62.0% |
| Four XS, mixed | 76.9 | 78.3 | 66.3 | 15.3% |
| Four XS, four scans | 88.0 | 86.4 | 76.6 | 11.3% |

Worker placement materially changes the result. In both four-XS controls, all
80 reused batch requests stayed on one worker. Independent requests mostly used
two workers, occasionally a third; a fourth was available but remained idle.
The **11–15%** improvements in those controls support bounded local batch gains,
not a claim of improved production fleet throughput. Four slots do not guarantee
four workers or represent a recommended production default.

All **5,460 primary measured questions** returned the expected results. Each was
matched one-to-one to a backend request and a distinct query ID. Traces showed no
overlapping use of a borrowed session, no retained-slot bound violations, and no
AUTO reuse. Each fixed pool retained four session IDs during its measured run.
An additional 5,460 measured questions passed in diagnostic configurations, giving
10,920 in total. Diagnostic runs used file-backed DuckLake metadata and, in some
cases, a one-connection rollback fixture; they do not support production sizing.

[Sizing results](session-reuse-sizing.csv) contain medians, nearest-rank p95,
sample counts, individual workloads, and all primary layouts. These replace the
earlier sizing interpretation. [Earlier paired timings](session-reuse-results.csv)
remain diagnostic evidence from the original fixture.

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

The worker-lease regression test failed against main with HTTP 400 instead of
200, then passed with the companion fix. All 36 HTTP query/cancel/progress tests,
16 session-manager tests and 17 Flight SQL tests passed, along with formatting
and Clippy. Independent review found no blocking issues.

The actual Metabase driver was then tested against the corrected backend: release
the worker behind four cached sessions, run one independent control and eight
pooled questions. All nine succeeded, including the first after release. Four
sessions were replaced and their replacements reused, with exactly nine backend
query requests and nine distinct query IDs.

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

For the large aggregate, also create `main.metabase_session_bench_large` with the
same columns using `range(20000000)`, then use the query builder to sum
`amount * amount`. Test each fixed size with both zero and four reusable slots;
test AUTO separately. Verify actual worker threads and routing before comparing
resource sizes. Keep the backend API pool and DuckLake storage configuration
equal between modes.

## Limits

These are warm, repeated synthetic queries on a local debug backend. They do not
measure production traffic, network latency, browser rendering, multi-pod
failover, large result streaming, arbitrary joins, or memory pressure. Warmup
measurements are separate; the persisted M warmup included a roughly five-second
first grouped query. Twenty samples per query and twenty batches cannot establish
a production tail-latency bound. M and larger sizes have similar warm scan times
on this host; the oversubscribed L/XL results do not justify a production sizing
recommendation.

Native SQL and AUTO receive no session-reuse benefit from this feature. A
production improvement claim requires the actual question mix and deployment
configuration. Pooling can concentrate work on one backend worker.

The pool bounds reusable slots, not total concurrency or server session count.
Overflow stays independent. The HTTP API has no session-close operation, so
retired sessions depend on backend expiration, currently four hours. Explicit
ephemeral execution remains a separate change: the inspected cancel endpoint
requires a registered persistent session and earlier real-backend tests
reproduced HTTP 404 for ephemeral cancellation. The companion expiry fix does
not change that contract.

These checks establish the tested behavior and measured local improvements.
They do not prove an absence of every possible failure.
