import { useEffect, useState } from "react";
import { api, DownloadResult, fmtBytes, PersistPlan, UsbDevice } from "../api";
/** Shared USB / SD writer. `media` only filters which removable disks are offered; the pkexec helper re-validates either way. */
export default function Flasher({ iso, media, onDone }: { iso: DownloadResult | null; media: "usb" | "sd"; onDone: () => void }) {
  const label = media === "sd" ? "SD card" : "USB drive";
  const [devices, setDevices] = useState<UsbDevice[]>([]);
  const [dev, setDev] = useState<UsbDevice | null>(null);
  const [typed, setTyped] = useState("");
  const [status, setStatus] = useState("");
  const [busy, setBusy] = useState(false);
  const [accepted, setAccepted] = useState(false);
  const [persist, setPersist] = useState(false);
  const [plan, setPlan] = useState<PersistPlan | null>(null);
  const [planErr, setPlanErr] = useState("");
  const same = (a: UsbDevice, b: UsbDevice) => a.path === b.path && a.size === b.size && a.model === b.model && a.transport === b.transport;
  const refresh = () => api.listDevices().then((ds) => {
    const list = ds.filter((d) => d.media === media);
    setDevices(list);
    // A device that vanished or changed identity can never keep its confirmation.
    setDev((cur) => { if (cur && !list.some((d) => same(d, cur))) { setTyped(""); setStatus("The selected device changed or was removed; choose it again."); return null; } return cur; });
  }).catch((e) => setStatus(String(e)));
  useEffect(() => { refresh(); setDev(null); }, [media]);
  // A different image (path or hash) invalidates the target choice, typed confirmation and acceptance.
  useEffect(() => { setDev(null); setTyped(""); setAccepted(false); setStatus(""); setPersist(false); }, [iso?.path, iso?.sha256]);
  // Persistence is a separate, optional step; its layout is computed for the selected drive and image.
  useEffect(() => {
    setPlan(null); setPlanErr("");
    if (!persist || !dev || !iso) return;
    let live = true;
    api.persistencePlan(iso.path, dev.size).then((p) => live && setPlan(p)).catch((e) => live && setPlanErr(String(e)));
    return () => { live = false; };
  }, [persist, dev?.path, dev?.size, iso?.path, iso?.sha256]);
  const unverifiedLocal = !!iso && !iso.verified && iso.local;
  if (!iso || (!iso.verified && !(unverifiedLocal))) return <section><h2>Flash {label}</h2><p>Download and verify an image first, or pick a local image.</p></section>;
  const confirmed = !!dev && (typed.trim() === dev.name || typed.trim() === dev.path) && (iso.verified || accepted);
  const flash = async () => {
    if (!dev) return; setBusy(true); setStatus("Writing… you'll be asked for your password.");
    try { await api.flash(iso.path, dev.path, typed, iso.sha256, dev.size, persist && !!plan); setStatus("Done! Written and read-back verified."); onDone(); }
    catch (e) { setStatus(`Failed: ${e}`); } finally { setBusy(false); }
  };
  return (
    <section>
      <h2>Flash {label}</h2>
      <p>Image: <code>{iso.path}</code> — SHA-256 <code>{iso.sha256}</code> — {iso.verified ? `verified${iso.trust_source ? ` against ${iso.trust_source}` : ""}` : "NOT independently verified"} · GPG: {iso.gpg}</p>
      {unverifiedLocal && (
        <label className="warn"><input type="checkbox" checked={accepted} onChange={(e) => setAccepted(e.target.checked)} /> This hash was computed here only. I built or obtained this image myself and accept writing exactly these bytes; the write fails if the file changes.</label>
      )}
      <div className="row"><h3>{media === "sd" ? "SD cards" : "Removable USB drives"}</h3><button onClick={refresh}>Refresh</button></div>
      {devices.length === 0 && <p>No {label}s found. Internal disks, eMMC and system disks are never shown.</p>}
      {devices.map((d) => (
        <label key={d.path} className={`device ${dev?.path === d.path ? "sel" : ""}`}>
          <input type="radio" name="dev" checked={dev?.path === d.path} onChange={() => { setDev(d); setTyped(""); }} />
          <strong>{d.path}</strong> {d.model || label} · {fmtBytes(d.size)} {d.mounted.length > 0 && <em>(mounted: {d.mounted.join(", ")})</em>}
        </label>
      ))}
      {dev && (
        <div className="danger">
          <p>⚠ ALL DATA on <strong>{dev.path}</strong> ({dev.model}, {fmtBytes(dev.size)}) will be destroyed. Type <code>{dev.name}</code> to confirm.</p>
          <label className="persist"><input type="checkbox" checked={persist} onChange={(e) => setPersist(e.target.checked)} /> Also add a persistent <code>VICTORDATA</code> partition for models and settings (VictorLinux images only)</label>
          {persist && plan && <p>Data partition: <strong>{fmtBytes(plan.data_bytes)}</strong> computed from this drive's real size ({fmtBytes(dev.size)}); 1 GiB is left unallocated.</p>}
          {persist && planErr && <p className="error">Persistence is not possible here: {planErr}. The image can still be written without it.</p>}
          <input value={typed} onChange={(e) => setTyped(e.target.value)} placeholder={dev.name} />
          <button className="primary danger-btn" disabled={!confirmed || busy || (persist && !plan)} onClick={flash}>Erase & flash</button>
        </div>
      )}
      {status && <p>{status}</p>}
    </section>
  );
}
