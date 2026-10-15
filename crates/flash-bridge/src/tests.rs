use super::*;
use std::cell::RefCell;

const NODE: &str = "vf-0123456789abcdef0123456789abcdef";
const ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
const LSBLK: &str = r#"{"blockdevices":[
  {"name":"nvme0n1","path":"/dev/nvme0n1","type":"disk","rm":false,"hotplug":false,"ro":false,"size":512110190592,"tran":"nvme",
   "children":[{"name":"nvme0n1p2","type":"part","mountpoints":["/"]}]},
  {"name":"sdb","path":"/dev/sdb","type":"disk","rm":true,"hotplug":true,"ro":false,"size":31406948352,"model":"Ultra","tran":"usb"}]}"#;

#[derive(Default)]
struct Fake { helper_calls: RefCell<Vec<Vec<String>>>, helper_lines: Vec<&'static str>, helper_err: Option<&'static str>, write_size: Option<u64>,
    fetched: Vec<u8>, stored: RefCell<Option<String>> }
impl System for Fake {
    fn lsblk_json(&self) -> Result<String, String> { Ok(LSBLK.into()) }
    fn fetch_https(&self, _: &str) -> Result<Vec<u8>, String> { Ok(self.fetched.clone()) }
    fn sha256_file(&self, _: &str) -> Result<String, String> { Ok(ABC.into()) }
    fn image_write_size(&self, _: &str) -> Result<u64, String> { Ok(self.write_size.unwrap_or(3)) }
    fn run_helper(&self, a: &[String], on: &mut dyn FnMut(&str)) -> Result<(), String> {
        self.helper_calls.borrow_mut().push(a.to_vec());
        for l in &self.helper_lines { on(l) }
        match self.helper_err { Some(e) => Err(e.into()), None => Ok(()) }
    }
    fn load_catalog_override(&self) -> Option<String> { self.stored.borrow().clone() }
    fn store_catalog_override(&self, j: &str) -> Result<(), String> { *self.stored.borrow_mut() = Some(j.into()); Ok(()) }
}

fn bridge(f: Fake) -> Bridge<Fake> { Bridge::new(NODE.into(), f) }
fn req(op: &str, params: Value) -> String {
    json!({"protocol_major":1,"request_id":"r-1","target_node_id":NODE,"operation":op,"timeout_ms":1000,"parameters":params}).to_string()
}
fn run(b: &Bridge<Fake>, line: &str) -> Vec<BridgeResponse> { let mut v = vec![]; b.handle_line(line, &mut |f| v.push(f.clone())); v }
fn last(b: &Bridge<Fake>, line: &str) -> BridgeResponse { run(b, line).pop().unwrap() }
fn cat(r: &BridgeResponse) -> &str { &r.error.as_ref().expect("expected error").category }

fn write_params() -> Value { json!({"image":"/home/don/img.iso","device":"/dev/sdb","sha256":ABC,"confirm":"sdb"}) }

#[test] fn node_id_format() {
    assert!(is_valid_node_id(NODE));
    assert!(!is_valid_node_id("rf-0123456789abcdef0123456789abcdef"));
    assert!(!is_valid_node_id("vf-0123456789ABCDEF0123456789abcdef"));
    assert!(is_valid_node_id(&node_id_from_bytes(&[0xab; 16])));
}

#[test] fn request_round_trips_and_defaults() {
    let r: BridgeRequest = serde_json::from_str(r#"{"protocol_major":1,"request_id":"x","target_node_id":"n","operation":"o"}"#).unwrap();
    assert_eq!(r.timeout_ms, 10_000);
    assert!(r.parameters.is_empty());
    let back: BridgeRequest = serde_json::from_str(&serde_json::to_string(&r).unwrap()).unwrap();
    assert_eq!(back, r);
}

#[test] fn malformed_and_protocol_and_node_checks() {
    let b = bridge(Fake::default());
    let r = last(&b, "not json"); assert!(!r.ok); assert_eq!(cat(&r), "malformed_request"); assert_eq!(r.node_id, NODE);
    let r = last(&b, r#"{"protocol_major":1,"request_id":"q"}"#); assert_eq!(cat(&r), "malformed_request"); assert_eq!(r.request_id.as_deref(), Some("q"));
    let r = last(&b, &req("flash.capabilities.get", json!({})).replace("\"protocol_major\":1", "\"protocol_major\":2"));
    assert_eq!(cat(&r), "protocol_mismatch");
    let other = req("flash.capabilities.get", json!({})).replace(NODE, "vf-ffffffffffffffffffffffffffffffff");
    assert_eq!(cat(&last(&b, &other)), "node_mismatch");
    assert_eq!(cat(&last(&b, &req("flash.nope", json!({})))), "unsupported_operation");
    assert_eq!(cat(&last(&b, &"x".repeat(70_000))), "malformed_request");
}

#[test] fn capabilities_and_devices() {
    let b = bridge(Fake::default());
    let r = last(&b, &req("flash.capabilities.get", json!({})));
    assert!(r.ok); assert_eq!(r.request_id.as_deref(), Some("r-1"));
    let res = r.result.unwrap();
    assert_eq!(res["node_id"], NODE);
    assert_eq!(res["operations"].as_array().unwrap().len(), OPERATIONS.len());
    let d = last(&b, &req("flash.devices.list", json!({}))).result.unwrap();
    let names: Vec<_> = d["devices"].as_array().unwrap().iter().map(|x| x["name"].as_str().unwrap().to_string()).collect();
    assert_eq!(names, vec!["sdb"]);
    let c = last(&b, &req("flash.catalog.list", json!({}))).result.unwrap();
    assert_eq!(c["source"], "embedded");
    assert!(c["entries"].as_array().unwrap().iter().any(|e| e["id"] == "ubuntu"));
}

