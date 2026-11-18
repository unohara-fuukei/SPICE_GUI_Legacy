//! Native file export: open a save dialog and write the payload (mmCIF / PNG).
//! The frontend generates the content; this command handles dialog + disk I/O.

use serde::Serialize;
use std::io::Write;
use tauri_plugin_dialog::{DialogExt, FilePath};
use tauri_plugin_fs::{FsExt, OpenOptions};

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub struct SaveOut {
    pub saved: bool,
    pub path: Option<String>,
}

#[tauri::command]
pub async fn save_export(
    app: tauri::AppHandle,
    default_name: String,
    data_b64: String,
) -> Result<SaveOut, String> {
    let ext = std::path::Path::new(&default_name)
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("");

    let mut dialog = app.dialog().file();
    if !ext.is_empty() {
        dialog = dialog.add_filter("Selected Format", &[ext]);
    }

    let picked = dialog
        .add_filter("SPICE Formats", &["spiceg", "spicep", "spiceproj", "spicefig", "mmcif", "cif", "svg", "png", "tif", "tiff", "pdf", "pptx", "json", "md", "txt"])
        .set_file_name(&default_name)
        .blocking_save_file();

    let Some(fp) = picked else {
        // user cancelled the dialog
        return Ok(SaveOut {
            saved: false,
            path: None,
        });
    };

    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&data_b64)
        .map_err(|e| format!("base64: {e}"))?;

    if bytes.is_empty() {
        return Err("export payload is empty (0 bytes)".to_string());
    }

    // On Android the save dialog returns a SAF `content://` Uri, which a plain
    // `std::fs::write` cannot open (it would leave the system's 0-byte
    // placeholder untouched). Route the Uri through the fs plugin, whose mobile
    // side resolves it to a file descriptor via ContentResolver. Local paths
    // (desktop, app-private dirs) keep using std::fs directly.
    match fp {
        FilePath::Path(p) => {
            std::fs::write(&p, &bytes).map_err(|e| format!("write {}: {e}", p.display()))?;
            Ok(SaveOut {
                saved: true,
                path: Some(p.display().to_string()),
            })
        }
        FilePath::Url(u) => {
            let mut opts = OpenOptions::new();
            opts.write(true).create(true).truncate(true);
            let mut file = app
                .fs()
                .open(FilePath::Url(u.clone()), opts)
                .map_err(|e| format!("open uri: {e}"))?;
            file
                .write_all(&bytes)
                .map_err(|e| format!("write uri: {e}"))?;
            Ok(SaveOut {
                saved: true,
                path: Some(u.to_string()),
            })
        }
    }
}

#[tauri::command]
pub async fn save_file_direct(
    path: String,
    data_b64: String,
) -> Result<bool, String> {
    use base64::Engine;
    let bytes = base64::engine::general_purpose::STANDARD
        .decode(&data_b64)
        .map_err(|e| format!("base64: {e}"))?;

    if bytes.is_empty() {
        return Err("save payload is empty (0 bytes)".to_string());
    }

    std::fs::write(&path, bytes).map_err(|e| format!("write {path}: {e}"))?;
    Ok(true)
}
