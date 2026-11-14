import { useEffect, useRef, useState } from "react";
import { api, Distro, DownloadResult, fmtBytes, LocalImage } from "../api";
export default function DistroPicker({ selected, filter = () => true, onSelect, onDownloaded, onLocal }: { selected: Distro | null; filter?: (d: Distro) => boolean; onSelect: (d: Distro) => void; onDownloaded: (r: DownloadResult, id: string) => void; onLocal?: (r: DownloadResult) => void }) {
  const [distros, setDistros] = useState<Distro[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [localPath, setLocalPath] = useState("");
  const [localSha, setLocalSha] = useState("");
  const [localBusy, setLocalBusy] = useState(false);
  const [localInfo, setLocalInfo] = useState<LocalImage | null>(null);
  const reqId = useRef(0);
  useEffect(() => { api.listDistros().then(setDistros).catch((e) => setError(String(e))); }, []);
  const download = async () => {
    if (!selected) return; setBusy(true); setError("");
    const requestedId = selected.id;
    try { const r = await api.download(requestedId); if (!r.verified) throw new Error("Checksum verification FAILED"); onDownloaded(r, requestedId); }
    catch (e) { setError(String(e)); } finally { setBusy(false); }
  };
  const inspectLocal = async () => {
    const mine = ++reqId.current; setLocalBusy(true); setError(""); setLocalInfo(null);
    try {
      const r = await api.inspectLocal(localPath.trim(), localSha.trim());
      if (mine !== reqId.current) return;           // a newer request replaced this one
      setLocalInfo(r);
      onLocal?.({ path: r.path, sha256: r.sha256, verified: r.verified, gpg: "n/a (local file)", local: true, trust: r.trust, trust_source: r.trust_source, name: r.name, write_size: r.write_size });
    } catch (e) { if (mine === reqId.current) setError(String(e)); } finally { if (mine === reqId.current) setLocalBusy(false); }
  };
  return (
    <section>
      <h2>Choose your OS</h2>
      <div className="grid">
        {distros.filter(filter).map((d) => (
          <button key={d.id} className={`card ${selected?.id === d.id ? "sel" : ""}`} disabled={busy} onClick={() => onSelect(d)}>
            <strong>{d.name}</strong><small>{d.version}</small><p>{d.description}</p>
          </button>
        ))}
      </div>
      <button className="primary" disabled={!selected || busy} onClick={download}>{busy ? "Downloading & verifying…" : "Download & verify SHA-256"}</button>
      {onLocal && (
        <div className="card local">
          <h3>Or use an image already on this computer</h3>
          <p>For example a VictorLinux ISO you built. Enter its published SHA-256 to verify it. Without one, the hash is only computed here: the app can still catch later changes, but cannot tell where the file came from.</p>
          <label>Absolute path <input aria-label="Local image path" value={localPath} onChange={(e) => { setLocalPath(e.target.value); setLocalInfo(null); }} placeholder="/home/you/victorlinux-2026.11.14.iso" /></label>
          <label>Expected SHA-256 (optional) <input aria-label="Expected SHA-256" value={localSha} onChange={(e) => { setLocalSha(e.target.value); setLocalInfo(null); }} placeholder="64 hex characters" /></label>
          <button className="primary" disabled={!localPath.trim() || localBusy} onClick={inspectLocal}>{localBusy ? "Hashing…" : "Hash & use this image"}</button>
          {localInfo && <p className={localInfo.verified ? "ok" : "warn"}>{localInfo.name} · {fmtBytes(localInfo.write_size)} · SHA-256 {localInfo.sha256.slice(0, 16)}… · {localInfo.verified ? `verified against ${localInfo.trust_source}` : "NOT independently verified"}</p>}
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  );
}
