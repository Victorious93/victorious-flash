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
    /// What kind of image this entry is. Defaults to a desktop Linux ISO, so existing
    /// entries need no change.
    #[serde(default)]
    pub kind: ImageKind,
    /// How the image reaches the target. Only `usb-dd` can be written by this app/bridge.
    #[serde(default)]
    pub flash_method: FlashMethod,
    /// Container format of the downloaded file.
    #[serde(default)]
    pub image_format: ImageFormat,
    /// Android / RootForge OS images: device codenames the image is built for. Empty = any.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub device_codenames: Vec<String>,
    /// Optional free-form notes (e.g. "unlock bootloader first").
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ImageKind { #[default] LinuxIso, RootforgeOs, AndroidImage }

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum FlashMethod {
    /// Raw block write to a removable disk (the only method this app performs).
    #[default] UsbDd,
    /// `fastboot flash` on an attached Android device (catalogued, not executed here).
    Fastboot,
    /// `adb sideload` from recovery (catalogued, not executed here).
    RecoverySideload,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(rename_all = "kebab-case")]
pub enum ImageFormat { #[default] Iso, Img, Zip, ImgXz }

impl Distro {
    /// True when the entry can be written by the pkexec raw-write helper.
    pub fn is_usb_writable(&self) -> bool {
        self.flash_method == FlashMethod::UsbDd && matches!(self.image_format, ImageFormat::Iso | ImageFormat::Img)
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
        if d.kind != ImageKind::LinuxIso && d.device_codenames.iter().any(|c| c.is_empty() || !c.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')) {
            return Err(bad("bad device codename"))
        }
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
            assert_eq!(d.kind, ImageKind::LinuxIso);
            assert!(d.is_usb_writable());
        }
        assert!(parse_catalog(CATALOG_JSON).is_ok());
    }

    const BASE: &str = r#""name":"X","version":"1","description":"d","iso_url":"https://e.test/a.img","checksum_url":"https://e.test/SUMS","iso_filename":"a.img","signature_url":null,"homepage":"https://e.test""#;

    #[test]
    fn parses_rootforge_and_android_entries() {
        let j = format!(r#"[{{"id":"rootforge-os",{BASE},"kind":"rootforge-os","image_format":"img","device_codenames":["generic_x86_64"]}},
                           {{"id":"lineage-pixel",{BASE},"kind":"android-image","flash_method":"fastboot","image_format":"zip","device_codenames":["oriole"],"notes":"unlock first"}}]"#);
        let c = parse_catalog(&j).unwrap();
        assert_eq!(c[0].kind, ImageKind::RootforgeOs);
        assert!(c[0].is_usb_writable());
        assert_eq!(c[1].flash_method, FlashMethod::Fastboot);
        assert!(!c[1].is_usb_writable());
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
