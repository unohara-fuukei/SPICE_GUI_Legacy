//! Custom-drawn views: circular plasmid map, linear map, sequence view.

use crate::app::{App, Tab};
use crate::seq::{revcomp, translate};
use egui::{Align2, Color32, FontId, Pos2, Rect, Sense, Stroke, Vec2};

pub const PALETTE: [Color32; 8] = [
    Color32::from_rgb(0, 183, 195),  // cyan
    Color32::from_rgb(63, 185, 80),  // green
    Color32::from_rgb(240, 136, 62), // orange
    Color32::from_rgb(210, 168, 255),// purple
    Color32::from_rgb(227, 179, 81), // yellow
    Color32::from_rgb(248, 81, 73),  // red
    Color32::from_rgb(88, 166, 255), // blue
    Color32::from_rgb(63, 226, 214), // teal
];

const FG: Color32 = Color32::from_rgb(200, 211, 221);
const FG_DIM: Color32 = Color32::from_rgb(125, 139, 152);
const BORDER: Color32 = Color32::from_rgb(48, 54, 61);
const RULER: Color32 = Color32::from_rgb(90, 98, 107);

// ---------------- circular map ----------------

pub fn draw_circular(ui: &mut egui::Ui, app: &mut App) {
    let (rect, _resp) = ui.allocate_exact_size(ui.available_size(), Sense::hover());
    let painter = ui.painter().clone();
    let rec = &app.record;
    let n = rec.seq.len().max(1);
    let center = rect.center();
    let r = ((rect.width().min(rect.height()) / 2.0) - 64.0).max(60.0);

    // backbone double circle
    for rr in [r, r - 5.0] {
        painter.circle_stroke(center, rr, Stroke::new(1.5, BORDER));
    }

    // ticks every 250 bp, number every 500 bp
    let mut bp = 0usize;
    while bp < n {
        let a = bp as f64 * std::f64::consts::TAU / n as f64 - std::f64::consts::FRAC_PI_2;
        let (s, c) = (a.sin(), a.cos());
        let p0 = Pos2::new(center.x + c as f32 * r, center.y + s as f32 * r);
        let p1 = Pos2::new(center.x + c as f32 * (r + 5.0), center.y + s as f32 * (r + 5.0));
        painter.line_segment([p0, p1], Stroke::new(1.0, RULER));
        if bp % 500 == 0 && bp > 0 {
            let pt = Pos2::new(center.x + c as f32 * (r + 18.0), center.y + s as f32 * (r + 18.0));
            painter.text(pt, Align2::CENTER_CENTER, format!("{}", bp + 1), FontId::proportional(9.0), RULER);
        }
        bp += 250;
    }

    // features as colored arcs
    for f in &rec.features {
        let color = PALETTE[f.color_idx % 8];
        let (a0, a1) = (f.start as f64, f.end as f64);
        let span = (a1 - a0).max(1.0);
        let segs = if span < 60.0 { 8 } else { 96 };
        let rr = r - 14.0;
        let mut prev: Option<Pos2> = None;
        for k in 0..=segs {
            let b = a0 + span * k as f64 / segs as f64;
            let ang = b * std::f64::consts::TAU / n as f64 - std::f64::consts::FRAC_PI_2;
            let p = Pos2::new(
                center.x + ang.cos() as f32 * rr,
                center.y + ang.sin() as f32 * rr,
            );
            if let Some(p0) = prev {
                painter.line_segment([p0, p], Stroke::new(7.0, color));
            }
            prev = Some(p);
        }
        // label at feature midpoint
        let mid = ((a0 + a1) / 2.0) * std::f64::consts::TAU / n as f64 - std::f64::consts::FRAC_PI_2;
        let lp = Pos2::new(
            center.x + mid.cos() as f32 * (r - 34.0),
            center.y + mid.sin() as f32 * (r - 34.0),
        );
        painter.text(lp, Align2::CENTER_CENTER, &f.label, FontId::proportional(10.0), color);
    }

    // enzyme cut ticks + labels (alternating radius to reduce overlap)
    let mut lab_i = 0usize;
    for (name, positions) in app.active_cuts() {
        let color = app.enz_color(&name);
        for &pos in &positions {
            let a = pos as f64 * std::f64::consts::TAU / n as f64 - std::f64::consts::FRAC_PI_2;
            let (s, c) = (a.sin(), a.cos());
            let p0 = Pos2::new(center.x + c as f32 * (r + 3.0), center.y + s as f32 * (r + 3.0));
            let lr = if lab_i % 2 == 0 { r + 12.0 } else { r + 26.0 };
            let p1 = Pos2::new(center.x + c as f32 * lr, center.y + s as f32 * lr);
            painter.line_segment([p0, p1], Stroke::new(1.2, color));
            if positions.len() <= 3 {
                painter.text(p1, Align2::CENTER_CENTER, &name, FontId::proportional(9.0), color);
            }
            lab_i += 1;
        }
    }

    // center caption
    painter.text(center, Align2::CENTER_CENTER, rec.name.clone(), FontId::proportional(16.0), FG);
    painter.text(
        Pos2::new(center.x, center.y + 20.0),
        Align2::CENTER_CENTER,
        format!("{} bp · {}", n, rec.topology),
        FontId::proportional(10.0),
        FG_DIM,
    );
}

