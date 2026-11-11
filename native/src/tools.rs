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

// ---------------------------------------------------------------- P2: protein page

pub fn draw_protein(ui: &mut egui::Ui, app: &App) {
    use crate::protein as pr;
    egui::ScrollArea::vertical().show(ui, |ui| {
        ui.heading(format!("{} — 蛋白页", app.record.name));
        ui.add_space(4.0);

        // selection → protein fragment analysis (联动)
        if let Some((a, b)) = app.selection_range() {
            let frag = &app.record.seq[a..=b];
            let prot = crate::seq::translate(frag);
            ui.label(format!(
                "选中区域 {}..{} ({} bp) → 翻译 {} aa:",
                a + 1, b + 1, b - a + 1, prot.len()
            ));
            ui.monospace(String::from_utf8_lossy(&prot).to_string());
            ui.add_space(2.0);
            props_grid(ui, &prot);
            ui.add_space(6.0);
            ui.separator();
            ui.add_space(6.0);
        }

        // whole-sequence frame +1
        let full = crate::seq::translate(&app.record.seq);
        ui.strong("全长翻译 (框架 +1):");
        ui.monospace(wrap60(&full));
        ui.add_space(4.0);
        props_grid(ui, &full);

        ui.add_space(8.0);
        ui.separator();
        ui.strong("六框翻译 — ORF 摘要 (≥30 aa):");
        ui.add_space(4.0);
        egui::Grid::new("six-frame").num_columns(4).spacing([16.0, 3.0]).show(ui, |ui| {
            ui.strong("框架");
            ui.strong("ORF 数");
            ui.strong("最长 (aa)");
            ui.strong("最长 ORF 位置");
            ui.end_row();
            for (frame, prot) in pr::six_frame(&app.record.seq) {
                let orfs = orfs_from_protein(&prot);
                let max = orfs.iter().map(|(a, b)| b - a).max().unwrap_or(0);
                let pos = orfs
                    .iter()
                    .max_by_key(|(a, b)| b - a)
                    .map(|(a, b)| format!("{}..{}", a + 1, b))
                    .unwrap_or_else(|| "—".into());
                ui.label(format!("{:+}", frame));
                ui.label(format!("{}", orfs.len()));
                ui.label(if max == 0 { "—".into() } else { format!("{}", max) });
                ui.label(pos);
                ui.end_row();
            }
        });
    });
}

fn wrap60(prot: &[u8]) -> String {
    let s = String::from_utf8_lossy(prot).to_string();
    s.as_bytes()
        .chunks(60)
        .map(|c| String::from_utf8_lossy(c).to_string())
        .collect::<Vec<_>>()
        .join("\n")
}

fn props_grid(ui: &mut egui::Ui, prot: &[u8]) {
    use crate::protein as pr;
    egui::Grid::new("props").num_columns(2).spacing([10.0, 2.0]).show(ui, |ui| {
        ui.label("分子量");
        ui.label(format!("{:.2} kDa", pr::mw(prot)));
        ui.end_row();
        ui.label("等电点 pI");
        ui.label(format!("{:.2}", pr::pi(prot)));
        ui.end_row();
        ui.label("GRAVY 疏水指数");
        ui.label(format!("{:+.3}", pr::gravy(prot)));
        ui.end_row();
        ui.label("A280 消光系数");
        ui.label(format!("{} M⁻¹cm⁻¹", pr::extinction_280(prot)));
        ui.end_row();
        ui.label("长度");
        ui.label(format!("{} aa", prot.len()));
        ui.end_row();
    });
}

fn orfs_from_protein(prot: &[u8]) -> Vec<(usize, usize)> {
    let mut out = Vec::new();
    let mut start: Option<usize> = None;
    for (i, &a) in prot.iter().enumerate() {
        if a == b'M' && start.is_none() {
            start = Some(i);
        }
        if a == b'*' {
            if let Some(s) = start {
                if i - s >= 30 {
                    out.push((s, i));
                }
            }
            start = None;
        }
    }
    out
}

// ---------------------------------------------------------------- P2: 3D structure

