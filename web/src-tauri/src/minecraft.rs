//! Finds installed Minecraft versions and the language files the launcher downloaded next to them.

use serde::Serialize;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Install {
  pub version: String,
  pub jar: String,
  pub root: String,
  pub asset_index: Option<String>,
  pub modified: u64,
}

/// The launcher's default game folder on this system.
pub fn default_root() -> Option<PathBuf> {
  #[cfg(windows)]
  {
    std::env::var_os("APPDATA").map(|d| PathBuf::from(d).join(".minecraft"))
  }
  #[cfg(target_os = "macos")]
  {
    std::env::var_os("HOME").map(|d| PathBuf::from(d).join("Library/Application Support/minecraft"))
  }
  #[cfg(not(any(windows, target_os = "macos")))]
  {
    std::env::var_os("HOME").map(|d| PathBuf::from(d).join(".minecraft"))
  }
}

/// Versions under `<root>/versions` that have a client jar, newest first.
pub fn list(root: &Path) -> Vec<Install> {
  let mut out = Vec::new();
  let Ok(entries) = fs::read_dir(root.join("versions")) else { return out };
  for entry in entries.flatten() {
    let dir = entry.path();
    let Some(name) = dir.file_name().and_then(|n| n.to_str()).map(str::to_owned) else { continue };
    let jar = dir.join(format!("{name}.jar"));
    let Ok(meta) = fs::metadata(&jar) else { continue };
    // Loader profiles sometimes leave an empty jar behind.
    if meta.len() < 1024 * 1024 {
      continue;
    }
    let asset_index = fs::read(dir.join(format!("{name}.json")))
      .ok()
      .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
      .and_then(|v| v.pointer("/assetIndex/id").and_then(|id| id.as_str()).map(str::to_owned));
    let modified = meta
      .modified()
      .ok()
      .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
      .map_or(0, |d| d.as_secs());
    out.push(Install {
      version: name,
      jar: jar.to_string_lossy().into_owned(),
      root: root.to_string_lossy().into_owned(),
      asset_index,
      modified,
    });
  }
  out.sort_by(|a, b| b.modified.cmp(&a.modified));
  out
}

/// Block names (`block.*` keys) of every language in an asset index, except `en_us`, which the jar already has.
pub fn languages(root: &Path, asset_index: &str) -> Result<HashMap<String, HashMap<String, String>>, String> {
  if asset_index.contains(['/', '\\']) || asset_index.contains("..") {
    return Err("bad asset index".into());
  }
  let assets = root.join("assets");
  let index: serde_json::Value = fs::read(assets.join("indexes").join(format!("{asset_index}.json")))
    .map_err(|e| e.to_string())
    .and_then(|b| serde_json::from_slice(&b).map_err(|e| e.to_string()))?;
  let objects = index.get("objects").and_then(|o| o.as_object()).ok_or("no objects in the asset index")?;
  let mut out = HashMap::new();
  for (key, value) in objects {
    let Some(code) = key.strip_prefix("minecraft/lang/").and_then(|k| k.strip_suffix(".json")) else { continue };
    let Some(hash) = value.get("hash").and_then(|h| h.as_str()) else { continue };
    if code == "en_us" || hash.len() < 2 || !hash.chars().all(|c| c.is_ascii_hexdigit()) {
      continue;
    }
    let Ok(bytes) = fs::read(assets.join("objects").join(&hash[..2]).join(hash)) else { continue };
    let Ok(table) = serde_json::from_slice::<HashMap<String, serde_json::Value>>(&bytes) else { continue };
    let names: HashMap<String, String> = table
      .into_iter()
      .filter(|(k, _)| k.starts_with("block."))
      .filter_map(|(k, v)| v.as_str().map(|s| (k, s.to_owned())))
      .collect();
    if !names.is_empty() {
      out.insert(code.to_owned(), names);
    }
  }
  Ok(out)
}