// ---------------- linear map ----------------

pub fn draw_linear(ui: &mut egui::Ui, app: &mut App) {
    let (rect, _resp) = ui.allocate_exact_size(ui.available_size(), Sense::hover());
    let painter = ui.painter().clone();
    let rec = &app.record;
    let n = rec.seq.len().max(1);

    let pad_l = 16.0;
    let pad_r = 16.0;
    let w = (rect.width() - pad_l - pad_r).max(50.0);
    let x = |bp: usize| -> f32 { rect.left() + pad_l + bp as f32 * w / n as f32 };

    // enzyme tick bands (two staggered rows above the element band)
    let band_y = rect.top() + 54.0;
    let mut row = 0usize;
    for (name, positions) in app.active_cuts() {
        let color = app.enz_color(&name);
        for &pos in &positions {
            let px = x(pos);
            let top = band_y - 30.0 + if row % 2 == 0 { 0.0 } else { 14.0 };
            painter.line_segment(
                [Pos2::new(px, top), Pos2::new(px, top + 12.0)],
                Stroke::new(1.2, color),
            );
            painter.text(
                Pos2::new(px + 2.0, top + 6.0),
                Align2::LEFT_CENTER,
                &name,
                FontId::proportional(9.0),
                color,
            );
            row += 1;
        }
    }

    // element (feature) band
    let eb_top = band_y;
    let eb_h = 16.0;
    for f in &rec.features {
        let color = PALETTE[f.color_idx % 8];
        let x0 = x(f.start);
        let x1 = x(f.end);
        let r = Rect::from_min_max(
            Pos2::new(x0.min(x1), eb_top),
            Pos2::new(x0.max(x1), eb_top + eb_h),
        );
        painter.rect_filled(r, egui::Rounding::same(2.0), color);
        // strand arrow tip
        let tip_x = if f.strand == 1 { r.right() } else { r.left() };
        let cy = r.top() + eb_h / 2.0;
        let dir: f32 = if f.strand == 1 { 1.0 } else { -1.0 };
        painter.line_segment(
            [Pos2::new(tip_x - dir * 6.0, cy - 4.0), Pos2::new(tip_x, cy)],
            Stroke::new(1.5, Color32::BLACK),
        );
        painter.line_segment(
            [Pos2::new(tip_x - dir * 6.0, cy + 4.0), Pos2::new(tip_x, cy)],
            Stroke::new(1.5, Color32::BLACK),
        );
        // label
        painter.text(
            Pos2::new(r.left() + 3.0, r.top() - 8.0),
            Align2::LEFT_BOTTOM,
            &f.label,
            FontId::proportional(9.0),
            color,
        );
    }
    // backbone line of the element band
    painter.line_segment(
        [Pos2::new(rect.left() + pad_l, eb_top + eb_h + 6.0), Pos2::new(rect.right() - pad_r, eb_top + eb_h + 6.0)],
        Stroke::new(1.0, BORDER),
    );

    // ruler: number every 500 bp (matches web layout: 501, 1001, …)
    let ry = eb_top + eb_h + 10.0;
    let step = if n > 6000 { 1000 } else { 500 };
    let mut b = 0usize;
    while b < n {
        let px = x(b);
        painter.line_segment([Pos2::new(px, ry), Pos2::new(px, ry + 5.0)], Stroke::new(1.0, RULER));
        if b % step == 0 {
            painter.text(Pos2::new(px, ry + 9.0), Align2::LEFT_TOP, format!("{}", b + 1), FontId::proportional(9.0), RULER);
        }
        b += step / 5;
    }

    // selection overlay
    if let Some((s, e)) = app.selection_range() {
        let (a, b) = if s <= e { (s, e) } else { (e, s) };
        let sel = Rect::from_min_max(Pos2::new(x(a), band_y - 34.0), Pos2::new(x(b + 1), ry));
        painter.rect_filled(sel, egui::Rounding::same(0.0), Color32::from_rgba_premultiplied(0, 183, 195, 24));
    }

    painter.text(
        Pos2::new(rect.left() + pad_l, rect.top() + 6.0),
        Align2::LEFT_TOP,
        format!("{} — 线性图谱 · {} bp", rec.name, n),
        FontId::proportional(12.0),
        FG,
    );
}