pub fn draw_structure(ui: &mut egui::Ui, app: &mut App) {
    let (rect, resp) = ui.allocate_exact_size(ui.available_size(), Sense::drag());
    // rotation via drag, zoom via wheel
    if resp.dragged() {
        let d = resp.drag_delta();
        app.rot_y += d.x * 0.01;
        app.rot_x += d.y * 0.01;
    }
    let scroll = ui.input(|i| i.raw_scroll_delta.y);
    if scroll != 0.0 {
        app.zoom = (app.zoom * (1.0 - scroll * 0.001)).clamp(0.2, 5.0);
    }
    let painter = ui.painter().clone();

    let pdb = match &app.pdb {
        Some(p) => p.clone(),
        None => {
            painter.text(
                rect.center(),
                Align2::CENTER_CENTER,
                "未载入结构 — 菜单「分析与多视图 → 载入 PDB…」\n或使用内置演示结构",
                FontId::proportional(12.0),
                Color32::from_rgb(125, 139, 152),
            );
            return;
        }
    };

    let n = pdb.atoms.len();
    let cx = rect.center();
    let scale = (rect.width().min(rect.height()) * 0.4) / pdb.radius() * app.zoom;
    let c = pdb.center();

    // rotation matrices
    let (sx, cxs) = app.rot_x.sin_cos();
    let (sy, cys) = app.rot_y.sin_cos();

    let project = |a: &crate::pdb::Atom| -> (Pos2, f32) {
        // translate to center
        let (x, y, z) = (a.x - c[0], a.y - c[1], a.z - c[2]);
        // rotate X
        let (y1, z1) = (y * cxs - z * sx, y * sx + z * cxs);
        // rotate Y
        let (x2, z2) = (x * cys + z1 * sy, -x * sy + z1 * cys);
        // weak perspective
        let persp = 1.0 / (1.0 + z2 / (pdb.radius() * 4.0));
        (
            Pos2::new(cx.x + x2 * scale * persp, cx.y - y1 * scale * persp),
            z2,
        )
    };

    let pts: Vec<(Pos2, f32)> = pdb.atoms.iter().map(project).collect();

    // backbone trace, painter's-depth sorted segments
    let mut segs: Vec<(f32, usize)> = (0..n.saturating_sub(1))
        .map(|i| ((pts[i].1 + pts[i + 1].1) * 0.5, i))
        .collect();
    segs.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());

    for (depth, i) in segs {
        let t = i as f32 / n as f32;
        // rainbow along the chain
        let color = Color32::from_rgb(
            (255.0 * (1.0 - (t * 2.0 - 1.0).abs())) as u8,
            (255.0 * (1.0 - (t * 2.0).abs())) as u8,
            (255.0 * ((t * 2.0).min(1.0))) as u8,
        );
        // dim by depth
        let shade = 1.0 / (1.0 + depth / pdb.radius()).clamp(0.5, 2.0);
        let color = Color32::from_rgb(
            (color.r() as f32 * shade) as u8,
            (color.g() as f32 * shade) as u8,
            (color.b() as f32 * shade) as u8,
        );
        let w = (2.5 * shade).clamp(1.0, 4.0);
        painter.line_segment([pts[i].0, pts[i + 1].0], Stroke::new(w, color));
    }

    // N / C termini markers
    if let (Some(first), Some(last)) = (pts.first(), pts.last()) {
        painter.circle_filled(first.0, 4.0, Color32::from_rgb(120, 200, 255));
        painter.text(first.0 + Vec2::new(8.0, -8.0), Align2::LEFT_CENTER, "N", FontId::proportional(10.0), Color32::from_rgb(120, 200, 255));
        painter.circle_filled(last.0, 4.0, Color32::from_rgb(248, 81, 73));
        painter.text(last.0 + Vec2::new(8.0, 8.0), Align2::LEFT_CENTER, "C", FontId::proportional(10.0), Color32::from_rgb(248, 81, 73));
    }

    painter.text(
        Pos2::new(rect.left() + 8.0, rect.top() + 6.0),
        Align2::LEFT_TOP,
        format!("{} · {} 残基 · 拖动旋转 / 滚轮缩放", pdb.name, n),
        FontId::proportional(11.0),
        Color32::from_rgb(200, 211, 221),
    );
}

