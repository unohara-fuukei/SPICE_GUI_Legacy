//! Application shell: menus, tabs, status bar, shared state.

use crate::enzymes::{cut_sites, Enzyme, CATALOG};
use crate::seq::{parse_auto, revcomp, SeqRecord};
use crate::views;
use egui::{Color32, Context};
use std::collections::BTreeSet;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Tab {
    Circular,
    Linear,
    Sequence,
    Enzymes,
    Notes,
    Gel,
    Protein,
    Structure,
}

const ENZ_COLORS: [Color32; 10] = [
    Color32::from_rgb(0, 183, 195),
    Color32::from_rgb(63, 185, 80),
    Color32::from_rgb(240, 136, 62),
    Color32::from_rgb(210, 168, 255),
    Color32::from_rgb(227, 179, 81),
    Color32::from_rgb(248, 81, 73),
    Color32::from_rgb(88, 166, 255),
    Color32::from_rgb(63, 226, 214),
    Color32::from_rgb(255, 120, 190),
    Color32::from_rgb(180, 200, 120),
];

pub struct App {
    pub record: SeqRecord,
    pub tab: Tab,
    pub enzymes_on: BTreeSet<String>,
    pub enz_filter: String,
    pub selection: Option<(usize, usize)>,
    pub sel_anchor: Option<usize>,
    pub show_translation: bool,
    pub status: String,
    pub about_open: bool,
    pub paste_open: bool,
    pub paste_text: String,
    // P1 tool states
    pub primer_open: bool,
    pub primer_start: usize,
    pub primer_end: usize,
    pub primer_tm: f32,
    pub primer_result: Option<(crate::cloning::Primer, crate::cloning::Primer, usize)>,
    pub dd_open: bool,
    pub gibson_open: bool,
    pub gibson_cut: usize,
    pub gibson_overlap: usize,
    pub gibson_insert_text: String,
    pub gibson_result: Option<crate::cloning::GibsonResult>,
    pub gg_open: bool,
    // P2 states
    pub pdb: Option<crate::pdb::Pdb>,
    pub rot_x: f32,
    pub rot_y: f32,
    pub zoom: f32,
    pub pdb_paste_open: bool,
    pub pdb_paste_text: String,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>) -> Self {
        install_fonts(cc);
        install_dark_visuals(&cc.egui_ctx);
        let mut enzymes_on = BTreeSet::new();
        for n in ["EcoRI", "BamHI", "HindIII", "XhoI", "SalI", "PstI", "KpnI", "XbaI"] {
            enzymes_on.insert(n.to_string());
        }
        Self {
            record: SeqRecord::demo_puc19(),
            tab: Tab::Circular,
            enzymes_on,
            enz_filter: String::new(),
            selection: None,
            sel_anchor: None,
            show_translation: false,
            status: "已载入演示质粒 pUC19".into(),
            about_open: false,
            paste_open: false,
            paste_text: String::new(),
            primer_open: false,
            primer_start: 100,
            primer_end: 700,
            primer_tm: 60.0,
            primer_result: None,
            dd_open: false,
            gibson_open: false,
            gibson_cut: 396,
            gibson_overlap: 25,
            gibson_insert_text: String::new(),
            gibson_result: None,
            gg_open: false,
            pdb: None,
            rot_x: 0.5,
            rot_y: 0.3,
            zoom: 1.0,
            pdb_paste_open: false,
            pdb_paste_text: String::new(),
        }
    }

    pub fn active_cuts(&self) -> Vec<(String, Vec<usize>)> {
        let mut out = Vec::new();
        for name in &self.enzymes_on {
            if let Some(e) = CATALOG.iter().find(|e| e.name == *name) {
                let v = cut_sites(&self.record.seq, e);
                if !v.is_empty() {
                    out.push((name.clone(), v));
                }
            }
        }
        out
    }

    pub fn enzyme_by_name(&self, name: &str) -> Option<&'static Enzyme> {
        CATALOG.iter().find(|e| e.name == name)
    }

    pub fn cut_count(&self, name: &str) -> usize {
        self.enzyme_by_name(name)
            .map(|e| cut_sites(&self.record.seq, e).len())
            .unwrap_or(0)
    }

    pub fn enz_color(&self, name: &str) -> Color32 {
        let mut h = 0usize;
        for b in name.bytes() {
            h = h.wrapping_mul(31).wrapping_add(b as usize);
        }
        ENZ_COLORS[h % ENZ_COLORS.len()]
    }

    pub fn selection_range(&self) -> Option<(usize, usize)> {
        self.selection.map(|(a, b)| if a <= b { (a, b) } else { (b, a) })
    }

    fn open_file_dialog(&mut self) {
        #[cfg(not(feature = "file-dialog"))]
        {
            self.status = "此构建未启用系统文件对话框 (feature file-dialog)；可用「编辑 → 从文本导入序列」".into();
            self.paste_open = true;
        }
        #[cfg(feature = "file-dialog")]
        {
        if let Some(path) = rfd::FileDialog::new()
            .add_filter("序列文件 (FASTA / GenBank)", &["fasta", "fa", "fna", "gb", "gbk", "genbank", "txt"])
            .pick_file()
        {
            match std::fs::read_to_string(&path) {
                Ok(text) => match parse_auto(&text) {
                    Ok(mut rec) => {
                        if rec.features.is_empty() && rec.topology != "linear" {
                            // keep it as-is
                        }
                        if rec.name == "unnamed" {
                            rec.name = path
                                .file_stem()
                                .map(|s| s.to_string_lossy().to_string())
                                .unwrap_or_else(|| "unnamed".into());
                        }
                        self.record = rec;
                        self.selection = None;
                        self.status = "文件已载入".into();
                    }
                    Err(e) => self.status = format!("解析失败: {}", e),
                },
                Err(e) => self.status = format!("读取失败: {}", e),
            }
        }
        }
    }

    fn copy_to_clipboard(&self, ctx: &Context, s: String) {
        ctx.copy_text(s);
    }
}

