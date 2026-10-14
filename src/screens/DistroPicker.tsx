import { useEffect, useState } from "react";
import { api, Distro, DownloadResult } from "../api";
export default function DistroPicker({ selected, onSelect, onDownloaded }: { selected: Distro | null; onSelect: (d: Distro) => void; onDownloaded: (r: DownloadResult) => void }) {
  const [distros, setDistros] = useState<Distro[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { api.listDistros().then(setDistros).catch((e) => setError(String(e))); }, []);
  const download = async () => {
    if (!selected) return; setBusy(true); setError("");
    try { const r = await api.download(selected.id); if (!r.verified) throw new Error("Checksum verification FAILED"); onDownloaded(r); }
    catch (e) { setError(String(e)); } finally { setBusy(false); }
  };
  return (
    <section>
      <h2>Choose your Linux</h2>
      <div className="grid">
        {distros.map((d) => (
          <button key={d.id} className={`card ${selected?.id === d.id ? "sel" : ""}`} onClick={() => onSelect(d)}>
            <strong>{d.name}</strong><small>{d.version}</small><p>{d.description}</p>
          </button>
        ))}
      </div>
      <button className="primary" disabled={!selected || busy} onClick={download}>{busy ? "Downloading & verifying…" : "Download & verify SHA-256"}</button>
      {error && <p className="error">{error}</p>}
    </section>
  );
}
