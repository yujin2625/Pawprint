mod minecraft;

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Mutex;
use std::sync::atomic::{AtomicU32, Ordering};
use tauri::ipc::{InvokeBody, Request, Response};
use tauri::{AppHandle, Emitter, Manager, State, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_opener::OpenerExt;

/// Files given on the command line (a double-clicked `.pawprint` / `.pawpack`) that the page has not picked up yet.
#[derive(Default)]
struct OpenFiles(Mutex<Vec<String>>);

fn file_args(args: impl IntoIterator<Item = String>) -> Vec<String> {
  args
    .into_iter()
    .filter(|a| {
      let lower = a.to_lowercase();
      (lower.ends_with(".pawprint") || lower.ends_with(".pawpack")) && PathBuf::from(a).is_file()
    })
    .collect()
}

#[tauri::command]
fn take_open_files(state: State<OpenFiles>) -> Vec<String> {
  std::mem::take(&mut *state.0.lock().unwrap())
}

#[tauri::command]
async fn read_file(path: String) -> Result<Response, String> {
  std::fs::read(&path).map(Response::new).map_err(|e| e.to_string())
}

/// The body is the raw file; the target path comes in the `path` header (URI-encoded).
#[tauri::command]
fn write_file(request: Request) -> Result<(), String> {
  let InvokeBody::Raw(data) = request.body() else { return Err("expected raw bytes".into()) };
  let path = request.headers().get("path").and_then(|v| v.to_str().ok()).ok_or("missing path")?;
  std::fs::write(percent_decode(path)?, data).map_err(|e| e.to_string())
}

fn percent_decode(s: &str) -> Result<String, String> {
  let bytes = s.as_bytes();
  let mut out = Vec::with_capacity(bytes.len());
  let mut i = 0;
  while i < bytes.len() {
    if bytes[i] == b'%' && i + 2 < bytes.len() {
      let hex = std::str::from_utf8(&bytes[i + 1..i + 3]).map_err(|e| e.to_string())?;
      out.push(u8::from_str_radix(hex, 16).map_err(|e| e.to_string())?);
      i += 3;
    } else {
      out.push(bytes[i]);
      i += 1;
    }
  }
  String::from_utf8(out).map_err(|e| e.to_string())
}

#[tauri::command]
fn minecraft_default_root() -> Option<String> {
  minecraft::default_root().filter(|p| p.is_dir()).map(|p| p.to_string_lossy().into_owned())
}

#[tauri::command]
fn minecraft_installs(root: String) -> Vec<minecraft::Install> {
  minecraft::list(&PathBuf::from(root))
}

#[tauri::command]
async fn minecraft_languages(root: String, asset_index: String) -> Result<HashMap<String, HashMap<String, String>>, String> {
  minecraft::languages(&PathBuf::from(root), &asset_index)
}

static POPOUTS: AtomicU32 = AtomicU32::new(0);

/// The main window. Its `window.open` calls for panels (moved to their own window) become app windows that share
/// the page's JavaScript, so those panels keep working on the same blueprint. Other links (downloads, GitHub) open
/// in the system browser.
fn main_window(app: &AppHandle) -> tauri::Result<()> {
  let config = app.config().app.windows.iter().find(|w| w.label == "main").cloned().expect("main window config");
  let handle = app.clone();
  WebviewWindowBuilder::from_config(app, &config)?
    .on_new_window(move |url, features| {
      let local = matches!(url.host_str(), Some("tauri.localhost" | "localhost")) || url.scheme() == "tauri";
      if !(local && url.path().ends_with("/popout.html")) {
        if matches!(url.scheme(), "http" | "https") {
          let _ = handle.opener().open_url(url.as_str(), None::<&str>);
        }
        return tauri::webview::NewWindowResponse::Deny;
      }
      let label = format!("panel-{}", POPOUTS.fetch_add(1, Ordering::Relaxed));
      let built = WebviewWindowBuilder::new(&handle, label, WebviewUrl::External("about:blank".parse().unwrap()))
        .window_features(features)
        .title("Pawprint")
        .on_document_title_changed(|window, title| {
          let _ = window.set_title(&title);
        })
        .build();
      match built {
        Ok(window) => tauri::webview::NewWindowResponse::Create { window },
        Err(_) => tauri::webview::NewWindowResponse::Deny,
      }
    })
    .build()?;
  Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
  tauri::Builder::default()
    .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
      let files = file_args(args.into_iter().skip(1));
      if !files.is_empty() {
        let _ = app.emit_to("main", "open-files", files);
      }
      if let Some(main) = app.get_webview_window("main") {
        let _ = main.unminimize();
        let _ = main.set_focus();
      }
    }))
    .plugin(tauri_plugin_dialog::init())
    .plugin(tauri_plugin_opener::init())
    .plugin(
      // Only the main window: panel windows are placed by the saved panel layout.
      tauri_plugin_window_state::Builder::default().with_filter(|label| label == "main").build(),
    )
    .manage(OpenFiles(Mutex::new(file_args(std::env::args().skip(1)))))
    .invoke_handler(tauri::generate_handler![
      take_open_files,
      read_file,
      write_file,
      minecraft_default_root,
      minecraft_installs,
      minecraft_languages
    ])
    .setup(|app| {
      main_window(app.handle())?;
      Ok(())
    })
    // Panel windows belong to the main one: closing it ends the app.
    .on_window_event(|window, event| {
      if window.label() == "main" && matches!(event, tauri::WindowEvent::Destroyed) {
        window.app_handle().exit(0);
      }
    })
    .run(tauri::generate_context!())
    .expect("error while running Pawprint");
}
