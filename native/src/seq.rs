//! Sequence records, FASTA / GenBank parsing, and basic sequence algebra.

#[derive(Clone, Debug)]
pub struct Feature {
    pub label: String,
    /// 0-based inclusive start on the top strand.
    pub start: usize,
    /// 0-based exclusive end.
    pub end: usize,
    pub strand: i8,
    pub color_idx: usize,
}

#[derive(Clone, Debug)]
pub struct SeqRecord {
    pub name: String,
    pub seq: Vec<u8>,
    pub features: Vec<Feature>,
    pub topology: String,
}

impl SeqRecord {
    pub fn len(&self) -> usize {
        self.seq.len()
    }

    pub fn demo_puc19() -> SeqRecord {
        let mut rec = parse_fasta(include_str!("../assets/pUC19.fasta"))
            .expect("embedded pUC19 must parse");
        rec.name = "pUC19".into();
        rec.topology = "circular".into();
        // Feature map follows the standard pUC19 annotation (L09137 numbering).
        rec.features = vec![
            Feature { label: "lacZα".into(), start: 395, end: 637, strand: 1, color_idx: 0 },
            Feature { label: "MCS".into(), start: 396, end: 455, strand: 1, color_idx: 1 },
            Feature { label: "bla (AmpR)".into(), start: 1628, end: 2486, strand: -1, color_idx: 2 },
            Feature { label: "ori".into(), start: 2608, end: 2667, strand: 1, color_idx: 3 },
        ];
        rec
    }
}

/// Parse a FASTA document (multi-record: takes the first record).
pub fn parse_fasta(text: &str) -> Result<SeqRecord, String> {
    let mut name = String::new();
    let mut seq: Vec<u8> = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix('>') {
            if !seq.is_empty() {
                break; // first record only
            }
            name = rest.split_whitespace().next().unwrap_or("unnamed").to_string();
        } else if !line.starts_with(';') {
            for b in line.bytes() {
                if b.is_ascii_alphabetic() {
                    seq.push(b.to_ascii_uppercase());
                }
            }
        }
    }
    if seq.is_empty() {
        return Err("FASTA 中未找到序列".into());
    }
    Ok(SeqRecord { name, seq, features: Vec::new(), topology: "linear".into() })
}

/// Parse a GenBank document (single Locus).
pub fn parse_genbank(text: &str) -> Result<SeqRecord, String> {
    let mut name = "unnamed".into();
    let mut topology = "linear".into();
    let mut features: Vec<Feature> = Vec::new();
    let mut seq: Vec<u8> = Vec::new();
    let mut in_origin = false;

    let mut color_idx = 0usize;
    let mut pending: Option<Feature> = None;
    let mut flush = |pending: &mut Option<Feature>, features: &mut Vec<Feature>, color_idx: &mut usize| {
        if let Some(mut f) = pending.take() {
            if f.label.is_empty() {
                f.label = "feature".into();
            }
            f.color_idx = *color_idx;
            *color_idx = (*color_idx + 1) % 8;
            features.push(f);
        }
    };

    for line in text.lines() {
        if in_origin {
            if line.trim().starts_with("//") {
                break;
            }
            for b in line.bytes() {
                if b.is_ascii_alphabetic() {
                    seq.push(b.to_ascii_uppercase());
                }
            }
            continue;
        }
        let t = line.trim();
        if t.starts_with("LOCUS") {
            let mut it = t.split_whitespace();
            let _ = it.next();
            if let Some(n) = it.next() {
                name = n.to_string();
            }
            if t.contains("circular") {
                topology = "circular".into();
            }
        } else if t.starts_with("ORIGIN") {
            flush(&mut pending, &mut features, &mut color_idx);
            in_origin = true;
        } else if t.starts_with("FEATURES") {
            // no-op
        } else if line.len() >= 6 && line[..5].trim().is_empty() && !t.starts_with('/') {
            // feature key line: "     CDS             469..142"
            flush(&mut pending, &mut features, &mut color_idx);
            let key = t.split_whitespace().next().unwrap_or("");
            if matches!(key, "CDS" | "gene" | "rep_origin" | "regulatory" | "misc_feature" | "source" | "promoter" | "rep_rec" | "LTR") {
                let loc = t[key.len()..].trim();
                if let Some((s, e, strand)) = parse_location(loc) {
                    if s < e {
                        pending = Some(Feature {
                            label: String::new(),
                            start: s,
                            end: e,
                            strand,
                            color_idx: 0,
                        });
                    }
                }
            }
        } else if t.starts_with("/gene=") || t.starts_with("/label=") || t.starts_with("/product=") {
            let val = t.split('=').nth(1).unwrap_or("").trim_matches('"').to_string();
            if let Some(f) = pending.as_mut() {
                if f.label.is_empty() {
                    f.label = val;
                }
            }
        }
    }
    if seq.is_empty() {
        return Err("GenBank 中未找到 ORIGIN 序列".into());
    }
    Ok(SeqRecord { name, seq, features, topology })
}