fn install_fonts(cc: &eframe::CreationContext<'_>) {
    let mut fonts = egui::FontDefinitions::default();
    let bytes = include_bytes!("../assets/NotoSansSC-Subset.ttf").to_vec();
    fonts
        .font_data
        .insert("noto_sc".into(), egui::FontData::from_owned(bytes));
    // put CJK after the default latin font so latin glyphs keep egui's metrics
    fonts
        .families
        .get_mut(&egui::FontFamily::Proportional)
        .unwrap()
        .push("noto_sc".into());
    fonts
        .families
        .get_mut(&egui::FontFamily::Monospace)
        .unwrap()
        .push("noto_sc".into());
    cc.egui_ctx.set_fonts(fonts);
}

fn install_dark_visuals(ctx: &Context) {
    let mut vis = egui::Visuals::dark();
    vis.panel_fill = Color32::from_rgb(13, 17, 23);
    vis.window_fill = Color32::from_rgb(22, 27, 34);
    vis.extreme_bg_color = Color32::from_rgb(9, 12, 16);
    vis.faint_bg_color = Color32::from_rgb(22, 27, 34);
    vis.widgets.noninteractive.bg_fill = Color32::from_rgb(22, 27, 34);
    vis.widgets.inactive.bg_fill = Color32::from_rgb(33, 38, 45);
    vis.selection.bg_fill = Color32::from_rgba_premultiplied(0, 120, 130, 120);
    vis.hyperlink_color = Color32::from_rgb(0, 183, 195);
    ctx.set_visuals(vis);
}

