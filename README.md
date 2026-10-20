<p align="center">
  <img src="assets/logo.png" alt="victorious-flash logo" width="200">
</p>

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

Victorious Flash is a Linux desktop app that takes you from "I want to try Linux" to a bootable, verified USB stick or SD card, or to a boot entry on your own disk. Choose an image from a curated list, and the app downloads it, checks it against the vendor's checksum, and then writes it to a removable drive or sets it up for direct boot. A built-in guide then walks you through installing it.

Screenshots coming soon.

## Modes

1. **Direct install** (no stick): download and verify the ISO, then boot it from this computer's own disk with a GRUB loopback entry that the app generates. You paste it into `/etc/grub.d/40_custom` and run `update-grub`; the app never edits boot config itself. Currently Ubuntu and Linux Mint only (the casper loopback recipe).
2. **USB**: the safe flasher. It lists only removable/hotplug whole disks from `lsblk` and excludes NVMe/SATA internals, loop/zram/optical, read-only, anything over 2 TiB, and anything hosting `/`, `/boot`, `/home`, swap and so on. You must **type the device name** to confirm. The write happens in a tiny privileged helper launched with **pkexec**, which re-validates the target itself, writes, `fsync`s and **reads back to verify** the SHA-256.
3. **SD card**: the same writer and the same safeguards, filtered to SD cards. The catalog includes **Raspberry Pi OS** (Lite 64-bit, Desktop 64-bit, Lite 32-bit, pinned to the 2026-10-06 trixie release). It is shipped as `.img.xz`, and the helper decompresses it while writing (`mmcblkN` readers, or USB card readers). eMMC internal storage (any `mmcblkN` with `boot0`/`rpmb` siblings) is never offered.

Common to all modes:

- **Distro picker**: a curated catalog (`catalog/distros.json`) with official image and checksum URLs for Ubuntu, Fedora Workstation, Linux Mint Cinnamon, Debian, Pop!_OS, openSUSE Tumbleweed, Arch Linux and Raspberry Pi OS.
- **Verified downloads**: live download progress, then a SHA-256 check against the vendor's checksum file (GNU and BSD formats) or a pinned hash.
- **Install guide**: backups, dual-boot vs. wipe, boot-menu and BIOS keys by vendor, UEFI vs. legacy, Secure Boot and MOK, and partitioning basics.

There is also an [Android app](#android-phone-as-a-bootable-usb) and a [headless fleet bridge](#fleet-bridge-droidcommand-ai).

## Install

The Victorious Flash desktop app runs on Linux only. Windows and macOS are not planned. The catalog ships x86_64 ISOs, plus the Raspberry Pi OS images for SD cards.

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
2. **Flash USB or SD card**: plug in the drive and click **Refresh** if it is not listed. Select it, type its device name to confirm, and click **Erase & flash**. Everything on the drive is erased. Authenticate when the polkit prompt appears. (For Ubuntu and Linux Mint you can instead use **Direct install** to boot the ISO from this computer's own disk, as described in [Modes](#modes).)
3. **Install guide**: step through the in-app guide, then reboot from the USB stick or SD card to install.

## Build from source

The project is a Cargo workspace (`flash-core`, `flash-helper`, `flash-bridge`, and the Tauri app in `src-tauri`) with a React + TypeScript + Vite frontend. You need Rust (stable), Node.js 20 and npm.

```bash
# System dependencies (Debian / Ubuntu)
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev

npm install
cargo test -p flash-core -p victorious-flash-bridge
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
crates/flash-bridge   victorious-flash-bridge: headless DroidCommand AI fleet bridge (JSON lines over SSH)
src-tauri             Tauri v2 app: list_distros, list_devices, download_iso, flash commands
src                   React UI: DistroPicker, Flasher, Guide screens
catalog               distro catalog (distros.json)
android               Android app: phone as a bootable USB (see android/README.md)
docs                  design notes (ANDROID_PLAN.md)
packaging             polkit policy for the helper
```

## Roadmap

- [x] SHA-256 verification of downloads and read-back verification after flashing
- [ ] GPG signature verification (signature URLs are in the catalog, but verification is a stub and the UI reports `not-checked`)
- [ ] Bundle the helper in the AppImage
- [ ] Download resume, mirror selection, catalog auto-update

## Android: phone as a bootable USB

For people with a phone but no USB stick, `android/` holds a Kotlin + Jetpack Compose app that turns a **rooted** phone into a read-only USB disk or CD-ROM holding one verified ISO/IMG, so a PC can boot it. Root is needed because Android has no non-root API for acting as a USB disk, and some kernels have no `mass_storage` function at all, in which case the app says so. See [android/README.md](android/README.md) for how it works and its safety rules, and [docs/ANDROID_PLAN.md](docs/ANDROID_PLAN.md) for the plan and later phases.

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
Entries accept an optional `kind` (`linux-iso` by default, `rootforge-os`, or `raspberry-pi-os`), an optional `image_format` (`iso` by default, a raw `img`, or `img-xz`) and optional `notes`.

**`.img.xz` writes** (`flash_core::image::write_verified`, used by the pkexec helper on a single open file):
1. The SHA-256 of the *compressed* file, which is what vendors publish, is checked before any byte is written.
2. The size check uses the decompressed size from the xz index, not the download size. An image that expands past the card is refused.
3. The image is stream-decompressed (`xz2`, statically linked liblzma) while it is written, and the *decompressed* stream is hashed as it goes.
4. The device read-back must match that decompressed hash.

The bridge applies the same rules for `flash.image.write`. Its result reports `image_sha256` (compressed) and `written_sha256` (decompressed). Use those two for `flash.readback.verify` later. Any entry can be written in USB or SD mode. Direct install is offered only for ISOs with a known loopback recipe. No RootForge OS entry ships yet, because `Victorious93/rootforge-os` has no published release with an image and checksum. Its `release.yml` is meant to produce one: it attaches the ISO with SHA-256 sums to a draft release, which a maintainer then publishes.

## License

MIT, see [LICENSE](LICENSE). Copyright (c) 2026 Don Victor / Victorious.
