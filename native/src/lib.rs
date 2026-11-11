//! SPICE GUI — native (WebView-free) port. Rust + egui.
//!
//! Desktop:  `cargo run --release`  (inside `native/`)
//! Android:  `cargo build --release --target aarch64-linux-android --features android`
//!           then `bash scripts/build_android_apk.sh`  (see scripts/)

pub mod ai;
pub mod app;
pub mod cloning;
pub mod enzymes;
pub mod gel;
pub mod pdb;
pub mod project;
pub mod protein;
pub mod seq;
pub mod tools;
pub mod views;

/// Desktop entry (window 1280×720).
#[cfg(not(target_os = "android"))]
pub fn run_desktop() -> eframe::Result {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1280.0, 720.0])
            .with_title("SPICE GUI Native"),
        ..Default::default()
    };
    eframe::run_native(
        "SPICE GUI Native",
        options,
        Box::new(|cc| Ok(Box::new(app::App::new(cc)))),
    )
}

/// Android entry: inject the `AndroidApp` from `android_main` into eframe's
/// event-loop builder hook (eframe 0.29 style), then run full-screen.
#[cfg(target_os = "android")]
#[no_mangle]
pub fn android_main(app: android_activity::AndroidApp) {
    use winit::platform::android::EventLoopBuilderExtAndroid;

    let mut options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_title("SPICE GUI Native"),
        ..Default::default()
    };
    options.event_loop_builder = Some(Box::new(move |builder| {
        builder.with_android_app(app.clone());
    }));
    let _ = eframe::run_native(
        "SPICE GUI Native",
        options,
        Box::new(|cc| Ok(Box::new(app::App::new(cc)))),
    );
}
