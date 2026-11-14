import { useState } from "react";
const BOOT_KEYS: [string, string, string][] = [
  ["Dell", "F12", "F2"], ["HP", "F9 / Esc", "F10"], ["Lenovo", "F12 (or Novo button)", "F1 / F2"], ["ASUS", "F8 / Esc", "F2 / Del"],
  ["Acer", "F12", "F2 / Del"], ["MSI", "F11", "Del"], ["Gigabyte", "F12", "Del"], ["Microsoft Surface", "Hold Vol-Down + Power", "Hold Vol-Up + Power"],
  ["Apple Intel Mac", "Hold Option (⌥)", "—"], ["Framework", "F12", "F2"],
];
const STEPS = [
  { id: "victorlinux", title: "VictorLinux: portable AI workspace", body: <>
    <p>VictorLinux boots a PC from a USB stick or from a verified ISO exposed by Victorious Flash on a supported rooted phone. The PC supplies the CPU, GPU and RAM in either case.</p>
    <p>A raw image write does not create persistent data storage. Use the VictorLinux provisioning tool or a separate VICTORDATA drive; a phone exposing a read-only ISO is boot media only. Keep it connected while the live system is using it.</p>
    <p>After boot, open the local Control Center, select storage, check this PC's model recommendations, import a GGUF and start inference. This live image is not an automatic internal-disk installer. Confirm the image's actual checksum and current firmware compatibility before relying on it.</p>
  </> },
  { id: "backup", title: "1. Back up first", body: <>
    <p className="error">Installing an operating system can erase your data. Back up everything you care about to an external drive or cloud storage before you continue.</p>
    <ul><li>Copy documents, photos, browser bookmarks and password-manager exports.</li><li>Note your Windows/BitLocker recovery key (account.microsoft.com/devices/recoverykey) — you may need it after changing boot settings.</li><li>Keep the laptop plugged in during installation.</li></ul></> },
  { id: "mode", title: "2. Dual-boot or wipe?", body: <>
    <h4>Dual-boot (keep Windows)</h4><ul><li>Shrink the Windows partition in Windows Disk Management first (right-click C: → Shrink Volume); leave at least 30–50 GB unallocated.</li><li>Disable Windows Fast Startup (Control Panel → Power Options) and suspend BitLocker.</li><li>In the installer choose "Install alongside Windows" or use the free space.</li></ul>
    <h4>Wipe the disk (Linux only)</h4><ul><li>Use "Erase disk and install" only when you intend to replace everything on the selected disk.</li><li><strong>Everything on the disk is permanently deleted</strong>, including Windows and recovery partitions.</li></ul></> },
  { id: "keys", title: "3. Boot the installer (USB, SD or GRUB entry)", body: <>
    <p>Direct install: just reboot and pick the new GRUB entry. USB/SD: plug it in, restart, and tap the boot-menu key immediately at power-on. Use the firmware (BIOS/UEFI) setup key to change settings.</p>
    <table><thead><tr><th>Brand</th><th>Boot menu</th><th>BIOS/UEFI setup</th></tr></thead><tbody>{BOOT_KEYS.map(([b, m, s]) => <tr key={b}><td>{b}</td><td>{m}</td><td>{s}</td></tr>)}</tbody></table>
    <p>From Windows you can also: Settings → System → Recovery → Advanced startup → Restart now → Use a device.</p>
    <p><strong>UEFI vs legacy BIOS:</strong> modern PCs use UEFI — pick the entry starting with "UEFI:" for your USB. Don't mix modes with an existing Windows install (Windows on GPT/UEFI ⇒ install Linux in UEFI mode).</p></> },
  { id: "secureboot", title: "4. Secure Boot", body: <>
    <ul><li>Secure Boot support depends on the exact distribution and image. Check that image's official instructions; do not infer support from this generic guide.</li><li>VictorLinux Secure Boot compatibility has not been verified for this revised ISO. Check its release test report and your PC firmware's boot policy.</li><li>If you install NVIDIA or other third-party drivers you may be asked to enroll a MOK key on next boot: choose "Enroll MOK" and enter the password you set.</li><li>Also check SATA mode is "AHCI" rather than "RAID/Intel RST" if the installer can't see your disk (change only after reading your vendor's docs).</li></ul></> },
  { id: "partition", title: "5. Partitioning basics", body: <>
    <ul><li><strong>EFI System Partition</strong> (FAT32, ~512 MB–1 GB, mounted at /boot/efi) — reuse the existing one when dual-booting; don't format it.</li><li><strong>Root (/)</strong> ext4 or btrfs, 30 GB minimum, more is better.</li><li><strong>/home</strong> (optional) separate partition keeps your files across reinstalls.</li><li><strong>Swap</strong>: most distros use a swap file automatically; add a swap partition ≥ RAM size only if you want hibernation.</li><li>Enable full-disk encryption (LUKS) if the device is a laptop — and store the passphrase safely.</li></ul></> },
];
export default function Guide() {
  const [step, setStep] = useState(0);
  return (
    <section className="guide">
      <aside>{STEPS.map((s, i) => <button key={s.id} className={i === step ? "active" : ""} onClick={() => setStep(i)}>{s.title}</button>)}</aside>
      <article><h2>{STEPS[step].title}</h2>{STEPS[step].body}
        <div className="row">{step > 0 && <button onClick={() => setStep(step - 1)}>Back</button>}{step < STEPS.length - 1 && <button className="primary" onClick={() => setStep(step + 1)}>Next</button>}</div>
      </article>
    </section>
  );
}
