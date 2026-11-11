//! P1 tool windows: primer design, double digest, Gibson, Golden Gate, gel.

use crate::app::{App, Tab};
use crate::cloning as cl;
use crate::gel as gel;
use egui::{Align2, Color32, FontId, Pos2, Rect, Sense, Stroke, Vec2};

fn primer_row(ui: &mut egui::Ui, name: &str, p: &cl::Primer) {
    ui.strong(name);
    ui.monospace(&p.seq);
    ui.label(format!("{} nt · Tm {:.1}°C · GC {:.0}%", p.seq.len(), p.tm, p.gc));
    if p.issues.is_empty() {
        ui.colored_label(Color32::from_rgb(63, 185, 80), "✓ 无风险提示");
    } else {
        for i in &p.issues {
            ui.colored_label(Color32::from_rgb(240, 180, 60), format!("⚠ {}", i));
        }
    }
    ui.add_space(4.0);
}

pub fn primer_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.primer_open;
    if !open {
        return;
    }
    let n = app.record.seq.len();
    egui::Window::new("PCR 引物设计")
        .open(&mut open)
        .default_width(560.0)
        .show(ctx, |ui| {
            ui.label(format!("模板: {} ({} bp)", app.record.name, n));
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                let mut s = app.primer_start as i32;
                ui.label("起始 (1-based):");
                ui.add(egui::DragValue::new(&mut s).range(1..=(n as i32 - 41)));
                app.primer_start = s.max(0) as usize;
                let mut e = app.primer_end as i32;
                ui.label("终止:");
                ui.add(egui::DragValue::new(&mut e).range((app.primer_start as i32 + 41)..=(n as i32)));
                app.primer_end = e.max(0) as usize;
            });
            ui.horizontal(|ui| {
                let mut t = app.primer_tm;
                ui.label("目标 Tm (°C):");
                ui.add(egui::Slider::new(&mut t, 52.0..=68.0));
                app.primer_tm = t;
                if let Some((a, b)) = app.selection_range() {
                    if ui.button("用当前选中区域").clicked() {
                        app.primer_start = a;
                        app.primer_end = b + 1;
                    }
                }
            });
            ui.add_space(6.0);
            if ui.button("设计引物").clicked() {
                match cl::design_primer_pair(&app.record.seq, app.primer_start, app.primer_end, app.primer_tm as f64) {
                    Ok(r) => {
                        app.primer_result = Some(r);
                        app.status = "引物设计完成".into();
                    }
                    Err(e) => {
                        app.primer_result = None;
                        app.status = format!("设计失败: {}", e);
                    }
                }
            }
            ui.add_space(6.0);
            if let Some((f, r, len)) = &app.primer_result {
                ui.separator();
                ui.label(format!("扩增产物: {} bp ({}..{})", len, app.primer_start + 1, app.primer_end));
                ui.add_space(4.0);
                primer_row(ui, "Forward 引物", f);
                primer_row(ui, "Reverse 引物", r);
                if let Some(k) = cl::cross_dimer_3prime(f.seq.as_bytes(), r.seq.as_bytes()) {
                    ui.colored_label(Color32::from_rgb(248, 81, 73), format!("⚠ 引物二聚体: 3' 端互补 {} bp", k));
                } else {
                    ui.colored_label(Color32::from_rgb(63, 185, 80), "✓ 无引物二聚体风险");
                }
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    if ui.button("复制 Forward").clicked() {
                        ui.ctx().copy_text(f.seq.clone());
                    }
                    if ui.button("复制 Reverse").clicked() {
                        ui.ctx().copy_text(r.seq.clone());
                    }
                });
            }
        });
    app.primer_open = open;
}

pub fn dd_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.dd_open;
    if !open {
        return;
    }
    egui::Window::new("双酶切方案向导")
        .open(&mut open)
        .default_width(520.0)
        .show(ctx, |ui| {
            ui.label(format!("序列: {} ({} bp) — 下列酶对各切 1–2 次", app.record.name, app.record.seq.len()));
            ui.add_space(4.0);
            let pairs = cl::double_digest_candidates(&app.record);
            if pairs.is_empty() {
                ui.label("无合适的双酶切组合（无单/双切点酶）");
                return;
            }
            ui.label(format!("共 {} 组候选，前 12 组：", pairs.len()));
            ui.add_space(4.0);
            egui::Grid::new("dd-grid").num_columns(3).spacing([20.0, 4.0]).show(ui, |ui| {
                ui.strong("酶 A");
                ui.strong("酶 B");
                ui.strong("缓冲液建议");
                ui.end_row();
                for (a, b) in pairs.iter().take(12) {
                    ui.label(*a);
                    ui.label(*b);
                    ui.label(cl::buffer_recommendation(a, b));
                    ui.end_row();
                }
            });
        });
    app.dd_open = open;
}