// ---------------- sequence view ----------------

pub fn draw_sequence(ui: &mut egui::Ui, app: &mut App) {
    let rec = app.record.clone();
    let n = rec.seq.len();
    let per_line = 60usize;
    let char_w = 9.0;
    let line_h = 18.0;
    let pad_l = 60.0;
    let show_tr = app.show_translation;

    let fonts_h = line_h * (if show_tr { 2.0 } else { 1.0 }) + 6.0;
    let total_h = (n / per_line + 1) as f32 * fonts_h;
    let (rect, resp) = ui.allocate_exact_size(
        Vec2::new(ui.available_width().max(200.0), total_h + 8.0),
        Sense::click_and_drag(),
    );
    let painter = ui.painter().clone();

    // enzyme highlight set: (start, len, color)
    let mut hl: Vec<(usize, usize, Color32)> = Vec::new();
    for (name, positions) in app.active_cuts() {
        let color = app.enz_color(&name);
        let m = app.enzyme_by_name(&name).map(|e| e.site.len()).unwrap_or(6);
        for &pos in &positions {
            hl.push((pos, m, color));
        }
    }

    let x_of = |col: usize| -> f32 { rect.left() + pad_l + col as f32 * char_w };
    let y_of = |row: usize| -> f32 { rect.top() + row as f32 * fonts_h + 4.0 };

    // selection via pointer
    let bp_at = |p: Pos2| -> usize {
        let col_f = ((p.x - rect.left() - pad_l) / char_w).floor().max(0.0) as usize;
        let row = ((p.y - rect.top() - 4.0) / fonts_h).max(0.0) as usize;
        (row * per_line + col_f).min(n - 1)
    };
    if n > 0 {
        if let Some(p) = resp.interact_pointer_pos() {
            if resp.drag_started() {
                let b = bp_at(p);
                app.sel_anchor = Some(b);
                app.selection = Some((b, b));
            } else if resp.dragged() {
                if let Some(a) = app.sel_anchor {
                    app.selection = Some((a, bp_at(p)));
                }
            }
        }
    }

    let seq = &rec.seq;
    let mut row = 0usize;
    let mut off = 0usize;
    while off < n {
        let y = y_of(row);
        painter.text(
            Pos2::new(rect.left() + 4.0, y + line_h / 2.0),
            Align2::LEFT_CENTER,
            format!("{:>6}", off + 1),
            FontId::monospace(10.0),
            FG_DIM,
        );
        for col in 0..per_line {
            let i = off + col;
            if i >= n {
                break;
            }
            let x = x_of(col);
            let b = seq[i];
            let color = match b {
                b'A' => Color32::from_rgb(120, 200, 255),
                b'T' | b'U' => Color32::from_rgb(255, 140, 120),
                b'G' => Color32::from_rgb(160, 220, 130),
                b'C' => Color32::from_rgb(250, 200, 100),
                _ => FG_DIM,
            };
            // enzyme highlight background
            for (s, m, c) in &hl {
                let in_range = (i >= *s && i < *s + *m) || (*s + *m > n && i < (*s + *m) % n);
                if in_range {
                    let r = Rect::from_min_max(Pos2::new(x - 1.0, y), Pos2::new(x + char_w, y + line_h));
                    painter.rect_filled(r, egui::Rounding::same(0.0), *c);
                }
            }
            painter.text(
                Pos2::new(x + char_w / 2.0, y + line_h / 2.0),
                Align2::CENTER_CENTER,
                (b as char).to_string(),
                FontId::monospace(11.0),
                color,
            );
            // every-10 separator
            if col == 9 || col == 19 || col == 29 || col == 39 || col == 49 {
                painter.line_segment(
                    [Pos2::new(x + char_w, y), Pos2::new(x + char_w, y + line_h)],
                    Stroke::new(0.5, Color32::from_rgb(35, 41, 48)),
                );
            }
        }
        // translation row
        if show_tr {
            let ty = y + line_h + 2.0;
            let prot = translate(&seq[off..(off + per_line).min(n)]);
            for (k, aa) in prot.iter().enumerate() {
                let x = x_of(k * 3) + char_w * 1.5;
                painter.text(
                    Pos2::new(x, ty + 8.0),
                    Align2::CENTER_CENTER,
                    (*aa as char).to_string(),
                    FontId::monospace(10.0),
                    if *aa == b'*' { Color32::from_rgb(248, 81, 73) } else { FG_DIM },
                );
            }
        }
        row += 1;
        off += per_line;
    }

    // selection overlay
    if let Some((s, e)) = app.selection {
        if n > 0 {
            let (a, b) = if s <= e { (s, e) } else { (e, s) };
            let r0 = a / per_line;
            let r1 = b / per_line;
            for r in r0..=r1 {
                let y = y_of(r);
                let c0 = if r == r0 { a % per_line } else { 0 };
                let c1 = if r == r1 { b % per_line + 1 } else { per_line };
                let sel = Rect::from_min_max(Pos2::new(x_of(c0), y), Pos2::new(x_of(c1.max(c0 + 1)), y + line_h));
                painter.rect_filled(sel, egui::Rounding::same(0.0), Color32::from_rgba_premultiplied(0, 183, 195, 30));
            }
        }
    }
}