impl eframe::App for App {
    fn update(&mut self, ctx: &Context, _frame: &mut eframe::Frame) {
        // ---------- top menu bar ----------
        egui::TopBottomPanel::top("menubar").show(ctx, |ui| {
            egui::menu::bar(ui, |ui| {
                ui.label(egui::RichText::new("SPICE").strong().color(Color32::from_rgb(0, 183, 195)));
                ui.separator();

                ui.menu_button("文件", |ui| {
                    if ui.button("打开序列文件…").clicked() {
                        ui.close_menu();
                        self.open_file_dialog();
                    }
                    if ui.button("从文本导入序列…").clicked() {
                        self.paste_open = true;
                        ui.close_menu();
                    }
                    if ui.button("载入演示质粒 pUC19").clicked() {
                        self.record = SeqRecord::demo_puc19();
                        self.selection = None;
                        self.status = "已载入演示质粒 pUC19".into();
                        ui.close_menu();
                    }
                    ui.separator();
                    if ui.button("复制全部序列").clicked() {
                        let s = String::from_utf8_lossy(&self.record.seq).to_string();
                        self.copy_to_clipboard(ctx, s);
                        self.status = "已复制全部序列".into();
                        ui.close_menu();
                    }
                    if ui.button("复制为 FASTA").clicked() {
                        let s = format!(
                            ">{}\n{}\n",
                            self.record.name,
                            String::from_utf8_lossy(&self.record.seq)
                        );
                        self.copy_to_clipboard(ctx, s);
                        self.status = "已复制 FASTA".into();
                        ui.close_menu();
                    }
                    ui.separator();
                    if ui.button("退出").clicked() {
                        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
                    }
                });

                ui.menu_button("编辑", |ui| {
                    let has_sel = self.selection.is_some();
                    if ui
                        .add_enabled(has_sel, egui::Button::new("复制选中区域"))
                        .clicked()
                    {
                        if let Some((a, b)) = self.selection_range() {
                            let s = String::from_utf8_lossy(&self.record.seq[a..=b]).to_string();
                            self.copy_to_clipboard(ctx, s);
                            self.status = format!("已复制 {} bp", b - a + 1);
                        }
                        ui.close_menu();
                    }
                    if ui.button("全选").clicked() {
                        let n = self.record.seq.len();
                        self.selection = Some((0, n - 1));
                        ui.close_menu();
                    }
                    ui.separator();
                    if ui.button("反向互补 (整条)").clicked() {
                        self.record.seq = revcomp(&self.record.seq);
                        // flip feature strands
                        let n = self.record.seq.len() as i64;
                        for f in &mut self.record.features {
                            let (s, e) = (f.start as i64, f.end as i64);
                            f.start = (n - e) as usize;
                            f.end = (n - s) as usize;
                            f.strand = -f.strand;
                        }
                        self.status = "序列已反向互补".into();
                        ui.close_menu();
                    }
                });

                ui.menu_button("分子克隆工具", |ui| {
                    if ui.button("酶切位点分析").clicked() {
                        self.tab = Tab::Enzymes;
                        ui.close_menu();
                    }
                    if ui.button("双酶切方案向导…").clicked() {
                        self.dd_open = true;
                        ui.close_menu();
                    }
                    if ui.button("PCR 引物设计…").clicked() {
                        if let Some((a, b)) = self.selection_range() {
                            self.primer_start = a;
                            self.primer_end = b + 1;
                        }
                        self.primer_open = true;
                        ui.close_menu();
                    }
                    if ui.button("Gibson 装配…").clicked() {
                        self.gibson_open = true;
                        ui.close_menu();
                    }
                    if ui.button("Golden Gate 装配…").clicked() {
                        self.gg_open = true;
                        ui.close_menu();
                    }
                    if ui.button("电泳模拟").clicked() {
                        self.tab = Tab::Gel;
                        ui.close_menu();
                    }
                });

                ui.menu_button("分析与多视图", |ui| {
                    if ui.button("环状图谱").clicked() {
                        self.tab = Tab::Circular;
                        ui.close_menu();
                    }
                    if ui.button("线性图谱").clicked() {
                        self.tab = Tab::Linear;
                        ui.close_menu();
                    }
                    if ui.button("序列视图").clicked() {
                        self.tab = Tab::Sequence;
                        ui.close_menu();
                    }
                    ui.separator();
                    ui.checkbox(&mut self.show_translation, "翻译框架 (+1)");
                    if ui.button("ORF 查找 (≥50 aa)").clicked() {
                        let orfs = crate::seq::find_orfs(&self.record.seq, 50);
                        if orfs.is_empty() {
                            self.status = "未发现 ≥50 aa 的 ORF".into();
                        } else {
                            let s: Vec<String> = orfs
                                .iter()
                                .take(8)
                                .map(|(a, b, st)| format!("{}..{} ({})", a + 1, b, if *st == 1 { "+" } else { "-" }))
                                .collect();
                            self.status = format!("ORF: {}（共 {} 个）", s.join(", "), orfs.len());
                        }
                        ui.close_menu();
                    }
                    if ui.button("蛋白页 (性质/六框翻译)").clicked() {
                        self.tab = Tab::Protein;
                        ui.close_menu();
                    }
                    if ui.button("3D 结构 (PDB)").clicked() {
                        self.tab = Tab::Structure;
                        ui.close_menu();
                    }
                    if ui.button("载入 PDB…").clicked() {
                        self.pdb_paste_open = true;
                        ui.close_menu();
                    }
                    if ui.button("GC 含量").clicked() {
                        let gc = crate::seq::gc_content(&self.record.seq);
                        self.status = format!("GC 含量: {:.1}%", gc);
                        ui.close_menu();
                    }
                });

                ui.menu_button("窗口与设置", |ui| {
                    if ui.button("重置视图").clicked() {
                        self.selection = None;
                        self.tab = Tab::Circular;
                        ui.close_menu();
                    }
                    ui.separator();
                    if ui.button("关于 SPICE Native").clicked() {
                        self.about_open = true;
                        ui.close_menu();
                    }
                });
            });
        });

        // ---------- status bar ----------
        egui::TopBottomPanel::bottom("statusbar").show(ctx, |ui| {
            ui.horizontal(|ui| {
                let gc = crate::seq::gc_content(&self.record.seq);
                ui.label(
                    egui::RichText::new(format!(
                        "{} · {} bp · GC {:.1}%",
                        self.record.name,
                        self.record.seq.len(),
                        gc
                    ))
                    .color(Color32::from_rgb(125, 139, 152)),
                );
                if let Some((a, b)) = self.selection_range() {
                    ui.label(
                        egui::RichText::new(format!("选中 {}..{} ({} bp)", a + 1, b + 1, b - a + 1))
                            .color(Color32::from_rgb(0, 183, 195)),
                    );
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(egui::RichText::new(&self.status).color(Color32::from_rgb(63, 185, 80)));
                });
            });
        });

