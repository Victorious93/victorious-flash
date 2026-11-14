# VictorLinux integration handoff

Work attribution: **Victorious / Victorious Framework**. Base inspected: `735c1fe65f973cd5e434f364684735ef08ab12c0`.

## Confirmed implementation paths

- Android rooted phone-as-boot-media: `gadget/Probe.kt`, `GadgetController.kt`, `GadgetScripts.kt`, `GadgetService.kt`; read-only disk/CD-ROM, kernel capability checks and restore logic.
- Android physical USB over OTG: `otg/UsbWriteService.kt`, `DiskWriter.kt`, `UsbBulkTransport.kt`, `ScsiBlockDevice.kt`; SHA-256 and read-back verification.
- Desktop flasher: Rust helper/bridge plus React/Tauri frontend. Device validation and privileged writes remain in the helper.
- Experimental no-root UEFI HTTP/EFI path: Ubuntu/casper-specific extraction and boot logic; it must not be represented as generic VictorLinux Arch support.

The phone can expose the same PC ISO that a USB stick holds. **The PC boots the OS and uses the PC GPU.** Phone gadget and OTG/host mode are different USB roles; do not promise concurrent use of one port in both roles.

## What is implemented (and what is not)

**Desktop (Rust + Tauri + React)**
- `flash-core::writer::flash` is the single write pipeline: verify the image hash first, check size, stream-write (xz decompressed on the fly), flush, read back through a *fresh* reader, compare. Raw images are additionally checked so the bytes streamed equal the verified hash (a source that changes between the hash pass and the write pass is caught). Tested with fault-injecting disks: wrong hash, oversize, failed flush, mid-write disconnect, read-back corruption, short read-back (capacity change/disconnect), mutating source.
- The privileged helper now refuses: a target whose size differs from what the owner confirmed (`write … <size-bytes>`; the GUI and bridge pass it), an image stored on the target device, a target it could not fully unmount, and re-checks identity right before opening the device.
- **Local images** (`flash-core::local`, Tauri `inspect_local_image`): any absolute regular-file path. Trust is explicit: `owner-supplied` (you typed the expected SHA-256), `sidecar` (a `SHA256SUMS`/`<image>.sha256` beside it lists this exact file), or `unverified-local` (hash only computed here: later changes are caught but origin is not proven; the UI requires an explicit acceptance box). A mismatching expected hash or sidecar refuses the file. Picking another image, mode or distro clears the target selection, typed confirmation and acceptance.
- **Persistence** (`flash-core::persist`, helper `write … persist`, bridge param `persistence`): layout computed from the image's own MBR and the drive's real size (1 MiB aligned, 1 GiB headroom, minimum 8 GiB data), MBR slot 3, ext4 label `VICTORDATA`. Tested on a file-backed disk with real `mke2fs`. Raw write and persistence are separate steps in one run.
- Not done: installing VictorLinux onto an internal disk (not offered), a catalog entry (no release exists), and any no-root HTTP/EFI recipe for VictorLinux (the Ubuntu-only path now refuses an Arch/archiso image with a clear message).

**Android phone-as-boot-media**
- `ImageGuard`: at expose time the file is re-hashed against the **trusted** SHA-256 (never one computed from the same file), refused if it changed while hashing, marked read-only, and watched every 2 s while exposed; a change restores USB and says why.
- The probe checks CD-ROM support non-destructively (an unlinked scratch `mass_storage` function); CD-ROM is refused when the kernel says it has none, disk mode stays available. Root alone is never a verdict.
- Restore paths (fail, stop, task removed, service destroyed, leftover backup offered at next start) are unchanged and covered by script/controller tests.
- Not done: a second writable data LUN (deliberately; no hardware to prove exclusive ownership), and **no on-device test** of any gadget behaviour.

## Checks
See the test report in the VictorLinux repository (`docs/2026-11-14-victorlinux-session-results.md`) for exact counts. The browser test is a Tauri-stubbed UI fixture and does not certify native writes; the Rust tests use files/in-memory disks, never a real removable drive.
