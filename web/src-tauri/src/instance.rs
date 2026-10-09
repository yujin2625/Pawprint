//! Reads a modded game folder (an "instance") without starting the game, for making block packs: the vanilla jar,
//! every mod jar (and the jars nested in them), assets mods generated and cached on disk, and the enabled resource
//! packs, layered in the game's order. Only block-related assets are indexed: blockstates, models, item models,
//! textures and language files.

use serde::Serialize;
use std::collections::HashMap;
use std::fs::{self, File};
use std::io::{Cursor, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use zip::ZipArchive;
use zip::write::SimpleFileOptions;

const MAX_ENTRY: u64 = 64 * 1024 * 1024;

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct InstanceInfo {
  pub mc_version: Option<String>,
  pub loader: Option<String>,
  pub vanilla_jar: Option<String>,
  /// The world data version from the vanilla jar's version.json.
  pub data_version: Option<u64>,
  /// The launcher folder holding `assets/` next to the vanilla jar's `versions/`, and the jar's asset index:
  /// language files other than English are there, not in the jar.
  pub launcher_root: Option<String>,
  pub asset_index: Option<String>,
  /// Enabled resource packs, lowest priority first (as in options.txt).
  pub resource_packs: Vec<String>,
  pub mod_files: usize,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct ModInfo {
  pub id: String,
  pub name: String,
  pub version: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexStats {
  pub sources: usize,
  pub files: usize,
  pub mods: Vec<ModInfo>,
  /// Indexed paths other than textures (blockstates, models, item models).
  pub names: Vec<String>,
}

enum Source {
  Dir(PathBuf),
  Zip(PathBuf),
  Bytes(Arc<Vec<u8>>),
}

#[derive(Default)]
pub struct Index {
  sources: Vec<Source>,
  /// Path → the source that wins (the highest priority one) and the entry name in it.
  files: HashMap<String, (usize, String)>,
  /// Language files from every source, lowest priority first: the game merges them key by key.
  langs: HashMap<String, Vec<(usize, String)>>,
}

/// `assets/<namespace>/<kind>/...` files a block pack can use.
fn wanted(name: &str) -> bool {
  let mut parts = name.splitn(4, '/');
  let (Some("assets"), Some(ns), Some(kind), Some(rest)) = (parts.next(), parts.next(), parts.next(), parts.next()) else {
    return false;
  };
  if ns.is_empty() || rest.is_empty() || name.contains("..") {
    return false;
  }
  match kind {
    "blockstates" | "models" | "items" | "lang" => rest.ends_with(".json"),
    "textures" => rest.ends_with(".png") || rest.ends_with(".png.mcmeta"),
    _ => false,
  }
}

fn read_json(path: &Path) -> Option<serde_json::Value> {
  fs::read(path).ok().and_then(|b| serde_json::from_slice(&b).ok())
}

/// Game version, loader and the vanilla jar, from CurseForge or Prism/MultiMC files, or the folder layout.
pub fn info(dir: &Path) -> InstanceInfo {
  let mut mc_version = None;
  let mut loader = None;
  if let Some(cf) = read_json(&dir.join("minecraftinstance.json")) {
    mc_version = cf.get("gameVersion").and_then(|v| v.as_str()).map(str::to_owned);
    loader = cf
      .pointer("/baseModLoader/name")
      .and_then(|v| v.as_str())
      .and_then(|n| n.split('-').next())
      .map(str::to_owned);
  }
  for pack in [dir.join("mmc-pack.json"), dir.join("../mmc-pack.json")] {
    let Some(mmc) = read_json(&pack) else { continue };
    for c in mmc.get("components").and_then(|c| c.as_array()).into_iter().flatten() {
      let uid = c.get("uid").and_then(|u| u.as_str()).unwrap_or("");
      let version = c.get("version").and_then(|v| v.as_str()).map(str::to_owned);
      match uid {
        "net.minecraft" => mc_version = mc_version.or(version),
        "net.neoforged" => loader = loader.or(Some("neoforge".into())),
        "net.minecraftforge" => loader = loader.or(Some("forge".into())),
        "net.fabricmc.fabric-loader" => loader = loader.or(Some("fabric".into())),
        "org.quiltmc.quilt-loader" => loader = loader.or(Some("quilt".into())),
        _ => {}
      }
    }
  }
  let vanilla_jar = mc_version
    .as_deref()
    .and_then(|v| vanilla_candidates(dir, v).into_iter().find(|p| p.is_file()))
    .map(|p| clean(&p));
  let (launcher_root, asset_index) = vanilla_jar.as_deref().map(launcher_assets).unwrap_or((None, None));
  let mod_files = fs::read_dir(dir.join("mods"))
    .map(|r| r.flatten().filter(|e| e.path().extension().is_some_and(|x| x == "jar")).count())
    .unwrap_or(0);
  InstanceInfo {
    mc_version,
    loader,
    data_version: vanilla_jar.as_deref().and_then(data_version),
    launcher_root: launcher_root.map(|p| p.to_string_lossy().into_owned()),
    asset_index,
    vanilla_jar: vanilla_jar.map(|p| p.to_string_lossy().into_owned()),
    resource_packs: enabled_packs(dir),
    mod_files,
  }
}

/// A path without `..` parts or the `\\?\` prefix Windows adds when resolving.
fn clean(path: &Path) -> PathBuf {
  let resolved = fs::canonicalize(path).unwrap_or_else(|_| path.to_owned());
  let text = resolved.to_string_lossy();
  PathBuf::from(text.strip_prefix(r"\\?\").unwrap_or(&text).to_owned())
}

/// For `<root>/versions/<v>/<v>.jar`: `<root>` (if it has `assets/`) and the asset index named in `<v>.json`.
pub fn launcher_assets(jar: &Path) -> (Option<PathBuf>, Option<String>) {
  let index = fs::read(jar.with_extension("json"))
    .ok()
    .and_then(|b| serde_json::from_slice::<serde_json::Value>(&b).ok())
    .and_then(|v| v.pointer("/assetIndex/id").and_then(|id| id.as_str()).map(str::to_owned));
  let root = jar.parent().and_then(Path::parent).and_then(Path::parent).filter(|r| r.join("assets/indexes").is_dir()).map(Path::to_owned);
  (root, index)
}

/// `world_version` from a client jar's version.json.
pub fn data_version(jar: &Path) -> Option<u64> {
  let mut archive = ZipArchive::new(File::open(jar).ok()?).ok()?;
  let bytes = entry(&mut archive, "version.json")?;
  serde_json::from_slice::<serde_json::Value>(&bytes).ok()?.get("world_version")?.as_u64()
}

fn vanilla_candidates(dir: &Path, v: &str) -> Vec<PathBuf> {
  let mut out = vec![
    // CurseForge: Instances/<name> next to Install/versions.
    dir.join(format!("../../Install/versions/{v}/{v}.jar")),
    dir.join(format!("versions/{v}/{v}.jar")),
    // Prism / MultiMC: instances/<name>/.minecraft next to libraries/.
    dir.join(format!("../../../libraries/com/mojang/minecraft/{v}/minecraft-{v}-client.jar")),
    dir.join(format!("../../libraries/com/mojang/minecraft/{v}/minecraft-{v}-client.jar")),
  ];
  if let Some(root) = crate::minecraft::default_root() {
    out.push(root.join(format!("versions/{v}/{v}.jar")));
  }
  out
}

/// `resourcePacks:[...]` from options.txt, lowest priority first.
fn enabled_packs(dir: &Path) -> Vec<String> {
  let Ok(text) = fs::read_to_string(dir.join("options.txt")) else { return vec!["vanilla".into(), "mod_resources".into()] };
  text
    .lines()
    .find_map(|l| l.strip_prefix("resourcePacks:"))
    .and_then(|list| serde_json::from_str::<Vec<String>>(list).ok())
    .unwrap_or_else(|| vec!["vanilla".into(), "mod_resources".into()])
}

impl Index {
  fn add_names(&mut self, source: usize, names: impl IntoIterator<Item = String>) {
    for name in names {
      if !wanted(&name) {
        continue;
      }
      if name.split('/').nth(2) == Some("lang") {
        let code = name.rsplit('/').next().unwrap_or("").trim_end_matches(".json").to_lowercase();
        self.langs.entry(code).or_default().push((source, name.clone()));
      }
      self.files.insert(name.clone(), (source, name));
    }
  }

  fn add_zip_names<R: std::io::Read + std::io::Seek>(&mut self, source: usize, archive: &mut ZipArchive<R>) {
    let names: Vec<String> = archive.file_names().map(str::to_owned).collect();
    self.add_names(source, names);
  }

  fn add_zip(&mut self, path: &Path) -> Option<ZipArchive<File>> {
    let mut archive = ZipArchive::new(File::open(path).ok()?).ok()?;
    let id = self.sources.len();
    self.sources.push(Source::Zip(path.to_owned()));
    self.add_zip_names(id, &mut archive);
    Some(archive)
  }

  fn add_dir(&mut self, root: &Path) {
    if !root.join("assets").is_dir() {
      return;
    }
    let id = self.sources.len();
    self.sources.push(Source::Dir(root.to_owned()));
    let mut names = Vec::new();
    let mut stack = vec![root.join("assets")];
    while let Some(dir) = stack.pop() {
      for entry in fs::read_dir(&dir).into_iter().flatten().flatten() {
        let path = entry.path();
        if path.is_dir() {
          stack.push(path);
        } else if let Ok(rel) = path.strip_prefix(root) {
          names.push(rel.to_string_lossy().replace('\\', "/"));
        }
      }
    }
    self.add_names(id, names);
  }

  /// A mod jar, the jars nested in it, and its metadata.
  fn add_mod(&mut self, path: &Path, mods: &mut Vec<ModInfo>) {
    let Some(mut archive) = self.add_zip(path) else { return };
    mods.extend(mod_info(&mut archive));
    let nested: Vec<String> = archive
      .file_names()
      .filter(|n| (n.starts_with("META-INF/jarjar/") || n.starts_with("META-INF/jars/")) && n.ends_with(".jar"))
      .map(str::to_owned)
      .collect();
    for name in nested {
      let Ok(mut file) = archive.by_name(&name) else { continue };
      if file.size() > MAX_ENTRY {
        continue;
      }
      let mut bytes = Vec::with_capacity(file.size() as usize);
      if file.read_to_end(&mut bytes).is_err() {
        continue;
      }
      let bytes = Arc::new(bytes);
      let Ok(mut inner) = ZipArchive::new(Cursor::new(bytes.as_slice())) else { continue };
      if !inner.file_names().any(|n| n.starts_with("assets/")) {
        continue;
      }
      mods.extend(mod_info(&mut inner));
      let id = self.sources.len();
      self.sources.push(Source::Bytes(bytes.clone()));
      self.add_zip_names(id, &mut inner);
    }
  }
}

/// modId, name and version from neoforge.mods.toml / mods.toml / fabric.mod.json.
fn mod_info<R: std::io::Read + std::io::Seek>(archive: &mut ZipArchive<R>) -> Vec<ModInfo> {
  let mut text = |name: &str| -> Option<String> {
    let mut f = archive.by_name(name).ok()?;
    if f.size() > 1024 * 1024 {
      return None;
    }
    let mut s = String::new();
    f.read_to_string(&mut s).ok()?;
    Some(s)
  };
  if let Some(json) = text("fabric.mod.json").and_then(|s| serde_json::from_str::<serde_json::Value>(&s).ok()) {
    let get = |k: &str| json.get(k).and_then(|v| v.as_str()).unwrap_or("").to_owned();
    let id = get("id");
    if !id.is_empty() {
      let name = json.get("name").and_then(|v| v.as_str()).unwrap_or(&id).to_owned();
      return vec![ModInfo { id, name, version: get("version") }];
    }
  }
  let toml = text("META-INF/neoforge.mods.toml").or_else(|| text("META-INF/mods.toml"));
  let Some(toml) = toml else { return vec![] };
  let manifest_version = text("META-INF/MANIFEST.MF").and_then(|m| {
    m.lines().find_map(|l| l.strip_prefix("Implementation-Version:").map(|v| v.trim().to_owned()))
  });
  // Each [[mods]] table: modId, displayName, version (often "${file.jarVersion}", taken from the manifest).
  let mut out = Vec::new();
  for table in toml.split("[[mods]]").skip(1) {
    let table = table.split("\n[").next().unwrap_or(table);
    let value = |key: &str| -> Option<String> {
      table.lines().find_map(|l| {
        let l = l.trim();
        let rest = l.strip_prefix(key)?.trim_start().strip_prefix('=')?.trim();
        let rest = rest.strip_prefix('"').or_else(|| rest.strip_prefix('\''))?;
        Some(rest.split(['"', '\'']).next()?.to_owned())
      })
    };
    let Some(id) = value("modId") else { continue };
    let name = value("displayName").unwrap_or_else(|| id.clone());
    let mut version = value("version").unwrap_or_default();
    if version.contains("${") {
      version = manifest_version.clone().unwrap_or_default();
    }
    out.push(ModInfo { id, name, version });
  }
  out
}

/// Builds the index: vanilla, then the packs in options.txt order with "mod_resources" standing for the mods and
/// the assets mods generated at run time (dynamic-resource-pack-cache, kubejs/assets).
pub fn build(dir: &Path, vanilla_jar: &Path) -> (Index, IndexStats) {
  let mut index = Index::default();
  let mut mods = Vec::new();
  index.add_zip(vanilla_jar);
  let mut packs = enabled_packs(dir);
  if !packs.iter().any(|p| p == "mod_resources") {
    packs.insert(0, "mod_resources".into());
  }
  for pack in packs {
    if pack == "mod_resources" {
      let mut jars: Vec<PathBuf> = fs::read_dir(dir.join("mods"))
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "jar"))
        .collect();
      jars.sort();
      for jar in jars {
        index.add_mod(&jar, &mut mods);
      }
      for cache in fs::read_dir(dir.join("dynamic-resource-pack-cache")).into_iter().flatten().flatten() {
        index.add_dir(&cache.path());
      }
      index.add_dir(&dir.join("kubejs"));
    } else if let Some(file) = pack.strip_prefix("file/") {
      let path = dir.join("resourcepacks").join(file);
      if path.is_dir() {
        index.add_dir(&path);
      } else {
        index.add_zip(&path);
      }
    }
  }
  mods.retain(|m| !matches!(m.id.as_str(), "minecraft" | "neoforge" | "forge" | "fabricloader" | "java"));
  mods.sort_by(|a, b| a.id.cmp(&b.id));
  mods.dedup_by(|a, b| a.id == b.id);
  let mut names: Vec<String> = index.files.keys().filter(|n| n.split('/').nth(2) != Some("textures")).cloned().collect();
  names.sort();
  let stats = IndexStats { sources: index.sources.len(), files: index.files.len(), mods, names };
  (index, stats)
}

impl Index {
  /// Reads entries grouped by source so each archive is opened once.
  fn read_many(&self, wanted: &[(usize, String)]) -> HashMap<(usize, String), Vec<u8>> {
    let mut by_source: HashMap<usize, Vec<&String>> = HashMap::new();
    for (s, name) in wanted {
      by_source.entry(*s).or_default().push(name);
    }
    let mut out = HashMap::new();
    for (s, names) in by_source {
      let mut put = |name: &String, data: Vec<u8>| {
        out.insert((s, name.clone()), data);
      };
      match &self.sources[s] {
        Source::Dir(root) => {
          for name in names {
            if let Ok(data) = fs::read(root.join(name)) {
              put(name, data);
            }
          }
        }
        Source::Zip(path) => {
          let Ok(file) = File::open(path) else { continue };
          let Ok(mut archive) = ZipArchive::new(file) else { continue };
          for name in names {
            if let Some(data) = entry(&mut archive, name) {
              put(name, data);
            }
          }
        }
        Source::Bytes(bytes) => {
          let Ok(mut archive) = ZipArchive::new(Cursor::new(bytes.as_slice())) else { continue };
          for name in names {
            if let Some(data) = entry(&mut archive, name) {
              put(name, data);
            }
          }
        }
      }
    }
    out
  }

  /// The winning copy of each path, as an uncompressed zip (missing paths are left out).
  pub fn read(&self, paths: &[String]) -> Result<Vec<u8>, String> {
    let wanted: Vec<(usize, String)> = paths.iter().filter_map(|p| self.files.get(p).cloned()).collect();
    let data = self.read_many(&wanted);
    let mut writer = zip::ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored);
    for key in &wanted {
      let Some(bytes) = data.get(key) else { continue };
      writer.start_file(key.1.as_str(), options).map_err(|e| e.to_string())?;
      writer.write_all(bytes).map_err(|e| e.to_string())?;
    }
    Ok(writer.finish().map_err(|e| e.to_string())?.into_inner())
  }

  /// `block.*` names per language, merged across every source in priority order (as the game does).
  pub fn languages(&self) -> HashMap<String, HashMap<String, String>> {
    let all: Vec<(usize, String)> = self.langs.values().flatten().cloned().collect();
    let data = self.read_many(&all);
    let mut out: HashMap<String, HashMap<String, String>> = HashMap::new();
    for (code, files) in &self.langs {
      let table = out.entry(code.clone()).or_default();
      for key in files {
        let Some(bytes) = data.get(key) else { continue };
        let Ok(map) = serde_json::from_slice::<HashMap<String, serde_json::Value>>(strip_bom(bytes)) else { continue };
        for (k, v) in map {
          if let (true, Some(s)) = (k.starts_with("block."), v.as_str()) {
            table.insert(k, s.to_owned());
          }
        }
      }
    }
    out.retain(|_, t| !t.is_empty());
    out
  }
}

fn strip_bom(bytes: &[u8]) -> &[u8] {
  bytes.strip_prefix(&[0xEF, 0xBB, 0xBF]).unwrap_or(bytes)
}

fn entry<R: std::io::Read + std::io::Seek>(archive: &mut ZipArchive<R>, name: &str) -> Option<Vec<u8>> {
  let mut file = archive.by_name(name).ok()?;
  if file.size() > MAX_ENTRY {
    return None;
  }
  let mut data = Vec::with_capacity(file.size() as usize);
  file.read_to_end(&mut data).ok()?;
  Some(data)
}
