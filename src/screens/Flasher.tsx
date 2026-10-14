import { useEffect, useState } from "react";
import { api, DownloadResult, fmtBytes, UsbDevice } from "../api";
export default function Flasher({ iso, onDone }: { iso: DownloadResult | null; onDone: () => void }) {
  const [devices, setDevices] = useState<UsbDevice[]>([]);
  const [dev, setDev] = useState<UsbDevice | null>(null);
  const [typed, setTyped] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const refresh = () => api.listDevices().then(setDevices).catch((e) => setStatus(String(e)));
  useEffect(() => { refresh(); }, []);
  if (!iso) return <section><h2>Flash USB</h2><p>Download and verify an ISO first.</p></section>;
  const confirmed = !!dev && (typed.trim() === dev.name || typed.trim() === dev.path);
  const flash = async () => {
    if (!dev) return; setBusy(true); setStatus("Writing… you'll be asked for your password.");
    try { await api.flash(iso.path, dev.path, typed, iso.sha256); setStatus("Done! Written and read-back verified."); onDone(); }
    catch (e) { setStatus(`Failed: ${e}`); } finally { setBusy(false); }
  };
  return (
    <section>
      <h2>Flash USB</h2>
      <p>Image: <code>{iso.path}</code> — SHA-256 ✔ {iso.verified ? "verified" : "NOT verified"} · GPG: {iso.gpg}</p>
      <div className="row"><h3>Removable drives</h3><button onClick={refresh}>Refresh</button></div>
      {devices.length === 0 && <p>No removable USB drives found. Internal and system disks are never shown.</p>}
      {devices.map((d) => (
        <label key={d.path} className={`device ${dev?.path === d.path ? "sel" : ""}`}>
          <input type="radio" name="dev" checked={dev?.path === d.path} onChange={() => { setDev(d); setTyped(""); }} />
          <strong>{d.path}</strong> {d.model || "USB drive"} · {fmtBytes(d.size)} {d.mounted.length > 0 && <em>(mounted: {d.mounted.join(", ")})</em>}
        </label>
      ))}
      {dev && (
        <div className="danger">
          <p>⚠ ALL DATA on <strong>{dev.path}</strong> ({dev.model}, {fmtBytes(dev.size)}) will be destroyed. Type <code>{dev.name}</code> to confirm.</p>
          <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={dev.name} />
          <button className="primary danger-btn" disabled={!confirmed || busy} onClick={flash}>Erase & flash</button>
        </div>
      )}
      {status && <p>{status}</p>}
    </section>
  );
}
