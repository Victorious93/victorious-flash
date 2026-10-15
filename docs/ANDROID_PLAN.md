# Victorious Flash for Android — Plan

Status: P0 (plan only). No code in this PR.

## Goal
An Android APK version of Victorious Flash, written in **Kotlin + Jetpack Compose**, living in the `android/` folder of this repo. It flashes drives from a phone or tablet over USB OTG, and joins the **DroidCommand AI fleet** (`Victorious93/droidcommand-AI`) as a companion.

## What the APK must do
1. **Flash OS images to USB drives over OTG — no root.** Use the Android USB Host API to talk to the drive directly with USB Mass Storage Bulk-Only Transport (BOT) and SCSI commands (READ CAPACITY, WRITE(10)/(16), SYNCHRONIZE CACHE) to write raw blocks, like libaums does. Root fallback: if the device is rooted, write to `/dev/block/...` directly.
2. **Write Raspberry Pi SD images.** Go through a USB SD card reader over OTG (same raw path). The phone's own internal SD slot needs root, so it is root-only. Stream-decompress `.img.xz` (and `.gz`, `.zip`) while writing, so the full image never sits on the phone.
3. **Make a Ventoy-style multi-ISO USB.** From Android, write the layout: partition table (MBR or GPT), a large FAT32/exFAT data partition, and a small EFI/boot partition. Then copy ISOs onto the data partition as files.
   - **Option A — reuse Ventoy:** Ventoy is GPLv3. We can ship its prebuilt boot images unchanged if we follow GPLv3 (credit it, include the license, link the matching source, and keep the parts we ship as clearly separate works). Pro: boots lots of ISOs, works with existing Ventoy tools. Con: license work, have to keep up with Ventoy's on-disk format.
   - **Option B — our own GRUB2 loopback:** GRUB2 (also GPLv3) EFI + BIOS images plus a generated `grub.cfg` that uses `loopback` to boot each ISO. Pro: simple and we control it. Con: only works for ISOs that support loopback boot.
   - Plan: prototype B first, look at A for broader ISO support; make the call in P4.
4. **Curated distro list.** Each entry is tagged with what it supports: `loopback` (works on the multiboot USB), `dd-only` (must be written raw), `pi` (Raspberry Pi image). Reuse/extend the existing `catalog/` data.

## Safety rules (always on)
- Check the image checksum (SHA-256) **before** writing; verify by reading back after writing.
- Big, clear warnings that show the target's name, size and vendor.
- **Typed confirmation** (e.g. type the drive size or `ERASE`) before any write.
- Never wipe anything without that confirmation — not from the UI, not from the fleet.
- Refuse non-removable devices. Only USB mass storage seen through USB Host is allowed; internal storage is never a target (root mode included, unless the user explicitly picks a removable SD slot).

## Joining the DroidCommand fleet
- The APK registers as a **DroidCommand companion**: an AIDL bound service guarded by a **signature permission**, listed in `KnownCompanions` with a `CompanionDescriptor` (from `core-companion`), and checked by `DependencyGuard`.
- Capability IDs, for example:
  - `flash.devices.list` — read-only, list attached drives.
  - `flash.image.verify` — check an image's checksum.
  - `flash.image.write` — write an image. **Needs per-call approval on the phone** (same typed confirmation as the UI).
  - `flash.multiboot.create`, `flash.multiboot.addIso` — also per-call approval.
- Coordinate with the existing desktop **flash-bridge** protocol (the SSH JSON bridge used for external nodes): same message shapes and capability names, so the fleet can send a job to either desktop or phone.

## Phases
- **P0 — Plan.** This document.
- **P1 — OTG detection + read-only inventory.** `android/` Gradle project, Compose UI, USB permission flow, list drives (vendor, size, removable), read sectors only. No writes.
- **P2 — Raw image writer with verification.** BOT/SCSI write path, checksum before, read-back verify after, progress, cancel, all safety prompts.
- **P3 — Pi SD + xz.** SD readers over OTG, streaming `.img.xz` decompression, Pi catalog entries, root-only internal SD slot option.
- **P4 — Ventoy-style multiboot.** Partition table + FAT/exFAT + EFI writer, GRUB2 loopback config (and/or Ventoy images after the license call), ISO copy, loopback catalog tags.
- **P5 — Fleet companion.** AIDL service, signature permission, KnownCompanions/CompanionDescriptor entry, DependencyGuard, capabilities with per-call approval, flash-bridge compatibility.
- **P6 — Real-device testing.** Several phones (Android 10–15), hubs, sticks, SD readers; boot-test the results on real PCs and Pis.

## Risks and unknowns
- **USB BOT reliability:** some sticks/readers have quirks, stall, or need resets; long writes may fail midway.
- **Power over OTG:** phones may not power hungry drives or readers; may need a powered hub.
- **Write speed:** user-space BOT through the Host API is slower than a desktop; large images may take a long time and heat the phone.
- **Android 14+ USB permission UX:** permission dialogs, re-asks after replug, background limits, foreground-service rules for long writes.
- **exFAT writing** needs our own code or a library; FAT32 has a 4 GB file limit for ISOs.
- **Licensing:** GPLv3 for Ventoy/GRUB2 boot images affects how we ship.
- **Root fallback** varies a lot by device.

## Rule
PRs merge only when all tests are green.
