//! Tatara fleet-device host (AWAI-3410). It owns the device's runner key and
//! makes the only network requests: read-only GETs of runs and run bundles
//! from the configured coordinator. The page verifies bundles itself.
use std::fs;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::Duration;

use base64::Engine;
use serde_json::{json, Value};
use tauri::Manager;
use zeroize::Zeroize;

const DID_PREFIX: &str = "did:tatara:ed25519:";
/// DER SubjectPublicKeyInfo prefix for an Ed25519 key (RFC 8410).
const SPKI_PREFIX: [u8; 12] = [0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00];
const MAX_BODY: u64 = 4 * 1024 * 1024;

fn data_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let d = app.path().app_data_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    Ok(d)
}

/// Write with mode 0600 from the start, then rename into place.
fn write_private(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let mut opts = fs::OpenOptions::new();
    opts.write(true).create(true).truncate(true);
    #[cfg(unix)]
    { use std::os::unix::fs::OpenOptionsExt; opts.mode(0o600); }
    let mut f = opts.open(&tmp).map_err(|e| e.to_string())?;
    f.write_all(bytes).and_then(|_| f.sync_all()).map_err(|e| e.to_string())?;
    fs::rename(&tmp, path).map_err(|e| e.to_string())
}

pub fn did_of(public: &[u8; 32]) -> String {
    let spki = [SPKI_PREFIX.as_slice(), public.as_slice()].concat();
    format!("{DID_PREFIX}{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(spki))
}

/// The device's runner key: 32 random bytes in a 0600 file in app data
/// (design §9: file first; Keystore later). Only the DID leaves this function.
pub fn load_or_create_identity(dir: &Path) -> Result<String, String> {
    let path = dir.join("device-key-v1");
    let mut seed = [0u8; 32];
    if path.exists() {
        let mut b = fs::read(&path).map_err(|e| e.to_string())?;
        if b.len() != 32 { b.zeroize(); return Err("device key file is damaged".into()); }
        seed.copy_from_slice(&b); b.zeroize();
    } else {
        getrandom::fill(&mut seed).map_err(|e| e.to_string())?;
        write_private(&path, &seed)?;
    }
    let sk = ed25519_dalek::SigningKey::from_bytes(&seed);
    seed.zeroize();
    let did = did_of(sk.verifying_key().as_bytes());
    // Public; lets fleet provisioning read the DID without touching the key.
    write_private(&dir.join("device-did"), did.as_bytes())?;
    Ok(did)
}

/// Coordinator base URL: http(s), host and optional port only.
pub fn check_url(u: &str) -> Result<String, String> {
    let u = u.trim().trim_end_matches('/');
    let rest = u.strip_prefix("http://").or_else(|| u.strip_prefix("https://")).ok_or("URL must start with http:// or https://")?;
    if rest.is_empty() || !rest.chars().all(|c| c.is_ascii_alphanumeric() || ".-:[]".contains(c)) {
        return Err("URL must be scheme://host[:port] only".into());
    }
    Ok(u.to_string())
}

pub fn check_run_id(id: &str) -> Result<(), String> {
    let hex = id.strip_prefix("cid:sha256:").ok_or("bad run id")?;
    if hex.len() == 64 && hex.chars().all(|c| matches!(c, '0'..='9' | 'a'..='f')) { Ok(()) } else { Err("bad run id".into()) }
}

fn config_path(app: &tauri::AppHandle) -> Result<PathBuf, String> { Ok(data_dir(app)?.join("config.json")) }

fn coordinator(app: &tauri::AppHandle) -> Result<String, String> {
    let v: Value = serde_json::from_slice(&fs::read(config_path(app)?).map_err(|_| "set the coordinator URL first")?)
        .map_err(|e| e.to_string())?;
    check_url(v["coordinator"].as_str().ok_or("set the coordinator URL first")?)
}

fn get_text(url: &str) -> Result<String, String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(15))).http_status_as_error(false).build().into();
    let mut res = agent.get(url).call().map_err(|e| format!("coordinator unreachable: {e}"))?;
    let status = res.status().as_u16();
    let mut body = String::new();
    res.body_mut().as_reader().take(MAX_BODY + 1).read_to_string(&mut body).map_err(|e| e.to_string())?;
    if body.len() as u64 > MAX_BODY { return Err("response too large".into()); }
    if status != 200 { return Err(format!("coordinator answered {status}: {}", body.chars().take(200).collect::<String>())); }
    Ok(body)
}

