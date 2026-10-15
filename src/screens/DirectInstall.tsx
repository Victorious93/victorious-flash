import { useEffect, useState } from "react";
import { api, Distro, DownloadResult } from "../api";
/** Direct install: boot the verified ISO from this computer's disk. The app only shows the GRUB entry; it never edits boot config. */
export default function DirectInstall({ distro, iso, onDone }: { distro: Distro | null; iso: DownloadResult | null; onDone: () => void }) {
  const [entry, setEntry] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    setEntry(""); setError("");
    if (distro && iso) api.directEntry(distro.id, iso.path).then(setEntry).catch((e) => setError(String(e)));
  }, [distro, iso]);
  if (!distro || !iso) return <section><h2>Direct install</h2><p>Download and verify an ISO first.</p></section>;
  return (
    <section>
      <h2>Direct install (no USB)</h2>
      <p>Image: <code>{iso.path}</code> · SHA-256 {iso.verified ? "verified ✔" : "NOT verified"}</p>
      {error && <p className="error">{error}</p>}
      {entry && <>
        <p>This computer must already boot with GRUB (most Linux installs do). Keep the ISO where it is. It must be on a partition GRUB can read, and the installer must not erase that partition.</p>
        <ol>
          <li>Open <code>/etc/grub.d/40_custom</code> as root (e.g. <code>sudo nano /etc/grub.d/40_custom</code>) and add this at the end:</li>
        </ol>
        <pre>{entry}</pre>
        <ol start={2}>
          <li>Run <code>sudo update-grub</code> (Fedora/openSUSE: <code>sudo grub2-mkconfig -o /boot/grub2/grub.cfg</code>).</li>
          <li>Reboot and pick the new entry. Then start the installer and follow the install guide.</li>
        </ol>
        <p>If /home is on its own partition, write the path relative to that partition (drop the leading <code>/home</code>).</p>
        <button className="primary" onClick={onDone}>Continue to install guide</button>
      </>}
    </section>
  );
}