pub fn gibson_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.gibson_open;
    if !open {
        return;
    }
    let n = app.record.seq.len();
    egui::Window::new("Gibson 装配")
        .open(&mut open)
        .default_width(600.0)
        .show(ctx, |ui| {
            ui.label(format!("载体: {} ({} bp, 环形)", app.record.name, n));
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                let mut c = app.gibson_cut as i32;
                ui.label("线性化位点 (1-based):");
                ui.add(egui::DragValue::new(&mut c).range(1..=n as i32));
                app.gibson_cut = c.max(0) as usize;
                let mut o = app.gibson_overlap as i32;
                ui.label("同源臂 (bp):");
                ui.add(egui::DragValue::new(&mut o).range(15..=40));
                app.gibson_overlap = o.max(0) as usize;
            });
            ui.add_space(4.0);
            ui.label("插入片段 (粘贴 FASTA / 裸序列):");
            egui::TextEdit::multiline(&mut app.gibson_insert_text)
                .desired_rows(5)
                .desired_width(f32::INFINITY)
                .show(ui);
            ui.add_space(4.0);
            if ui.button("设计 Gibson 引物").clicked() {
                let ins = match crate::seq::parse_auto(&app.gibson_insert_text) {
                    Ok(r) => r.seq,
                    Err(_) => {
                        // treat as raw sequence
                        app.gibson_insert_text
                            .bytes()
                            .filter(|b| b.is_ascii_alphabetic())
                            .map(|b| b.to_ascii_uppercase())
                            .collect::<Vec<u8>>()
                    }
                };
                match cl::gibson_design(&app.record.seq, app.gibson_cut, &ins, app.gibson_overlap) {
                    Ok(r) => {
                        app.gibson_result = Some(r);
                        app.status = "Gibson 设计完成".into();
                    }
                    Err(e) => {
                        app.gibson_result = None;
                        app.status = format!("Gibson 设计失败: {}", e);
                    }
                }
            }
            ui.add_space(6.0);
            if let Some(res) = &app.gibson_result {
                ui.separator();
                ui.label(format!("装配子总长: {} bp (环形)", res.assembled_len));
                for j in &res.junctions {
                    ui.monospace(j);
                }
                ui.add_space(4.0);
                primer_row(ui, "Insert-F (含载体同源尾)", &res.insert_fwd);
                primer_row(ui, "Insert-R (含载体同源尾)", &res.insert_rev);
                primer_row(ui, "Vector-F (载体扩增)", &res.vector_fwd);
                primer_row(ui, "Vector-R (载体扩增)", &res.vector_rev);
                ui.horizontal(|ui| {
                    if ui.button("复制 Insert-F").clicked() {
                        ui.ctx().copy_text(res.insert_fwd.seq.clone());
                    }
                    if ui.button("复制 Insert-R").clicked() {
                        ui.ctx().copy_text(res.insert_rev.seq.clone());
                    }
                });
            }
        });
    app.gibson_open = open;
}

pub fn gg_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.gg_open;
    if !open {
        return;
    }
    egui::Window::new("Golden Gate 装配筛查")
        .open(&mut open)
        .default_width(560.0)
        .show(ctx, |ui| {
            ui.label(format!("序列: {} — IIS 型酶 (BsaI / BsmBI / BbsI) 位点与接头筛查", app.record.name));
            ui.add_space(4.0);
            let sites = cl::golden_gate_sites(&app.record.seq, app.record.topology == "circular");
            if sites.is_empty() {
                ui.colored_label(Color32::from_rgb(63, 185, 80), "✓ 无 IIS 型酶位点 — 该序列可安全作为 Golden Gate 载体骨架");
                return;
            }
            ui.label(format!("共 {} 个位点：", sites.len()));
            ui.add_space(4.0);
            egui::Grid::new("gg-grid").num_columns(4).spacing([20.0, 4.0]).show(ui, |ui| {
                ui.strong("酶");
                ui.strong("位置");
                ui.strong("接头 (5' 突出)");
                ui.strong("接头质量");
                ui.end_row();
                for s in &sites {
                    ui.label(s.enzyme);
                    ui.label(format!("{}", s.pos + 1));
                    ui.monospace(&s.overhang);
                    ui.label(cl::overhang_quality(&s.overhang));
                    ui.end_row();
                }
            });
            ui.add_space(6.0);
            let ov = cl::golden_gate_overlaps(&sites);
            if ov.is_empty() {
                ui.label("无共享接头（各接头互不相容 — 无串接风险）");
            } else {
                ui.colored_label(Color32::from_rgb(240, 180, 60), "共享接头（可融合的连接对）:");
                for o in ov {
                    ui.monospace(o);
                }
            }
        });
    app.gg_open = open;
}

