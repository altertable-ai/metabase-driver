#!/usr/bin/env bash

set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
config_dir="$project_dir/.clj-kondo/config/modules"
config_file="$config_dir/config.edn"

# Pinned to the Metabase version in deps.edn :test alias.
metabase_tag="v0.61.2"
metabase_sha="0c64e27763e13123766434979ed3e41f0cd185bc"

if [[ -f "$config_file" ]]; then
  exit 0
fi

mkdir -p "$config_dir"

gitlibs_config="$HOME/.gitlibs/commits/metabase/metabase/${metabase_sha}/.clj-kondo/config/modules/config.edn"
if [[ -f "$gitlibs_config" ]]; then
  cp "$gitlibs_config" "$config_file"
  exit 0
fi

curl -fsSL \
  "https://raw.githubusercontent.com/metabase/metabase/${metabase_tag}/.clj-kondo/config/modules/config.edn" \
  -o "$config_file"
