# Victorious Flash for Android (Phase 1: phone as bootable USB)

Kotlin + Jetpack Compose app (`com.victorious.flash.android`). It turns a **rooted** phone into a
read-only USB disk or CD-ROM holding one verified ISO/IMG, so a PC can boot it. See
`../docs/ANDROID_PLAN.md` (P1).

## How it works
1. **Capability check** (root shell): `id -u`, `/config/usb_gadget/*`, `functions/*`,
   `/proc/modules` / `/sys/module/usb_f_mass_storage`, `/sys/class/udc/*`, and legacy
   `/sys/class/android_usb/android0` (`f_mass_storage`, `functions`). The app shows a checklist
   and a plain works / doesn't-work verdict (`gadget/Probe.kt`).
2. **Pick an image** with the system file picker (SAF). It is copied into the app's private
   storage (`files/images/`) and hashed with SHA-256 while copying. The expected hash comes from the
   shared catalog (`../catalog/distros.json`, entries tagged `gadget-disk` / `gadget-cdrom`), as a pinned
   hash or fetched from the vendor's checksum file, or you type it in. **Expose is blocked unless the hashes match.**
3. **Expose** (foreground service `gadget/GadgetService.kt`, scripts from `gadget/GadgetScripts.kt`):
   - **configfs:** back up the `UDC`, the config's function links and `sys.usb.config`, then unbind and
     remove the links (**MTP/ADB stop**). Next, create `functions/mass_storage.vflash` with `lun.0`
     `removable=1`, `cdrom=0|1`, `ro=1` and `file=<image>`, link it, and bind the original UDC. Any
     failure runs the restore steps.
   - **legacy android_usb:** back up `functions`/`enable`, set `functions=mass_storage`, `f_mass_storage/lun/file`
     (`ro`/`cdrom` when the kernel has them), and re-enable.
4. **Stop** (button, notification action, task removed, or service destroyed) removes our
   function, re-links the original functions, rebinds the UDC and re-applies `sys.usb.config`.
   If a backup is left over from a crash, the app offers **Restore USB**. Rebooting also resets USB.

## Safety
- Only regular `.iso`/`.img` files inside the app's own image folder are ever exposed (`gadget/PathSafety.kt`).
  The app refuses `/dev`, `/proc`, `/sys`, symlinks, `..`, and odd characters, and the root script checks
  the file again (`-f`, not `-L`, not `-b`).
- Gadget, config and UDC names are validated, and every value in a script is single-quoted.
- Big red warnings explain that USB, ADB, MTP and tethering drop while an image is exposed, and there is a confirmation dialog.

## Build
```
cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```
Needs the Android SDK (platform 36, build-tools 36.0.0) and JDK 17+. CI: `.github/workflows/android.yml`.

## Signing

Debug builds (local and CI) are signed with the committed key `app/debug.keystore`
(alias `androiddebugkey`, store/key password `android`, `CN=Victorious Flash Debug`, RSA 2048).
This is a **NON-SECRET debug key**: it is public on purpose so every machine and CI produce
debug APKs with the same signature (installs upgrade in place instead of failing with a
signature mismatch). Never use it for anything you publish.

Release signing is separate and is **not** in this repo: no release keystore or release
credentials are committed, and none must ever be.

## Not verified yet
Real rooted devices (configfs and legacy kernels), real PC boots (UEFI/legacy, disk vs CD-ROM), and
vendor USB HALs that may reset the gadget behind the app's back. That is plan phase P6.

## No-root mode (UEFI HTTP Boot over USB tethering) — experimental

For unrooted phones. After the image's SHA-256 matches, the app runs a foreground-service HTTP server
(`netboot/IsoHttpServer.kt`: GET/HEAD, single byte ranges, keep-alive, serves ONE file only, read-only)
bound to the USB-tethering / hotspot / LAN IPv4 addresses on port 8080, and shows
`http://<phone-ip>:8080/ubuntu.iso`. The user types that URL into the PC firmware's *HTTP Boot
Configuration*. `.iso` is served as `application/vnd.efi.iso` so EDK II-style firmware boots it as a RAM disk.

Requirements on the PC (any one failing = it won't work): UEFI HTTP Boot with manual URI, plain `http://`
allowed, a firmware driver for the phone's USB network (RNDIS/NCM — the usual blocker), ISO URLs accepted
(Dell client BIOS documents only `.efi`), and enough RAM to hold the whole ISO. **Not yet confirmed on real
hardware.** The app auto-detects `su` (without invoking it) and defaults to Rooted mode when present; rooted
mode is unchanged.

### Lighter `.efi` boot path (for Dell-style firmwares that only accept `.efi` URLs)

When the verified file is an Ubuntu ISO, `netboot/Iso9660.kt` (pure Kotlin, Rock Ridge aware) locates
`EFI/boot/{bootx64,grubx64,mmx64}.efi` and `casper/{vmlinuz,initrd}` inside it and the server streams them
directly from the ISO (no copy) at `/boot/bootx64.efi`, `/boot/grubx64.efi`, `/boot/mmx64.efi`,
`/casper/vmlinuz`, `/casper/initrd`. A grub.cfg is generated per request (with the IP:port the PC
connected to) at `/grub/grub.cfg` and `/boot/grub/grub.cfg` (+ `x86_64-efi/` variants), booting
`linux /casper/vmlinuz ip=dhcp iso-url=http://<phone>:8080/ubuntu.iso cloud-config-url=/dev/null ---`.
If the hash came from the catalog and its URL is `https://releases.ubuntu.com|cdimage.ubuntu.com/...iso`, a
second entry downloads from Ubuntu instead (needs mobile data through tethering). Either way casper loads
the whole ISO into PC RAM. The PC URL is `http://<phone>:8080/boot/bootx64.efi`.

Verified: extraction byte-identical to pycdlib on Ubuntu's real `noble-mini-iso-amd64.iso`
(`VFLASH_REAL_ISO=/path/to.iso ./gradlew :app:testDebugUnitTest --tests '*RealIsoTest*'`).
Not verified: anything on real firmware (no QEMU/OVMF run either).
