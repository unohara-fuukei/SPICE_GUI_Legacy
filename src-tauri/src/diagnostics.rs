// Crash diagnostics: the Android MainActivity installs an
// UncaughtExceptionHandler that persists the last native crash
// (time / thread / stack trace) into the app's private external files
// directory before killing the process. After a restart, the web layer can
// call `get_last_crash` to surface that report to the user / logs.

#[tauri::command]
pub fn get_last_crash() -> Option<String> {
  #[cfg(target_os = "android")]
  {
    const CRASH_FILE_CANDIDATES: [&str; 2] = [
      // app-specific external files dir (primary user)
      "/storage/emulated/0/Android/data/com.spice.gui/files/last_crash.txt",
      // fallback: internal filesDir
      "/data/data/com.spice.gui/files/last_crash.txt",
    ];
    for p in CRASH_FILE_CANDIDATES.iter() {
      if let Ok(s) = std::fs::read_to_string(p) {
        let _ = std::fs::remove_file(p);
        return Some(s);
      }
    }
  }
  #[cfg(not(target_os = "android"))]
  {
    // Desktop builds never write the crash file.
  }
  None
}
