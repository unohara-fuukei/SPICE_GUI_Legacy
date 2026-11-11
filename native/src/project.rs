//! P3 project hub: documents, experiment log, attachments, JSON persistence.

use serde::{Deserialize, Serialize};

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Doc {
    pub title: String,
    pub body: String,
    pub updated: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct LogEntry {
    pub ts: String,
    pub text: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Attachment {
    pub name: String,
    pub kind: String,
    pub size: usize,
    /// base64 payload for small text attachments (≤ 64 KiB)
    pub data: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Project {
    pub name: String,
    pub pi: String,
    pub description: String,
    pub docs: Vec<Doc>,
    pub logs: Vec<LogEntry>,
    pub attachments: Vec<Attachment>,
    #[serde(default)]
    pub current_doc: usize,
    #[serde(default)]
    pub presenting: bool,
}

impl Project {
    pub fn new(name: &str) -> Project {
        Project {
            name: name.into(),
            pi: String::new(),
            description: String::new(),
            docs: vec![Doc { title: "实验记录".into(), body: String::new(), updated: now() }],
            logs: Vec::new(),
            attachments: Vec::new(),
            current_doc: 0,
            presenting: false,
        }
    }

    /// Demo project mirroring the WebView version's demo content.
    pub fn demo() -> Project {
        Project {
            name: "演示项目：pUC19 克隆验证".into(),
            pi: "张三".into(),
            description: "验证 MCS 插入片段的酶切图谱与测序一致性。".into(),
            docs: vec![
                Doc {
                    title: "实验记录".into(),
                    body: "D1 载体制备：pUC19 经 EcoRI/HindIII 双酶切，1% 胶回收 2686 bp 片段。\nD2 连接：Insert (600 bp) 与载体 3:1 摩尔比，T4 ligase 16°C 过夜。\nD3 转化：热激法转入 DH5α，Amp 平板 37°C 过夜。\nD4 鉴定：菌落 PCR + EcoRI/HindIII 双酶切电泳，预期 600 + 2686 bp。".into(),
                    updated: "2026-11-11 10:00".into(),
                },
                Doc {
                    title: "引物信息".into(),
                    body: "Insert-F: (见分子克隆工具 → PCR 引物设计)\nInsert-R: (见分子克隆工具 → PCR 引物设计)\n测序引物: M13F / M13R".into(),
                    updated: "2026-11-11 11:30".into(),
                },
            ],
            logs: vec![
                LogEntry { ts: "2026-11-11 10:00".into(), text: "创建项目".into() },
                LogEntry { ts: "2026-11-11 10:05".into(), text: "添加文档「实验记录」".into() },
                LogEntry { ts: "2026-11-11 11:30".into(), text: "添加文档「引物信息」".into() },
            ],
            attachments: vec![Attachment {
                name: "pUC19.fasta".into(),
                kind: "FASTA".into(),
                size: 2786,
                data: None,
            }],
            current_doc: 0,
            presenting: false,
        }
    }

    pub fn add_doc(&mut self, title: &str) {
        self.docs.push(Doc { title: title.into(), body: String::new(), updated: now() });
        self.current_doc = self.docs.len() - 1;
        self.log(&format!("添加文档「{}」", title));
    }

    pub fn add_log_entry(&mut self, text: &str) {
        self.logs.push(LogEntry { ts: now(), text: text.into() });
    }

    fn log(&mut self, text: &str) {
        self.add_log_entry(text);
    }

    pub fn add_attachment_text(&mut self, name: &str, content: &str) {
        let encoded = if content.len() <= 64 * 1024 {
            Some(base64_encode(content.as_bytes()))
        } else {
            None
        };
        self.attachments.push(Attachment {
            name: name.into(),
            kind: "text".into(),
            size: content.len(),
            data: encoded,
        });
        self.add_log_entry(&format!("附件「{}」({} B)", name, content.len()));
    }

    pub fn to_json(&self) -> Result<String, String> {
        serde_json::to_string_pretty(self).map_err(|e| e.to_string())
    }

    pub fn from_json(text: &str) -> Result<Project, String> {
        serde_json::from_str(text).map_err(|e| e.to_string())
    }
}

/// RFC4648 base64 (standard alphabet, with padding), no external crate.
pub fn base64_encode(data: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity((data.len() + 2) / 3 * 4);
    for chunk in data.chunks(3) {
        let b = [chunk[0], *chunk.get(1).unwrap_or(&0), *chunk.get(2).unwrap_or(&0)];
        let n = (b[0] as u32) << 16 | (b[1] as u32) << 8 | b[2] as u32;
        out.push(T[(n >> 18) as usize & 63] as char);
        out.push(T[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        out.push(if chunk.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    out
}

pub fn now() -> String {
    // UTC timestamp without chrono: days-since-epoch conversion
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = secs / 86400;
    let rem = secs % 86400;
    let (h, m, s) = (rem / 3600, rem % 3600 / 60, rem % 60);
    // civil-from-days (Howard Hinnant's algorithm)
    let z = days as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", y, m, d, h, m, s)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn json_roundtrip() {
        let p = Project::demo();
        let j = p.to_json().unwrap();
        let q = Project::from_json(&j).unwrap();
        assert_eq!(p.name, q.name);
        assert_eq!(p.docs.len(), q.docs.len());
        assert_eq!(p.docs[0].body, q.docs[0].body);
        assert_eq!(p.attachments[0].name, q.attachments[0].name);
    }

    #[test]
    fn add_doc_logs() {
        let mut p = Project::new("t");
        let n0 = p.logs.len();
        p.add_doc("新文档");
        assert_eq!(p.docs.len(), 2);
        assert_eq!(p.current_doc, 1);
        assert_eq!(p.logs.len(), n0 + 1);
    }

    #[test]
    fn b64_known_vectors() {
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"f"), "Zg==");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_encode(b"foo"), "Zm9v");
        assert_eq!(base64_encode(b"foobar"), "Zm9vYmFy");
    }

    #[test]
    fn timestamp_shape() {
        let t = now();
        // 2026-xx-xx .. shape
        assert_eq!(t.len(), 19);
        assert_eq!(&t[4..5], "-");
        assert_eq!(&t[10..11], " ");
    }

    #[test]
    fn attachment_text_stored() {
        let mut p = Project::new("t");
        p.add_attachment_text("note.txt", "hello world");
        assert_eq!(p.attachments.len(), 1);
        assert_eq!(p.attachments[0].size, 11);
        assert!(p.attachments[0].data.is_some());
    }
}
