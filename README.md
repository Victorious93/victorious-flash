<p align="center"><img src="assets/logo-512.png" width="200" alt="Victorious Flash logo"></p>
<h1 align="center">Victorious Flash</h1>
<p align="center"><strong>Pick a Linux. Verify it. Flash it. Install it.</strong></p>

<p align="center">
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-green"></a>
  <img alt="Platform: Linux" src="https://img.shields.io/badge/platform-Linux-blue">
  <img alt="Built with Rust" src="https://img.shields.io/badge/Rust-2021-orange?logo=rust">
  <img alt="Built with TypeScript" src="https://img.shields.io/badge/TypeScript-React-3178c6?logo=typescript&logoColor=white">
  <img alt="Tauri v2" src="https://img.shields.io/badge/Tauri-v2-24c8db?logo=tauri&logoColor=white">
</p>

## Overview

Victorious Flash is a Linux desktop app that takes you from "I want to try Linux" to a bootable, verified USB stick. Choose a distribution from a curated list, and the app downloads its official ISO, checks it against the vendor's checksum, and writes it to a USB drive. A built-in guide then walks you through installing it.

Screenshots coming soon.

## Features

- **Distro picker**: a curated catalog (`catalog/distros.json`) with the official ISO and checksum URLs for Ubuntu, Fedora Workstation, Linux Mint Cinnamon, Debian, Pop!_OS, openSUSE Tumbleweed and Arch Linux.
- **Verified downloads**: live download progress, then a SHA-256 check against the vendor's checksum file (GNU and BSD formats) or a pinned hash.
- **Safe USB flasher**: lists only removable whole disks from `lsblk`. It excludes internal NVMe/SATA drives, loop/zram/optical devices, read-only devices, disks over 2 TiB, and anything hosting `/`, `/boot`, `/home` or swap. You must type the device name to confirm.
- **Privileged helper**: writing runs in a small helper launched through `pkexec`. The helper re-validates the target, writes, calls `fsync`, and reads the data back to verify its SHA-256.
- **Install guide**: backups, dual-boot vs. wipe, boot-menu and BIOS keys by vendor, UEFI vs. legacy, Secure Boot and MOK, and partitioning basics.

## Install

Victorious Flash runs on Linux only. Windows and macOS are not planned. The catalog ships x86_64 ISOs.

There is no published release yet. Until there is one, get a build in one of two ways:

- **From CI**: open the latest successful run of the [Build Linux bundles](.github/workflows/build.yml) workflow on the repository's Actions tab and download the `victorious-flash-linux` artifact. It contains an AppImage and a `.deb`.
- **From source**: follow [Build from source](#build-from-source).

Then install one of the bundles:

```bash
# Debian / Ubuntu: installs the app, the polkit policy and the helper
sudo apt install ./path/to/victorious-flash*.deb
```

The `.deb` depends on `pkexec` (polkit) and `util-linux`, and installs the helper at `/usr/lib/victorious-flash/victorious-flash-helper`.

The AppImage does not bundle the helper yet. To flash from an AppImage, point it at a helper you have installed:

```bash
VFLASH_HELPER=/usr/lib/victorious-flash/victorious-flash-helper ./Victorious*.AppImage
```

## Usage

1. **Choose distro**: select a Linux from the list and click **Download & verify SHA-256**. The ISO is saved to `~/Downloads/victorious-flash/` (or your system's download folder) and checked against the vendor's checksum.
2. **Flash USB**: plug in a USB drive and click **Refresh** if it is not listed. Select the drive, type its device name to confirm, and click **Erase & flash**. Everything on the drive is erased. Authenticate when the polkit prompt appears.
3. **Install guide**: step through the in-app guide, then reboot from the USB stick to install.

## Build from source

The project is a Cargo workspace (`flash-core`, `flash-helper`, and the Tauri app in `src-tauri`) with a React + TypeScript + Vite frontend. You need Rust (stable), Node.js 20 and npm.

```bash
# System dependencies (Debian / Ubuntu)
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev

npm install
cargo test -p flash-core
cargo build -p victorious-flash-helper
VFLASH_HELPER=$PWD/target/debug/victorious-flash-helper npm run tauri dev
```

To build the AppImage and `.deb`:

```bash
cargo build --release -p victorious-flash-helper
npx tauri build --bundles appimage,deb
```

Bundles are written to `target/release/bundle/`. CI builds both on every push to `main` and on pull requests.

## Project layout

```
crates/flash-core     catalog, checksum parsing/verification, lsblk device filter (unit-tested)
crates/flash-helper   victorious-flash-helper: root helper run via pkexec (write + read-back verify)
src-tauri             Tauri v2 app: list_distros, list_devices, download_iso, flash commands
src                   React UI: DistroPicker, Flasher, Guide screens
catalog               distro catalog (distros.json)
packaging             polkit policy for the helper
```

## Roadmap

- [x] SHA-256 verification of downloads and read-back verification after flashing
- [ ] GPG signature verification (signature URLs are in the catalog, but verification is a stub and the UI reports `not-checked`)
- [ ] Bundle the helper in the AppImage
- [ ] Download resume, mirror selection, catalog auto-update

## License

MIT, see [LICENSE](LICENSE). Copyright (c) 2026 Don Victor / Victorious.
