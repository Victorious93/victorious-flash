//! Removable-device discovery and safety filtering based on `lsblk --json`.
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Deserialize)]
pub struct LsblkOutput { pub blockdevices: Vec<LsblkDevice> }

#[derive(Debug, Clone, Deserialize)]
pub struct LsblkDevice {
    pub name: String,
    #[serde(default)] pub path: Option<String>,
    #[serde(rename = "type", default)] pub kind: Option<String>,
    #[serde(default, deserialize_with = "boolish")] pub rm: bool,
    #[serde(default, deserialize_with = "boolish")] pub hotplug: bool,
    #[serde(default, deserialize_with = "boolish")] pub ro: bool,
    #[serde(default, deserialize_with = "u64ish")] pub size: Option<u64>,
    #[serde(default)] pub model: Option<String>,
    #[serde(default)] pub vendor: Option<String>,
    #[serde(default)] pub tran: Option<String>,
    #[serde(default)] pub mountpoint: Option<String>,
    #[serde(default)] pub mountpoints: Option<Vec<Option<String>>>,
    #[serde(default)] pub children: Vec<LsblkDevice>,
}

/// lsblk emits booleans as `true`/`false` (new) or `"1"`/`"0"` (old).
fn boolish<'de, D: serde::Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(match v { serde_json::Value::Bool(b) => b, serde_json::Value::String(s) => s == "1" || s == "true",
        serde_json::Value::Number(n) => n.as_u64() == Some(1), _ => false })
}

fn u64ish<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<u64>, D::Error> {
    let v = serde_json::Value::deserialize(d)?;
    Ok(match v { serde_json::Value::Number(n) => n.as_u64(), serde_json::Value::String(s) => s.parse().ok(), _ => None })
}

#[derive(Debug, Clone, Serialize, PartialEq)]
pub struct UsbDevice {
    pub name: String,
    pub path: String,
    pub size: u64,
    pub model: String,
    pub transport: String,
    pub mounted: Vec<String>,
    /// Which flashing mode this target belongs to.
    pub media: Media,
}

/// Target media for the two write modes. (The third mode, Direct install, writes no device.)
#[derive(Debug, Clone, Copy, Serialize, serde::Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum Media { Usb, Sd }

pub const LSBLK_ARGS: &[&str] = &["--json", "--bytes", "--output",
    "NAME,PATH,TYPE,RM,HOTPLUG,RO,SIZE,MODEL,VENDOR,TRAN,MOUNTPOINT,MOUNTPOINTS"];

const SYSTEM_MOUNTS: &[&str] = &["/", "/boot", "/boot/efi", "/efi", "/usr", "/var", "/home", "[SWAP]"];
/// Refuse anything larger than 2 TiB — almost certainly not a USB stick.
pub const MAX_SIZE: u64 = 2 * 1024 * 1024 * 1024 * 1024;

fn mounts(d: &LsblkDevice, out: &mut Vec<String>) {
    if let Some(m) = &d.mountpoint { out.push(m.clone()); }
    if let Some(ms) = &d.mountpoints { out.extend(ms.iter().flatten().cloned()); }
    for c in &d.children { mounts(c, out); }
}

/// An SD card in a built-in reader shows up as `mmcblkN`. eMMC (soldered internal storage) also
/// uses `mmcblkN`, but always exposes `mmcblkNboot0`/`mmcblkNrpmb` siblings; any mmc disk with
/// those siblings is treated as internal and never offered.
fn is_emmc(name: &str, all: &[LsblkDevice]) -> bool {
    all.iter().any(|o| o.name == format!("{name}boot0") || o.name == format!("{name}rpmb"))
}

/// Keep only whole, writable, removable/hotplug disks (USB sticks, SD cards) that host no system mount.
pub fn filter_removable(out: &LsblkOutput) -> Vec<UsbDevice> {
    out.blockdevices.iter().filter_map(|d| {
        let is_mmc = d.name.starts_with("mmcblk") || d.tran.as_deref() == Some("mmc");
        if is_mmc && (is_emmc(&d.name, &out.blockdevices) || d.name.contains("boot") || d.name.contains("rpmb")) { return None; }
        if d.kind.as_deref() != Some("disk") || d.ro { return None; }
        if !(d.rm || d.hotplug || d.tran.as_deref() == Some("usb") || is_mmc) { return None; }
        if matches!(d.tran.as_deref(), Some("nvme") | Some("sata") | Some("ata")) && !d.rm { return None; }
        if d.name.starts_with("loop") || d.name.starts_with("zram") || d.name.starts_with("sr") { return None; }
        let size = d.size.unwrap_or(0);
        if size == 0 || size > MAX_SIZE { return None; }
        let mut m = Vec::new(); mounts(d, &mut m);
        m.sort(); m.dedup();
        if m.iter().any(|p| SYSTEM_MOUNTS.contains(&p.as_str())) { return None; }
        let model = [d.vendor.as_deref(), d.model.as_deref()].iter().flatten()
            .map(|s| s.trim()).filter(|s| !s.is_empty()).collect::<Vec<_>>().join(" ");
        Some(UsbDevice { name: d.name.clone(), path: d.path.clone().unwrap_or(format!("/dev/{}", d.name)),
            size, model, transport: d.tran.clone().unwrap_or_default(), mounted: m,
            media: if is_mmc || looks_like_sd_reader(&d.model) { Media::Sd } else { Media::Usb } })
    }).collect()
}

