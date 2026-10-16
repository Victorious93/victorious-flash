import { useEffect, useState } from "react";
import { api, DIRECT_IDS, DownloadResult, Distro, Mode, Progress } from "./api";
import DistroPicker from "./screens/DistroPicker";
import Flasher from "./screens/Flasher";
import DirectInstall from "./screens/DirectInstall";
import Guide from "./screens/Guide";

const MODES: { id: Mode; title: string; body: string }[] = [
  { id: "direct", title: "Direct install", body: "Boot the ISO straight from this computer's disk via GRUB. No stick needed (Ubuntu, Mint)." },
  { id: "usb", title: "USB", body: "Write a bootable USB stick, with read-back verification." },
  { id: "sd", title: "SD card", body: "Write an SD card (Raspberry Pi, handhelds, RootForge OS images)." },
];
type Tab = "mode" | "distro" | "target" | "guide";
export default function App() {
  const [tab, setTab] = useState<Tab>("mode");
  const [mode, setMode] = useState<Mode>("usb");
  const [distro, setDistro] = useState<Distro | null>(null);
  const [iso, setIso] = useState<DownloadResult | null>(null);
  const [progress, setProgress] = useState<Progress | null>(null);
  useEffect(() => { const u = api.onProgress(setProgress); return () => { u.then((f) => f()); }; }, []);
  const targetLabel = { direct: "Install directly", usb: "Flash USB", sd: "Flash SD" }[mode];
  return (
    <div className="app">
      <header><img className="logo" src="/logo.png" alt="" /><h1>Victorious <em>Flash</em></h1></header>
      <nav>
        {(["mode", "distro", "target", "guide"] as Tab[]).map((t, i) => (
          <button key={t} className={tab === t ? "active" : ""} onClick={() => setTab(t)}>{i + 1}. {{ mode: "Choose mode", distro: "Choose OS", target: targetLabel, guide: "Install guide" }[t]}</button>
        ))}
      </nav>
      {progress && progress.total > 0 && progress.done < progress.total && (
        <div className="progress"><div style={{ width: `${(100 * progress.done) / progress.total}%` }} /><span>{progress.phase} {Math.floor((100 * progress.done) / progress.total)}%</span></div>
      )}
      <main>
        {tab === "mode" && (
          <section>
            <h2>How do you want to install?</h2>
            <div className="grid">
              {MODES.map((m) => (
                <button key={m.id} className={`card ${mode === m.id ? "sel" : ""}`} onClick={() => { setMode(m.id); setTab("distro"); }}>
                  <strong>{m.title}</strong><p>{m.body}</p>
                </button>
              ))}
            </div>
          </section>
        )}
        {tab === "distro" && <DistroPicker selected={distro} filter={(d) => mode !== "direct" || DIRECT_IDS.includes(d.id)} onSelect={setDistro} onDownloaded={(r) => { setIso(r); setTab("target"); }} />}
        {tab === "target" && mode === "direct" && <DirectInstall distro={distro} iso={iso} onDone={() => setTab("guide")} />}
        {tab === "target" && mode !== "direct" && <Flasher iso={iso} media={mode} onDone={() => setTab("guide")} />}
        {tab === "guide" && <Guide />}
      </main>
    </div>
  );
}
