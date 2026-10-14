//! Privileged helper, run via `pkexec victorious-flash-helper write <iso> <device> <expected-sha256>`.
//! Re-validates the target against lsblk (never trusts the GUI), writes the image with
//! O_SYNC-style flushing, then reads back and compares SHA-256.
//! Progress is printed to stdout as `PROGRESS <phase> <done> <total>` lines.
use flash_core::{checksum, devices};
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::process::{exit, Command};

fn die(msg: &str) -> ! { eprintln!("ERROR {msg}"); exit(1) }

fn main() {
    let a: Vec<String> = std::env::args().collect();
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

    let mut src = File::open(iso).unwrap_or_else(|e| die(&format!("open iso: {e}")));
    let total = src.metadata().map(|m| m.len()).unwrap_or(0);
    if total > target.size { die("image larger than device") }
    let mut dst = OpenOptions::new().write(true).open(dev).unwrap_or_else(|e| die(&format!("open device: {e}")));

    let mut buf = vec![0u8; 4 << 20];
    let mut done = 0u64;
    loop {
        let n = src.read(&mut buf).unwrap_or_else(|e| die(&format!("read: {e}")));
        if n == 0 { break }
        dst.write_all(&buf[..n]).unwrap_or_else(|e| die(&format!("write: {e}")));
        done += n as u64;
        println!("PROGRESS write {done} {total}");
    }
    dst.sync_all().unwrap_or_else(|e| die(&format!("sync: {e}")));
    drop(dst);

    let mut rd = File::open(dev).unwrap_or_else(|e| die(&format!("reopen: {e}")));
    rd.seek(SeekFrom::Start(0)).ok();
    let got = checksum::sha256_reader(rd.take(total), |d| println!("PROGRESS verify {d} {total}"))
        .unwrap_or_else(|e| die(&format!("readback: {e}")));
    if got != expected { die(&format!("verify mismatch: expected {expected} got {got}")) }
    println!("DONE {got}");
}
