//! Image sources for raw writes: plain `.iso`/`.img`, or `.img.xz` decompressed on the fly.
//!
//! For xz the *compressed* file is what the vendor's SHA-256 covers, so it is hashed before any
//! byte is written. While writing, the *decompressed* stream is hashed too, and that hash is what
//! the device read-back must match.
use sha2::{Digest, Sha256};
use std::io::{self, Read, Seek, SeekFrom, Write};

pub const XZ_MAGIC: [u8; 6] = [0xFD, b'7', b'z', b'X', b'Z', 0x00];

#[derive(Debug, thiserror::Error)]
pub enum ImageError {
    #[error("io: {0}")]
    Io(#[from] io::Error),
    #[error("invalid xz file: {0}")]
    BadXz(&'static str),
    #[error("image ({image} bytes) is larger than the device ({device} bytes)")]
    TooLarge { image: u64, device: u64 },
    #[error("checksum mismatch: expected {expected}, got {actual}")]
    Mismatch { expected: String, actual: String },
    #[error("decompressed {actual} bytes but the xz index promised {expected}")]
    SizeMismatch { expected: u64, actual: u64 },
}

pub fn is_xz<R: Read + Seek>(r: &mut R) -> io::Result<bool> {
    let mut m = [0u8; 6];
    r.seek(SeekFrom::Start(0))?;
    let ok = match r.read_exact(&mut m) { Ok(()) => m == XZ_MAGIC, Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => false, Err(e) => return Err(e) };
    r.seek(SeekFrom::Start(0))?;
    Ok(ok)
}

fn read_varint(b: &[u8], pos: &mut usize) -> Result<u64, ImageError> {
    let mut v = 0u64;
    for i in 0..9 {
        let byte = *b.get(*pos).ok_or(ImageError::BadXz("truncated index"))?;
        *pos += 1;
        v |= u64::from(byte & 0x7f) << (7 * i);
        if byte & 0x80 == 0 { return Ok(v) }
    }
    Err(ImageError::BadXz("varint too long"))
}

/// Total decompressed size from the xz index of every stream, without decompressing anything.
pub fn xz_uncompressed_size<R: Read + Seek>(r: &mut R) -> Result<u64, ImageError> {
    let mut end = r.seek(SeekFrom::End(0))?;
    let mut total = 0u64;
    let mut streams = 0;
    while end > 0 {
        // Skip stream padding (multiples of 4 zero bytes).
        let mut w = [0u8; 4];
        r.seek(SeekFrom::Start(end.checked_sub(4).ok_or(ImageError::BadXz("truncated"))?))?;
        r.read_exact(&mut w)?;
        if w == [0; 4] { end -= 4; continue }
        if end < 24 { return Err(ImageError::BadXz("truncated")) }
        let mut footer = [0u8; 12];
        r.seek(SeekFrom::Start(end - 12))?;
        r.read_exact(&mut footer)?;
        if &footer[10..12] != b"YZ" { return Err(ImageError::BadXz("missing stream footer")) }
        let index_size = (u64::from(u32::from_le_bytes(footer[4..8].try_into().unwrap())) + 1) * 4;
        if index_size > (64 << 20) || index_size + 24 > end { return Err(ImageError::BadXz("bad index size")) }
        let index_start = end - 12 - index_size;
        let mut idx = vec![0u8; index_size as usize];
        r.seek(SeekFrom::Start(index_start))?;
        r.read_exact(&mut idx)?;
        if idx[0] != 0 { return Err(ImageError::BadXz("bad index indicator")) }
        let mut pos = 1;
        let n = read_varint(&idx, &mut pos)?;
        let mut blocks = 0u64;
        for _ in 0..n {
            let unpadded = read_varint(&idx, &mut pos)?;
            let size = read_varint(&idx, &mut pos)?;
            blocks = blocks.checked_add((unpadded + 3) & !3).ok_or(ImageError::BadXz("overflow"))?;
            total = total.checked_add(size).ok_or(ImageError::BadXz("overflow"))?;
        }
        let stream_len = 12 + blocks + index_size + 12;
        end = end.checked_sub(stream_len).ok_or(ImageError::BadXz("stream sizes inconsistent"))?;
        let mut hdr = [0u8; 6];
        r.seek(SeekFrom::Start(end))?;
        r.read_exact(&mut hdr)?;
        if hdr != XZ_MAGIC { return Err(ImageError::BadXz("stream header not where the index says")) }
        streams += 1;
    }
    if streams == 0 { return Err(ImageError::BadXz("no streams")) }
    r.seek(SeekFrom::Start(0))?;
    Ok(total)
}

/// Bytes that will land on the device: the file size, or the xz-index size for `.xz`.
pub fn write_size<R: Read + Seek>(r: &mut R) -> Result<u64, ImageError> {
    if is_xz(r)? { xz_uncompressed_size(r) } else { let n = r.seek(SeekFrom::End(0))?; r.seek(SeekFrom::Start(0))?; Ok(n) }
}

/// Outcome of [`write_verified`].
#[derive(Debug, PartialEq, Eq)]
pub struct Written { pub bytes: u64, pub sha256: String }

/// The full source-side pipeline on ONE open file:
/// 1. hash the file as stored (compressed for xz) and compare with `expected_file_sha256`,
/// 2. check the output size (xz index for xz) against `device_size`,
/// 3. rewind, then decompress if needed while writing to `dst` and hashing what was written.
/// Nothing is written unless 1 and 2 pass. Returns the hash of the bytes written, which the
/// device read-back must match.
pub fn write_verified<R: Read + Seek, W: Write>(
    src: &mut R, dst: &mut W, expected_file_sha256: &str, device_size: u64, mut progress: impl FnMut(&str, u64, u64),
) -> Result<Written, ImageError> {
    let file_len = src.seek(SeekFrom::End(0))?;
    src.seek(SeekFrom::Start(0))?;
    let actual = crate::checksum::sha256_reader(&mut *src, |d| progress("hash", d, file_len))?;
    if !actual.eq_ignore_ascii_case(expected_file_sha256) {
        return Err(ImageError::Mismatch { expected: expected_file_sha256.to_lowercase(), actual });
    }
    let xz = is_xz(src)?;
    let total = if xz { xz_uncompressed_size(src)? } else { file_len };
    if total > device_size { return Err(ImageError::TooLarge { image: total, device: device_size }) }
    src.seek(SeekFrom::Start(0))?;
    let mut reader: Box<dyn Read + '_> = if xz { Box::new(xz2::read::XzDecoder::new_multi_decoder(&mut *src)) } else { Box::new(&mut *src) };
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 4 << 20];
    let mut done = 0u64;
    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 { break }
        if done + n as u64 > total { return Err(ImageError::SizeMismatch { expected: total, actual: done + n as u64 }) }
        dst.write_all(&buf[..n])?;
        h.update(&buf[..n]);
        done += n as u64;
        progress("write", done, total);
    }
    if done != total { return Err(ImageError::SizeMismatch { expected: total, actual: done }) }
    dst.flush()?;
    Ok(Written { bytes: done, sha256: hex::encode(h.finalize()) })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn payload() -> Vec<u8> { (0..300_000u32).flat_map(|i| (i.wrapping_mul(2654435761) >> 13).to_le_bytes()).collect() }
    fn xz(data: &[u8]) -> Vec<u8> { let mut e = xz2::write::XzEncoder::new(Vec::new(), 6); e.write_all(data).unwrap(); e.finish().unwrap() }
    fn sha(b: &[u8]) -> String { crate::checksum::sha256_reader(b, |_| {}).unwrap() }

