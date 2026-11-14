#!/usr/bin/env bash
# Does sccache add anything over what CI already does (Swatinem/rust-cache + plain rustc)? Measured with FRESH target dirs.
#   scripts/measure-sccache.sh SCCACHE_BIN RESULT_DIR
# Steps mirror .github/workflows/build.yml's Rust parts (the Tauri bundle needs GTK/WebKit and is NOT measured here):
#   cargo test --no-run -p flash-core -p victorious-flash-bridge   and   cargo build --release -p victorious-flash-helper -p victorious-flash-bridge
# Safety code is untouched; this script only builds. RUSTC_WRAPPER is set ONLY for the sccache runs and CARGO_INCREMENTAL=0 only there
# (sccache cannot cache incremental compilations); ordinary developer builds keep incremental compilation.
set -euo pipefail
sc="$(readlink -f "${1:?sccache}")"; res="${2:?result dir}"; mkdir -p "$res"
cd "$(dirname "$0")/.."
export CARGO_HOME="${CARGO_HOME:-$HOME/.cargo}"
cargo fetch --locked >/dev/null 2>&1
cache="$res/sccache-dir"; rm -rf "$cache"
steps() { cargo test --no-run -p flash-core -p victorious-flash-bridge >"$1.log" 2>&1 && cargo build --release -p victorious-flash-helper -p victorious-flash-bridge >>"$1.log" 2>&1; }
run() { # label env...
  local label="$1"; shift; local td; td="$(mktemp -d /var/tmp/flash-target.XXXX)"
  local t0 t1; t0=$(date +%s.%N)
  ( export CARGO_TARGET_DIR="$td"; for kv in "$@"; do export "$kv"; done; cd "$PWD"; steps "$res/$label" ) && rc=0 || rc=$?
  t1=$(date +%s.%N)
  local stats="{}"
  if [[ " $* " == *RUSTC_WRAPPER* ]]; then stats="$(SCCACHE_DIR="$cache" "$sc" --show-stats --stats-format json | python3 -c 'import sys,json; s=json.load(sys.stdin)["stats"]; h=sum(s["cache_hits"]["counts"].values()); m=sum(s["cache_misses"]["counts"].values()); print(json.dumps({"hits":h,"misses":m,"compile_requests":s["compile_requests"],"requests_not_cacheable":s["requests_not_cacheable"],"non_cacheable_compilations":s["non_cacheable_compilations"],"not_cached_reasons":s["not_cached"]}))')"; fi
  python3 - "$label" "$rc" "$t0" "$t1" "$stats" "$td" >> "$res/results.jsonl" <<'PY'
import json,sys; l,rc,t0,t1,st,td=sys.argv[1:7]
import subprocess; sz=subprocess.run(["du","-sm",td],capture_output=True,text=True).stdout.split()[0]
print(json.dumps({"label":l,"exit":int(rc),"wall_s":round(float(t1)-float(t0),1),"target_dir_mb":int(sz),"sccache":json.loads(st)}))
PY
  rm -rf "$td"
}
: > "$res/results.jsonl"
{ echo "rustc: $(rustc --version)"; echo "cargo: $(cargo --version)"; echo "sccache: $("$sc" --version)"; echo "cpus: $(nproc)"; echo "date: $(date -u +%FT%TZ)"; } > "$res/env.txt"
run 1-plain-default-incremental
run 2-plain-incremental-off CARGO_INCREMENTAL=0
export SCCACHE_DIR="$cache" SCCACHE_IDLE_TIMEOUT=0
"$sc" --stop-server >/dev/null 2>&1 || true; "$sc" --start-server >/dev/null; "$sc" --zero-stats >/dev/null
run 3-sccache-cold CARGO_INCREMENTAL=0 "RUSTC_WRAPPER=$sc" "SCCACHE_DIR=$cache"
"$sc" --zero-stats >/dev/null
run 4-sccache-warm-fresh-target CARGO_INCREMENTAL=0 "RUSTC_WRAPPER=$sc" "SCCACHE_DIR=$cache"
du -sm "$cache" | awk '{print "sccache_cache_mb=" $1}' >> "$res/env.txt"; "$sc" --stop-server >/dev/null 2>&1 || true
cat "$res/results.jsonl"
