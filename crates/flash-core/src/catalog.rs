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
        }
    }
}
