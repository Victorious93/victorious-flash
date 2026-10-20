<p align="center"><img src="assets/manual-header.svg" alt="Victorious Flash Manual" width="100%"></p>

# Victorious Flash: User Manual

This manual covers two apps:

- **Victorious Flash for Linux desktop**, a Tauri app on the `main` branch.
- **Victorious Flash for Android**, a Kotlin app in `android/`.

It only describes what the code does today. Each feature has one of these labels:

| Label | Meaning |
|---|---|
| ✅ **Available (main)** | Merged into `main`. |
| 🚧 **Coming soon, in PR** | Being built on a branch. Not merged. |
| 💤 **Planned, not yet available** | No code exists yet. |

Screenshots are placeholders until real captures are added.

---

> ## ⚠️ READ THIS FIRST: WRITING TO A DRIVE ERASES IT
> **Flashing a USB stick or SD card destroys everything on it.** Files, photos and partitions are all gone, and they cannot be recovered.
> - Copy anything you need off the stick **before** you start.
> - Check the device name and size **twice**. The app makes you type the name (desktop) or type `ERASE` (Android OTG) for this reason.
> - **Installing Linux "wipe" style on a PC erases that PC's disk.** That happens in the Linux installer, not in Victorious Flash. Read [Dual-boot vs wipe](#dual-boot-vs-wipe) first.
> - Victorious Flash **never writes to your PC's internal disks.** It hides NVMe/SATA internal drives, eMMC, the system disk and anything over 2 TiB, so you cannot pick them.

---

