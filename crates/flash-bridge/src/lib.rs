//! Headless DroidCommand AI fleet bridge for Victorious Flash.
//!
//! Wire contract (protocol major 1, the same contract as the RootForge bridge): one JSON object
//! per line on stdin/stdout.
//!
//! Request:  `{protocol_major, request_id, target_node_id, operation, timeout_ms, parameters}`
//! Response: `{protocol_major, request_id, node_id, ok, result, error{category,message}}`
//!
//! `flash.image.write` and `flash.readback.verify` additionally stream *progress frames* before the
//! final frame: `{protocol_major, request_id, node_id, ok:true, progress{phase,done,total}}`. A frame
//! carrying `progress` is never final; the first frame without it is the answer.
//!
//! Safety: the bridge never opens a block device itself. Every raw read/write goes through the
//! pkexec helper (`victorious-flash-helper`), which re-validates the target against lsblk and
//! reads back the SHA-256. The bridge validates first as well (defence in depth) and requires a
//! typed `confirm` parameter equal to the device's kernel name or path.
use flash_core::{catalog, checksum, devices};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

pub const BRIDGE_PROTOCOL_MAJOR: i64 = 1;
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

pub const OPERATIONS: &[&str] = &[
    "flash.capabilities.get", "flash.catalog.list", "flash.catalog.update", "flash.devices.list",
    "flash.verify", "flash.readback.verify", "flash.image.write",
];

#[derive(Debug, Clone, Deserialize, Serialize, PartialEq)]
pub struct BridgeRequest {
    pub protocol_major: i64,
    pub request_id: String,
    pub target_node_id: String,
    pub operation: String,
    #[serde(default = "default_timeout")]
    pub timeout_ms: u64,
    #[serde(default)]
    pub parameters: Map<String, Value>,
}
fn default_timeout() -> u64 { 10_000 }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct BridgeError { pub category: String, pub message: String }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct Progress { pub phase: String, pub done: u64, pub total: u64 }

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct BridgeResponse {
    pub protocol_major: i64,
    pub request_id: Option<String>,
    pub node_id: String,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<BridgeError>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub progress: Option<Progress>,
}

/// Error categories (stable strings; clients branch on them).
pub mod category {
    pub const MALFORMED_REQUEST: &str = "malformed_request";
    pub const PROTOCOL_MISMATCH: &str = "protocol_mismatch";
    pub const NODE_MISMATCH: &str = "node_mismatch";
    pub const UNSUPPORTED_OPERATION: &str = "unsupported_operation";
    pub const INVALID_PARAMETERS: &str = "invalid_parameters";
    pub const SAFETY_REFUSED: &str = "safety_refused";
    pub const VERIFY_FAILED: &str = "verify_failed";
    pub const INTERNAL: &str = "internal";
}

/// Everything that touches the host. Real impl in `main.rs`; tests use a fake.
pub trait System {
    fn lsblk_json(&self) -> Result<String, String>;
    fn fetch_https(&self, url: &str) -> Result<Vec<u8>, String>;
    fn sha256_file(&self, path: &str) -> Result<String, String>;
    /// Size of a regular file; Err if missing or not a regular file.
    fn regular_file_len(&self, path: &str) -> Result<u64, String>;
    /// Runs `pkexec <helper> <args...>`, feeding each stdout line to `on_line`. Err(stderr) on non-zero exit.
    fn run_helper(&self, args: &[String], on_line: &mut dyn FnMut(&str)) -> Result<(), String>;
    fn load_catalog_override(&self) -> Option<String>;
    fn store_catalog_override(&self, json: &str) -> Result<(), String>;
}

pub fn is_valid_node_id(s: &str) -> bool {
    s.len() == 35 && s.starts_with("vf-") && s[3..].chars().all(|c| matches!(c, '0'..='9' | 'a'..='f'))
}

pub fn node_id_from_bytes(b: &[u8; 16]) -> String { format!("vf-{}", hex::encode(b)) }

