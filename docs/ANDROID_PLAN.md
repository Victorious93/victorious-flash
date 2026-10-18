# Victorious Flash for Android — Plan

Status: P0 (plan only). No code in this PR.

## Goal
Don often has **no USB stick, only a phone**. So the first job of the Android app is to **turn the phone into a bootable USB drive**: plug the phone into a PC, and the PC sees an ISO/IMG as a USB disk and/or CD-ROM, boots it, and runs or installs Linux (like DriveDroid).

Writing to real USB sticks and SD cards comes later.

Built in **Kotlin + Jetpack Compose**, in the `android/` folder of this repo. Later it joins the **DroidCommand AI fleet** (`Victorious93/droidcommand-AI`) as a companion.

## The honest part: root is needed
- Android has **no non-root API** that lets an app pretend to be a USB disk. Normal apps only get MTP/PTP.
- On nearly all phones this mode needs **root**. The app changes the kernel's USB gadget setup:
  - **Modern kernels (configfs):** under `/config/usb_gadget/<gadget>/`, create `functions/mass_storage.0`, set `lun.0/file` to the ISO, set `lun.0/cdrom=1` (CD-ROM) or `0` (disk), `lun.0/ro=1`, `lun.0/removable=1`, link the function into the config, then bind by writing the controller name (from `/sys/class/udc/`) to `UDC`.
  - **Old kernels (legacy `android_usb`):** `/sys/class/android_usb/android0/` with `functions=mass_storage` and `f_mass_storage/lun/file`.
- **Some kernels have no `mass_storage` function at all.** Then this mode can't work on that phone without a custom kernel, and the app has to say so plainly.
- Without root, the app still verifies and manages images, and points the user to the later modes (OTG writing) or a desktop.

## Phases
### P0 — Plan
This document.

### P1 — Phone as bootable USB (top priority)
- **Capability check**, shown as a clear checklist: root available? configfs mounted / `usb_gadget` present? `mass_storage` function supported (try creating it, or check for `usb_f_mass_storage`)? A UDC controller found? Legacy `android_usb` instead? Show the result as "works / doesn't work, and why".
- **Expose one verified ISO, read-only**, as a removable disk or a CD-ROM (the user picks; disk is the default for hybrid ISOs, CD-ROM for ISO-only images).
- Verify the SHA-256 before exposing it.
- **On stop** (button, unplug, app crash or reboot): unbind, remove our function, and **restore normal USB/MTP**. Back up the original gadget setup before changing anything, and keep a "restore USB" action.
- **Never expose phone partitions** or block devices: only regular image files in the app's storage, opened read-only. Reject `/dev/block/*` and symlinks to it.
- Long-running foreground service with a visible notification while exposed.

### P2 — Multi-ISO image on the phone (Ventoy-style)
- One disk image file on the phone: partition table + EFI/boot partition + FAT32/exFAT data partition holding many ISOs; a GRUB2 `grub.cfg` that boots each ISO with `loopback`.
- Exposed through the same gadget mode (this one as a disk, may be read-write so ISOs can be added from the phone side when not exposed).
- Licensing: GRUB2 and Ventoy are GPLv3. Start with our own GRUB2 loopback config; look at reusing Ventoy's prebuilt boot images for wider ISO support, and follow GPLv3 if we do (license, credit, source links).

### P3 — Write real USB sticks over OTG (no root)
USB Host API + USB Mass Storage Bulk-Only Transport/SCSI raw block writes (libaums-style). Root fallback: write the block device directly. Checksum before, read-back verify after.

### P4 — Raspberry Pi SD writing
Through a USB SD reader over OTG (the phone's own SD slot needs root). Streaming `.img.xz` decompression while writing.

### P5 — DroidCommand fleet companion
- AIDL bound service protected by a **signature permission**; entry in `KnownCompanions` with a `CompanionDescriptor` (`core-companion`); checked by `DependencyGuard`.
- Capability IDs such as `flash.gadget.status`, `flash.gadget.expose`, `flash.gadget.stop`, `flash.devices.list`, `flash.image.write`. Anything that changes USB setup or writes to a drive needs **per-call approval on the phone**.
- Use the same message shapes as the desktop flash-bridge (SSH JSON bridge for external nodes), so jobs can go to either one.

### P6 — Testing on real devices
Several rooted phones (configfs and legacy kernels), several PCs (UEFI + legacy BIOS, Secure Boot on/off), and real sticks and SD readers for P3–P4.

## Curated distro list
Each entry says which modes work:
- `gadget-disk` — boots when the phone shows it as a USB disk (most **hybrid ISOs**: Ubuntu, Fedora, Debian live, Mint, Arch…).
- `gadget-cdrom` — boots when shown as a CD-ROM (ISO-only images, e.g. some Windows/older ISOs).
- `loopback` — works in the multi-ISO image (P2).
- `dd-only` — must be written raw (P3).
- `pi` — Raspberry Pi image (P4).
Reuse/extend the existing `catalog/` data.

## Safety
- **Gadget mode rewrites the phone's USB gadget config.** Warn before the first use: USB/MTP/ADB/tethering will stop while the ISO is exposed, and a bad kernel may need a reboot to recover. Always back up and restore.
- Exposed images are read-only (except the P2 multi-ISO image when the user asks), and never phone partitions.
- **Write modes (P3/P4) erase the target drive.** Show name, size and vendor; require typed confirmation; never wipe without it; refuse non-removable devices.
- Checksum (SHA-256) before exposing or writing; read-back verify after writing.

## Risks and unknowns
- Many phones: no root, no `mass_storage` in the kernel, or vendor USB HALs that reset the gadget setup behind our back.
- CD-ROM emulation support and quirks vary by kernel; some PCs won't boot from a phone gadget.
- Android 14+ USB permission and foreground-service rules; ADB is lost while exposed.
- USB BOT reliability, power over OTG, and write speed for P3/P4.
- 4 GB file limit on FAT32; exFAT writer needed.
- GPLv3 terms for GRUB2/Ventoy boot images.

## Rule
PRs merge only when all tests are green.
