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

/// The single hash in a per-file checksum file (`<image>.sha256`), whatever name it lists. Vendors such as
/// openSUSE publish `...-Current.iso.sha256` containing the dated snapshot's file name, so a name lookup
/// would never match. Only used when the checksum URL is exactly `<iso url>.sha256`/`.sha256sum`, and only
/// when the file has exactly ONE entry, so a multi-file SHA256SUMS can never be mistaken for it.
pub fn single_entry_sha256(contents: &str) -> Option<String> {
    let mut found = None;
    for line in contents.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')) {
        let h = if let Some(rest) = line.strip_prefix("SHA256 (") { rest.split_once(") = ").map(|(_, h)| h.trim()) }
                else { line.split_whitespace().next() };
        match h { Some(h) if is_hex64(h) => { if found.is_some() { return None } found = Some(h.to_lowercase()) } _ => {} }
    }
    found
}

pub fn is_per_file_checksum(iso_url: &str, checksum_url: &str) -> bool {
    checksum_url == format!("{iso_url}.sha256") || checksum_url == format!("{iso_url}.sha256sum")
}

/// Like [`verify`], with the per-file fallback described on [`single_entry_sha256`].
pub fn verify_for_catalog(actual: &str, checksum_file: &str, filename: &str, iso_url: &str, checksum_url: &str) -> Result<(), ChecksumError> {
    if find_sha256(checksum_file, filename).is_none() && is_per_file_checksum(iso_url, checksum_url) {
        if let Some(expected) = single_entry_sha256(checksum_file) {
            return if expected.eq_ignore_ascii_case(actual) { Ok(()) } else { Err(ChecksumError::Mismatch { expected, actual: actual.into() }) };
        }
    }
    verify(actual, checksum_file, filename)
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
    fn parses_pgp_signed_fedora_checksum() {
        let f = "-----BEGIN PGP SIGNED MESSAGE-----\nHash: SHA256\n\n# Fedora-Workstation-Live-42-1.1.x86_64.iso: 2398523392 bytes\nSHA256 (Fedora-Workstation-Live-42-1.1.x86_64.iso) = 98958d80e8a80eabe61275337f969c8e2212adc3a223d9bbdab9411bb1c95cba\n-----BEGIN PGP SIGNATURE-----\n\niQIzBAEBCAAdFiEEsPSVBFj2nhFQxsXtyKxJFhBe+UQF\n-----END PGP SIGNATURE-----\n";
        assert_eq!(find_sha256(f, "Fedora-Workstation-Live-42-1.1.x86_64.iso").as_deref(),
            Some("98958d80e8a80eabe61275337f969c8e2212adc3a223d9bbdab9411bb1c95cba"));
    }
    #[test]
    fn verify_detects_mismatch() {
        let f = format!("{ABC}  a.iso\n");
        assert!(verify(ABC, &f, "a.iso").is_ok());
        assert!(matches!(verify(&"f".repeat(64), &f, "a.iso"), Err(ChecksumError::Mismatch { .. })));
        assert!(matches!(verify(ABC, &f, "b.iso"), Err(ChecksumError::NotFound(_))));
    }

    #[test]
    fn per_file_checksum_naming_a_dated_snapshot_still_verifies_only_for_its_own_url() {
        let f = format!("{ABC}  openSUSE-Tumbleweed-DVD-x86_64-Snapshot20261111-Media.iso\n");
        let iso = "https://x/openSUSE-Tumbleweed-DVD-x86_64-Current.iso";
        assert!(verify(ABC, &f, "openSUSE-Tumbleweed-DVD-x86_64-Current.iso").is_err());
        assert!(verify_for_catalog(ABC, &f, "openSUSE-Tumbleweed-DVD-x86_64-Current.iso", iso, &format!("{iso}.sha256")).is_ok());
        assert!(matches!(verify_for_catalog(&"f".repeat(64), &f, "openSUSE-Tumbleweed-DVD-x86_64-Current.iso", iso, &format!("{iso}.sha256")), Err(ChecksumError::Mismatch { .. })));
        // a shared SHA256SUMS URL never gets the fallback, and neither does a multi-entry file
        assert!(verify_for_catalog(ABC, &f, "b.iso", iso, "https://x/SHA256SUMS").is_err());
        let two = format!("{ABC}  a.iso\n{ABC}  b.iso\n");
        assert!(verify_for_catalog(ABC, &two, "c.iso", iso, &format!("{iso}.sha256")).is_err());
    }
}