#[test] fn write_refuses_without_typed_confirmation() {
    for (k, v) in [("confirm", Value::Null), ("confirm", json!("sda")), ("confirm", json!(""))] {
        let b = bridge(Fake::default());
        let mut p = write_params(); if v.is_null() { p.as_object_mut().unwrap().remove(k); } else { p[k] = v; }
        let r = last(&b, &req("flash.image.write", p));
        assert_eq!(cat(&r), "safety_refused", "{r:?}");
        assert!(b.sys.helper_calls.borrow().is_empty(), "helper must not run on refusal");
    }
}

#[test] fn write_refuses_ineligible_targets_and_paths() {
    let cases = [("device", json!("/dev/nvme0n1")), ("device", json!("/dev/../sda")), ("device", json!("sdb")),
                 ("image", json!("relative.iso")), ("image", json!("/dev/sda")), ("image", json!("/home/../etc/shadow"))];
    for (k, v) in cases {
        let b = bridge(Fake::default());
        let mut p = write_params(); p[k] = v.clone();
        if k == "device" { p["confirm"] = json!("nvme0n1") }
        let r = last(&b, &req("flash.image.write", p));
        assert_eq!(cat(&r), "safety_refused", "{k}={v}");
        assert!(b.sys.helper_calls.borrow().is_empty());
    }
}

#[test] fn write_refuses_hash_mismatch_and_unknown_catalog_entries() {
    let b = bridge(Fake::default());
    let mut p = write_params(); p["sha256"] = json!("0".repeat(64));
    assert_eq!(cat(&last(&b, &req("flash.image.write", p))), "verify_failed");
    let mut p = write_params(); p["sha256"] = json!("xyz");
    assert_eq!(cat(&last(&b, &req("flash.image.write", p))), "invalid_parameters");
    assert!(b.sys.helper_calls.borrow().is_empty());

    let mut p = write_params(); p["catalog_id"] = json!("no-such-image");
    assert_eq!(cat(&last(&b, &req("flash.image.write", p))), "invalid_parameters");
    assert!(b.sys.helper_calls.borrow().is_empty());
}

#[test] fn write_streams_progress_and_goes_through_helper() {
    let b = bridge(Fake { helper_lines: vec!["PROGRESS write 1 3", "PROGRESS write 3 3", "PROGRESS verify 3 3", "DONE ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"], ..Default::default() });
    let frames = run(&b, &req("flash.image.write", write_params()));
    let (fin, prog) = frames.split_last().unwrap();
    assert!(prog.len() >= 3 && prog.iter().all(|f| f.progress.is_some() && f.result.is_none() && f.request_id.as_deref() == Some("r-1") && f.node_id == NODE));
    assert!(prog.iter().any(|f| f.progress.as_ref().unwrap().phase == "verify"));
    assert!(fin.ok && fin.progress.is_none());
    assert_eq!(fin.result.as_ref().unwrap()["verified"], true);
    assert_eq!(b.sys.helper_calls.borrow()[0], vec!["write", "/home/don/img.iso", "/dev/sdb", ABC]);
}

#[test] fn helper_mismatch_maps_to_verify_failed() {
    let b = bridge(Fake { helper_err: Some("ERROR verify mismatch: expected a got b"), ..Default::default() });
    assert_eq!(cat(&last(&b, &req("flash.image.write", write_params()))), "verify_failed");
    let r = last(&b, &req("flash.readback.verify", json!({"device":"/dev/sdb","sha256":ABC,"length":3})));
    assert_eq!(cat(&r), "verify_failed");
    assert_eq!(b.sys.helper_calls.borrow()[1][0], "verify");
}

#[test] fn verify_and_catalog_update() {
    let b = bridge(Fake::default());
    let r = last(&b, &req("flash.verify", json!({"image":"/x.iso","sha256":ABC}))).result.unwrap();
    assert_eq!(r["matches"], true);

    let doc = flash_core::catalog::CATALOG_JSON.as_bytes().to_vec();
    let h = flash_core::checksum::sha256_reader(&doc[..], |_| {}).unwrap();
    let b = bridge(Fake { fetched: doc, ..Default::default() });
    assert_eq!(cat(&last(&b, &req("flash.catalog.update", json!({"url":"http://e.test/c.json","sha256":h})))), "safety_refused");
    assert_eq!(cat(&last(&b, &req("flash.catalog.update", json!({"url":"https://e.test/c.json","sha256":ABC})))), "verify_failed");
    assert!(b.sys.stored.borrow().is_none());
    assert!(last(&b, &req("flash.catalog.update", json!({"url":"https://e.test/c.json","sha256":h}))).ok);
    assert_eq!(last(&b, &req("flash.catalog.list", json!({}))).result.unwrap()["source"], "updated");
}

#[test] fn write_refuses_image_that_expands_past_the_device() {
    // sdb in the fixture is 31406948352 bytes; an .img.xz whose index says one byte more is refused.
    let b = bridge(Fake { write_size: Some(31_406_948_353), ..Default::default() });
    let mut p = write_params(); p["image"] = json!("/home/don/raspios.img.xz");
    let r = last(&b, &req("flash.image.write", p));
    assert_eq!(cat(&r), "safety_refused");
    assert!(r.error.unwrap().message.contains("expands"));
    assert!(b.sys.helper_calls.borrow().is_empty());
}

#[test] fn host_write_size_reads_xz_index() {
    use std::io::Write;
    let data = vec![7u8; 1 << 20];
    let mut e = xz2::write::XzEncoder::new(Vec::new(), 6); e.write_all(&data).unwrap();
    let c = e.finish().unwrap();
    assert_eq!(flash_core::image::write_size(&mut std::io::Cursor::new(c)).unwrap(), 1 << 20);
}