/// USB card readers present as `sdX`; their model string usually says so.
fn looks_like_sd_reader(model: &Option<String>) -> bool {
    model.as_deref().map(|m| { let m = m.to_ascii_lowercase(); m.contains("sd") && (m.contains("card") || m.contains("reader")) || m.contains("sdxc") || m.contains("sdhc") }).unwrap_or(false)
}

pub fn parse_and_filter(json: &str) -> Result<Vec<UsbDevice>, serde_json::Error> {
    Ok(filter_removable(&serde_json::from_str(json)?))
}

/// User must type the exact kernel name (e.g. `sdb`) or full path.
pub fn confirmation_matches(dev: &UsbDevice, typed: &str) -> bool {
    let t = typed.trim();
    !t.is_empty() && (t == dev.name || t == dev.path)
}

/// Validate that a path given to the privileged helper is a plain /dev node name.
pub fn is_safe_device_path(p: &str) -> bool {
    p.strip_prefix("/dev/").map_or(false, |n| !n.is_empty() && n.chars().all(|c| c.is_ascii_alphanumeric()))
}

#[cfg(test)]
mod tests {
    use super::*;
    const SAMPLE: &str = r#"{"blockdevices":[
      {"name":"nvme0n1","path":"/dev/nvme0n1","type":"disk","rm":false,"hotplug":false,"ro":false,"size":512110190592,"model":"Samsung SSD","tran":"nvme","mountpoint":null,
       "children":[{"name":"nvme0n1p2","type":"part","rm":false,"mountpoints":["/"]}]},
      {"name":"sda","path":"/dev/sda","type":"disk","rm":false,"hotplug":false,"ro":false,"size":1000204886016,"tran":"sata"},
      {"name":"sdb","path":"/dev/sdb","type":"disk","rm":true,"hotplug":true,"ro":false,"size":31406948352,"vendor":"SanDisk ","model":"Ultra","tran":"usb",
       "children":[{"name":"sdb1","type":"part","mountpoints":["/media/don/STICK"]}]},
      {"name":"sdc","path":"/dev/sdc","type":"disk","rm":"1","hotplug":"1","ro":"0","size":"0","tran":"usb"},
      {"name":"sdd","path":"/dev/sdd","type":"disk","rm":true,"hotplug":true,"ro":false,"size":16000000000,"tran":"usb",
       "children":[{"name":"sdd1","type":"part","mountpoints":["/boot/efi"]}]},
      {"name":"loop0","path":"/dev/loop0","type":"loop","rm":false,"ro":true,"size":4096},
      {"name":"sr0","path":"/dev/sr0","type":"rom","rm":true,"ro":false,"size":1073741312},
      {"name":"sde","path":"/dev/sde","type":"disk","rm":true,"hotplug":true,"ro":true,"size":8000000000,"tran":"usb"}
    ]}"#;

    #[test]
    fn filters_to_only_safe_removable() {
        let devs = parse_and_filter(SAMPLE).unwrap();
        let names: Vec<_> = devs.iter().map(|d| d.name.as_str()).collect();
        assert_eq!(names, vec!["sdb"]);
        assert_eq!(devs[0].model, "SanDisk Ultra");
        assert_eq!(devs[0].mounted, vec!["/media/don/STICK"]);
    }
    #[test]
    fn sd_cards_allowed_emmc_refused() {
        let j = r#"{"blockdevices":[
          {"name":"mmcblk0","path":"/dev/mmcblk0","type":"disk","rm":false,"ro":false,"size":64000000000},
          {"name":"mmcblk0boot0","path":"/dev/mmcblk0boot0","type":"disk","rm":false,"ro":true,"size":4194304},
          {"name":"mmcblk1","path":"/dev/mmcblk1","type":"disk","rm":false,"ro":false,"size":31914983424},
          {"name":"sdc","path":"/dev/sdc","type":"disk","rm":true,"hotplug":true,"ro":false,"size":15931539456,"model":"SD Card Reader","tran":"usb"},
          {"name":"sdd","path":"/dev/sdd","type":"disk","rm":true,"hotplug":true,"ro":false,"size":15931539456,"model":"Cruzer","tran":"usb"}]}"#;
        let d = parse_and_filter(j).unwrap();
        let got: Vec<_> = d.iter().map(|x| (x.name.as_str(), x.media)).collect();
        assert_eq!(got, vec![("mmcblk1", Media::Sd), ("sdc", Media::Sd), ("sdd", Media::Usb)]);
    }
    #[test]
    fn confirmation_requires_exact_name() {
        let d = UsbDevice { name: "sdb".into(), path: "/dev/sdb".into(), size: 1, model: String::new(), transport: "usb".into(), mounted: vec![], media: Media::Usb };
        assert!(confirmation_matches(&d, "sdb"));
        assert!(confirmation_matches(&d, " /dev/sdb "));
        assert!(!confirmation_matches(&d, "sda"));
        assert!(!confirmation_matches(&d, ""));
        assert!(!confirmation_matches(&d, "sd"));
    }
    #[test]
    fn helper_path_validation() {
        assert!(is_safe_device_path("/dev/sdb"));
        assert!(!is_safe_device_path("/dev/../etc/passwd"));
        assert!(!is_safe_device_path("/etc/sdb"));
        assert!(!is_safe_device_path("/dev/"));
    }
}
