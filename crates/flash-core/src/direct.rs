//! Direct install: boot a downloaded, verified ISO straight from this computer's disk with a GRUB
//! loopback entry, then run the installer from there. No USB stick or SD card is needed.
//!
//! The app never edits the bootloader itself. It generates the menu entry and shows the user how
//! to add it (`/etc/grub.d/40_custom` + `update-grub`). Writing boot configuration as root is a
//! bigger risk than this MVP should take on.

/// (kernel, initrd, kernel args) inside the ISO. Only recipes we know: casper-based distros.
pub fn loopback_recipe(distro_id: &str) -> Option<(&'static str, &'static str, &'static str)> {
    match distro_id {
        "ubuntu" | "mint" => Some(("/casper/vmlinuz", "/casper/initrd", "boot=casper iso-scan/filename=${isofile} quiet splash")),
        _ => None,
    }
}

/// A GRUB `menuentry` for `iso_path`, or None if unsupported or the path is unsafe to embed.
/// `iso_path` must be the path *on the partition that holds it* (e.g. `/home/don/x.iso` when /home
/// is on the root partition).
pub fn grub_entry(distro_id: &str, title: &str, iso_path: &str) -> Option<String> {
    let (kernel, initrd, args) = loopback_recipe(distro_id)?;
    let safe = |s: &str| !s.is_empty() && s.chars().all(|c| c.is_ascii_alphanumeric() || " ./_-+:".contains(c));
    if !iso_path.starts_with('/') || iso_path.contains("..") || !safe(iso_path) || !safe(title) { return None }
    Some(format!(
        "menuentry \"{title} (live from ISO on disk)\" {{\n    set isofile=\"{iso_path}\"\n    search --no-floppy --set=root --file \"$isofile\"\n    loopback loop ($root)$isofile\n    linux (loop){kernel} {args}\n    initrd (loop){initrd}\n}}\n",
        args = args.replace("${isofile}", "$isofile"),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn ubuntu_entry() {
        let e = grub_entry("ubuntu", "Ubuntu 24.04", "/home/don/Downloads/ubuntu.iso").unwrap();
        assert!(e.contains("loopback loop ($root)$isofile"));
        assert!(e.contains("linux (loop)/casper/vmlinuz boot=casper iso-scan/filename=$isofile"));
    }
    #[test]
    fn refuses_unknown_and_unsafe() {
        assert!(grub_entry("arch", "Arch", "/a.iso").is_none());
        assert!(grub_entry("ubuntu", "U", "rel.iso").is_none());
        assert!(grub_entry("ubuntu", "U", "/a\"; reboot; \".iso").is_none());
        assert!(grub_entry("ubuntu", "U", "/x/../a.iso").is_none());
    }
}
