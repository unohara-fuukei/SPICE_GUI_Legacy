//! P4 AI copilot: local heuristic suggestions + optional remote
//! (OpenAI-compatible) chat via the `remote-ai` feature.

use crate::app::App;

/// Build a compact system context describing the current SPICE state.
pub fn system_context(app: &App) -> String {
    let rec = &app.record;
    let feats: Vec<String> = rec
        .features
        .iter()
        .map(|f| format!("{}:{}..{}({})", f.label, f.start + 1, f.end, if f.strand == 1 { "+" } else { "-" }))
        .collect();
    format!(
        "你是分子克隆助手 SPICE Copilot。当前工作区状态：序列 {}（{} bp，{}，GC {:.1}%），特征：{}；启用酶：{}。",
        rec.name,
        rec.seq.len(),
        rec.topology,
        crate::seq::gc_content(&rec.seq),
        if feats.is_empty() { "无".to_string() } else { feats.join(", ") },
        app.enzymes_on.iter().cloned().collect::<Vec<_>>().join(", "),
    )
}

/// Offline heuristic suggestions (no network, deterministic).
pub fn local_suggestions(app: &App) -> Vec<String> {
    let rec = &app.record;
    let n = rec.seq.len();
    let mut out = Vec::new();

    // single-cut enzymes → ideal for insertion cloning
    let singles: Vec<&str> = crate::enzymes::CATALOG
        .iter()
        .filter(|e| crate::enzymes::cut_sites(&rec.seq, e).len() == 1)
        .map(|e| e.name)
        .collect();
    if singles.is_empty() {
        out.push("当前序列没有任何单切点限制酶 — 建议用 Gibson/Golden Gate 无痕克隆路线。".into());
    } else {
        out.push(format!(
            "单切点酶（适合定点插入）: {} — 双酶切组合见「分子克隆工具 → 双酶切方案向导」。",
            singles.iter().take(6).cloned().collect::<Vec<_>>().join(", ")
        ));
    }

    // GC content advice
    let gc = crate::seq::gc_content(&rec.seq);
    if gc > 65.0 {
        out.push(format!("GC 含量 {:.1}% 偏高 — 设计引物时避开 GC 富集区，PCR 建议加 DMSO (3–5%)。", gc));
    } else if gc < 35.0 {
        out.push(format!("GC 含量 {:.1}% 偏低 — 引物 Tm 可能不足，建议延长结合区至 25–30 nt。", gc));
    } else {
        out.push(format!("GC 含量 {:.1}% 正常，常规 PCR/克隆条件即可。", gc));
    }

    // ORF advice
    let orfs = crate::seq::find_orfs(&rec.seq, 50);
    if !orfs.is_empty() {
        let best = orfs.iter().max_by_key(|o| o.1 - o.0).unwrap();
        out.push(format!(
            "发现 {} 个 ≥50 aa 的 ORF，最长 {}..{} ({} aa, {}) — 在「蛋白页」查看翻译与性质。",
            orfs.len(),
            best.0 + 1,
            best.1,
            (best.1 - best.0) / 3,
            if best.2 == 1 { "正向" } else { "反向" }
        ));
    }

    // topology-specific
    if rec.topology == "circular" {
        out.push("环形质粒 — 酶切位点分析已含跨起点搜索；电泳模拟条带数 = 切点数。".into());
    } else {
        out.push("线性片段 — Gibson 装配需先确认两端 15–40 bp 同源臂（分子克隆工具 → Gibson 装配）。".into());
    }

    // length sanity
    if n > 10000 {
        out.push(format!("序列 {} kb 较长 — 建议分区设计引物并核对载体拷贝数。", n / 1000));
    }
    out
}

/// One remote chat completion (OpenAI-compatible /chat/completions).
#[cfg(feature = "remote-ai")]
pub fn remote_chat(
    endpoint: &str,
    api_key: &str,
    model: &str,
    system: &str,
    history: &[(bool, String)],
) -> Result<String, String> {
    use serde_json::json;
    let mut msgs = vec![json!({"role": "system", "content": system})];
    for (is_user, text) in history {
        msgs.push(json!({
            "role": if *is_user { "user" } else { "assistant" },
            "content": text
        }));
    }
    let url = format!("{}/chat/completions", endpoint.trim_end_matches('/'));
    let resp = ureq::post(&url)
        .set("Authorization", &format!("Bearer {}", api_key))
        .set("Content-Type", "application/json")
        .send_json(json!({ "model": model, "messages": msgs, "temperature": 0.3 }))
        .map_err(|e| format!("请求失败: {}", e))?;
    let body: serde_json::Value = resp
        .into_json()
        .map_err(|e| format!("响应解析失败: {}", e))?;
    body["choices"][0]["message"]["content"]
        .as_str()
        .map(|s| s.to_string())
        .ok_or_else(|| "响应中无内容".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn demo_app_record() -> crate::seq::SeqRecord {
        crate::seq::SeqRecord::demo_puc19()
    }

    #[test]
    fn suggestions_nonempty() {
        let rec = demo_app_record();
        let app = App {
            record: rec,
            tab: crate::app::Tab::Circular,
            enzymes_on: ["EcoRI".to_string()].into_iter().collect(),
            enz_filter: String::new(),
            selection: None,
            sel_anchor: None,
            show_translation: false,
            status: String::new(),
            about_open: false,
            paste_open: false,
            paste_text: String::new(),
            primer_open: false,
            primer_start: 0,
            primer_end: 0,
            primer_tm: 60.0,
            primer_result: None,
            dd_open: false,
            gibson_open: false,
            gibson_cut: 0,
            gibson_overlap: 25,
            gibson_insert_text: String::new(),
            gibson_result: None,
            gg_open: false,
            pdb: None,
            rot_x: 0.0,
            rot_y: 0.0,
            zoom: 1.0,
            pdb_paste_open: false,
            pdb_paste_text: String::new(),
            project: crate::project::Project::new("t"),
            newproj_open: false,
            newproj_name: String::new(),
            newproj_pi: String::new(),
            newproj_desc: String::new(),
            attach_open: false,
            attach_name: String::new(),
            attach_text: String::new(),
            proj_import_open: false,
            proj_import_text: String::new(),
            ai_open: false,
            ai_msgs: Vec::new(),
            ai_input: String::new(),
            ai_endpoint: String::new(),
            ai_key: String::new(),
            ai_model: String::new(),
        };
        let s = local_suggestions(&app);
        assert!(!s.is_empty());
        assert!(s.iter().any(|x| x.contains("GC")));
        let ctx = system_context(&app);
        assert!(ctx.contains("pUC19") && ctx.contains("EcoRI"));
    }
}
