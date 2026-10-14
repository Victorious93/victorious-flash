<p align="center"><img src="src-tauri/icons/128x128.png" width="96" alt="Victorious Flash logo"></p>
<h1 align="center">⚡ Victorious Flash</h1>
<p align="center"><strong>Pick a Linux. Verify it. Flash it. Install it — victoriously.</strong></p>

Victorious Flash is a Linux desktop app (Tauri v2 · Rust · React + TypeScript + Vite) that takes you from "I want to try Linux" to a bootable, verified USB stick and a step-by-step install guide.

## Features (MVP)
1. **Distro picker** — curated catalog (`catalog/distros.json`): Ubuntu, Fedora, Linux Mint, Debian, Pop!_OS, openSUSE Tumbleweed, Arch Linux, with official ISO and checksum URLs. Downloads with live progress and verifies **SHA-256** against the vendor's checksum file (GNU and BSD formats) or a pinned hash.
2. **Safe USB flasher** — lists only removable/hotplug whole disks from `lsblk` (excludes NVMe/SATA internals, loop/zram/optical, read-only, >2 TiB, and anything hosting `/`, `/boot`, `/home`, swap…). You must **type the device name** to confirm. Writing happens in a tiny privileged helper launched with **pkexec**, which re-validates the target itself, writes, `fsync`s and **reads back to verify** the SHA-256.
3. **Install guide** — backups, dual-boot vs wipe, boot-menu/BIOS keys by vendor, UEFI vs legacy, Secure Boot & MOK, partitioning basics.

## Architecture
```
crates/flash-core     pure Rust: catalog, checksum parsing/verify, lsblk device filter (unit-tested)
crates/flash-helper   victorious-flash-helper — root helper run via pkexec (write + read-back verify)
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
