use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Distro {
    pub id: String,
    pub name: String,
    pub version: String,
    pub description: String,
    pub iso_url: String,
    /// URL of the published checksum file (e.g. SHA256SUMS).
    pub checksum_url: String,
    /// File name to look up inside the checksum file.
    pub iso_filename: String,
    /// Optional detached GPG signature of the checksum file.
    pub signature_url: Option<String>,
    pub homepage: String,
    /// Pinned SHA-256 when the vendor has no checksum file (e.g. Pop!_OS API).
    #[serde(default)]
    pub sha256: Option<String>,
    /// What kind of image this entry is. Defaults to a desktop Linux ISO.
    #[serde(default)]
    pub kind: ImageKind,
    /// Container format of the downloaded file: `iso`, raw `img`, or `img-xz` (decompressed while writing).
    #[serde(default)]
    pub image_format: ImageFormat,
    /// Optional free-form notes (e.g. "unlock bootloader first").
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ImageKind { #[default] LinuxIso, RootforgeOs, RaspberryPiOs }

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ImageFormat { #[default] Iso, Img, ImgXz }

/// The three ways to use an image.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum Mode { Direct, Usb, Sd }

impl Distro {
    /// Every catalog image can be raw-written to USB or SD. Direct install (boot the ISO from
    /// this computer's disk via GRUB loopback) is only offered where we know the loopback recipe.
    pub fn modes(&self) -> Vec<Mode> {
        let mut m = Vec::new();
        if self.image_format == ImageFormat::Iso && crate::direct::loopback_recipe(&self.id).is_some() { m.push(Mode::Direct) }
        m.push(Mode::Usb); m.push(Mode::Sd);
        m
    }
}

#[derive(Debug, thiserror::Error)]
pub enum CatalogError {
    #[error("catalog JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error("catalog entry {0}: {1}")]
    Invalid(String, String),
}

/// Parse and validate a catalog document (embedded or downloaded via catalog.update).
pub fn parse_catalog(json: &str) -> Result<Vec<Distro>, CatalogError> {
    let c: Vec<Distro> = serde_json::from_str(json)?;
    let mut seen = std::collections::HashSet::new();
    for d in &c {
        let bad = |m: &str| CatalogError::Invalid(d.id.clone(), m.into());
        if d.id.is_empty() || !d.id.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') { return Err(bad("bad id")) }
        if !seen.insert(d.id.clone()) { return Err(bad("duplicate id")) }
        if !d.iso_url.starts_with("https://") || !d.checksum_url.starts_with("https://") { return Err(bad("URLs must be https")) }
        if d.iso_filename.contains('/') || d.iso_filename.is_empty() { return Err(bad("bad iso_filename")) }
        if let Some(h) = &d.sha256 { if !(h.len() == 64 && h.chars().all(|c| c.is_ascii_hexdigit())) { return Err(bad("bad sha256")) } }
    }
    Ok(c)
}

pub const CATALOG_JSON: &str = include_str!("../../../catalog/distros.json");

pub fn load_catalog() -> Result<Vec<Distro>, serde_json::Error> {
    serde_json::from_str(CATALOG_JSON)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn catalog_parses_and_has_core_distros() {
        let c = load_catalog().unwrap();
        for id in ["ubuntu", "fedora", "mint", "debian", "popos", "opensuse", "arch"] {
            assert!(c.iter().any(|d| d.id == id), "missing {id}");
        }
        for d in &c {
            assert!(d.iso_url.starts_with("https://"), "{}", d.id);
            assert!(d.checksum_url.starts_with("https://"), "{}", d.id);
            if let Some(h) = &d.sha256 { assert!(h.len() == 64 && h.chars().all(|c| c.is_ascii_hexdigit()), "{}", d.id); }
            assert!(d.modes().contains(&Mode::Usb) && d.modes().contains(&Mode::Sd));
        }
        assert!(parse_catalog(CATALOG_JSON).is_ok());
    }

    #[test]
    fn raspberry_pi_os_entries_are_pinned_img_xz() {
        let c = load_catalog().unwrap();
        for id in ["raspios-lite-arm64", "raspios-arm64", "raspios-lite-armhf"] {
            let d = c.iter().find(|d| d.id == id).unwrap_or_else(|| panic!("missing {id}"));
            assert_eq!(d.kind, ImageKind::RaspberryPiOs);
            assert_eq!(d.image_format, ImageFormat::ImgXz);
            assert!(d.iso_url.starts_with("https://downloads.raspberrypi.com/") && d.iso_url.ends_with(&d.iso_filename));
            assert_eq!(d.checksum_url, format!("{}.sha256", d.iso_url));
            assert!(d.sha256.is_some(), "{id} must pin a hash");
            assert!(!d.iso_url.contains("_latest"), "{id} must pin a dated release");
            assert_eq!(d.modes(), vec![Mode::Usb, Mode::Sd]);
        }
    }

    const BASE: &str = r#""name":"X","version":"1","description":"d","iso_url":"https://e.test/a.img","checksum_url":"https://e.test/SUMS","iso_filename":"a.img","signature_url":null,"homepage":"https://e.test""#;

    #[test]
    fn parses_rootforge_entries_and_modes() {
        let j = format!(r#"[{{"id":"rootforge-os",{BASE},"kind":"rootforge-os","image_format":"img"}}]"#);
        let c = parse_catalog(&j).unwrap();
        assert_eq!(c[0].kind, ImageKind::RootforgeOs);
        assert_eq!(c[0].modes(), vec![Mode::Usb, Mode::Sd]);
        let ubuntu = load_catalog().unwrap().into_iter().find(|d| d.id == "ubuntu").unwrap();
        assert_eq!(ubuntu.modes(), vec![Mode::Direct, Mode::Usb, Mode::Sd]);
        // Removed fields/values are rejected rather than silently ignored.
        assert!(parse_catalog(&format!(r#"[{{"id":"a",{BASE},"kind":"android-image"}}]"#)).is_err());
    }

    #[test]
    fn rejects_unsafe_entries() {
        let http = format!(r#"[{{"id":"x",{}}}]"#, BASE.replace("https://e.test/a.img", "http://e.test/a.img"));
        assert!(parse_catalog(&http).is_err());
        let dup = format!(r#"[{{"id":"x",{BASE}}},{{"id":"x",{BASE}}}]"#);
        assert!(parse_catalog(&dup).is_err());
        let badid = format!(r#"[{{"id":"../x",{BASE}}}]"#);
        assert!(parse_catalog(&badid).is_err());
    }
}
