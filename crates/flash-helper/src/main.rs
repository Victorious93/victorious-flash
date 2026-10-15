//! Privileged helper, run via `pkexec victorious-flash-helper write <image> <device> <sha256-of-image-file>`.
//! `<image>` may be .iso/.img or .img.xz (detected by magic bytes, decompressed while writing).
//! Re-validates the target against lsblk (never trusts the GUI), writes the image with
//! O_SYNC-style flushing, then reads back and compares SHA-256.
//! Also: `victorious-flash-helper verify <device> <expected-sha256> <length>` — read-back only.
//! Progress is printed to stdout as `PROGRESS <phase> <done> <total>` lines.
use flash_core::{checksum, devices, image};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom};
use std::process::{exit, Command};

fn die(msg: &str) -> ! { eprintln!("ERROR {msg}"); exit(1) }

fn main() {
    let a: Vec<String> = std::env::args().collect();
    if a.len() == 5 && a[1] == "verify" { return verify_only(&a[2], &a[3].to_lowercase(), &a[4]) }
    if a.len() != 5 || a[1] != "write" { die("usage: victorious-flash-helper write <iso> <device> <sha256>") }
    let (iso, dev, expected) = (&a[2], &a[3], a[4].to_lowercase());
    if !devices::is_safe_device_path(dev) { die("invalid device path") }

    let out = Command::new("lsblk").args(devices::LSBLK_ARGS).output().unwrap_or_else(|e| die(&format!("lsblk: {e}")));
    let list = devices::parse_and_filter(&String::from_utf8_lossy(&out.stdout)).unwrap_or_else(|e| die(&format!("lsblk parse: {e}")));
    let target = list.iter().find(|d| &d.path == dev).unwrap_or_else(|| die("device is not an eligible removable disk"));
    if !target.mounted.is_empty() {
        // Unmount user-media partitions (never system mounts; filter already excluded those).
        for m in &target.mounted { let _ = Command::new("umount").arg(m).status(); }
    }

    // One open file for hash, size check and write. For .img.xz the file's SHA-256 (what the
    // vendor publishes) is checked before anything is written, the size check uses the xz index's
    // decompressed size, and the bytes are decompressed as they are written.
    let mut src = File::open(iso).unwrap_or_else(|e| die(&format!("open image: {e}")));
    if !src.metadata().map(|m| m.is_file()).unwrap_or(false) { die("image is not a regular file") }
    let mut dst = OpenOptions::new().write(true).open(dev).unwrap_or_else(|e| die(&format!("open device: {e}")));
    let written = image::write_verified(&mut src, &mut dst, &expected, target.size, |ph, d, t| println!("PROGRESS {ph} {d} {t}"))
        .unwrap_or_else(|e| die(&e.to_string()));
    dst.sync_all().unwrap_or_else(|e| die(&format!("sync: {e}")));
    drop(dst);

    let total = written.bytes;
    let mut rd = File::open(dev).unwrap_or_else(|e| die(&format!("reopen: {e}")));
    rd.seek(SeekFrom::Start(0)).ok();
    let got = checksum::sha256_reader(rd.take(total), |d| println!("PROGRESS verify {d} {total}"))
        .unwrap_or_else(|e| die(&format!("readback: {e}")));
    if got != written.sha256 { die(&format!("verify mismatch: expected {} got {got}", written.sha256)) }
    println!("DONE {got}");
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