// ---------------- enzyme panel ----------------

pub fn draw_enzymes(ui: &mut egui::Ui, app: &mut App) {
    ui.horizontal(|ui| {
        ui.label("过滤:");
        ui.add(
            egui::TextEdit::singleline(&mut app.enz_filter)
                .hint_text("酶名…")
                .desired_width(140.0),
        );
        if ui.button("全选常用").clicked() {
            for n in ["EcoRI", "BamHI", "HindIII", "XhoI", "SalI", "PstI", "KpnI", "XbaI", "SacI", "SmaI"] {
                app.enzymes_on.insert(n.to_string());
            }
        }
        if ui.button("清空").clicked() {
            app.enzymes_on.clear();
        }
    });
    ui.add_space(4.0);
    egui::ScrollArea::vertical().show(ui, |ui| {
        egui::Grid::new("enz-grid").num_columns(4).spacing([24.0, 3.0]).show(ui, |ui| {
            ui.strong("启用");
            ui.strong("酶");
            ui.strong("识别序列");
            ui.strong("切点");
            ui.end_row();
            for e in crate::enzymes::CATALOG {
                if !app.enz_filter.is_empty()
                    && !e.name.to_lowercase().contains(&app.enz_filter.to_lowercase())
                {
                    continue;
                }
                let mut on = app.enzymes_on.contains(e.name);
                if ui.checkbox(&mut on, "").changed() {
                    if on {
                        app.enzymes_on.insert(e.name.to_string());
                    } else {
                        app.enzymes_on.remove(e.name);
                    }
                }
                ui.label(e.name);
                ui.monospace(e.site);
                let n = app.cut_count(e.name);
                ui.label(if n == 0 { "—".to_string() } else { format!("{}", n) });
                ui.end_row();
            }
        });
    });
}

// ---------------- notes / features panel ----------------

pub fn draw_notes(ui: &mut egui::Ui, app: &App) {
    ui.heading(format!("{} — 注释信息", app.record.name));
    ui.add_space(6.0);
    egui::Grid::new("feat-grid").num_columns(5).spacing([18.0, 4.0]).show(ui, |ui| {
        ui.strong("特征");
        ui.strong("起点");
        ui.strong("终点");
        ui.strong("方向");
        ui.strong("长度");
        ui.end_row();
        for f in &app.record.features {
            let color = PALETTE[f.color_idx % 8];
            ui.colored_label(color, &f.label);
            ui.label(format!("{}", f.start + 1));
            ui.label(format!("{}", f.end));
            ui.label(if f.strand == 1 { "→".to_string() } else { "←".to_string() });
            ui.label(format!("{} bp", f.end - f.start));
            ui.end_row();
        }
    });
    if app.record.features.is_empty() {
        ui.label("（当前序列无注释 — 可打开 GenBank 文件载入特征）");
    }
    ui.add_space(10.0);
    ui.separator();
    ui.label("反向互补预览 (前 60 bp):");
    ui.monospace(
        String::from_utf8_lossy(&revcomp(&app.record.seq[..app.record.seq.len().min(60)]).to_vec())
            .to_string(),
    );
}

pub fn tab_label(t: Tab) -> &'static str {
    match t {
        Tab::Circular => "环状图谱",
        Tab::Linear => "线性图谱",
        Tab::Sequence => "序列视图",
        Tab::Enzymes => "酶切面板",
        Tab::Notes => "注释信息",
        Tab::Gel => "电泳模拟",
    }
}