#[tauri::command]
fn tatara_identity(app: tauri::AppHandle) -> Result<Value, String> {
    Ok(json!({"did": load_or_create_identity(&data_dir(&app)?)?, "store": "app-private file (0600)"}))
}

#[tauri::command]
fn tatara_config_get(app: tauri::AppHandle) -> Result<Value, String> {
    Ok(json!({"coordinator": coordinator(&app).ok()}))
}

#[tauri::command]
fn tatara_config_set(app: tauri::AppHandle, coordinator: String) -> Result<Value, String> {
    let u = check_url(&coordinator)?;
    write_private(&config_path(&app)?, json!({"coordinator": u}).to_string().as_bytes())?;
    Ok(json!({"coordinator": u}))
}

#[tauri::command]
async fn tatara_runs(app: tauri::AppHandle) -> Result<String, String> {
    let base = coordinator(&app)?;
    tauri::async_runtime::spawn_blocking(move || get_text(&format!("{base}/v1/runs"))).await.map_err(|e| e.to_string())?
}

#[tauri::command]
async fn tatara_bundle(app: tauri::AppHandle, run_id: String) -> Result<String, String> {
    check_run_id(&run_id)?;
    let base = coordinator(&app)?;
    tauri::async_runtime::spawn_blocking(move || get_text(&format!("{base}/v1/runs/{run_id}/bundle"))).await.map_err(|e| e.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default().runtime(tauri_runtime_wry::Wry::default())
        .invoke_handler(tauri::generate_handler![tatara_identity, tatara_config_get, tatara_config_set, tatara_runs, tatara_bundle])
        .run(tauri::generate_context!())
        .expect("Tatara initialization failed");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_stable_private_and_a_tatara_did() {
        let dir = std::env::temp_dir().join(format!("tatara-app-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir); fs::create_dir_all(&dir).unwrap();
        let d1 = load_or_create_identity(&dir).unwrap();
        let d2 = load_or_create_identity(&dir).unwrap();
        assert_eq!(d1, d2);
        assert!(d1.starts_with(DID_PREFIX));
        assert_eq!(fs::read_to_string(dir.join("device-did")).unwrap(), d1);
        #[cfg(unix)]
        { use std::os::unix::fs::PermissionsExt;
          assert_eq!(fs::metadata(dir.join("device-key-v1")).unwrap().permissions().mode() & 0o777, 0o600); }
        fs::write(dir.join("device-key-v1"), b"short").unwrap();
        assert!(load_or_create_identity(&dir).is_err());
    }

    #[test]
    fn did_matches_the_coordinator_form() {
        // The coordinator's fixture DIDs are JDK SPKI encodings; same prefix and length.
        let d = did_of(&[7u8; 32]);
        assert!(d.starts_with("did:tatara:ed25519:MCowBQYDK2VwAyEA"));
        assert_eq!(d.len(), DID_PREFIX.len() + 59);
    }

    #[test]
    fn url_and_run_id_checks() {
        assert_eq!(check_url("http://100.108.223.94:8790/").unwrap(), "http://100.108.223.94:8790");
        assert!(check_url("ftp://x").is_err());
        assert!(check_url("http://x/v1/runs?a=b").is_err());
        assert!(check_url("http://").is_err());
        assert!(check_run_id(&format!("cid:sha256:{}", "a".repeat(64))).is_ok());
        assert!(check_run_id("cid:sha256:../../x").is_err());
        assert!(check_run_id(&format!("cid:sha256:{}", "A".repeat(64))).is_err());
    }
}