fn is_hex64(s: &str) -> bool { s.len() == 64 && s.chars().all(|c| c.is_ascii_hexdigit()) }

type Fail = (&'static str, String);
fn invalid(m: impl Into<String>) -> Fail { (category::INVALID_PARAMETERS, m.into()) }
fn refused(m: impl Into<String>) -> Fail { (category::SAFETY_REFUSED, m.into()) }

pub struct Bridge<S: System> { pub node_id: String, pub sys: S }

impl<S: System> Bridge<S> {
    pub fn new(node_id: String, sys: S) -> Self {
        assert!(is_valid_node_id(&node_id), "node id must be vf-<32 hex>");
        Bridge { node_id, sys }
    }

    fn frame(&self, rid: Option<String>, r: Result<Value, Fail>) -> BridgeResponse {
        match r {
            Ok(v) => BridgeResponse { protocol_major: BRIDGE_PROTOCOL_MAJOR, request_id: rid, node_id: self.node_id.clone(), ok: true, result: Some(v), error: None, progress: None },
            Err((c, m)) => BridgeResponse { protocol_major: BRIDGE_PROTOCOL_MAJOR, request_id: rid, node_id: self.node_id.clone(), ok: false, result: None,
                error: Some(BridgeError { category: c.into(), message: m }), progress: None },
        }
    }

    /// Handle one request line; `emit` receives zero or more progress frames and exactly one final frame.
    pub fn handle_line(&self, line: &str, emit: &mut dyn FnMut(&BridgeResponse)) {
        if line.len() > 65_536 {
            return emit(&self.frame(None, Err((category::MALFORMED_REQUEST, "request frame too large".into()))));
        }
        let raw: Value = match serde_json::from_str(line) {
            Ok(v) => v,
            Err(_) => return emit(&self.frame(None, Err((category::MALFORMED_REQUEST, "request is not JSON".into())))),
        };
        let rid = raw.get("request_id").and_then(|v| v.as_str()).map(String::from);
        // Check the major before the shape, so a v2 client gets a clear answer.
        if let Some(m) = raw.get("protocol_major").and_then(|v| v.as_i64()) {
            if m != BRIDGE_PROTOCOL_MAJOR {
                return emit(&self.frame(rid, Err((category::PROTOCOL_MISMATCH, format!("node speaks protocol {BRIDGE_PROTOCOL_MAJOR}, request is {m}")))));
            }
        }
        let req: BridgeRequest = match serde_json::from_value(raw) {
            Ok(r) => r,
            Err(e) => return emit(&self.frame(rid, Err((category::MALFORMED_REQUEST, format!("bad request frame: {e}"))))),
        };
        if req.request_id.is_empty() || req.request_id.len() > 128 {
            return emit(&self.frame(None, Err((category::MALFORMED_REQUEST, "request_id must be 1..128 chars".into()))));
        }
        if req.target_node_id != self.node_id {
            return emit(&self.frame(Some(req.request_id), Err((category::NODE_MISMATCH, "request is addressed to a different node".into()))));
        }
        let rid = req.request_id.clone();
        let result = {
            // Progress frames are written as they happen (streamed), not buffered.
            let mut sink = |p: Progress| emit(&emit_progress(&self.node_id, &rid, p));
            self.dispatch(&req, &mut sink)
        };
        emit(&self.frame(Some(req.request_id), result));
    }

    fn dispatch(&self, req: &BridgeRequest, progress: &mut dyn FnMut(Progress)) -> Result<Value, Fail> {
        let p = &req.parameters;
        match req.operation.as_str() {
            "flash.capabilities.get" => Ok(json!({
                "product": "victorious-flash", "version": VERSION, "protocol_major": BRIDGE_PROTOCOL_MAJOR,
                "node_id": self.node_id, "operations": OPERATIONS,
                "write_requires_confirmation": true, "privileged_writes_via": "pkexec victorious-flash-helper",
            })),
            "flash.catalog.list" => {
                let (source, entries) = self.catalog()?;
                Ok(json!({ "source": source, "entries": entries }))
            }
            "flash.catalog.update" => self.catalog_update(p),
            "flash.devices.list" => Ok(json!({ "devices": self.devices()? })),
            "flash.verify" => {
                let path = abs_path(p, "image")?;
                let want = sha_param(p, "sha256")?;
                let got = self.sys.sha256_file(&path).map_err(invalid)?;
                Ok(json!({ "image": path, "sha256": got, "matches": got.eq_ignore_ascii_case(&want) }))
            }
            "flash.readback.verify" => {
                let dev = self.eligible(str_param(p, "device")?)?;
                let want = sha_param(p, "sha256")?;
                let len = p.get("length").and_then(|v| v.as_u64()).filter(|n| *n > 0).ok_or_else(|| invalid("length must be a positive integer"))?;
                if len > dev.size { return Err(refused("length exceeds device size")) }
                let got = self.helper(&["verify".into(), dev.path.clone(), want.clone(), len.to_string()], progress)?;
                Ok(json!({ "device": dev.path, "sha256": got, "verified": true }))
            }
            "flash.image.write" => self.write(p, progress),
            other => Err((category::UNSUPPORTED_OPERATION, format!("unsupported operation '{other}'"))),
        }
    }

    fn catalog(&self) -> Result<(&'static str, Vec<catalog::Distro>), Fail> {
        if let Some(j) = self.sys.load_catalog_override() {
            if let Ok(c) = catalog::parse_catalog(&j) { return Ok(("updated", c)) }
        }
        catalog::parse_catalog(catalog::CATALOG_JSON).map(|c| ("embedded", c)).map_err(|e| (category::INTERNAL, e.to_string()))
    }

    fn catalog_update(&self, p: &Map<String, Value>) -> Result<Value, Fail> {
        let url = str_param(p, "url")?;
        if !url.starts_with("https://") || url.chars().any(|c| c.is_whitespace() || c.is_control()) { return Err(refused("catalog url must be https")) }
        let want = sha_param(p, "sha256")?;
        let body = self.sys.fetch_https(url).map_err(|e| (category::INTERNAL, format!("fetch failed: {e}")))?;
        if body.len() > 1 << 20 { return Err(refused("catalog larger than 1 MiB")) }
        let got = checksum::sha256_reader(&body[..], |_| {}).map_err(|e| (category::INTERNAL, e.to_string()))?;
        if got != want { return Err((category::VERIFY_FAILED, format!("catalog sha256 mismatch: expected {want}, got {got}"))) }
        let text = String::from_utf8(body).map_err(|_| invalid("catalog is not UTF-8"))?;
        let c = catalog::parse_catalog(&text).map_err(|e| invalid(e.to_string()))?;
        self.sys.store_catalog_override(&text).map_err(|e| (category::INTERNAL, e))?;
        Ok(json!({ "entries": c.len(), "sha256": got }))
    }

    fn devices(&self) -> Result<Vec<devices::UsbDevice>, Fail> {
        let j = self.sys.lsblk_json().map_err(|e| (category::INTERNAL, format!("lsblk: {e}")))?;
        devices::parse_and_filter(&j).map_err(|e| (category::INTERNAL, format!("lsblk parse: {e}")))
    }

    fn eligible(&self, dev: &str) -> Result<devices::UsbDevice, Fail> {
        if !devices::is_safe_device_path(dev) { return Err(refused("device must be a plain /dev/<name> path")) }
        self.devices()?.into_iter().find(|d| d.path == dev).ok_or_else(|| refused("device is not an eligible removable disk"))
    }

    fn write(&self, p: &Map<String, Value>, progress: &mut dyn FnMut(Progress)) -> Result<Value, Fail> {
        // Parameter shape first, then safety — every refusal happens before anything privileged runs.
        let image = abs_path(p, "image")?;
        let device = str_param(p, "device")?;
        let want = sha_param(p, "sha256")?;
        let confirm = p.get("confirm").and_then(|v| v.as_str()).ok_or_else(|| refused("missing typed confirmation 'confirm' (the device name, e.g. sdb)"))?;
        if let Some(id) = p.get("catalog_id").and_then(|v| v.as_str()) {
            let (_, c) = self.catalog()?;
            let e = c.iter().find(|d| d.id == id).ok_or_else(|| invalid(format!("unknown catalog_id '{id}'")))?;
            if !e.is_usb_writable() { return Err(refused(format!("catalog entry '{id}' is not a raw USB image (flash_method/image_format)"))) }
        }
        let dev = self.eligible(device)?;
        if !devices::confirmation_matches(&dev, confirm) { return Err(refused("confirmation does not match the device name")) }
        let len = self.sys.regular_file_len(&image).map_err(invalid)?;
        if len == 0 { return Err(refused("image is empty")) }
        if len > dev.size { return Err(refused("image larger than device")) }
        progress(Progress { phase: "hash".into(), done: 0, total: len });
        let got = self.sys.sha256_file(&image).map_err(invalid)?;
        if !got.eq_ignore_ascii_case(&want) { return Err((category::VERIFY_FAILED, format!("image sha256 mismatch: expected {want}, got {got}"))) }
        progress(Progress { phase: "hash".into(), done: len, total: len });
        let readback = self.helper(&["write".into(), image.clone(), dev.path.clone(), want.clone()], progress)?;
        Ok(json!({ "device": dev.path, "image": image, "bytes": len, "sha256": readback, "verified": true }))
    }

    /// Run the pkexec helper, translating `PROGRESS` lines; returns the `DONE` hash.
    fn helper(&self, args: &[String], progress: &mut dyn FnMut(Progress)) -> Result<String, Fail> {
        let mut done = None;
        let r = self.sys.run_helper(args, &mut |l: &str| {
            let t: Vec<&str> = l.split_whitespace().collect();
            match t.as_slice() {
                ["PROGRESS", ph, d, tot] => progress(Progress { phase: ph.to_string(), done: d.parse().unwrap_or(0), total: tot.parse().unwrap_or(0) }),
                ["DONE", h] => done = Some(h.to_string()),
                _ => {}
            }
        });
        match r {
            Err(e) if e.contains("mismatch") => Err((category::VERIFY_FAILED, e)),
            Err(e) => Err((category::INTERNAL, format!("helper failed: {e}"))),
            Ok(()) => done.ok_or_else(|| (category::INTERNAL, "helper finished without DONE".into())),
        }
    }
}

fn emit_progress(node: &str, rid: &str, p: Progress) -> BridgeResponse {
    BridgeResponse { protocol_major: BRIDGE_PROTOCOL_MAJOR, request_id: Some(rid.into()), node_id: node.into(), ok: true, result: None, error: None, progress: Some(p) }
}

fn str_param<'a>(p: &'a Map<String, Value>, k: &str) -> Result<&'a str, Fail> {
    p.get(k).and_then(|v| v.as_str()).filter(|s| !s.is_empty()).ok_or_else(|| invalid(format!("missing string parameter '{k}'")))
}
fn sha_param(p: &Map<String, Value>, k: &str) -> Result<String, Fail> {
    let s = str_param(p, k)?;
    if is_hex64(s) { Ok(s.to_lowercase()) } else { Err(invalid(format!("'{k}' must be 64 hex chars"))) }
}
fn abs_path(p: &Map<String, Value>, k: &str) -> Result<String, Fail> {
    let s = str_param(p, k)?;
    if !s.starts_with('/') || s.contains('\0') || s.split('/').any(|c| c == "..") || s.starts_with("/dev/") || s.starts_with("/proc/") || s.starts_with("/sys/") {
        return Err(refused(format!("'{k}' must be an absolute path to a regular image file")))
    }
    Ok(s.to_string())
}

#[cfg(test)]
mod tests;
