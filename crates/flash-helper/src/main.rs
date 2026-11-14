//! Privileged helper, run via `pkexec victorious-flash-helper write <image> <device> <sha256-of-image-file>`.
//! Optional trailing `<size-bytes>` pins the device identity the owner confirmed.
//! `<image>` may be .iso/.img or .img.xz (detected by magic bytes, decompressed while writing).
//! Re-validates the target against lsblk (never trusts the GUI), writes the image with
//! O_SYNC-style flushing, then reads back and compares SHA-256.
//! Also: `victorious-flash-helper verify <device> <expected-sha256> <length>` — read-back only.
//! Progress is printed to stdout as `PROGRESS <phase> <done> <total>` lines.
use flash_core::{checksum, devices, persist, writer};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom};
use std::process::{exit, Command};

fn die(msg: &str) -> ! { eprintln!("ERROR {msg}"); exit(1) }

fn lsblk_eligible() -> Vec<devices::UsbDevice> {
    let out = Command::new("lsblk").args(devices::LSBLK_ARGS).output().unwrap_or_else(|e| die(&format!("lsblk: {e}")));
    devices::parse_and_filter(&String::from_utf8_lossy(&out.stdout)).unwrap_or_else(|e| die(&format!("lsblk parse: {e}")))
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() == 5 && a[1] == "verify" { return verify_only(&a[2], &a[3].to_lowercase(), &a[4]) }
    // write <iso> <device> <sha256> [<confirmed-size-bytes>]
    if !(5..=7).contains(&a.len()) || a[1] != "write" { die("usage: victorious-flash-helper write <iso> <device> <sha256> [<size-bytes> [persist]]") }
    let want_persist = match a.get(6).map(String::as_str) { None => false, Some("persist") => true, Some(_) => die("unknown option") };
    let (iso, dev, expected) = (&a[2], &a[3], a[4].to_lowercase());
    if !devices::is_safe_device_path(dev) { die("invalid device path") }
    let confirmed_size: Option<u64> = a.get(5).map(|s| s.parse().unwrap_or_else(|_| die("invalid size")));

    let list = lsblk_eligible();
    let target = list.iter().find(|d| &d.path == dev).unwrap_or_else(|| die("device is not an eligible removable disk"));
    if let Some(why) = devices::identity_problem(confirmed_size, target) { die(&why) }

    // The image must be a regular file that does not live on the target.
    let canon = std::fs::canonicalize(iso).unwrap_or_else(|e| die(&format!("open image: {e}")));
    if devices::image_is_on_target(&canon, target) { die("the image is stored on the target device; copy it elsewhere first") }
    let mut src = File::open(&canon).unwrap_or_else(|e| die(&format!("open image: {e}")));
    if !src.metadata().map(|m| m.is_file()).unwrap_or(false) { die("image is not a regular file") }

    // Unmount user-media partitions (system mounts were already excluded by the filter) and make
    // sure they are really gone: writing under a mounted filesystem corrupts both.
    for m in &target.mounted { let _ = Command::new("umount").arg(m).status(); }
    if lsblk_eligible().iter().find(|d| &d.path == dev).map_or(true, |d| !d.mounted.is_empty()) {
        die("could not unmount the device (or it disappeared); close anything using it and retry")
    }

    // Re-check identity immediately before opening for write (closes the window during unmount).
    if let Some(now) = lsblk_eligible().iter().find(|d| &d.path == dev) { if let Some(why) = devices::identity_problem(Some(target.size), now) { die(&why) } }

    let mut dst = OpenOptions::new().write(true).open(dev).unwrap_or_else(|e| die(&format!("open device: {e}")));
    let dev_path = dev.clone();
    let mut reopen = move || -> std::io::Result<Box<dyn Read>> {
        let mut rd = File::open(&dev_path)?;
        rd.seek(SeekFrom::Start(0))?;
        Ok(Box::new(rd))
    };
    let written = writer::flash(&mut src, &mut dst, &mut reopen, &expected, target.size, |ph, d, t| println!("PROGRESS {ph} {d} {t}"))
        .unwrap_or_else(|e| die(&e.to_string()));
    drop(dst);
    if want_persist { add_persistence(dev, &mut src, written.bytes, target.size) }
    println!("DONE {}", written.sha256);
}

