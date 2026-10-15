<p align="center"><img src="docs/logo.png" width="160" alt="Victorious Flash logo"></p>
<h1 align="center">⚡ Victorious Flash</h1>
<p align="center"><strong>Pick a Linux. Verify it. Flash it. Install it — victoriously.</strong></p>

Victorious Flash is a Linux desktop app (Tauri v2 · Rust · React + TypeScript + Vite) that takes you from "I want to try Linux" to a bootable, verified USB stick and a step-by-step install guide.

## Branding
Victorious navy (`#0B1530` base, `#13214A` panels) with an electric-green accent (`#39FF14`, gradient to `#00FF85`) for buttons, highlights, progress bars and the **V** icon. Red (`#FF4D5E`) is reserved strictly for danger: erase/flash warnings and errors.

## Three modes
1. **Direct install** (no stick): download and verify the ISO, then boot it from this computer's own disk with a GRUB loopback entry that the app generates. You paste it into `/etc/grub.d/40_custom` and run `update-grub`; the app never edits boot config itself. Currently Ubuntu and Linux Mint only (the casper loopback recipe).
2. **USB**: the safe flasher. It lists only removable/hotplug whole disks from `lsblk` and excludes NVMe/SATA internals, loop/zram/optical, read-only, anything over 2 TiB, and anything hosting `/`, `/boot`, `/home`, swap and so on. You must **type the device name** to confirm. The write happens in a tiny privileged helper launched with **pkexec**, which re-validates the target itself, writes, `fsync`s and **reads back to verify** the SHA-256.
3. **SD card**: the same writer and the same safeguards, filtered to SD cards (`mmcblkN` readers, or USB card readers). eMMC internal storage (any `mmcblkN` with `boot0`/`rpmb` siblings) is never offered.

Common to all modes: a curated catalog (`catalog/distros.json`) with official image and checksum URLs. Every download is verified with **SHA-256** against the vendor's checksum file (GNU and BSD formats) or a pinned hash. The **install guide** covers backups, dual-boot vs wipe, boot-menu/BIOS keys by vendor, UEFI vs legacy, Secure Boot & MOK, and partitioning basics.

## Architecture
```
crates/flash-core     pure Rust: catalog, checksum parsing/verify, lsblk device filter (unit-tested)
crates/flash-helper   victorious-flash-helper — root helper run via pkexec (write + read-back verify)
crates/flash-bridge   victorious-flash-bridge — headless DroidCommand AI fleet bridge (JSON lines over SSH)
src-tauri             Tauri v2 app: commands list_distros, list_devices, download_iso, flash
src                   React UI: screens/DistroPicker, Flasher, Guide
packaging             polkit policy for the helper
```

## Develop
```bash
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev   # Debian/Ubuntu
npm install
cargo test -p flash-core
cargo build -p victorious-flash-helper
VFLASH_HELPER=$PWD/target/debug/victorious-flash-helper npm run tauri dev
```

## Package
```bash
cargo build --release -p victorious-flash-helper
npx tauri build --bundles appimage,deb
```
The `.deb` installs the helper at `/usr/lib/victorious-flash/victorious-flash-helper` plus a polkit policy. CI (`.github/workflows/build.yml`) builds both bundles on every push.

## Status / roadmap
- [x] SHA-256 verification of downloads and read-back verification after flashing
- [ ] **GPG signature verification** — signature URLs are in the catalog, verification is a stub (UI reports `not-checked`)
- [ ] AppImage: bundle the helper (currently the AppImage needs `VFLASH_HELPER` pointing at an installed helper)
- [ ] Download resume, mirror selection, catalog auto-update
- [ ] Windows/macOS: not planned — Linux only

## License
MIT © 2026 Don Victor / Victorious

## Fleet bridge (DroidCommand AI)
`crates/flash-bridge` builds `victorious-flash-bridge`, a headless node that DroidCommand AI's `core-flash` module drives over SSH with a **pinned host key**. It uses the same newline-delimited JSON contract (protocol major **1**) as the RootForge bridge:

```
request : {"protocol_major":1,"request_id":"…","target_node_id":"vf-<32hex>","operation":"flash.devices.list","timeout_ms":10000,"parameters":{}}
response: {"protocol_major":1,"request_id":"…","node_id":"vf-<32hex>","ok":true,"result":{…}}   | "ok":false,"error":{"category","message"}
progress: {"protocol_major":1,"request_id":"…","node_id":"…","ok":true,"progress":{"phase":"write","done":N,"total":M}}   (never final)
```

| Operation | Parameters | Notes |
|---|---|---|
| `flash.capabilities.get` | – | version, node id, operation list |
| `flash.catalog.list` | – | embedded catalog, or the last verified update |
| `flash.catalog.update` | `url` (https), `sha256` | pinned hash required; validated before it is stored |
| `flash.devices.list` | – | same lsblk safety filter as the GUI |
| `flash.verify` | `image`, `sha256` | hashes an image file on the node |
| `flash.readback.verify` | `device`, `sha256`, `length` | via `pkexec victorious-flash-helper verify`; streams progress |
| `flash.image.write` | `image`, `device`, `sha256`, **`confirm`**, optional `catalog_id` | `confirm` must equal the device name (e.g. `sdb`) or path. The image hash is checked first, and the write goes **only** through the pkexec helper, which re-validates and reads back. Streams progress |

Error categories: `malformed_request`, `protocol_mismatch`, `node_mismatch`, `unsupported_operation`, `invalid_parameters`, `safety_refused`, `verify_failed`, `internal`.

Node setup: `victorious-flash-bridge node-id` prints the node id, creating it under `$XDG_STATE_HOME/victorious-flash-bridge/` on first run. Then add the controller's key to `~/.ssh/authorized_keys` with a forced command:
`command="/usr/bin/victorious-flash-bridge serve",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA…`
SSH sessions have no polkit agent, so remote writes need a polkit rule that authorises `com.victorious.flash` for that user. That rule is deliberately not shipped by default.

### Catalog: RootForge OS images
Entries accept optional `kind` (`linux-iso` by default, or `rootforge-os`), `image_format` (`iso` by default, or a raw `img`) and `notes`. Any entry can be written in USB or SD mode. Direct install is offered only for ISOs with a known loopback recipe. No RootForge OS entry ships yet, because `Victorious93/rootforge-os` has no published release with an image and checksum. Its `release.yml` is meant to produce one: it attaches the ISO with SHA-256 sums to a draft release, which a maintainer then publishes.
