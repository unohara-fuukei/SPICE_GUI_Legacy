//! Desktop binary entry point. The Android build uses `lib.rs::android_main`
//! (crate-type cdylib, no `main`).

#[cfg(not(target_os = "android"))]
fn main() -> eframe::Result {
    spice_native::run_desktop()
}

/// Cargo on Android still expects a `main` symbol for bin targets, but the
/// APK loads the cdylib directly; keep this stub to satisfy the linker.
#[cfg(target_os = "android")]
fn main() {}