## Contents
1. [What Victorious Flash is](#1-what-victorious-flash-is)
2. [Install and setup](#2-install-and-setup)
3. [Features, step by step](#3-features-step-by-step)
4. [Permissions and root](#4-permissions-and-root)
5. [Fleet integration and safety gates](#5-fleet-integration-and-safety-gates)
6. [Troubleshooting and FAQ](#6-troubleshooting-and-faq)
7. [Privacy and data handling](#7-privacy-and-data-handling)

---

## 1. What Victorious Flash is

Victorious Flash takes you from "I want to try Linux" to a **verified**, bootable installer:

- **Pick** a Linux from a curated list (Ubuntu, Fedora, Mint, Debian, Pop!_OS, openSUSE, Arch, Raspberry Pi OS).
- **Verify** the download with SHA-256 against the vendor's official checksum.
- **Flash** it to a USB stick or SD card, then read the stick back to make sure it was written correctly.
- **Install** it, with a plain-language guide that covers backups, boot keys and Secure Boot.

**Its role in Don's Victorious fleet.** Victorious Flash is the fleet's OS-imaging tool. It is a sibling of RootForge and is driven by **DroidCommand AI**. On the desktop, a headless helper called `victorious-flash-bridge` lets DroidCommand AI's `core-flash` module list drives, verify images and write images on a fleet PC over SSH. It uses the same JSON protocol (major version 1) as the RootForge bridge. See [section 5](#5-fleet-integration-and-safety-gates). The Android app is the "phone in your pocket" version: it boots a PC straight from your phone or writes a stick from it.

The desktop app uses navy with electric-green accents. **Red is used only for danger**: erase warnings and errors.

---

## 2. Install and setup

### 2.1 Linux desktop ✅ Available (main)

Linux only. Windows and macOS are not planned.

**From a package.** CI (`.github/workflows/build.yml`) builds a `.deb` and an AppImage on every push.
- **.deb (recommended):** `sudo apt install ./victorious-flash_*.deb`. This installs the app, the write helper at `/usr/lib/victorious-flash/victorious-flash-helper`, and the polkit policy that lets the helper ask for your password.
- **AppImage:** this does not bundle the helper yet. Install the `.deb` as well, or set `VFLASH_HELPER` to point at an installed helper.

**From source (developers):**
```bash
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libssl-dev
npm install
cargo build -p victorious-flash-helper
VFLASH_HELPER=$PWD/target/debug/victorious-flash-helper npm run tauri dev
```

[Screenshot: Desktop app home screen with the three modes, to be added]

### 2.2 Android ✅ Available (main)

- Package `com.victorious.flash.android` (Kotlin and Jetpack Compose).
- Install the debug APK built by CI (`.github/workflows/android.yml`), or build it yourself with `cd android && ./gradlew :app:assembleDebug`. You need the Android SDK 36 and JDK 17+.
- To install, allow "Install unknown apps" for your browser or file manager, then open the APK.
- ✅ **Fixed debug signature (PR #10, on main).** Debug APKs are signed with a committed, **non-secret** debug key, so newer debug builds install over older ones. That key is public on purpose. It is not a release key and must never be used for published builds. A release-signed build does not exist yet. 💤 **Planned, not yet available.**
- **Root is optional.** The app checks whether `su` exists without running it, and picks Rooted mode by default if it finds it. Without root you get the no-root modes.

[Screenshot: Android app first screen with the mode choice, to be added]

### 2.3 Pairing with the fleet controller

- **Desktop:** pairing is manual, over SSH. No pairing screen exists. Follow the steps in [5.2](#52-setting-up-a-desktop-node-manual).
- **Android:** 💤 **Planned, not yet available.** The Android app has no fleet bridge and no pairing.
- **One-tap or QR pairing (any platform):** 💤 **Planned, not yet available.**

---

## 3. Features, step by step

### 3.1 Distro catalog ✅ (desktop and Android)

The catalog lives in `catalog/distros.json`. It lists official image URLs and checksum URLs (or a pinned hash) for:

Ubuntu 24.04.5 LTS · Fedora Workstation 42 · Linux Mint Cinnamon 22.2 · Debian 13 (trixie) netinst · Pop!_OS 24.04 · openSUSE Tumbleweed · Arch Linux (monthly) · Raspberry Pi OS Lite 64-bit / Desktop 64-bit / Lite 32-bit (2026-10-06, trixie).

**Desktop:**
1. Open the app. The **Distro picker** shows each entry's name, version and description.
2. Click a distro to choose it.

[Screenshot: Distro picker, to be added]

**Android:**
- Entries tagged `gadget-disk` or `gadget-cdrom` supply the expected hash for images you pick.

**Not yet available:**
- RootForge OS images: 💤 **Planned, not yet available.** The catalog supports the `rootforge-os` kind, but no RootForge OS release has a published image and checksum yet.
- Automatic catalog updates in the GUI: 💤 **Planned, not yet available.** The fleet bridge can update the catalog from an https URL with a pinned hash.

### 3.2 Checksum verification ✅

- **Desktop.** Every download is hashed with SHA-256 and compared with the vendor's checksum file (GNU or BSD format) or a pinned hash. A mismatch stops you.
- **Android.**
  1. Pick a file with the system file picker.
  2. The app copies it into its private storage and hashes it while copying.
  3. The expected hash comes from the catalog (pinned, or fetched from the vendor's checksum file), or you paste one in.
  4. **Nothing can be exposed, served or written unless the hashes match.**
- **GPG signature checks:** 💤 **Planned, not yet available.** The catalog has signature URLs, but the UI reports `not-checked`.
- **Download resume and mirror choice:** 💤 **Planned, not yet available.**

[Screenshot: Checksum verified badge, to be added]

### 3.3 Flashing a USB stick or SD card (desktop) ✅

> ### 🛑 THIS ERASES THE WHOLE STICK OR CARD
> Everything on the selected device is destroyed. Back it up first.

1. Choose **USB** mode, or **SD card** mode for Raspberry Pi and other cards.
2. Pick the distro. The app downloads and verifies it.
3. Plug in the stick or card. The device list shows **only** removable or hot-plug whole disks. The app hides:
   - NVMe and SATA internal drives
   - loop, zram and optical devices
   - read-only devices
   - anything over 2 TiB
   - anything holding `/`, `/boot`, `/home` or swap
   - eMMC internal storage
4. **Type the device name** (for example `sdb`) to confirm.
5. Enter your password when asked. A small helper runs as root through **pkexec**. It does four things:
   - re-checks the target on its own
   - writes the image
   - `fsync`s
   - **reads the device back and compares the SHA-256**
6. Wait for the "verified" result before you unplug.

**Raspberry Pi OS `.img.xz` files** work like this:
- The compressed file's hash is checked before anything is written.
- The helper refuses an image that expands to more than the card's size.
- The image is decompressed while it is written, and the read-back is compared with the decompressed hash.

[Screenshot: Device list and type-to-confirm box, to be added]
[Screenshot: Flash progress and read-back verify, to be added]

### 3.4 Direct install with a GRUB loopback entry (no stick) ✅ Ubuntu and Mint only

This boots the installer from an ISO saved on this PC's own disk.

1. Choose **Direct install**. The ISO is downloaded and verified.
2. The app generates a GRUB menu entry, using the casper loopback recipe.
3. **You** paste the entry into `/etc/grub.d/40_custom` and run `sudo update-grub`. The app never edits your boot configuration itself.
4. Reboot and pick the new entry.

- **Multi-ISO menus (several ISOs in one GRUB menu or on one stick):** 💤 **Planned, not yet available.**
- Direct install for other distros: 💤 **Planned, not yet available.**

[Screenshot: Generated GRUB entry, to be added]

### 3.5 Phone as a USB drive (rooted Android, configfs gadget) ✅ Available (main)

Your rooted phone shows up to the PC as a **read-only** USB disk or CD-ROM that holds one verified ISO or IMG. **Nothing on the PC or the phone is erased.**

1. **Check the phone.** The app shows a checklist and a plain "works / doesn't work" verdict. It looks for root, `/config/usb_gadget`, the mass_storage function, a USB controller (UDC), and the legacy `android_usb` interface.
2. **Pick and verify** the image (see 3.2).
3. Choose **Disk** or **CD-ROM** mode.
4. Tap **Expose** and confirm the warning.

> ⚠️ While the image is exposed, the phone's normal USB functions stop: **ADB, MTP file transfer and USB tethering**.

5. Plug the phone into the PC and boot from it using the boot-menu key (see [6.1](#61-bios--uefi-boot-keys)).
6. Tap **Stop**, in the app or in the notification. The phone's original USB setup is restored.
   - If the app crashed, it offers **Restore USB**.
   - Rebooting the phone also resets USB.

**Not verified on real hardware yet:** this includes real rooted devices and real PC boots. Some phone vendors reset USB behind the app's back.

[Screenshot: Gadget capability checklist, to be added]
[Screenshot: Exposed image notification, to be added]

### 3.6 No-root network boot over USB tethering (UEFI HTTP Boot) ✅ Available (main), experimental

For phones without root. **Nothing is erased.** The PC boots the installer into RAM.

- Basic ISO serving is ✅ on main (PR #7).
- The lighter `.efi` path (URL A) and the Dell HTTPs Boot steps are ✅ on main too (PR #8, included in main's #7 squash commit `a8bf108`).
- ✅ The QEMU/OVMF check script (`android/tools/netboot-qemu.sh`, PRs #9 and #11, which only kills its own Gradle processes) is on main via #14.

**Steps:**
1. Verify an image (3.2). Ubuntu is needed for the `.efi` path.
2. Turn on **USB tethering** on the phone and plug it into the PC.
3. Tap **Serve**. A foreground service starts a read-only HTTP server on port 8080. It shows URLs such as:
   - **URL A (recommended, required for Dell):** `http://<phone-ip>:8080/boot/bootx64.efi`. This boots Ubuntu's own signed shim and GRUB, which the app streams straight out of the ISO. GRUB then loads the ISO into PC RAM. If the catalog hash came from releases.ubuntu.com, a second menu entry downloads the ISO from Ubuntu instead, using the phone's mobile data.
   - **URL B:** `http://<phone-ip>:8080/ubuntu.iso`. The firmware downloads the whole ISO as a RAM disk.
4. Enter the URL in the PC firmware's **HTTP Boot** setting, then boot from it.

[Screenshot: Serve screen with URLs A and B, to be added]

**Dell PCs (BIOS "HTTPs Boot"):**
1. Press **F2** at the Dell logo. Enable **UEFI Network Stack** under Connection or Integrated Devices.
2. Set HTTPs Boot to manual mode and enter **URL A** exactly. It must end in `.efi`. Dell's Manual Mode accepts only `.efi` URLs, and it gives no error for a mistyped URL.
3. Apply, exit, press **F12** and choose **UEFI HTTPs Boot**.
4. If you see "Failed to find IP address", or the phone is not listed, this Dell cannot use the phone's USB network. Use Rooted mode or a USB stick instead.

**Requirements. If any one fails, this mode will not work:**
- The firmware supports UEFI HTTP Boot with a manually typed URI.
- The firmware allows plain `http://`. **Some firmwares only accept HTTPS. Recent ThinkPads' custom HTTPS boot is an example, so this mode will not work there.** The app serves http only.
- The firmware has a driver for the phone's USB network (RNDIS/NCM). This is the most common blocker.
- **Enough RAM.** The whole ISO is held in memory. Ubuntu Server (about 3.2 GB) needs roughly **6 GB+**. Ubuntu Desktop (about 6 GB) needs roughly **12–16 GB+**. Too little RAM fails with an "out of resources" error.
- Secure Boot can stay on for URL A, but only if the firmware trusts the "Microsoft 3rd-party UEFI CA" (see 6.2).

**Testing status:**
- A QEMU/OVMF test script exists (`android/tools/netboot-qemu.sh`).
- The ISO extraction matches pycdlib byte for byte on a real Ubuntu ISO.
- **Not yet confirmed on real PC hardware.**

### 3.7 Write to a USB stick over OTG (no root) ✅ Available (main), not yet tested on real hardware

This mode is on main (PR #16). It has **not been tested on a real phone and stick yet** (only against simulated sticks in unit tests). Here is what it does.

**You need:** a phone with USB host (OTG) support, an OTG adapter (or a USB-C stick), and a phone that can power the stick. No root. The app talks to the stick directly with Android's USB Host API and its own small USB Mass Storage (Bulk-Only Transport + SCSI) layer; no third-party library.

> ### 🛑 THIS ERASES THE WHOLE STICK
> The stick plugged into the phone is overwritten byte for byte.

1. Verify the image (3.2).
2. Plug a USB flash drive into the phone with an OTG adapter, or use a USB-C stick. Grant the USB permission when Android asks.
3. Pick the stick. Its vendor, product and capacity are shown. Sticks smaller than the image are refused.
4. A full-screen **red DANGER** screen names the stick and its size. You must **tick the box AND type `ERASE`**. Writing never starts on its own.
5. The app then:
   - re-hashes the image
   - blocks if the image's SHA-256 does not match the expected hash
   - checks the stick's size has not changed since you confirmed
   - writes it, showing progress, speed and ETA
   - flushes the stick's cache
   - **reads the whole written range back and compares the SHA-256**
   - shows "Write verified" only if they match
6. If you unplug or cancel, the stick is left incomplete. Write it again.

**Limits:**
- Raw `.iso` and `.img` files only. Hybrid Linux ISOs boot. Windows ISOs written raw do not.
- LUN 0 only.
- Images up to 2 TiB.
- Speed is often 5–30 MB/s. The read-back roughly doubles the time.
- Some phones cannot power a hungry stick. Use a powered hub if that happens.
- So far tested only against simulated sticks, not real hardware.

[Screenshot: OTG DANGER confirmation screen, to be added]

### 3.8 Fleet bridge

- **Desktop:** ✅ Available (main). See section 5.
- **Android:** 💤 **Planned, not yet available.**

### 3.9 Install guide (desktop) ✅

Built-in chapters:
1. Back up first
2. Dual-boot or wipe?
3. Boot the installer (USB, SD or GRUB entry)
4. Secure Boot
5. Partitioning basics

- **In-app Help/Manual screen:** 💤 **Planned, not yet available.** It will come in a separate PR.

---

## 4. Permissions and root

### Desktop
| What | Why |
|---|---|
| **pkexec / polkit** (`com.victorious.flash` action, your password) | Writing raw bytes to a USB device needs root. Only the tiny helper runs as root. It re-validates the target, so the GUI itself never has root. |
| Network | To download images and checksum files from official vendor URLs. |
| Fleet remote writes | SSH sessions have no password prompt, so remote writes need a polkit rule that you add yourself. **It is not shipped**, on purpose. |

### Android
| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | To fetch checksum files, and to find the phone's tethering, hotspot or LAN IP for the boot server. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `POST_NOTIFICATIONS` | To keep the gadget, serve or write job running, with a visible notification and Stop button. |
| `WAKE_LOCK` | To stop the phone sleeping during a long serve or write. |
| **Root (`su`)**, optional | Only for "phone as USB drive". Changing USB gadget settings in `/config/usb_gadget` or `/sys` requires root. The app only detects `su` on startup. It runs `su` when you use Rooted mode. |
| USB device permission (OTG mode, ✅ Available (main), not yet tested on real hardware) | Android asks before the app may talk to a stick you plug in. Only mass-storage devices attached to the phone can be targets. |
| Files | Images are chosen with the system file picker (no storage permission) and copied into the app's private folder. |

---

## 5. Fleet integration and safety gates

### 5.1 How the controller drives a node ✅ (desktop)
- DroidCommand AI connects over SSH with a **pinned host key** and runs `victorious-flash-bridge serve`.
- It sends one JSON request per line, using protocol major 1.
- Operations:
  - `flash.capabilities.get`
  - `flash.catalog.list`
  - `flash.catalog.update` (https plus a pinned hash)
  - `flash.devices.list`
  - `flash.verify`
  - `flash.readback.verify`
  - `flash.image.write`

### 5.2 Setting up a desktop node (manual)
1. Run `victorious-flash-bridge node-id` and note the `vf-…` id.
2. Add the controller's SSH key to `~/.ssh/authorized_keys` with a forced command:
   `command="/usr/bin/victorious-flash-bridge serve",no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding ssh-ed25519 AAAA…`
3. **Only if you want remote writes**, add a polkit rule that authorises `com.victorious.flash` for that user.

### 5.3 Remote control is OFF by default
Nothing is reachable remotely until you complete step 2. Remote **writing** stays impossible until you also complete step 3. There is no background listener: the bridge only runs when an authorised SSH key starts it.

### 5.4 Safety gates around flashing
These apply to the GUI, the bridge, and Android OTG.
- **Verified image only.** The hash must match before any write. The bridge checks the image hash first.
- **Safe-device filter.** The same `lsblk` filter is used for the GUI and the bridge. Internal NVMe and SATA disks, eMMC, the system disk, read-only devices and anything over 2 TiB are never offered. **The app never wipes the PC's internal disks.**
- **Explicit confirm.** You type the device name (GUI). The bridge's `confirm` parameter must equal the device name or path. On Android OTG you tick the box and type `ERASE`.
- **Root helper re-checks.** Writes go **only** through the pkexec helper, which validates the target again.
- **Read-back verify** after every write.
- Refused requests return `safety_refused` or `verify_failed`.

---

## 6. Troubleshooting and FAQ

### 6.1 BIOS / UEFI boot keys
Press the key repeatedly right after power-on.

| Brand | Boot menu | Firmware setup |
|---|---|---|
| Dell | F12 | F2 |
| HP | F9 / Esc | F10 |
| Lenovo | F12 (or Novo button) | F1 / F2 |
| ASUS | F8 / Esc | F2 / Del |
| Acer | F12 | F2 / Del |
| MSI | F11 | Del |
| Gigabyte | F12 | Del |
| Microsoft Surface | Hold Vol-Down + Power | Hold Vol-Up + Power |
| Apple Intel Mac | Hold Option (⌥) | — |
| Framework | F12 | F2 |

### 6.2 Secure Boot
- Ubuntu, Fedora, Mint, Debian, openSUSE and Pop!_OS boot with Secure Boot **on**, using a signed shim.
- **Arch Linux does not.** Turn Secure Boot off temporarily in firmware setup.
- Network boot URL A needs the firmware to trust the "Microsoft 3rd-party UEFI CA". Some Secured-core, Lenovo and Dell PCs ship with it off. Enable it, or turn Secure Boot off.
- If an installer asks you to enrol a MOK key, follow the blue MOK screen on the next boot.

### Dual-boot vs wipe
- **Dual-boot:** Linux is installed next to Windows. You pick one at startup. Shrink Windows first and keep a backup.
- **Wipe ("Erase disk and install"):**

> 🛑 **This erases the PC's whole disk: Windows and all your files.** Choose it only after backing up. This choice is made in the Linux installer. Victorious Flash never does it.

### FAQ
- **My stick isn't listed.** It may be over 2 TiB, read-only, or not reported as removable. Internal disks are hidden on purpose.
- **Checksum mismatch.** Delete the file and download it again. Do not use a file that fails the check.
- **The phone lost ADB, MTP or tethering.** That is expected while "phone as USB drive" is active. Tap Stop or Restore USB, or reboot the phone.
- **Network boot shows no phone network device.** The firmware has no driver for the phone's USB network. Use Rooted mode, a stick, or OTG (✅ Available (main), not yet tested on real hardware).
- **"Out of resources" during network boot.** The PC does not have enough RAM for the ISO (see 3.6).
- **ThinkPad refuses the URL.** The firmware is HTTPS-only. The app serves http only, so use a stick.
- **The AppImage can't flash.** Install the `.deb` (it includes the helper) or set `VFLASH_HELPER`.
- **Windows ISOs?** Not supported for raw writing.

---

## 7. Privacy and data handling
- **No accounts, no telemetry, no analytics.** No such code exists in either app.
- **Network traffic:**
  - Downloads go only to the official image and checksum URLs in the catalog, or to a URL you supply.
  - The bridge's catalog updates must use https and a pinned hash.
  - No-root network boot serves **one** verified file, read-only, on your local tethering, hotspot or LAN network on port 8080. Anyone on that network can download that one file while serving is on. Stop serving when you are done.
- **Android storage.** Picked images are copied into the app's private storage (`files/images/`). Uninstalling the app deletes them.
- **Desktop storage.** The bridge stores its node id under `$XDG_STATE_HOME/victorious-flash-bridge/`.
- **Fleet.** The controller only sees what bridge operations return: device lists, hashes and progress. No files from your PC are sent.

---
*Maintainers: update this manual as part of done for every feature PR (see [CONTRIBUTING](CONTRIBUTING.md)).*
