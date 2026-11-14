//! Owner-selected local images (e.g. a freshly built VictorLinux ISO that has no catalog entry yet).
//!
//! A local file is never trusted just because it exists. The result says exactly where the trust
//! comes from: a hash the owner typed, a checksum file next to the image, or nothing but a locally
//! computed hash (which still pins the bytes so a later change is caught, but proves nothing about
//! where they came from).
use crate::{checksum, image};
use serde::Serialize;
use std::io::{Read, Seek};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Trust {
    /// The owner supplied an expected SHA-256 and it matched.
    OwnerSupplied,
    /// A SHA256SUMS / `<image>.sha256` file beside the image listed this file and matched.
    Sidecar,
    /// No independent expectation: the hash was only computed here.
    UnverifiedLocal,
}

#[derive(Debug, thiserror::Error)]
pub enum LocalError {
    #[error("not an absolute path to a regular file: {0}")]
    BadPath(String),
    #[error("the file is empty")]
    Empty,
    #[error("expected hash must be 64 hex characters")]
    BadHash,
    #[error("checksum mismatch: expected {expected}, got {actual}. Do not flash this file")]
    Mismatch { expected: String, actual: String },
    #[error("{0}")]
    Image(#[from] image::ImageError),
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
}

#[derive(Debug, Clone, Serialize)]
pub struct LocalImage {
    pub path: String,
    pub name: String,
    pub file_size: u64,
    /// Bytes that will land on the device (xz index size for .img.xz).
    pub write_size: u64,
    pub sha256: String,
    pub trust: Trust,
    pub verified: bool,
    /// Where an expected hash came from, for display ("typed by you", "SHA256SUMS", …).
    pub trust_source: String,
}

pub fn is_hex64(s: &str) -> bool { s.len() == 64 && s.chars().all(|c| c.is_ascii_hexdigit()) }

/// Reject device/pseudo-filesystem paths and anything that is not a plain regular file.
pub fn resolve(path: &str) -> Result<PathBuf, LocalError> {
    if !path.starts_with('/') || path.contains('\0') { return Err(LocalError::BadPath(path.into())) }
    let p = std::fs::canonicalize(path).map_err(|_| LocalError::BadPath(path.into()))?;
    if p.starts_with("/dev") || p.starts_with("/proc") || p.starts_with("/sys") { return Err(LocalError::BadPath(path.into())) }
    if !std::fs::metadata(&p)?.is_file() { return Err(LocalError::BadPath(path.into())) }
    Ok(p)
}

/// Look for a published checksum for this exact file name beside it.
pub fn sidecar_hash(p: &Path) -> Option<(String, String)> {
    let name = p.file_name()?.to_str()?;
    let dir = p.parent()?;
    let own = dir.join(format!("{name}.sha256"));
    if let Ok(t) = std::fs::read_to_string(&own) {
        let t = t.trim();
        let first = t.split_whitespace().next().unwrap_or("");
        if is_hex64(first) && t.split_whitespace().count() == 1 { return Some((first.to_lowercase(), format!("{name}.sha256"))) }
        if let Some(h) = checksum::find_sha256(t, name) { return Some((h, format!("{name}.sha256"))) }
    }
    for f in ["SHA256SUMS", "SHA256SUMS.txt", "sha256sums.txt", "manifest.sha256"] {
        if let Ok(t) = std::fs::read_to_string(dir.join(f)) {
            if let Some(h) = checksum::find_sha256(&t, name) { return Some((h, f.to_string())) }
        }
    }
    None
}

/// Hash and classify a local image. `expected` (owner-typed) wins over a sidecar file.
pub fn inspect(path: &str, expected: Option<&str>, mut progress: impl FnMut(u64, u64)) -> Result<LocalImage, LocalError> {
    let p = resolve(path)?;
    let mut f = std::fs::File::open(&p)?;
    let file_size = f.metadata()?.len();
    if file_size == 0 { return Err(LocalError::Empty) }
    let write_size = image::write_size(&mut f)?;
    f.rewind()?;
    let sha = checksum::sha256_reader(f.by_ref().take(file_size), |d| progress(d, file_size))?;
    let (exp, trust, src) = match expected.map(str::trim).filter(|s| !s.is_empty()) {
        Some(e) => {
            if !is_hex64(e) { return Err(LocalError::BadHash) }
            (Some(e.to_lowercase()), Trust::OwnerSupplied, "the hash you entered".to_string())
        }
        None => match sidecar_hash(&p) {
            Some((h, from)) => (Some(h), Trust::Sidecar, from),
            None => (None, Trust::UnverifiedLocal, "computed here only; no published checksum was supplied".to_string()),
        },
    };
    if let Some(e) = &exp {
        if *e != sha { return Err(LocalError::Mismatch { expected: e.clone(), actual: sha }) }
    }
    Ok(LocalImage { path: p.to_string_lossy().into(), name: p.file_name().unwrap_or_default().to_string_lossy().into(),
        file_size, write_size, sha256: sha, verified: exp.is_some(), trust, trust_source: src })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn scratch(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("vflash-local-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }
    fn sha(b: &[u8]) -> String { checksum::sha256_reader(b, |_| {}).unwrap() }

    #[test]
    fn unverified_local_is_labelled_and_still_pins_the_hash() {
        let d = scratch("a"); let f = d.join("victorlinux.iso");
        std::fs::write(&f, b"hello iso").unwrap();
        let r = inspect(f.to_str().unwrap(), None, |_, _| {}).unwrap();
        assert_eq!(r.trust, Trust::UnverifiedLocal);
        assert!(!r.verified);
        assert_eq!(r.sha256, sha(b"hello iso"));
        assert_eq!(r.write_size, 9);
    }

    #[test]
    fn owner_supplied_hash_must_match() {
        let d = scratch("b"); let f = d.join("v.iso");
        std::fs::write(&f, b"abc").unwrap();
        let ok = inspect(f.to_str().unwrap(), Some(&sha(b"abc").to_uppercase()), |_, _| {}).unwrap();
        assert!(ok.verified && ok.trust == Trust::OwnerSupplied);
        assert!(matches!(inspect(f.to_str().unwrap(), Some(&"0".repeat(64)), |_, _| {}), Err(LocalError::Mismatch { .. })));
        assert!(matches!(inspect(f.to_str().unwrap(), Some("xyz"), |_, _| {}), Err(LocalError::BadHash)));
    }

    #[test]
    fn sidecar_gnu_bsd_and_bare_hash_are_found_and_enforced() {
        let d = scratch("c"); let f = d.join("victorlinux-1.iso");
        std::fs::write(&f, b"payload").unwrap();
        let h = sha(b"payload");
        std::fs::write(d.join("SHA256SUMS"), format!("{h}  victorlinux-1.iso\n{}  other.iso\n", "1".repeat(64))).unwrap();
        let r = inspect(f.to_str().unwrap(), None, |_, _| {}).unwrap();
        assert_eq!((r.trust, r.verified, r.trust_source.as_str()), (Trust::Sidecar, true, "SHA256SUMS"));
        std::fs::remove_file(d.join("SHA256SUMS")).unwrap();
        std::fs::write(d.join("victorlinux-1.iso.sha256"), format!("{h}\n")).unwrap();
        assert_eq!(inspect(f.to_str().unwrap(), None, |_, _| {}).unwrap().trust, Trust::Sidecar);
        // A checksum file that names a DIFFERENT hash for this file refuses it.
        std::fs::write(d.join("victorlinux-1.iso.sha256"), format!("{}\n", "2".repeat(64))).unwrap();
        assert!(matches!(inspect(f.to_str().unwrap(), None, |_, _| {}), Err(LocalError::Mismatch { .. })));
    }

    #[test]
    fn refuses_devices_relative_paths_empty_and_directories() {
        assert!(matches!(resolve("/dev/sda"), Err(LocalError::BadPath(_))));
        assert!(matches!(resolve("/proc/self/environ"), Err(LocalError::BadPath(_))));
        assert!(matches!(resolve("relative.iso"), Err(LocalError::BadPath(_))));
        let d = scratch("d");
        assert!(matches!(resolve(d.to_str().unwrap()), Err(LocalError::BadPath(_))));
        let e = d.join("empty.iso"); std::fs::File::create(&e).unwrap().flush().unwrap();
        assert!(matches!(inspect(e.to_str().unwrap(), None, |_, _| {}), Err(LocalError::Empty)));
    }
}
