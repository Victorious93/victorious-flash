# sccache evaluation for Flash (2026-11-14)

**Decision: keep the existing CI arrangement (`Swatinem/rust-cache@v2`, plain rustc). No workflow or Cargo change was made.**
The measured extra benefit of sccache is small, and it costs a cold-run penalty and a mode switch (`CARGO_INCREMENTAL=0`).

## Method
`scripts/measure-sccache.sh SCCACHE_BIN OUT_DIR` — fresh `CARGO_TARGET_DIR` per run, registry pre-fetched (`cargo fetch --locked`), so every eligible rustc call really runs.
Steps = the Rust parts of `.github/workflows/build.yml`: `cargo test --no-run -p flash-core -p victorious-flash-bridge`, then
`cargo build --release -p victorious-flash-helper -p victorious-flash-bridge`. rustc 1.97.0, sccache v0.10.0 (static release, tarball SHA-256 matches the published checksum),
4 vCPU / 15 GiB host. Raw output: `docs/evidence-sccache/`.

| Run | Wall time | sccache |
|---|---|---|
| 1 plain, cargo-default incremental | 19.7 s | – |
| 2 plain, `CARGO_INCREMENTAL=0` | 17.1 s | – |
| 3 sccache, cold | 24.1 s | 0 hits / 212 misses; 32 uncacheable (29 `crate-type` i.e. binaries/cdylibs/linking, 2 missing input, 1 stdin) |
| 4 sccache, warm, fresh target dir | 14.8 s | 146 hits / 66 misses |

Reading: warm sccache saves about 2.3 s (≈ 13 %) against the already-fast plain run and costs ≈ 7 s extra when cold; the 66 warm misses and 32 uncacheable calls
(link steps, build scripts) bound the benefit. Single runs on a shared VM — expect ±10 % noise, so the difference is within noise for planning purposes.
**Not measured:** the `npx tauri build --bundles appimage,deb` step (needs GTK/WebKit packages absent here), the frontend build, and CI-runner behaviour.
If the bundle step dominates CI time, re-measure there before changing anything.

## If it is ever adopted
`RUSTC_WRAPPER` and `CARGO_INCREMENTAL=0` must be set **only** in the CI cached job, never in `.cargo/config.toml` (developers keep incremental builds). No custom wrapper exists in this repo
today, so nothing needs composing. The device-filtering, target-confirmation, helper-validation, checksum, fsync and read-back code was not touched.