fn parse_location(loc: &str) -> Option<(usize, usize, i8)> {
    let (strand, inner) = if let Some(r) = loc.strip_prefix("complement(") {
        (-1, r.trim_end_matches(')'))
    } else {
        (1, loc)
    };
    let inner = inner.trim().trim_end_matches(')');
    let mut parts = inner.split("..");
    let a = parts.next()?;
    let b = parts.next()?;
    let a: usize = a.trim_start_matches(['<', '>']).parse().ok()?;
    let b: usize = b.trim_start_matches(['<', '>']).parse().ok()?;
    if a == 0 || b == 0 {
        return None;
    }
    Some((a - 1, b, strand))
}

/// Auto-detect FASTA vs GenBank.
pub fn parse_auto(text: &str) -> Result<SeqRecord, String> {
    if text.contains("LOCUS") && text.contains("ORIGIN") {
        parse_genbank(text)
    } else if text.contains('>') {
        parse_fasta(text)
    } else {
        // bare sequence
        let seq: Vec<u8> = text
            .bytes()
            .filter(|b| b.is_ascii_alphabetic())
            .map(|b| b.to_ascii_uppercase())
            .collect();
        if seq.len() < 12 {
            return Err("无法识别的序列文件格式".into());
        }
        Ok(SeqRecord { name: "unnamed".into(), seq, features: Vec::new(), topology: "linear".into() })
    }
}

pub fn complement(b: u8) -> u8 {
    match b {
        b'A' => b'T',
        b'T' => b'A',
        b'G' => b'C',
        b'C' => b'G',
        b'N' => b'N',
        b'R' => b'Y',
        b'Y' => b'R',
        b'S' => b'S',
        b'W' => b'W',
        b'K' => b'M',
        b'M' => b'K',
        b'B' => b'V',
        b'V' => b'B',
        b'D' => b'H',
        b'H' => b'D',
        _ => b'N',
    }
}

pub fn revcomp(seq: &[u8]) -> Vec<u8> {
    seq.iter().rev().map(|&b| complement(b)).collect()
}

pub fn gc_content(seq: &[u8]) -> f64 {
    if seq.is_empty() {
        return 0.0;
    }
    let gc = seq.iter().filter(|&&b| b == b'G' || b == b'C' || b == b'S').count();
    gc as f64 * 100.0 / seq.len() as f64
}

const CODON_TABLE: [&str; 64] = [
    "K", "N", "K", "N", "T", "T", "T", "T", "R", "S", "R", "S", "I", "I", "M", "I", "Q", "H",
    "Q", "H", "P", "P", "P", "P", "R", "R", "R", "R", "L", "L", "L", "L", "E", "D", "E", "D",
    "A", "A", "A", "A", "G", "G", "G", "G", "V", "V", "V", "V", "*", "Y", "*", "Y", "S", "S",
    "S", "S", "*", "C", "W", "C", "L", "F", "L", "F",
];

fn base_idx(b: u8) -> Option<usize> {
    Some(match b {
        b'T' | b'U' => 0,
        b'C' => 1,
        b'A' => 2,
        b'G' => 3,
        _ => return None,
    })
}

/// Translate in frame 0 of the given slice; non-ACGT becomes 'X'.
pub fn translate(seq: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(seq.len() / 3);
    let mut i = 0;
    while i + 3 <= seq.len() {
        let c = (|| {
            let a = base_idx(seq[i])?;
            let b = base_idx(seq[i + 1])?;
            let c = base_idx(seq[i + 2])?;
            Some(CODON_TABLE[a * 16 + b * 4 + c])
        })();
        match c {
            Some(aa) => out.extend_from_slice(aa.as_bytes()),
            None => out.push(b'X'),
        }
        i += 3;
    }
    out
}

/// Find ORFs (ATG..stop) in the given strand, min length in aa.
pub fn find_orfs(seq: &[u8], min_aa: usize) -> Vec<(usize, usize, i8)> {
    let mut out = Vec::new();
    let scan = |s: &[u8]| -> Vec<(usize, usize)> {
        let mut v = Vec::new();
        let mut i = 0;
        while i + 3 <= s.len() {
            if &s[i..i + 3] == b"ATG" {
                let mut j = i;
                while j + 3 <= s.len() {
                    let aa = &translate(&s[j..j + 3])[..1];
                    if aa == b"*" {
                        if (j - i) / 3 >= min_aa {
                            v.push((i, j + 3));
                        }
                        break;
                    }
                    j += 3;
                }
                i += 3;
            } else {
                i += 1;
            }
        }
        v
    };
    for (a, b) in scan(seq) {
        out.push((a, b, 1));
    }
    let rc = revcomp(seq);
    for (a, b) in scan(&rc) {
        out.push((seq.len() - b, seq.len() - a, -1));
    }
    out
}
