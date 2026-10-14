import { useEffect, useState } from "react";
import { api, DownloadResult, Distro, Progress } from "./api";
import DistroPicker from "./screens/DistroPicker";
import Flasher from "./screens/Flasher";
import Guide from "./screens/Guide";

type Tab = "distro" | "flash" | "guide";
export default function App() {
  const [tab, setTab] = useState<Tab>("distro");
  const [distro, setDistro] = useState<Distro | null>(null);
  const [iso, setIso] = useState<DownloadResult | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  useEffect(() => { const u = api.onProgress(setProgress); return () => { u.then((f) => f()); }; }, []);
  return (
    <div className="app">
      <header><span className="logo">V</span><h1>Victorious <em>Flash</em></h1></header>
      <nav>
        {(["distro","flash","guide"] as Tab[]).map((t, i) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>{i + 1}. {{ distro: "Choose distro", flash: "Flash USB", guide: "Install guide" }[t]}</button>
        ))}
      </nav>
      {progress && progress.total > 0 && progress.done < progress.total && (
        <div className="progress"><div style={{ width: `${(100 * progress.done) / progress.total}%` }} /><span>{progress.phase} {Math.floor((100 * progress.done) / progress.total)}%</span></div>
      )}
      <main>
        {tab === "distro" && <DistroPicker selected={distro} onSelect={setDistro} onDownloaded={(r) => { setIso(r); setTab("flash"); }} />}
        {tab === "flash" && <Flasher iso={iso} onDone={() => setTab("guide")} />}
        {tab === "guide" && <Guide />}
      </main>
    </div>
  );
}