pub fn pdb_paste_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.pdb_paste_open;
    if !open {
        return;
    }
    egui::Window::new("载入 PDB 结构")
        .open(&mut open)
        .default_width(520.0)
        .show(ctx, |ui| {
            ui.label("粘贴 PDB 文本 (ATOM 记录, 取首条链 CA 原子):");
            ui.add_space(4.0);
            egui::TextEdit::multiline(&mut app.pdb_paste_text)
                .desired_rows(8)
                .desired_width(f32::INFINITY)
                .show(ui);
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                if ui.button("载入").clicked() {
                    match crate::pdb::parse(&app.pdb_paste_text) {
                        Ok(p) => {
                            app.pdb = Some(p);
                            app.pdb_paste_open = false;
                            app.status = "结构已载入".into();
                        }
                        Err(e) => app.status = format!("PDB 解析失败: {}", e),
                    }
                }
                if ui.button("使用演示结构").clicked() {
                    app.pdb = Some(crate::pdb::Pdb::demo_helix_bundle());
                    app.pdb_paste_open = false;
                    app.status = "已载入演示 helix-turn-helix".into();
                }
                if ui.button("取消").clicked() {
                    app.pdb_paste_open = false;
                }
            });
        });
    app.pdb_paste_open = open;
}

// ---------------------------------------------------------------- P3: project hub

pub fn draw_project(ui: &mut egui::Ui, app: &mut App) {
    let presenting = app.project.presenting;
    if presenting {
        // 演示模式：全屏文档放映
        let doc = &app.project.docs[app.project.current_doc.min(app.project.docs.len() - 1)];
        ui.vertical_centered(|ui| {
            ui.add_space(16.0);
            ui.heading(egui::RichText::new(&doc.title).size(24.0));
            ui.add_space(10.0);
            ui.separator();
            ui.add_space(10.0);
            egui::ScrollArea::vertical().show(ui, |ui| {
                for line in doc.body.lines() {
                    ui.label(egui::RichText::new(line).size(18.0));
                }
            });
        });
        return;
    }

    // left sidebar: documents
    egui::SidePanel::left("proj-docs").default_width(170.0).show_inside(ui, |ui| {
        ui.heading("文档");
        let mut cur = app.project.current_doc;
        for (i, d) in app.project.docs.iter().enumerate() {
            if ui
                .selectable_label(cur == i, format!("📄 {}", d.title))
                .clicked()
            {
                cur = i;
            }
        }
        app.project.current_doc = cur.min(app.project.docs.len() - 1);
        ui.add_space(6.0);
        if ui.button("＋ 新文档").clicked() {
            app.project.add_doc(&format!("文档 {}", app.project.docs.len() + 1));
        }
        ui.add_space(12.0);
        ui.separator();
        ui.heading("附件");
        for a in &app.project.attachments {
            ui.label(format!("📎 {} ({} B)", a.name, a.size));
        }
        if ui.button("＋ 添加文本附件").clicked() {
            app.attach_open = true;
        }
    });

    // right panel: properties + log
    egui::SidePanel::right("proj-props").default_width(230.0).show_inside(ui, |ui| {
        ui.heading("项目属性");
        egui::Grid::new("proj-meta").num_columns(1).show(ui, |ui| {
            ui.label("项目名称:");
            ui.text_edit_singleline(&mut app.project.name);
            ui.label("负责人 (PI):");
            ui.text_edit_singleline(&mut app.project.pi);
            ui.label("描述:");
            ui.add(
                egui::TextEdit::multiline(&mut app.project.description)
                    .desired_rows(3)
                    .desired_width(f32::INFINITY),
            );
        });
        ui.add_space(8.0);
        ui.separator();
        ui.heading("操作日志");
        egui::ScrollArea::vertical().show(ui, |ui| {
            for l in app.project.logs.iter().rev() {
                ui.label(
                    egui::RichText::new(format!("[{}] {}", l.ts, l.text))
                        .small()
                        .color(Color32::from_rgb(125, 139, 152)),
                );
            }
        });
        ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
            if ui.button("▶ 演示模式").clicked() {
                app.project.presenting = true;
                app.project.add_log_entry("进入演示模式");
                app.tab = Tab::Project;
            }
        });
    });

    // central: current document editor
    egui::CentralPanel::default().show_inside(ui, |ui| {
        if app.project.docs.is_empty() {
            ui.label("无文档 — 左侧「＋ 新文档」创建");
            return;
        }
        let i = app.project.current_doc.min(app.project.docs.len() - 1);
        let title = app.project.docs[i].title.clone();
        ui.heading(format!("📄 {}", title));
        ui.add_space(4.0);
        let mut body = app.project.docs[i].body.clone();
        let edited = egui::TextEdit::multiline(&mut body)
            .desired_rows(14)
            .desired_width(f32::INFINITY)
            .show(ui)
            .response.changed();
        if edited {
            app.project.docs[i].body = body;
            app.project.docs[i].updated = crate::project::now();
        }
        ui.add_space(4.0);
        ui.label(
            egui::RichText::new(format!("最后修改: {}", app.project.docs[i].updated))
                .small()
                .color(Color32::from_rgb(125, 139, 152)),
        );
    });
}

