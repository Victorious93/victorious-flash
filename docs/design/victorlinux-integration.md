# VictorLinux integration plan (design only)

**Status:** planning only. This document proposes work. It does not implement it.
No feature code should land until Don approves this plan. This PR must stay open until then.

**Hard rule:** standalone in both directions. Victorious Flash must keep working fully without
VictorLinux (existing catalog images, raw USB/SD writes, rooted gadget, no-root HTTP boot, and
OTG writer stay available and unchanged when the new checkbox is off). VictorLinux must keep
working fully without Flash (`scripts/make-usb.sh` remains the canonical boot-media tool).
Neither repository becomes a runtime dependency of the other.

Claims are marked **[Certain]**, **[Likely]**, or **[Guessing]**.

---

## 1. What was reviewed

Reviewed on 16 November 2026, America/New_York, against live remotes. Don also uses Claude Code,
so this plan is based on current trees, not earlier session summaries.

### 1.1 Victorious Flash (`Victorious93/victorious-flash`, public)

**[Certain]** `origin/main` is `299a825` (14 November 2026, 17:21 America/New_York):
"Merge pull request #23 from Victorious93/ccr-de199f47-hejjrt".
No open pull requests at review time. Recent merges after the earlier "all PRs through #20"
summary:

| PR | Title | Relevance to flashing / partitioning / catalog |
|----|-------|-----------------------------------------------|
| #19 | Official logo (README + launcher icon) | Branding only |
| #20 | DroidCommand controller pre-pinned (debug cert); switch stays off | Fleet pairing, not layout |
| #21, #22 | README refresh (logo, package id, status, fleet links) | Docs only |
| #23 | sccache evaluation (measured, not adopted) | CI docs only (`docs/BUILD_CACHE_EVALUATION.md`) |

None of #19–#23 add partition writing, a VICTORDATA path, or a catalog entry.

**[Certain]** On `origin/main` there is **no** `VICTORDATA` string, no `victor_layout` port, and
no `persist` / GPT planner module. `crates/flash-core` is `catalog`, `checksum`, `devices`,
`direct`, and `image` only. USB/SD flashing is a raw image write plus a full-image SHA-256
read-back (`crates/flash-helper` `write`, Android `otg/DiskWriter.kt`).

**[Certain]** `catalog/distros.json` on main has Ubuntu, Fedora, Mint, Debian, Pop!_OS,
openSUSE, Arch, and three Raspberry Pi OS images. There is **no** VictorLinux entry. The same
is true of every `id` in that file.

**[Certain]** Drives larger than 2 TiB are already refused (`crates/flash-core/src/devices.rs`
`MAX_SIZE = 2 * 1024^4`, `size > MAX_SIZE`; documented in the README and `docs/MANUAL.md` §3.3
and §5.4). This cap stays until the planner in §3.1 actually lands.

**[Certain]** VictorLinux's `docs/flash-companion-changes.md` says Flash today does "a raw write,
then a 16-byte MBR slot-3 entry", and also says that description **was not re-verified** against
Flash source. That description does **not** match `origin/main`. Main has no slot-3 writer.
The companion list is still the right *target* behaviour; it is not a description of current Flash.

### 1.2 Unmerged Claude Code branch (do not land as-is)