        // ---------- tab strip ----------
        egui::TopBottomPanel::top("tabs").show(ctx, |ui| {
            ui.horizontal(|ui| {
                for t in [Tab::Circular, Tab::Linear, Tab::Sequence, Tab::Enzymes, Tab::Notes, Tab::Gel, Tab::Protein, Tab::Structure] {
                    if ui
                        .selectable_label(self.tab == t, views::tab_label(t))
                        .clicked()
                    {
                        self.tab = t;
                    }
                }
            });
        });

        // ---------- central view ----------
        egui::CentralPanel::default().show(ctx, |ui| {
            match self.tab {
                Tab::Circular => views::draw_circular(ui, self),
                Tab::Linear => views::draw_linear(ui, self),
                Tab::Sequence => {
                    egui::ScrollArea::both().show(ui, |ui| views::draw_sequence(ui, self));
                }
                Tab::Enzymes => views::draw_enzymes(ui, self),
                Tab::Notes => views::draw_notes(ui, self),
                Tab::Gel => crate::tools::draw_gel(ui, self),
                Tab::Protein => crate::tools::draw_protein(ui, self),
                Tab::Structure => crate::tools::draw_structure(ui, self),
            }
        });

        // ---------- P1 tool windows ----------
        crate::tools::primer_window(ctx, self);
        crate::tools::dd_window(ctx, self);
        crate::tools::gibson_window(ctx, self);
        crate::tools::gg_window(ctx, self);
        crate::tools::pdb_paste_window(ctx, self);

        // ---------- paste-import window ----------
        let mut paste_open = self.paste_open;
        if paste_open {
            egui::Window::new("从文本导入序列")
                .open(&mut paste_open)
                .default_width(520.0)
                .show(ctx, |ui| {
                    ui.label("粘贴 FASTA / GenBank 或裸序列文本:");
                    ui.add_space(4.0);
                    egui::TextEdit::multiline(&mut self.paste_text)
                        .desired_rows(8)
                        .desired_width(f32::INFINITY)
                        .show(ui);
                    ui.add_space(6.0);
                    ui.horizontal(|ui| {
                        if ui.button("导入").clicked() {
                            match parse_auto(&self.paste_text) {
                                Ok(mut rec) => {
                                    if rec.name == "unnamed" {
                                        rec.name = "imported".into();
                                    }
                                    self.record = rec;
                                    self.selection = None;
                                    self.status = "序列已导入".into();
                                    self.paste_open = false;
                                    self.paste_text.clear();
                                }
                                Err(e) => self.status = format!("导入失败: {}", e),
                            }
                        }
                        if ui.button("取消").clicked() {
                            self.paste_open = false;
                        }
                    });
                });
        }
        self.paste_open = paste_open;

        // ---------- about window ----------
        let mut about_open = self.about_open;
        if about_open {
            egui::Window::new("关于 SPICE Native")
                .open(&mut about_open)
                .resizable(false)
                .show(ctx, |ui| {
                    ui.heading("SPICE GUI — Native");
                    ui.label("不依赖 WebView 的原生移植版 (Rust + egui)");
                    ui.add_space(6.0);
                    ui.label("分支: native · 基线: 环状/线性质粒图谱、序列视图、酶切面板");
                    ui.label("完整功能一比一复刻路线图见仓库根目录 NATIVE_PORT.md");
                });
        }
        self.about_open = about_open;
    }
}
