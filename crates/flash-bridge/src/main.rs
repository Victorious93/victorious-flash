//! `victorious-flash-bridge` — run by an SSH forced command (`command="victorious-flash-bridge serve"`).
//! `victorious-flash-bridge node-id` prints (creating on first use) this node's id.
use flash_bridge::{node_id_from_bytes, is_valid_node_id, Bridge, System};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};

fn state_dir() -> PathBuf {
    if let Ok(d) = std::env::var("VFLASH_BRIDGE_STATE_DIR") { return d.into() }
    if let Ok(d) = std::env::var("XDG_STATE_HOME") { return PathBuf::from(d).join("victorious-flash-bridge") }
    PathBuf::from(std::env::var("HOME").unwrap_or_else(|_| "/tmp".into())).join(".local/state/victorious-flash-bridge")
}

fn node_id() -> std::io::Result<String> {
    let dir = state_dir();
    let f = dir.join("node_id");
    if let Ok(s) = std::fs::read_to_string(&f) {
        let s = s.trim().to_string();
        if is_valid_node_id(&s) { return Ok(s) }
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, format!("{} is corrupt; refusing to re-generate", f.display())));
    }
    std::fs::create_dir_all(&dir)?;
    let mut b = [0u8; 16];
    std::fs::File::open("/dev/urandom")?.read_exact(&mut b)?;
    let id = node_id_from_bytes(&b);
    std::fs::write(&f, format!("{id}\n"))?;
    Ok(id)
}

struct Host;
impl System for Host {
    fn lsblk_json(&self) -> Result<String, String> {
        let o = Command::new("lsblk").args(flash_core::devices::LSBLK_ARGS).output().map_err(|e| e.to_string())?;
        if !o.status.success() { return Err(String::from_utf8_lossy(&o.stderr).trim().into()) }
        Ok(String::from_utf8_lossy(&o.stdout).into())
    }
    fn fetch_https(&self, url: &str) -> Result<Vec<u8>, String> {
        let o = Command::new("curl").args(["--proto", "=https", "--tlsv1.2", "-fsSL", "--max-filesize", "1048576", "--max-time", "60", "--", url])
            .stdin(Stdio::null()).output().map_err(|e| e.to_string())?;
        if !o.status.success() { return Err(String::from_utf8_lossy(&o.stderr).trim().chars().take(300).collect()) }
        Ok(o.stdout)
    }
    fn sha256_file(&self, path: &str) -> Result<String, String> {
        flash_core::checksum::sha256_file(std::path::Path::new(path)).map_err(|e| e.to_string())
    }
    fn regular_file_len(&self, path: &str) -> Result<u64, String> {
        let m = std::fs::metadata(path).map_err(|e| e.to_string())?;
        if !m.is_file() { return Err("image is not a regular file".into()) }
        Ok(m.len())
    }
    fn run_helper(&self, args: &[String], on_line: &mut dyn FnMut(&str)) -> Result<(), String> {
        let helper = std::env::var("VFLASH_HELPER").unwrap_or_else(|_| "/usr/lib/victorious-flash/victorious-flash-helper".into());
        let mut c = Command::new("pkexec").arg(helper).args(args).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped())
            .spawn().map_err(|e| format!("pkexec: {e}"))?;
        let mut err = c.stderr.take().unwrap();
        let et = std::thread::spawn(move || { let mut s = String::new(); let _ = err.read_to_string(&mut s); s });
        for l in BufReader::new(c.stdout.take().unwrap()).lines().map_while(Result::ok) { on_line(&l) }
        let st = c.wait().map_err(|e| e.to_string())?;
        let e = et.join().unwrap_or_default();
        if st.success() { Ok(()) } else { Err(e.trim().chars().take(500).collect()) }
    }
    fn load_catalog_override(&self) -> Option<String> { std::fs::read_to_string(state_dir().join("catalog.json")).ok() }
    fn store_catalog_override(&self, json: &str) -> Result<(), String> {
        let d = state_dir();
        std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
        let tmp = d.join("catalog.json.tmp");
        std::fs::write(&tmp, json).map_err(|e| e.to_string())?;
        std::fs::rename(&tmp, d.join("catalog.json")).map_err(|e| e.to_string())
    }
}

fn main() {
    let cmd = std::env::args().nth(1).unwrap_or_else(|| "serve".into());
    let id = match node_id() { Ok(i) => i, Err(e) => { eprintln!("victorious-flash-bridge: {e}"); std::process::exit(2) } };
    match cmd.as_str() {
        "node-id" => println!("{id}"),
        "serve" => {
            let bridge = Bridge::new(id, Host);
            let stdout = std::io::stdout();
            for line in std::io::stdin().lock().lines().map_while(Result::ok) {
                if line.trim().is_empty() { continue }
                bridge.handle_line(&line, &mut |f| {
                    let mut o = stdout.lock();
                    let _ = writeln!(o, "{}", serde_json::to_string(f).unwrap());
                    let _ = o.flush();
                });
            }
        }
        _ => { eprintln!("usage: victorious-flash-bridge [serve|node-id]"); std::process::exit(2) }
    }
}