**[Certain]** Branch `claude/new-session-t2zzsm` (tip `2b9dd51`, 14 November 2026) is **not** on
main. It is based on `735c1fe` (PR #22) and does not include PR #23. It already contains feature
code: `crates/flash-core/src/persist.rs`, a desktop persistence checkbox, `docs/VICTORLINUX-INTEGRATION.md`,
a local-image trust model, and gadget re-verification.

That `persist.rs` is **not** a port of `scripts/victor_layout.py`. Compared with the official
planner it is MBR-slot-3 only, hard-codes 512-byte sectors, uses a 8 GiB minimum (official default
is 2 GiB), has no GPT path, no 4Kn / 512e handling, no "verify the payload after the 32 KiB
system area" step, and no locate-by-start-sector logic. It also predates the VictorLinux planner
fixes that prefer the proven MBR layout for hybrid images that already carry a GPT when it fits
(VictorLinux `061e416`, 14 November 2026).

**Recommendation:** do not merge `claude/new-session-t2zzsm` as the integration. Reuse only
ideas that still match this plan (opt-in checkbox, show a plan before confirm, keep raw write
and persistence as separate steps, local-image trust model as a later optional PR). Any port
must track `victor_layout.py` and its tests.

### 1.3 VictorLinux (`Victorious93/VictorLinux`, public)

**[Certain]** The repository was readable (API + `raw.githubusercontent.com` + a local clone of
`main`). Visibility is public. It is a fork. Default branch `main` tip at review was `01fdd30`
(15 November 2026, 00:46 America/New_York): "Merge pull request #8 from
Victorious93/claude/new-session-lhpgb4".

**[Certain]** **0 GitHub releases and 0 tags.** The VictorLinux README states that no release has
been published and that no ISO of the current revision was built or booted. There is therefore
no official ISO URL or SHA-256 to put in Flash's catalog.

Read in full: `docs/flash-companion-changes.md`, `scripts/victor_layout.py` (449 lines),
`tests/test_layout_planner.py` (239 lines), `docs/storage.md`, `docs/usb.md`, plus the
Flash-related parts of the README. Also skimmed `docs/companion-api.md` and
`docs/fleet-integration.md`.

The eight companion changes in `flash-companion-changes.md` are the backlog this plan covers:

1. Replace any "MBR slot 3 only" approach with the planner (checked 64-bit math, 1 MiB alignment,
   real logical and physical sector size, 1 GiB default reserve).
2. GPT mode that keeps the image's own GPT (including a non-standard entry-array LBA such as 20),
   relocates the backup GPT, writes CRCs, and adds a protective MBR entry when needed.
3. Reject before the first erase: 4Kn targets, plans below the minimum, MBR-only images that
   cannot be re-described as GPT (optional explicit "limit to 2 TiB", never silent truncation).
4. After the table is written, verify the ISO payload **after the 32 KiB system area** against
   the trusted SHA-256, and describe the disk as "partition table intentionally changed".
5. Locate the new partition by start sector, not by number.
6. UI: show the plan before the type-to-confirm step; allow an explicit data size.
7. Android OTG: sector-size check (logical/physical), refuse 4Kn the same way as desktop.
8. Phone-hosted read-only ISO boot stays unchanged; persistence comes from a **separate**
   writable drive (a `VICTORDATA` label is not required if the owner chooses the volume in
   VictorLinux).

---

## 2. Current Flash types (no new enum variants)

**[Certain]** Existing types on main, which this work must stay inside:

| Type | Variants today | How VictorLinux fits |
|------|----------------|----------------------|
| `ImageKind` | `linux-iso` (default), `rootforge-os`, `raspberry-pi-os` | A future catalog entry uses **`linux-iso`**. VictorLinux is a desktop Linux hybrid ISO. A new `victorlinux` kind is not needed and is not allowed in this work. |
| `ImageFormat` | `iso`, `img`, `img-xz` | `iso`. |
| Desktop `Mode` | `direct`, `usb`, `sd` | Persistence is an **opt-in checkbox on USB (and SD, if the image is written there)**, not a fourth mode. Direct install stays the existing GRUB loopback path (Ubuntu/Mint recipes only) and is not a VictorLinux installer. |
| Android UI mode | `ROOTED`, `NO_ROOT`, `OTG` | The checkbox belongs on **OTG write** only. Rooted gadget and no-root HTTP boot stay read-only ISO paths; they do not grow a new mode. |
| `gadget` tags | `gadget-disk`, `gadget-cdrom` | Use these only if/when a real ISO has been checked for gadget boot. No new tag. |
| Fleet ops | `status.get`, `capabilities.get`, `netboot.serve.start`/`stop`, `otg.write`, `gadget.expose` | No new opcode in this work. Persistence, if ever offered over the bridge, is an optional field on `otg.write`, and still requires the on-phone `ERASE` confirm. Default is to leave fleet persistence unsupported until Don says otherwise (open question). |

`UsbDevice` today has `name`, `path`, `size`, `model`, `transport`, `mounted`, `media`. Sector
sizes are **not** currently collected (`LSBLK_ARGS` has no `LOG-SEC`/`PHY-SEC`). The planner
needs those fields added to the existing struct (optional `logical_sector` / `physical_sector`),
not a new device enum.

The desktop `flash` Tauri command today is
`flash(iso, device, typed_confirmation, sha256)`. Extend it with optional parameters
(for example `add_persistence: bool`, `data_bytes: Option<u64>`) and add a read-only
`plan_persistence(...)` command for the UI preview. Same idea on the helper argv and the
Android write-service extras. No new `Mode`.

---

## 3. Work items

### 3.1 Port `victor_layout.py` plus tests — High / L

Port the planner as a **pure library**, then call it from the existing writers. Do not shell out
to Python at runtime. Do not add VictorLinux as a submodule. The source of truth for behaviour
is `scripts/victor_layout.py` and `tests/test_layout_planner.py` at the VictorLinux revision
reviewed (`01fdd30`). If that file moves later, re-diff before implementing.

#### Behaviour to preserve

**[Certain]** from `plan_layout` / `apply_plan` / the tests:

- Inspect the hybrid image's system area (MBR signature required). Record MBR entries and, if
  present, the image GPT, including its entry-array LBA and entry count (xorriso/GRUB images
  may place the array at LBA 20 beside Apple Partition Map blocks).
- All capacity and offset math in checked 64-bit integers (Rust `u64` / `checked_*`; Kotlin
  `Long` with explicit overflow checks). No silent wrap of MBR 32-bit fields.
- 1 MiB partition alignment. Default reserve 1 GiB. Default minimum data size **2 GiB**
  (not the 8 GiB used on the unmerged Claude branch).
- Use the device's real **logical and physical** sector size. Support 512-byte logical,
  including 512e (logical 512, physical 4096). **Refuse 4Kn** (logical 4096) before any erase.
  A 4Kn drive can still be a VictorLinux *data* drive; it must not be boot media.
- `table=auto`: keep the proven MBR layout (data partition in free MBR slot 3) when it is fully
  representable and the MBR is not a protective 0xEE MBR; otherwise GPT.
- GPT mode keeps the image's own GPT entries verbatim, relocates the backup header and array to
  the end of the real drive, writes primary and backup CRCs, and adds a protective MBR entry
  only when the image has none. Never blindly rebuild a hybrid ISO's table.
- MBR-only images whose partitions start below LBA 34, or whose LBAs 1–33 hold non-table data,
  cannot be re-described as GPT. Refuse. An explicit "limit data to the first 2 TiB, leave the
  rest unallocated" option (`allow_mbr_cap`) is never applied silently (open question: whether
  Flash's UI exposes this).
- After the table is written, verify the **ISO payload after the first 32 KiB** (system area)
  against the trusted SHA-256. Do **not** require the whole disk to match the ISO. The UI must
  say the partition table was intentionally changed. "Write verified" for a persistence write
  means: payload hash matches, and the data partition is found at the planned start LBA.
- Locate the data partition by **start sector** (sysfs / ioctl on desktop; SCSI READ of the
  table on OTG), not by assuming it is partition #3. On a GPT image it may not be.
- Plain ISO/IMG flashing with the checkbox **off** stays the current pipeline: full-image
  SHA-256 read-back, no table rewrite, no format.

#### Where the code lives

**Desktop / shared Rust**

- `crates/flash-core/src/layout.rs` — inspect, `plan_layout`, `apply_plan`, `build_mbr` /
  `build_gpt`, `validate_written_gpt`, `minimum_drive_bytes`. Pure functions over byte
  buffers and a small `BlockIo` trait (`read_at` / `write_at` / `flush`). No Tauri, no
  Android, no `pkexec`.
- `crates/flash-core/testdata/layout/` — committed golden vectors (see below).
- `crates/flash-helper` — after Don approves implementation: when the opt-in is on, the
  helper (1) re-reads geometry and identity, (2) plans, (3) refuses before opening the
  device for write if the plan fails, (4) raw-writes the ISO, (5) flushes, (6) applies only
  the partition-table bytes, (7) verifies the payload after 32 KiB, (8) locates the data
  region by start LBA, (9) runs `mkfs.ext4 -L VICTORDATA` on that region, (10) never formats
  or touches a partition it did not just create. The helper already re-validates the target
  with `lsblk`; extend that path to read logical/physical sector size (`blockdev --getss` /
  `--getpbsz`, or `BLKSSZGET` / `BLKPBSZGET`) rather than trusting the GUI.
- `src-tauri/src/lib.rs` — a `plan_persistence` command for the UI, and optional arguments
  on the existing `flash` command. Planning stays in `flash-core`; the Tauri layer only
  forwards.

**Android (Kotlin)**

- `android/app/src/main/kotlin/com/victorious/flash/android/otg/LayoutPlanner.kt` — the same
  pure plan/inspect/apply logic, unit-tested, no `UsbManager` types.
- `ScsiBlockDevice.kt` / `Scsi.kt` — the OTG sector-size check lives here. Today
  `Scsi.Capacity.blockSize` comes from READ CAPACITY(10/16) and already allows
  512, 1024, 2048, and 4096 for **plain** writes. For the persistence path, treat
  `blockSize` as the logical sector size and **refuse when it is not 512**. Optionally
  parse the READ CAPACITY(16) logical-blocks-per-physical-block exponent so 512e can be
  reported and aligned; 512e remains allowed. This check runs during probe, before the
  danger screen can start a persistence write.
- `DiskWriter.kt` / `UsbWriteService.kt` — after a successful raw write and cache flush,
  apply the table through the same SCSI READ/WRITE path, then payload-verify after 32 KiB
  and locate the partition by start LBA.

**[Certain]** Android OTG talks to the stick only as a USB mass-storage LUN. There is no
`/dev/sdX` on the phone, so the app cannot exec `mke2fs` against the device. Formatting
ext4 on OTG therefore needs a userspace writer that speaks the same `BlockDevice` interface,
or the partition is left unformatted. That is an open question (§7) and a reason to sequence
desktop format first.

**[Certain]** OTG today uses READ(10)/WRITE(10) only (`Scsi.kt`), which cannot address
LBAs past 2³²−1. Even after the planner lands, Android OTG cannot write a GPT backup at the
end of a >2 TiB disk without READ(16)/WRITE(16). Until those CDBs exist, OTG should keep
refusing targets above 2 TiB. Desktop can lift the cap once the GPT path is tested.

#### Shared tests (golden vectors)

Ship a checked-in fixture directory, for example `crates/flash-core/testdata/layout/`, also
copied or referenced from `android/app/src/test/resources/layout/`.

Each case is a JSON (or similar) record produced from the Python reference, not hand-invented:

- Inputs: synthetic hybrid image kind (`mbr` / `gpt` / protective-MBR / non-hybrid), image
  size, disk bytes, logical/physical sector size, optional requested data size, table mode,
  `allow_mbr_cap`.
- Outputs: either a `PlanError` string class (too small, 4Kn, GPT required, slot 3 taken, …)
  or the plan fields (`table`, `data_start_lba`, `data_len_lba`, `data_end_lba`, `data_bytes`,
  `unallocated_bytes`, `gpt_backup_lba`, notes).
- For apply cases: expected MBR slot-3 bytes and/or GPT header/array CRCs and the
  VICTORDATA start/end, including a case whose data end is past LBA 2³², and a GPT image
  whose entry-array LBA is not 2.

A small regenerator script (developer-only, not a CI runtime dependency) can call
`victor_layout.py` against the same synthetic images as `tests/test_layout_planner.py`.
CI runs **only** the Rust and Kotlin tests against the committed vectors, so Flash stays
standalone. Cover at least the planner cases already in that file: 8/16/59/119 GiB and
sub-2 TiB MBR, decimal 1–16 TB auto-GPT, exact 2³² boundary, 4Kn refusal, 512e alignment,
explicit data size, non-hybrid rejection, MBR slot-3 write, GPT write on a sparse 4 TiB
file, `allow_mbr_cap`, protective MBR, and "apply refuses, file untouched".

The unmerged Claude tests for "64 GB / 128 GB from the real size, not a nominal table" are
useful, but they are not a substitute for the official vectors.

#### Until this item lands

Keep the existing 2 TiB refusal on desktop and Android. Do not advertise Flash for boot
media above 2 TiB. Do not invent a partial GPT writer.

---

### 3.2 Show the plan in the UI before erase confirmation — High / M

The data partition is an **opt-in checkbox**, default **off**, labelled clearly, for example
"Also add a persistent VICTORDATA partition". Plain ISO flashing stays on the current path.

When the box is ticked:

1. The app gathers drive size and sector sizes, inspects the image, and runs the planner.
2. The **plan is shown before** the existing erase confirmation (desktop: type the device
   name; Android: tick plus type `ERASE` on the full-screen danger screen).
3. The plan lists at least: table type (MBR or GPT), logical/physical sector size, data
   start (LBA and bytes), data size, unallocated tail, reserve, minimum drive size, and any
   planner notes. The owner may set an explicit data size; the remainder stays unallocated.
4. If the plan fails (4Kn, too small, GPT required, slot taken, …), flashing stays disabled
   and the reason is shown. The drive is not opened for write.
5. The drive-wipe warning stays **impossible to miss**. Desktop keeps the red `.danger`
   panel and typed device name (`Flasher.tsx`). Android keeps `RedConfirmScreen` (full-screen
   `#B00020`, tick + `ERASE`) used by local OTG and fleet confirms. The plan is additional
   information on or immediately above that warning, never a replacement for it.

**Surfaces:** desktop `src/screens/Flasher.tsx` (USB and SD) and Android `OtgScreen.kt` /
`RedConfirmScreen`. Rooted gadget and no-root netboot do **not** offer the checkbox
(read-only ISO). Point those screens at the manual section in §3.4.

**Theme.** **[Certain]** The app today is Victorious navy + electric green
(`#0B1530`, `#13214A`, `#39FF14`, danger `#FF4D5E`), from the 14 October 2026 restyle,
in `src/styles.css` and `android/.../ui/Theme.kt`. This plan's visual constraint is the
dark neon palette `#0B0F1A`, `#00E5FF`, `#FF2BD6`. **Recommendation:** use those neon
colours on the **new plan card** (cyan labels, magenta accents, `#0B0F1A` panel) and leave
the rest of the app, and the red danger confirm, unchanged in the first implementation PR.
A whole-app retheme is a separate product decision (open question). Do not weaken the
danger contrast to make it "neon".

---

### 3.3 Catalog entry only after a real ISO — Med / S

**[Certain]** No VictorLinux release, tag, or published ISO+SHA-256 exists today. Flash
already requires `https://` URLs and a 64-hex SHA-256 when one is pinned
(`catalog.rs`).

Rules:

- Add a `catalog/distros.json` entry **only** when a real ISO, a real SHA-256, and a real
  HTTPS URL (and a checksum URL) exist. No placeholder URLs. No invented or estimated
  checksums. No "coming soon" entry with a dummy hash.
- Use existing fields only: `kind` omitted or `linux-iso`, `image_format: "iso"`, optional
  `gadget` tags after a real check, `notes` for caveats (for example that no-root HTTP boot
  is not supported for this image).
- Do not add a Direct-install loopback recipe unless one has been tested for this ISO.
  **[Certain]** today's Direct IDs are Ubuntu and Mint only.
- **[Certain]** Flash's no-root HTTP/EFI path is Ubuntu/casper-specific. VictorLinux
  (Arch/archiso, and the Debian edition) must not be advertised there. The VictorLinux
  README already says this.
- Until a release exists, a self-built ISO can only be written if Flash gains a local-file
  path with an owner-supplied SHA-256. Main has no local-image picker (that exists only on
  the unmerged Claude branch). That picker is **not** part of the minimum integration; it is
  an open question.

Same bar as RootForge OS: the parser already accepts `rootforge-os`, but there is no
catalog row on main until Don has a real image.

---

### 3.4 Document the phone-ISO boot path — Low / S

Update `docs/MANUAL.md` as part of the feature work (required by `docs/CONTRIBUTING.md`),
and keep this design's summary accurate. Suggested landing: a short subsection under §3.5
(rooted gadget) and a pointer from §3.6 and §3.7, labelled "Planned, not yet available"
until the code exists.

Document, without promising untested hardware behaviour:

- The phone can expose the same ISO a USB stick would hold (rooted gadget, read-only
  mass-storage or CD-ROM). The PC boots that OS and uses the **PC's** GPU and disks.
- That path does **not** create a VICTORDATA partition on the phone or add a second
  writable gadget LUN (deliberately; exclusive ownership of a writable LUN is unproven).
- Persistence for a phone-booted session comes from a **separate writable drive** plugged
  into the PC. In current VictorLinux (Arch edition), application state needs a POSIX
  filesystem (ext4/btrfs/xfs). The volume does **not** have to be labelled `VICTORDATA` if
  the owner selects it with `victor-storage state`. Models and documents may live on other
  ordinary drives (see VictorLinux `docs/storage.md`).
- OTG "write the ISO to a stick, optionally with VICTORDATA on that same stick" is a
  different path from "phone is the boot media".
- One USB-C port cannot be promised as gadget and OTG host at the same time.
- No-root UEFI HTTP Boot remains unsupported for VictorLinux.
- None of the phone-boot or OTG-with-persistence paths is verified on real hardware today
  **[Certain]** for Flash; VictorLinux also has not booted its current ISO on physical
  firmware.

---

## 4. Risks

- **Wrong planner port.** GPT geometry (non-standard entry-array LBA, backup relocation,
  CRCs) is easy to get subtly wrong and would produce unbootable or unreadable media.
  Mitigation: golden vectors plus, in desktop CI when `sgdisk` is present, an independent
  `sgdisk -v` check on a sparse image, matching VictorLinux's own test.
- **Landing the Claude MBR-only `persist.rs`.** It would leave Flash unable to do what
  `flash-companion-changes.md` asks, and would disagree with VictorLinux on minimum size
  and GPT. Mitigation: do not merge that branch as-is.
- **Advertising >2 TiB or a catalog ISO too early.** Mitigation: keep the 2 TiB cap until
  the planner is on main and tested; catalog row only with a real URL and hash.
- **Whole-disk verify vs payload verify.** If the UI still says "Write verified" in the
  current sense after rewriting the table, that is a false claim. Mitigation: distinct
  success copy.
- **Android format gap.** Partition table I/O over SCSI is feasible; `mkfs.ext4` is not,
  unless a userspace formatter is written. Mitigation: sequence desktop first; do not claim
  a ready VICTORDATA volume on OTG until format works.
- **Safety filter vs large removable disks.** Today's 2 TiB cap is also a hedge against
  listing a large USB HDD/SSD that is not a "stick". Lifting it after the planner lands
  needs an explicit Don decision (§7).
- **Theme clash.** A surprise whole-app retheme is out of scope for the first code PR
  unless Don asks for it.
- **Unverified hardware.** Planner tests use sparse files and, in VictorLinux, loop devices
  and QEMU of a generic GRUB image. The real VictorLinux ISO on real firmware is untested
  in both projects.

---

## 5. Test plan (for the future implementation PRs)

- Golden-vector parity with `victor_layout.py` in Rust and Kotlin (plan and apply, including
  refusals that leave a sentinel file untouched).
- Desktop: helper/integration test on a sparse or loop file: raw write, table apply, payload
  hash after 32 KiB, partition found by start LBA, ext4 label `VICTORDATA` when format is in
  scope. `sgdisk -v` when available.
- Checkbox off: existing USB/SD/OTG tests still pass; read-back is the full image; no table
  rewrite.
- 4Kn and "too small" refuse before the first write on desktop and on a fake OTG block
  device.
- 512e: data start aligned to the physical size.
- UI tests (desktop component / Compose): plan visible before confirm; confirm control
  disabled until the existing typed name / `ERASE` + tick; checkbox default off.
- Catalog tests: parser still rejects non-HTTPS and bad hashes; no VictorLinux fixture
  until a real ISO exists.
- Manual: `docs/MANUAL.md` updated in the same feature PR, with "Planned, not yet
  available" where needed.
- Do not treat QEMU of a generic image, or a file-backed disk, as proof of a real-PC boot.

---

## 6. Phased rollout

All phases after 0 wait for Don's approval of this plan. Each phase is its own PR, on a
branch, not a direct push to main. Merge only when Don has said yes (or, if he later
extends the existing "auto-merge when green" standing rule to these PRs, when every check
is green and the PR is clean). This design PR itself is **not** auto-merged.

0. **This document** (docs only). Stays open for Don's review.
1. **Planner library + golden tests only** in `flash-core` and Kotlin unit tests. Not wired
   to the helper, OTG writer, or UI. No behaviour change for users. Keep the 2 TiB cap.
2. **Desktop wiring:** checkbox, plan preview, helper apply, payload verify, format
   `VICTORDATA`, MANUAL updates for desktop. 2 TiB cap still on until this is green.
3. **Lift the desktop 2 TiB cap** only after phase 2 is proven (and after Don answers §7.3).
   **Android:** 4Kn / sector-size refusal, plan UI on the danger path, table apply + payload
   verify. Format on OTG only if §7.5 is decided.
4. **Phone-ISO boot path** documented in the manual (may ship with phase 2 or 3).
5. **Catalog entry** in a dedicated PR, only with a real ISO, SHA-256, and HTTPS URL.

---

## 7. Open questions for Don

1. **Theme.** Use dark neon (`#0B0F1A`, `#00E5FF`, `#FF2BD6`) only on the new plan card, or
   retheme the whole desktop and Android app away from the current navy + electric green?
2. **Unmerged Claude branch `claude/new-session-t2zzsm`.** Agree to not merge it as the
   integration, and to port `victor_layout.py` instead? Any pieces (local-image picker,
   gadget re-hash while exposed) you want kept as separate PRs?
3. **After the planner lands, should Flash list removable drives above 2 TiB for plain ISO
   writes as well, or only when the VICTORDATA checkbox is on?** Lifting the cap also
   lists large USB hard disks that the current safety filter hides.
4. **Expose "limit data to the first 2 TiB" (`allow_mbr_cap`) in the UI** when GPT is
   impossible for the image, or only refuse and tell the owner to rebuild the ISO?
5. **Android OTG format.** Leave the new partition unformatted (owner formats on a PC, or
   VictorLinux would have to gain an empty-partition format path), or invest in a userspace
   ext4 formatter that writes through SCSI? Recommendation: desktop format first; no OTG
   "ready to use VICTORDATA" claim until format works.
6. **Local ISO picker** (owner types a SHA-256; no invented hash) so a self-built
   VictorLinux image can be written before a public release? Main does not have this today.
7. **Minimum data size.** Follow the official planner (2 GiB) rather than the unmerged
   Claude branch (8 GiB)?
8. **Fleet.** Leave `otg.write` as a plain raw write for now, or allow an optional
   persistence field later, still with on-phone `ERASE` and the plan shown on the confirm
   screen?
9. Confirm the checkbox defaults **off**, and that rooted gadget will **not** grow a second
   writable data LUN in this work.
10. When a real ISO exists, should it be offered for **gadget-disk / gadget-cdrom** only
    after a real phone-boot check, and never for no-root HTTP boot?

---

## 8. Out of scope

- Implementing any of the above in this PR.
- Pushing or merging this PR without Don's approval.
- Installing VictorLinux onto an internal disk.
- New `ImageKind` / `Mode` / fleet opcode variants.
- Placeholder catalog rows, placeholder URLs, or invented checksums.
- Treating the unmerged Claude `persist.rs` as done work.
- Promising a verified boot of a production VictorLinux ISO on real hardware.

---

## 9. References (reviewed)

- Flash `origin/main` @ `299a825`
- Unmerged Flash branch `claude/new-session-t2zzsm` @ `2b9dd51` (not a source of truth)
- VictorLinux `main` @ `01fdd30`
- `docs/flash-companion-changes.md`, `scripts/victor_layout.py`,
  `tests/test_layout_planner.py`, `docs/usb.md`, `docs/storage.md`
- Flash `crates/flash-core/src/{catalog,devices}.rs`, `crates/flash-helper/src/main.rs`,
  `src/screens/Flasher.tsx`, `android/.../otg/{Scsi,ScsiBlockDevice,DiskWriter}.kt`,
  `android/.../OtgScreen.kt`, `docs/MANUAL.md`
