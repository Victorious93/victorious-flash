//! VictorLinux persistence layout: the verified hybrid ISO is written raw, then ONE extra MBR
//! partition (slot 3, type 0x83) labelled `VICTORDATA` is added in the free space after the image.
//! This mirrors `scripts/make-usb.sh` (raw layout) in the VictorLinux repository, and is computed
//! from the image's own partition table and the drive's real size, never from a nominal "64/128 GB".
//! Raw ISO writing and persistence creation are separate operations: the ISO read-back hash covers
//! the ISO bytes, and creating the partition afterwards changes only the MBR partition table.
use std::io::{self, Read, Seek, SeekFrom, Write};

pub const SECTOR: u64 = 512;
pub const ALIGN_SECTORS: u64 = 2048;            // 1 MiB
pub const HEADROOM_MIB: u64 = 1024;             // left unallocated at the end of the drive
pub const MIN_DATA_GIB: u64 = 8;
pub const LABEL: &str = "VICTORDATA";
const SLOT3: usize = 446 + 32;

#[derive(Debug, thiserror::Error)]
pub enum PersistError {
    #[error("the image has no MBR boot signature, so it is not a hybrid image this layout can extend")]
    NoMbr,
    #[error("the image's MBR partition slot 3 is already used; refusing to overwrite it")]
    SlotTaken,
    #[error("the drive is too small: only {have_gib} GiB would remain for data, at least {MIN_DATA_GIB} GiB is required")]
    TooSmall { have_gib: u64 },
    #[error("the drive is larger than an MBR partition can address (2 TiB)")]
    TooLargeForMbr,
    #[error("{0}")]
    Io(#[from] io::Error),
    #[error("{0}")]
    Other(String),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
pub struct Layout { pub start_sector: u64, pub len_sectors: u64 }
impl Layout {
    pub fn data_bytes(&self) -> u64 { self.len_sectors * SECTOR }
}

/// First sector after every partition the image's own MBR declares. Slot 3 must be free.
pub fn image_end_sector(mbr: &[u8]) -> Result<u64, PersistError> {
    if mbr.len() < 512 || mbr[510] != 0x55 || mbr[511] != 0xaa { return Err(PersistError::NoMbr) }
    if mbr[SLOT3..SLOT3 + 16].iter().any(|b| *b != 0) { return Err(PersistError::SlotTaken) }
    let mut end = 0u64;
    for i in 0..4 {
        let e = &mbr[446 + 16 * i..446 + 16 * (i + 1)];
        let start = u32::from_le_bytes(e[8..12].try_into().unwrap()) as u64;
        let len = u32::from_le_bytes(e[12..16].try_into().unwrap()) as u64;
        end = end.max(start + len);
    }
    Ok(end)
}

/// Layout for a drive of `device_bytes`, given the image MBR and the image's byte length (the
/// image may extend past its last declared partition, so the larger of the two decides).
pub fn plan(mbr: &[u8], image_bytes: u64, device_bytes: u64) -> Result<Layout, PersistError> {
    if device_bytes > (2u64 << 40) { return Err(PersistError::TooLargeForMbr) }
    let end = image_end_sector(mbr)?.max(image_bytes.div_ceil(SECTOR));
    let start = end.div_ceil(ALIGN_SECTORS) * ALIGN_SECTORS;
    let disk = device_bytes / SECTOR;
    let headroom = HEADROOM_MIB * 2048;
    let len = disk.saturating_sub(start).saturating_sub(headroom);
    let len = len / ALIGN_SECTORS * ALIGN_SECTORS;
    if len * SECTOR < MIN_DATA_GIB << 30 { return Err(PersistError::TooSmall { have_gib: (len * SECTOR) >> 30 }) }
    Ok(Layout { start_sector: start, len_sectors: len })
}

/// Put the Linux partition into MBR slot 3 (CHS fields are the LBA-only convention 0xFE 0xFF 0xFF).
pub fn apply_entry(mbr: &mut [u8], l: &Layout) -> Result<(), PersistError> {
    if l.start_sector > u32::MAX as u64 || l.len_sectors > u32::MAX as u64 { return Err(PersistError::TooLargeForMbr) }
    if mbr[SLOT3..SLOT3 + 16].iter().any(|b| *b != 0) { return Err(PersistError::SlotTaken) }
    let e = &mut mbr[SLOT3..SLOT3 + 16];
    e[0] = 0x00;
    e[1..4].copy_from_slice(&[0xfe, 0xff, 0xff]);
    e[4] = 0x83;
    e[5..8].copy_from_slice(&[0xfe, 0xff, 0xff]);
    e[8..12].copy_from_slice(&(l.start_sector as u32).to_le_bytes());
    e[12..16].copy_from_slice(&(l.len_sectors as u32).to_le_bytes());
    Ok(())
}

/// Write the new partition-table entry to sector 0 of `dev` and flush. Touches ONLY the 16-byte
/// slot-3 entry; every other byte of the image is left exactly as verified.
pub fn write_entry<D: Read + Write + Seek>(dev: &mut D, l: &Layout) -> Result<(), PersistError> {
    let mut sector = [0u8; 512];
    dev.seek(SeekFrom::Start(0))?;
    dev.read_exact(&mut sector)?;
    apply_entry(&mut sector, l)?;
    dev.seek(SeekFrom::Start(SLOT3 as u64))?;
    dev.write_all(&sector[SLOT3..SLOT3 + 16])?;
    dev.flush()?;
    Ok(())
}

/// Arguments for `mke2fs` to format the new region of a plain file (used by tests and file-backed images).
pub fn mke2fs_file_args(file: &str, l: &Layout) -> Vec<String> {
    vec!["-q".into(), "-F".into(), "-t".into(), "ext4".into(), "-L".into(), LABEL.into(),
         "-E".into(), format!("offset={}", l.start_sector * SECTOR), file.into(), format!("{}K", l.data_bytes() / 1024)]
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::process::Command;

    /// An MBR shaped like the VictorLinux hybrid ISO: ISO data partition + EFI system partition, slot 3 free.
    fn iso_mbr(end_sectors: u32) -> [u8; 512] {
        let mut m = [0u8; 512];
        m[446 + 4] = 0x17; m[446 + 8..446 + 12].copy_from_slice(&0u32.to_le_bytes()); m[446 + 12..446 + 16].copy_from_slice(&(end_sectors - 20_000).to_le_bytes());
        m[462 + 4] = 0xef; m[462 + 8..462 + 12].copy_from_slice(&(end_sectors - 20_000).to_le_bytes()); m[462 + 12..462 + 16].copy_from_slice(&20_000u32.to_le_bytes());
        m[510] = 0x55; m[511] = 0xaa; m
    }
    const GB: u64 = 1_000_000_000;

    #[test]
    fn layouts_are_computed_from_real_sizes_for_64_and_128_gb_media() {
        let mbr = iso_mbr(8_000_000);                 // ~3.9 GiB image
        for (nominal, real) in [(64u64, 62_534_975_488u64), (64, 64 * GB), (128, 125_269_877_760), (128, 128 * GB)] {
            let l = plan(&mbr, 4_096_000_000, real).unwrap();
            assert_eq!(l.start_sector % ALIGN_SECTORS, 0);
            assert_eq!(l.len_sectors % ALIGN_SECTORS, 0);
            // ends at least 1 GiB before the end of the drive and starts after the image
            assert!((l.start_sector + l.len_sectors) * SECTOR + (HEADROOM_MIB << 20) <= real, "{nominal}GB: {l:?}");
            assert!(l.start_sector >= 8_000_000);
            assert!(l.data_bytes() > (nominal - 8) * GB * 8 / 10, "{nominal} GB drive should leave most of it for data");
        }
        // different real sizes give different layouts (not a table of nominal constants)
        assert_ne!(plan(&mbr, 4_096_000_000, 62_534_975_488).unwrap(), plan(&mbr, 4_096_000_000, 64 * GB).unwrap());
    }

    #[test]
    fn refuses_small_drives_bad_images_and_taken_slots() {
        let mbr = iso_mbr(8_000_000);
        assert!(matches!(plan(&mbr, 4_096_000_000, 8 * GB), Err(PersistError::TooSmall { .. })));
        assert!(matches!(plan(&[0u8; 512], 1, 64 * GB), Err(PersistError::NoMbr)));
        let mut taken = mbr; taken[SLOT3 + 4] = 0x83;
        assert!(matches!(plan(&taken, 4_096_000_000, 64 * GB), Err(PersistError::SlotTaken)));
        assert!(matches!(plan(&mbr, 1, 3u64 << 40), Err(PersistError::TooLargeForMbr)));
    }

    #[test]
    fn image_longer_than_its_partition_table_still_wins() {
        let mbr = iso_mbr(1_000_000);
        let l = plan(&mbr, 6_000_000_000, 64 * GB).unwrap();
        assert!(l.start_sector * SECTOR >= 6_000_000_000);
    }

    #[test]
    fn entry_changes_only_slot_three() {
        let mbr = iso_mbr(8_000_000);
        let l = plan(&mbr, 4_096_000_000, 64 * GB).unwrap();
        let mut disk = std::io::Cursor::new({ let mut v = vec![0xabu8; 4096]; v[..512].copy_from_slice(&mbr); v });
        let before = disk.get_ref().clone();
        write_entry(&mut disk, &l).unwrap();
        let after = disk.get_ref();
        let diff: Vec<usize> = (0..4096).filter(|i| before[*i] != after[*i]).collect();
        assert!(diff.iter().all(|i| (SLOT3..SLOT3 + 16).contains(i)), "unexpected bytes changed: {diff:?}");
        assert_eq!(after[SLOT3 + 4], 0x83);
        assert_eq!(u32::from_le_bytes(after[SLOT3 + 8..SLOT3 + 12].try_into().unwrap()) as u64, l.start_sector);
        // a second run refuses to clobber the entry it just made
        assert!(matches!(write_entry(&mut disk, &l), Err(PersistError::SlotTaken)));
    }

    /// Real mke2fs on a sparse file-backed "disk": the filesystem lands at the planned offset with the right label.
    #[test]
    fn real_ext4_is_created_at_the_planned_offset_with_the_victordata_label() {
        if Command::new("mke2fs").arg("-V").output().is_err() { eprintln!("mke2fs missing; skipped"); return }
        let dir = std::env::temp_dir().join(format!("vflash-persist-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let img = dir.join("disk.img");
        let size = 10u64 * 1024 * 1024 * 1024;       // sparse 10 GiB: no real space used
        let f = std::fs::File::create(&img).unwrap(); f.set_len(size).unwrap(); drop(f);
        let mbr = iso_mbr(200_000);                   // small fake ISO so 10 GiB leaves >= 8 GiB
        { use std::os::unix::fs::FileExt; std::fs::OpenOptions::new().write(true).open(&img).unwrap().write_all_at(&mbr, 0).unwrap(); }
        let l = plan(&mbr, 100_000_000, size).unwrap();
        let mut f = std::fs::OpenOptions::new().read(true).write(true).open(&img).unwrap();
        write_entry(&mut f, &l).unwrap();
        let out = Command::new("mke2fs").args(mke2fs_file_args(img.to_str().unwrap(), &l)).output().unwrap();
        assert!(out.status.success(), "{}", String::from_utf8_lossy(&out.stderr));
        let dump = Command::new("dumpe2fs").arg("-h").arg(format!("{}?offset={}", img.display(), l.start_sector * SECTOR)).output();
        // dumpe2fs has no offset syntax for plain files in all versions; fall back to reading the superblock label directly.
        let _ = dump;
        use std::os::unix::fs::FileExt;
        let g = std::fs::File::open(&img).unwrap();
        let mut sb = [0u8; 1024];
        g.read_exact_at(&mut sb, l.start_sector * SECTOR + 1024).unwrap();
        assert_eq!(u16::from_le_bytes([sb[56], sb[57]]), 0xEF53, "ext superblock magic at the planned offset");
        let label = String::from_utf8_lossy(&sb[120..136]).trim_end_matches('\0').to_string();
        assert_eq!(label, LABEL);
        let mut m = [0u8; 512]; g.read_exact_at(&mut m, 0).unwrap();
        assert_eq!(m[SLOT3 + 4], 0x83);
        std::fs::remove_dir_all(&dir).ok();
    }
}
