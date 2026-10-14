use flash_core::{catalog, checksum, devices};
use futures_util::StreamExt;
use serde::Serialize;
use tauri::{AppHandle, Emitter};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

#[derive(Clone, Serialize)]
struct Progress { phase: String, done: u64, total: u64 }

fn err<E: std::fmt::Display>(e: E) -> String { e.to_string() }

#[tauri::command]
fn list_distros() -> Result<Vec<catalog::Distro>, String> { catalog::load_catalog().map_err(err) }

#[tauri::command]
async fn list_devices() -> Result<Vec<devices::UsbDevice>, String> {
    let out = tokio::process::Command::new("lsblk").args(devices::LSBLK_ARGS).output().await.map_err(err)?;
    devices::parse_and_filter(&String::from_utf8_lossy(&out.stdout)).map_err(err)
}

#[derive(Serialize)]
struct DownloadResult { path: String, sha256: String, verified: bool, gpg: String }

#[tauri::command]
async fn download_iso(app: AppHandle, id: String) -> Result<DownloadResult, String> {
    let d = catalog::load_catalog().map_err(err)?.into_iter().find(|d| d.id == id).ok_or("unknown distro")?;
    let dir = dirs::download_dir().unwrap_or(std::env::temp_dir()).join("victorious-flash");
    tokio::fs::create_dir_all(&dir).await.map_err(err)?;
    let path = dir.join(&d.iso_filename);

    let client = reqwest::Client::builder().user_agent("victorious-flash/0.1").build().map_err(err)?;
    let resp = client.get(&d.iso_url).send().await.map_err(err)?.error_for_status().map_err(err)?;
    let total = resp.content_length().unwrap_or(0);
    let mut file = tokio::fs::File::create(&path).await.map_err(err)?;
    let mut stream = resp.bytes_stream();
    let mut done = 0u64; let mut last = 0u64;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(err)?;
        file.write_all(&chunk).await.map_err(err)?;
        done += chunk.len() as u64;
        if done - last > 4 << 20 || done == total { last = done;
            let _ = app.emit("progress", Progress { phase: "download".into(), done, total }); }
    }
    file.flush().await.map_err(err)?;

    let p2 = path.clone(); let app2 = app.clone();
    let actual = tokio::task::spawn_blocking(move || {
        checksum::sha256_reader(std::fs::File::open(&p2)?, |n| { if n % (64 << 20) == 0 {
            let _ = app2.emit("progress", Progress { phase: "hash".into(), done: n, total }); } })
    }).await.map_err(err)?.map_err(err)?;

    let verified = match &d.sha256 {
        Some(pinned) => pinned.eq_ignore_ascii_case(&actual),
        None => {
            let sums = client.get(&d.checksum_url).send().await.map_err(err)?.error_for_status().map_err(err)?.text().await.map_err(err)?;
            checksum::verify(&actual, &sums, &d.iso_filename).map_err(err)?; true
        }
    };
    // GPG verification of the checksum file is not implemented yet (MVP stub).
    let gpg = if d.signature_url.is_some() { "not-checked (stub)" } else { "unavailable" }.to_string();
    Ok(DownloadResult { path: path.to_string_lossy().into(), sha256: actual, verified, gpg })
}

#[tauri::command]
async fn flash(app: AppHandle, iso: String, device: String, typed_confirmation: String, sha256: String) -> Result<String, String> {
    let devs = list_devices().await?;
    let target = devs.iter().find(|d| d.path == device).ok_or("device is not an eligible removable disk")?;
    if !devices::confirmation_matches(target, &typed_confirmation) { return Err("confirmation text does not match device name".into()) }
    let helper = std::env::var("VFLASH_HELPER").unwrap_or_else(|_| "/usr/lib/victorious-flash/victorious-flash-helper".into());
    let mut child = tokio::process::Command::new("pkexec").arg(helper).args(["write", &iso, &device, &sha256])
        .stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped()).spawn().map_err(err)?;
    let mut lines = BufReader::new(child.stdout.take().unwrap()).lines();
    let mut result = String::new();
    while let Ok(Some(l)) = lines.next_line().await {
        let p: Vec<&str> = l.split_whitespace().collect();
        if p.len() == 4 && p[0] == "PROGRESS" {
            let _ = app.emit("progress", Progress { phase: p[1].into(), done: p[2].parse().unwrap_or(0), total: p[3].parse().unwrap_or(0) });
        } else if p.first() == Some(&"DONE") { result = l.clone(); }
    }
    let out = child.wait_with_output().await.map_err(err)?;
    if !out.status.success() { return Err(String::from_utf8_lossy(&out.stderr).trim().to_string()) }
    Ok(result)
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![list_distros, list_devices, download_iso, flash])
        .run(tauri::generate_context!())
        .expect("error while running Victorious Flash");
}
