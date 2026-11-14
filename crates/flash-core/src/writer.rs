//! The complete raw-write pipeline, independent of any real device so it can be exercised with
//! file-backed and fault-injecting disks: verify source -> write -> flush -> read back -> compare.
//!
//! A write is only reported successful when ALL of these hold:
//! * the image file matched the expected SHA-256 before any byte was written,
//! * (raw images) the bytes actually streamed to the device hash to that same value, so a source
//!   that changed between the hash pass and the write pass is caught,
//! * the flush to the device succeeded,
//! * a fresh read of the device returned exactly as many bytes as were written, and their hash equals
//!   the hash of what was written.
use crate::image::{self, ImageError, Written};
use std::io::{self, Read, Seek, Write};

/// A destination that can be flushed durably (a real device uses `sync_all`).
pub trait Sink: Write {
    fn sync(&mut self) -> io::Result<()>;
}
impl Sink for std::fs::File {
    fn sync(&mut self) -> io::Result<()> { self.sync_all() }
}

#[derive(Debug, thiserror::Error)]
pub enum FlashError {
    #[error("{0}")]
    Image(#[from] ImageError),
    #[error("the device refused the final flush: {0}. The data on it is not trustworthy")]
    Flush(io::Error),
    #[error("could not read the device back: {0}")]
    Readback(io::Error),
    #[error("read-back returned only {got} of {expected} bytes (device disconnected or shrank)")]
    ReadbackShort { expected: u64, got: u64 },
    #[error("verify mismatch: expected {expected} got {actual}")]
    VerifyMismatch { expected: String, actual: String },
    #[error("the image file changed while it was being written (expected {expected}, streamed {streamed}); the device content is not trustworthy and must be rewritten")]
    SourceChanged { expected: String, streamed: String },
}

/// Write `src` to `dst`, then verify through a fresh reader obtained from `reopen`.
pub fn flash<S, D>(
    src: &mut S, dst: &mut D, reopen: &mut dyn FnMut() -> io::Result<Box<dyn Read>>,
    expected_file_sha256: &str, device_size: u64, mut progress: impl FnMut(&str, u64, u64),
) -> Result<Written, FlashError>
where S: Read + Seek, D: Sink {
    let xz = image::is_xz(src).map_err(ImageError::Io)?;
    let written = image::write_verified(src, dst, expected_file_sha256, device_size, &mut progress)?;
    if !xz && !written.sha256.eq_ignore_ascii_case(expected_file_sha256) {
        return Err(FlashError::SourceChanged { expected: expected_file_sha256.to_lowercase(), streamed: written.sha256 });
    }
    dst.sync().map_err(FlashError::Flush)?;
    let total = written.bytes;
    let mut seen = 0u64;
    let reader = reopen().map_err(FlashError::Readback)?;
    let got = crate::checksum::sha256_reader(reader.take(total), |d| { seen = d; progress("verify", d, total) })
        .map_err(FlashError::Readback)?;
    if seen != total { return Err(FlashError::ReadbackShort { expected: total, got: seen }) }
    if got != written.sha256 { return Err(FlashError::VerifyMismatch { expected: written.sha256, actual: got }) }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::io::Cursor;
    use std::rc::Rc;

    fn payload(n: usize) -> Vec<u8> { (0..n as u32).map(|i| (i.wrapping_mul(2654435761) >> 11) as u8).collect() }
    fn sha(b: &[u8]) -> String { crate::checksum::sha256_reader(b, |_| {}).unwrap() }

    /// A disk whose content is shared with its readers, with switchable faults.
    #[derive(Clone, Default)]
    struct Disk { data: Rc<RefCell<Vec<u8>>>, fail_flush: bool, write_limit: Option<usize>, corrupt_after_write: bool, truncate_readback: Option<usize> }
    struct Writer(Disk);
    impl Write for Writer {
        fn write(&mut self, b: &[u8]) -> io::Result<usize> {
            let mut d = self.0.data.borrow_mut();
            if let Some(limit) = self.0.write_limit {
                if d.len() >= limit { return Err(io::Error::new(io::ErrorKind::Other, "device removed")) }
                let n = b.len().min(limit - d.len());
                d.extend_from_slice(&b[..n]);
                return Ok(n);
            }
            d.extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> io::Result<()> { Ok(()) }
    }
    impl Sink for Writer {
        fn sync(&mut self) -> io::Result<()> {
            if self.0.fail_flush { return Err(io::Error::new(io::ErrorKind::Other, "I/O error")) }
            if self.0.corrupt_after_write { if let Some(b) = self.0.data.borrow_mut().get_mut(5) { *b ^= 0xff } }
            Ok(())
        }
    }
    fn run(disk: &Disk, image: &[u8], expect: &str, dev_size: u64) -> Result<Written, FlashError> {
        let mut w = Writer(disk.clone());
        let d = disk.clone();
        let mut reopen = move || -> io::Result<Box<dyn Read>> {
            let mut data = d.data.borrow().clone();
            if let Some(n) = d.truncate_readback { data.truncate(n) }
            Ok(Box::new(Cursor::new(data)))
        };
        flash(&mut Cursor::new(image.to_vec()), &mut w, &mut reopen, expect, dev_size, |_, _, _| {})
    }

    #[test]
    fn happy_path_writes_and_verifies() {
        let img = payload(3 << 20); let disk = Disk::default();
        let w = run(&disk, &img, &sha(&img), 1 << 30).unwrap();
        assert_eq!(w.bytes, img.len() as u64);
        assert_eq!(*disk.data.borrow(), img);
    }

    #[test]
    fn wrong_hash_writes_nothing() {
        let img = payload(1 << 20); let disk = Disk::default();
        assert!(matches!(run(&disk, &img, &"0".repeat(64), 1 << 30), Err(FlashError::Image(ImageError::Mismatch { .. }))));
        assert!(disk.data.borrow().is_empty());
    }

    #[test]
    fn image_larger_than_device_writes_nothing() {
        let img = payload(1 << 20); let disk = Disk::default();
        assert!(matches!(run(&disk, &img, &sha(&img), (1 << 20) - 1), Err(FlashError::Image(ImageError::TooLarge { .. }))));
        assert!(disk.data.borrow().is_empty());
    }

    #[test]
    fn failed_flush_is_a_failure() {
        let img = payload(1 << 20); let disk = Disk { fail_flush: true, ..Default::default() };
        assert!(matches!(run(&disk, &img, &sha(&img), 1 << 30), Err(FlashError::Flush(_))));
    }

    #[test]
    fn device_disconnect_mid_write_is_a_failure_not_a_short_success() {
        let img = payload(2 << 20); let disk = Disk { write_limit: Some(1 << 20), ..Default::default() };
        assert!(matches!(run(&disk, &img, &sha(&img), 1 << 30), Err(FlashError::Image(ImageError::Io(_)))));
    }

    #[test]
    fn readback_mismatch_detected() {
        let img = payload(1 << 20); let disk = Disk { corrupt_after_write: true, ..Default::default() };
        assert!(matches!(run(&disk, &img, &sha(&img), 1 << 30), Err(FlashError::VerifyMismatch { .. })));
    }

    #[test]
    fn short_readback_detected_as_capacity_change_or_disconnect() {
        let img = payload(1 << 20); let disk = Disk { truncate_readback: Some(1000), ..Default::default() };
        assert!(matches!(run(&disk, &img, &sha(&img), 1 << 30), Err(FlashError::ReadbackShort { expected, got }) if got == 1000 && expected == (1 << 20)));
    }

    /// Source that returns different bytes on its second pass (hash pass vs write pass).
    struct Mutating { data: Vec<u8>, pos: usize, total_read: usize }
    impl Read for Mutating {
        fn read(&mut self, b: &mut [u8]) -> io::Result<usize> {
            let n = b.len().min(self.data.len() - self.pos);
            b[..n].copy_from_slice(&self.data[self.pos..self.pos + n]);
            if self.total_read >= self.data.len() && n > 0 && self.pos == 0 { b[0] ^= 0x55 }
            self.pos += n;
            self.total_read += n;
            Ok(n)
        }
    }
    impl Seek for Mutating {
        fn seek(&mut self, p: io::SeekFrom) -> io::Result<u64> {
            match p {
                io::SeekFrom::Start(0) => self.pos = 0,
                io::SeekFrom::Start(n) => self.pos = n as usize,
                io::SeekFrom::End(0) => return Ok(self.data.len() as u64),
                _ => return Err(io::Error::new(io::ErrorKind::Unsupported, "seek")),
            }
            Ok(self.pos as u64)
        }
    }

    #[test]
    fn source_mutation_between_hash_and_write_is_caught() {
        let img = payload(1 << 20); let disk = Disk::default();
        let mut src = Mutating { data: img.clone(), pos: 0, total_read: 0 };
        let mut w = Writer(disk.clone());
        let d = disk.clone();
        let mut reopen = move || -> io::Result<Box<dyn Read>> { Ok(Box::new(Cursor::new(d.data.borrow().clone()))) };
        let r = flash(&mut src, &mut w, &mut reopen, &sha(&img), 1 << 30, |_, _, _| {});
        assert!(matches!(r, Err(FlashError::SourceChanged { .. })), "got {r:?}");
    }
}