pub fn attach_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.attach_open;
    if !open {
        return;
    }
    egui::Window::new("添加文本附件")
        .open(&mut open)
        .default_width(460.0)
        .show(ctx, |ui| {
            ui.label("附件名:");
            ui.text_edit_singleline(&mut app.attach_name);
            ui.label("内容 (纯文本):");
            egui::TextEdit::multiline(&mut app.attach_text)
                .desired_rows(6)
                .desired_width(f32::INFINITY)
                .show(ui);
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if ui.button("添加").clicked() {
                    if !app.attach_text.is_empty() {
                        let name = if app.attach_name.is_empty() {
                            format!("attachment-{}.txt", app.project.attachments.len() + 1)
                        } else {
                            app.attach_name.clone()
                        };
                        app.project.add_attachment_text(&name, &app.attach_text);
                        app.status = format!("附件「{}」已添加", name);
                    }
                    app.attach_open = false;
                    app.attach_text.clear();
                    app.attach_name.clear();
                }
                if ui.button("取消").clicked() {
                    app.attach_open = false;
                }
            });
        });
    app.attach_open = open;
}

pub fn new_project_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.newproj_open;
    if !open {
        return;
    }
    egui::Window::new("新建项目")
        .open(&mut open)
        .default_width(420.0)
        .show(ctx, |ui| {
            ui.label("项目名称:");
            ui.text_edit_singleline(&mut app.newproj_name);
            ui.label("负责人 (PI):");
            ui.text_edit_singleline(&mut app.newproj_pi);
            ui.label("描述:");
            egui::TextEdit::multiline(&mut app.newproj_desc)
                .desired_rows(3)
                .desired_width(f32::INFINITY)
                .show(ui);
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if ui.button("创建").clicked() {
                    let mut p = crate::project::Project::new(&app.newproj_name);
                    p.pi = app.newproj_pi.clone();
                    p.description = app.newproj_desc.clone();
                    p.add_log_entry("创建项目");
                    app.project = p;
                    app.tab = Tab::Project;
                    app.status = format!("项目「{}」已创建", app.newproj_name);
                    app.newproj_open = false;
                    app.newproj_name.clear();
                    app.newproj_pi.clear();
                    app.newproj_desc.clear();
                }
                if ui.button("取消").clicked() {
                    app.newproj_open = false;
                }
            });
        });
    app.newproj_open = open;
}

pub fn import_project_window(ctx: &egui::Context, app: &mut App) {
    let mut open = app.proj_import_open;
    if !open {
        return;
    }
    egui::Window::new("从 JSON 导入项目")
        .open(&mut open)
        .default_width(520.0)
        .show(ctx, |ui| {
            ui.label("粘贴项目 JSON (由「导出项目 JSON」生成):");
            egui::TextEdit::multiline(&mut app.proj_import_text)
                .desired_rows(8)
                .desired_width(f32::INFINITY)
                .show(ui);
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                if ui.button("导入").clicked() {
                    match crate::project::Project::from_json(&app.proj_import_text) {
                        Ok(p) => {
                            app.project = p;
                            app.tab = Tab::Project;
                            app.status = "项目已导入".into();
                            app.proj_import_open = false;
                        }
                        Err(e) => app.status = format!("导入失败: {}", e),
                    }
                }
                if ui.button("取消").clicked() {
                    app.proj_import_open = false;
                }
            });
        });
    app.proj_import_open = open;
}
