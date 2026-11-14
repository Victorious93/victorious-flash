import { useEffect, useRef, useState } from "react";
import { api, DIRECT_IDS, DownloadResult, Distro, Mode, Progress } from "./api";
import DistroPicker from "./screens/DistroPicker";
import Flasher from "./screens/Flasher";
import DirectInstall from "./screens/DirectInstall";
import Guide from "./screens/Guide";

const MODES: { id: Mode; title: string; body: string }[] = [
  { id: "direct", title: "Boot from this PC", body: "Prepare a GRUB entry for an ISO already on this computer. Ubuntu/Mint only; this does not install the OS for you." },
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
  const selectedId = useRef<string | null>(null);
  const chooseDistro = (next: Distro) => {
    if (selectedId.current !== next.id) { setIso(null); setProgress(null); }
    selectedId.current = next.id; setDistro(next);
  };
  const chooseMode = (next: Mode) => {
    if (next !== mode) { selectedId.current = null; setDistro(null); setIso(null); setProgress(null); }
    setMode(next); setTab("distro");
  };
  useEffect(() => { const u = api.onProgress(setProgress).catch(() => () => {}); return () => { u.then((f) => f()); }; }, []);
  const targetLabel = { direct: "Prepare boot entry", usb: "Flash USB", sd: "Flash SD" }[mode];
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
                <button key={m.id} className={`card ${mode === m.id ? "sel" : ""}`} onClick={() => chooseMode(m.id)}>
                  <strong>{m.title}</strong><p>{m.body}</p>
                </button>
              ))}
            </div>
          </section>
        )}
        {tab === "distro" && <DistroPicker onLocal={mode === "direct" ? undefined : (r) => { selectedId.current = `local:${r.path}`; setDistro(null); setProgress(null); setIso(r); setTab("target"); }} selected={distro} filter={(d) => mode !== "direct" || DIRECT_IDS.includes(d.id)} onSelect={chooseDistro} onDownloaded={(r, id) => { if (selectedId.current === id) { setIso(r); setTab("target"); } }} />}
        {tab === "target" && mode === "direct" && <DirectInstall distro={distro} iso={iso} onDone={() => setTab("guide")} />}
        {tab === "target" && mode !== "direct" && <Flasher iso={iso} media={mode} onDone={() => setTab("guide")} />}
        {tab === "guide" && <Guide />}
      </main>
    </div>
  );
}
