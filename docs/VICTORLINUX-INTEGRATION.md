# VictorLinux integration handoff

Work attribution: **Victorious / Victorious Framework**. Base inspected: `735c1fe65f973cd5e434f364684735ef08ab12c0`.

## Confirmed implementation paths

- Android rooted phone-as-boot-media: `gadget/Probe.kt`, `GadgetController.kt`, `GadgetScripts.kt`, `GadgetService.kt`; read-only disk/CD-ROM, kernel capability checks and restore logic.
- Android physical USB over OTG: `otg/UsbWriteService.kt`, `DiskWriter.kt`, `UsbBulkTransport.kt`, `ScsiBlockDevice.kt`; SHA-256 and read-back verification.
- Desktop flasher: Rust helper/bridge plus React/Tauri frontend. Device validation and privileged writes remain in the helper.
- Experimental no-root UEFI HTTP/EFI path: Ubuntu/casper-specific extraction and boot logic; it must not be represented as generic VictorLinux Arch support.

The phone can expose the same PC ISO that a USB stick holds. **The PC boots the OS and uses the PC GPU.** Phone gadget and OTG/host mode are different USB roles; do not promise concurrent use of one port in both roles.

## Source changes in this kit

- Changing distro or mode invalidates a previously verified image; a late download for an obsolete selection cannot overwrite the current selection.
- Distro selection is disabled while its download is active.
- The flasher UI refuses an image whose `verified` flag is false.
- Rounded dark desktop navigation/cards, better spacing, visible focus and responsive layouts.
- `tests/browser_flow.cjs` checks the stale-image failure and all tabs at three widths. It stubs Tauri calls and **never accesses a disk**.

## Checks observed here

`npm ci --ignore-scripts` and `npm run build` completed. The browser UI regression reproduced the stale image before the fix. Final test results are recorded in the ZIP report. Native helper and physical USB behavior are not established by a browser stub.

`cargo test -p flash-core -p victorious-flash-bridge` could not run because Cargo is absent. `android/gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` stopped downloading Gradle 8.14.3 with `java.net.SocketException: Network is unreachable`. Neither result is a passing test. Retry in the prepared Claude Code environment.

## Release integration still required

1. Build VictorLinux, compute/check the exact ISO hash, and publish only when authorized. Add its Flash catalog entry only after real HTTPS image/checksum URLs exist. Use existing `kind: linux-iso` and `image_format: iso`, updating every schema consumer if introducing new fields.
2. Permit verified local VictorLinux images in the desktop flow as well as Android. Do not fabricate a catalog download URL just to show a card.
3. Offer an explicit VictorLinux persistence option: raw ISO write alone creates no data partition. Reuse the target-selection/confirmation/read-back boundaries and compute layouts from actual capacities for 64/128 GB drives.
4. In phone gadget mode, state that the ISO is read-only; offer separate data-drive guidance. An optional writable data-image LUN must be exclusive, flushed and detached safely, never mounted writable by Android and the PC simultaneously.
5. Test short transfers, residue, detach, cancellation, changed source files, restoration after failures, missing mass-storage functions, wrong hashes and wrong target identities. Keep the existing helper validation and explicit destructive-action confirmation.
6. Do not offer an internal-disk OS installer for VictorLinux unless one is implemented and tested. Flashing live media is different from installing the OS onto a PC disk.

See the kit's master prompt and manual for the coordinated workflow and evidence requirements.