// ---------------------------------------------------------------- gel tab

pub fn draw_gel(ui: &mut egui::Ui, app: &App) {
    let lanes = gel::build_lanes(&app.record, &app.enzymes_on.iter().cloned().collect::<Vec<_>>());
    let (rect, _resp) = ui.allocate_exact_size(
        Vec2::new(ui.available_width().max(300.0), ui.available_height().max(200.0)),
        Sense::hover(),
    );
    let painter = ui.painter().clone();

    // gel box
    let margin_l = 90.0;
    let box_r = Rect::from_min_max(
        Pos2::new(rect.left() + margin_l, rect.top() + 40.0),
        Pos2::new(rect.right() - 16.0, rect.bottom() - 16.0),
    );
    painter.rect_filled(box_r, egui::Rounding::same(4.0), Color32::from_rgb(8, 12, 18));
    painter.rect_stroke(box_r, egui::Rounding::same(4.0), Stroke::new(1.0, Color32::from_rgb(48, 54, 61)));

    // well line
    painter.line_segment(
        [Pos2::new(box_r.left() + 6.0, box_r.top() + 18.0), Pos2::new(box_r.right() - 6.0, box_r.top() + 18.0)],
        Stroke::new(1.0, Color32::from_rgb(70, 80, 90)),
    );

    let lane_w = (box_r.width() / lanes.len() as f32).max(28.0);
    let top = box_r.top() + 34.0;
    let bottom = box_r.bottom() - 24.0;

    // bp axis labels on the left
    for bp in [10000, 5000, 3000, 2000, 1000, 500, 300, 100] {
        let y = gel::bp_to_y(bp, top, bottom);
        painter.text(
            Pos2::new(box_r.left() - 8.0, y),
            Align2::RIGHT_CENTER,
            if bp >= 1000 { format!("{} kb", bp / 1000) } else { format!("{}", bp) },
            FontId::proportional(9.0),
            Color32::from_rgb(125, 139, 152),
        );
        painter.line_segment(
            [Pos2::new(box_r.left(), y), Pos2::new(box_r.right(), y)],
            Stroke::new(0.25, Color32::from_rgb(28, 34, 40)),
        );
    }

    // lanes
    for (i, lane) in lanes.iter().enumerate() {
        let cx = box_r.left() + lane_w * (i as f32 + 0.5);
        // lane label
        painter.text(
            Pos2::new(cx, box_r.top() + 9.0),
            Align2::CENTER_CENTER,
            &lane.label,
            FontId::proportional(10.0),
            Color32::from_rgb(200, 211, 221),
        );
        // well
        painter.line_segment(
            [Pos2::new(cx - 10.0, box_r.top() + 18.0), Pos2::new(cx + 10.0, box_r.top() + 18.0)],
            Stroke::new(3.0, Color32::from_rgb(90, 98, 107)),
        );
        for b in &lane.bands {
            let y = gel::bp_to_y(b.bp, top, bottom);
            let (r, g, bl) = if lane.label == "Marker" {
                (230, 230, 235)
            } else {
                (220, 245, 215)
            };
            let a = (b.intensity * 230.0).clamp(30.0, 240.0) as u8;
            painter.rect_filled(
                Rect::from_min_max(Pos2::new(cx - lane_w * 0.32, y - 2.5), Pos2::new(cx + lane_w * 0.32, y + 2.5)),
                egui::Rounding::same(1.5),
                Color32::from_rgba_premultiplied(
                    (r as f32 * a as f32 / 255.0) as u8,
                    (g as f32 * a as f32 / 255.0) as u8,
                    (bl as f32 * a as f32 / 255.0) as u8,
                    a,
                ),
            );
        }
    }

    painter.text(
        Pos2::new(rect.left(), rect.top() + 6.0),
        Align2::LEFT_TOP,
        format!("琼脂糖电泳模拟 · 1% 胶 · {} ({} bp)", app.record.name, app.record.seq.len()),
        FontId::proportional(12.0),
        Color32::from_rgb(200, 211, 221),
    );
    let _ = Tab::Notes; // silence unused import if tab_label not used here
}