    #[test]
    fn xz_index_size_single_multi_and_padded() {
        let p = payload();
        let one = xz(&p);
        assert_eq!(xz_uncompressed_size(&mut Cursor::new(&one)).unwrap(), p.len() as u64);
        let mut two = one.clone(); two.extend([0u8; 8]); two.extend(xz(b"tail"));
        assert_eq!(xz_uncompressed_size(&mut Cursor::new(&two)).unwrap(), p.len() as u64 + 4);
        assert!(xz_uncompressed_size(&mut Cursor::new(&one[..one.len() - 3])).is_err());
        assert!(xz_uncompressed_size(&mut Cursor::new(b"not an xz file at all, definitely".to_vec())).is_err());
    }

    #[test]
    fn xz_round_trip_hashes_decompressed_stream() {
        let p = payload();
        let c = xz(&p);
        let mut out = Vec::new();
        let w = write_verified(&mut Cursor::new(&c), &mut out, &sha(&c), 1 << 30, |_, _, _| {}).unwrap();
        assert_eq!(out, p);
        assert_eq!(w, Written { bytes: p.len() as u64, sha256: sha(&p) });
    }

    #[test]
    fn raw_image_passes_through() {
        let p = payload();
        let mut out = Vec::new();
        let w = write_verified(&mut Cursor::new(&p), &mut out, &sha(&p), p.len() as u64, |_, _, _| {}).unwrap();
        assert_eq!(w.sha256, sha(&p));
        assert_eq!(out.len(), p.len());
    }

    #[test]
    fn refuses_before_writing_on_bad_hash_or_oversize() {
        let p = payload();
        let c = xz(&p);
        let mut out = Vec::new();
        assert!(matches!(write_verified(&mut Cursor::new(&c), &mut out, &sha(&p), 1 << 30, |_, _, _| {}), Err(ImageError::Mismatch { .. })));
        // Compressed file fits easily, decompressed does not: the xz index size is what counts.
        assert!((c.len() as u64) < p.len() as u64 - 1);
        assert!(matches!(write_verified(&mut Cursor::new(&c), &mut out, &sha(&c), p.len() as u64 - 1, |_, _, _| {}), Err(ImageError::TooLarge { .. })));
        assert!(out.is_empty(), "nothing may be written on refusal");
        assert_eq!(write_size(&mut Cursor::new(&c)).unwrap(), p.len() as u64);
    }
}
