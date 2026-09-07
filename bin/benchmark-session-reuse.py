#!/usr/bin/env python3
"""Compare two Metabase databases over the fixture in docs/session-reuse.md. Python 3.9+."""

import argparse
import json
import math
import os
import statistics
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from typing import Optional


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="Metabase URL, without /api")
    parser.add_argument("baseline_database", type=int, help="Database ID with reuse disabled")
    parser.add_argument("reuse_database", type=int, help="Database ID with reuse enabled")
    args = parser.parse_args()
    token = os.environ["METABASE_SESSION"]

    def api(path: str, payload: Optional[dict] = None) -> dict:
        request = urllib.request.Request(
            args.url.rstrip("/") + "/api" + path,
            data=None if payload is None else json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "X-Metabase-Session": token},
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    queries = {}
    settings = []
    for label, database_id in (("disabled", args.baseline_database), ("reused", args.reuse_database)):
        database = api(f"/database/{database_id}")
        assert database["engine"] == "altertable"
        details = database["details"]
        assert (int(details.get("session-pool-size", 0)) > 0) == (label == "reused")
        settings.append(details)
        tables = api(f"/database/{database_id}/metadata")["tables"]
        table, = [table for table in tables
                  if table["name"] == "metabase_session_bench" and table["schema"] == "main"]
        fields = {field["name"]: ["field", field["id"], None] for field in table["fields"]}
        for kind, clauses in {
            "count": {"aggregation": [["count"]]},
            "group": {"aggregation": [["count"], ["sum", fields["amount"]]],
                      "breakout": [fields["bucket"]]},
            "filter": {"aggregation": [["count"], ["sum", fields["amount"]]],
                       "filter": [">", fields["id"], 700000]},
        }.items():
            queries[label, kind] = {
                "database": database_id, "type": "query",
                "query": dict(clauses, **{"source-table": table["id"]}),
                "middleware": {"ignore-cache": True},
            }
    for key in ("base-url", "catalog", "schema", "username", "compute-size"):
        assert settings[0].get(key) == settings[1].get(key), f"Different {key}"
    assert settings[1].get("compute-size") in ("XS", "S", "M", "L", "XL")

    expected = {
        "count": [[1000000]],
        "filter": [[299999, 1499850000]],
        "group": [[bucket, 100000, 499500000 + 100000 * bucket] for bucket in range(10)],
    }

    def run_query(label: str, kind: str) -> dict:
        started = time.time()
        timer = time.perf_counter()
        result = api("/dataset", queries[label, kind])
        elapsed = (time.perf_counter() - timer) * 1000
        assert result["status"] == "completed", result.get("error")
        assert not result.get("cached")
        assert sorted(result["data"]["rows"]) == expected[kind], f"Wrong {label} {kind} result"
        return {"mode": label, "query": kind, "started": started, "elapsed_ms": elapsed}

    for _ in range(3):
        for label in ("disabled", "reused"):
            for kind in expected:
                run_query(label, kind)

    sequential = []
    batches = []
    for iteration in range(20):
        order = ("disabled", "reused") if iteration % 2 == 0 else ("reused", "disabled")
        for label in order:
            for kind in expected:
                sequential.append(run_query(label, kind))
    with ThreadPoolExecutor(max_workers=4) as executor:
        for iteration in range(20):
            order = ("disabled", "reused") if iteration % 2 == 0 else ("reused", "disabled")
            for label in order:
                timer = time.perf_counter()
                jobs = [executor.submit(run_query, label, kind)
                        for kind in ("count", "group", "filter", "group")]
                results = [job.result() for job in jobs]
                batches.append({"mode": label, "elapsed_ms": (time.perf_counter() - timer) * 1000,
                                "queries": results})

    def summarize(records: list) -> dict:
        summary = {}
        for label in ("disabled", "reused"):
            values = sorted(record["elapsed_ms"] for record in records if record["mode"] == label)
            summary[label] = {"n": len(values), "median_ms": statistics.median(values),
                              "p95_ms": values[math.ceil(.95 * len(values)) - 1]}
        return summary

    print(json.dumps({"summary": {"sequential": summarize(sequential), "batches_of_four": summarize(batches)},
                      "sequential": sequential, "batches": batches}, indent=2))


if __name__ == "__main__":
    main()