/// Separate step after the verified raw write: add the VICTORDATA partition in the free space.
/// The ISO read-back hash was already verified; this changes only the 16-byte MBR slot-3 entry.
fn add_persistence(dev: &str, src: &mut File, image_bytes: u64, device_bytes: u64) {
    let mut mbr = [0u8; 512];
    src.seek(SeekFrom::Start(0)).and_then(|_| src.read_exact(&mut mbr)).unwrap_or_else(|e| die(&format!("read image MBR: {e}")));
    let layout = persist::plan(&mbr, image_bytes, device_bytes).unwrap_or_else(|e| die(&format!("persistence: {e}")));
    println!("PROGRESS persist 0 2");
    let mut d = OpenOptions::new().read(true).write(true).open(dev).unwrap_or_else(|e| die(&format!("open device: {e}")));
    persist::write_entry(&mut d, &layout).unwrap_or_else(|e| die(&format!("persistence: {e}")));
    d.sync_all().unwrap_or_else(|e| die(&format!("sync: {e}")));
    drop(d);
    let _ = Command::new("blockdev").args(["--rereadpt", dev]).status();
    let _ = Command::new("udevadm").args(["settle", "--timeout=15"]).status();
    // The new partition is the one that starts at the planned sector.
    let part = find_partition(dev, layout.start_sector).unwrap_or_else(|| die("the kernel did not publish the new partition; replug the drive and run persistence again"));
    let r = Command::new("mkfs.ext4").args(["-q", "-F", "-L", persist::LABEL, &part]).status().unwrap_or_else(|e| die(&format!("mkfs.ext4: {e}")));
    if !r.success() { die("mkfs.ext4 failed; the partition entry exists but is unformatted") }
    println!("PROGRESS persist 2 2");
}

fn find_partition(dev: &str, start_sector: u64) -> Option<String> {
    let name = dev.trim_start_matches("/dev/");
    for _ in 0..20 {
        if let Ok(rd) = std::fs::read_dir(format!("/sys/block/{name}")) {
            for e in rd.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if !n.starts_with(name) { continue }
                if let Ok(s) = std::fs::read_to_string(e.path().join("start")) {
                    if s.trim().parse::<u64>().ok() == Some(start_sector) { return Some(format!("/dev/{n}")) }
                }
            }
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    None
}

fn verify_only(dev: &str, expected: &str, len: &str) {
    if !devices::is_safe_device_path(dev) { die("invalid device path") }
    if !(expected.len() == 64 && expected.chars().all(|c| c.is_ascii_hexdigit())) { die("invalid sha256") }
    let total: u64 = len.parse().unwrap_or_else(|_| die("invalid length"));
    let out = Command::new("lsblk").args(devices::LSBLK_ARGS).output().unwrap_or_else(|e| die(&format!("lsblk: {e}")));
    let list = devices::parse_and_filter(&String::from_utf8_lossy(&out.stdout)).unwrap_or_else(|e| die(&format!("lsblk parse: {e}")));
    let target = list.iter().find(|d| d.path == dev).unwrap_or_else(|| die("device is not an eligible removable disk"));
    if total == 0 || total > target.size { die("length out of range for device") }
    let rd = File::open(dev).unwrap_or_else(|e| die(&format!("open: {e}")));
    let got = checksum::sha256_reader(rd.take(total), |d| println!("PROGRESS verify {d} {total}"))
        .unwrap_or_else(|e| die(&format!("readback: {e}")));
    if got != expected { die(&format!("verify mismatch: expected {expected} got {got}")) }
    println!("DONE {got}");
}
