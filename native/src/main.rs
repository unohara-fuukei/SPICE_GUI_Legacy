//! SPICE GUI — native (WebView-free) port. Rust + egui.
//!
//! Run on desktop:  `cargo run --release`  (inside `native/`)
//! Android (planned, see NATIVE_PORT.md): `cargo ndk -t arm64-v8a build --release`

mod app;
mod cloning;
mod enzymes;
mod gel;
mod pdb;
mod project;
mod protein;
mod seq;
mod tools;
mod views;

fn main() -> eframe::Result {
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
