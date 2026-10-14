use sha2::{Digest, Sha256};
use std::io::Read;

#[derive(Debug, thiserror::Error)]
pub enum ChecksumError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("no checksum entry for {0}")]
    NotFound(String),
    #[error("checksum mismatch: expected {expected}, got {actual}")]
    Mismatch { expected: String, actual: String },
}

/// Stream a reader through SHA-256, calling `progress(bytes_so_far)`.
pub fn sha256_reader<R: Read>(mut r: R, mut progress: impl FnMut(u64)) -> std::io::Result<String> {
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    let mut total = 0u64;
    loop {
        let n = r.read(&mut buf)?;
        if n == 0 { break; }
        h.update(&buf[..n]);
        total += n as u64;
        progress(total);
    }
    Ok(hex::encode(h.finalize()))
}

pub fn sha256_file(path: &std::path::Path) -> std::io::Result<String> {
    sha256_reader(std::fs::File::open(path)?, |_| {})
}

/// Parse a checksum file in either GNU style (`<hash>  [*]name`) or
/// BSD style (`SHA256 (name) = <hash>`) and return the hash for `filename`.
pub fn find_sha256(contents: &str, filename: &str) -> Option<String> {
    for line in contents.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') { continue; }
        if let Some(rest) = line.strip_prefix("SHA256 (") {
            if let Some((name, hash)) = rest.split_once(") = ") {
                if name == filename && is_hex64(hash.trim()) { return Some(hash.trim().to_lowercase()); }
            }
            continue;
        }
        let mut parts = line.split_whitespace();
        if let (Some(hash), Some(name)) = (parts.next(), parts.next()) {
            let name = name.trim_start_matches('*').trim_start_matches("./");
            if name == filename && is_hex64(hash) { return Some(hash.to_lowercase()); }
        }
    }
    None
}

fn is_hex64(s: &str) -> bool { s.len() == 64 && s.chars().all(|c| c.is_ascii_hexdigit()) }

pub fn verify(actual: &str, checksum_file: &str, filename: &str) -> Result<(), ChecksumError> {
    let expected = find_sha256(checksum_file, filename).ok_or_else(|| ChecksumError::NotFound(filename.into()))?;
    if expected.eq_ignore_ascii_case(actual) { Ok(()) } else {
        Err(ChecksumError::Mismatch { expected, actual: actual.into() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    #[test]
    fn hashes_known_vector() {
        assert_eq!(sha256_reader(&b"abc"[..], |_| {}).unwrap(), ABC);
    }
    #[test]
    fn parses_gnu_and_bsd() {
        let gnu = format!("{ABC} *ubuntu.iso\n0000000000000000000000000000000000000000000000000000000000000000  other.iso\n");
        assert_eq!(find_sha256(&gnu, "ubuntu.iso").as_deref(), Some(ABC));
        let bsd = format!("# Fedora\nSHA256 (Fedora.iso) = {ABC}\n");
        assert_eq!(find_sha256(&bsd, "Fedora.iso").as_deref(), Some(ABC));
        assert!(find_sha256(&bsd, "missing.iso").is_none());
    }
    #[test]
    fn verify_detects_mismatch() {
        let f = format!("{ABC}  a.iso\n");
        assert!(verify(ABC, &f, "a.iso").is_ok());
        assert!(matches!(verify(&"f".repeat(64), &f, "a.iso"), Err(ChecksumError::Mismatch { .. })));
        assert!(matches!(verify(ABC, &f, "b.iso"), Err(ChecksumError::NotFound(_))));
    }
}
