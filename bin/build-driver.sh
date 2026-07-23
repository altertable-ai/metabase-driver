#!/usr/bin/env bash

set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ -z "${METABASE_DIR:-}" ]]; then
  echo "METABASE_DIR must point to a Metabase source checkout." >&2
  exit 2
fi

metabase_dir="$(cd "$METABASE_DIR" && pwd)"
target_dir="${TARGET_DIR:-$project_dir/target}"
mkdir -p "$target_dir"
target_dir="$(cd "$target_dir" && pwd)"

if [[ ! -f "$metabase_dir/deps.edn" ]]; then
  echo "METABASE_DIR does not contain a Metabase source checkout: $metabase_dir" >&2
  exit 2
fi

driver_deps="{:aliases {:altertable-driver {:extra-deps {ai.altertable/metabase-driver {:local/root \"$project_dir\"}}}}}"

cd "$metabase_dir"
clojure \
  -Sdeps "$driver_deps" \
  -X:build:altertable-driver \
  build-drivers.build-driver/build-driver! \
  "{:driver :altertable, :project-dir \"$project_dir\", :target-dir \"$target_dir\"}"

artifact="$target_dir/altertable.metabase-driver.jar"
if [[ ! -f "$artifact" ]]; then
  echo "Metabase driver build did not create $artifact" >&2
  exit 1
fi

echo "Built $artifact"
