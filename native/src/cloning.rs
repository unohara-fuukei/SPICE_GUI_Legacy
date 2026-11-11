//! P1 molecular-cloning tools: PCR primer design, double-digest wizard,
//! Gibson assembly, Golden Gate screening. Pure functions + unit tests.

use crate::enzymes::{cut_sites, CATALOG};
use crate::seq::{complement, revcomp, SeqRecord};

// ---------------------------------------------------------------- Tm / primer

/// Wallace rule (good for < 14 nt).
pub fn tm_wallace(seq: &[u8]) -> f64 {
    let gc = seq.iter().filter(|&&b| b == b'G' || b == b'C').count();
    let at = seq.len() - gc;
    (2.0 * at as f64) + (4.0 * gc as f64)
}

/// GC%-based SantaLucia approximation (good for 14-70 nt).
pub fn tm_gc(seq: &[u8]) -> f64 {
    let gc = seq.iter().filter(|&&b| b == b'G' || b == b'C').count();
    64.9 + 41.0 * (gc as f64 - 16.4) / seq.len() as f64
}

pub fn tm(seq: &[u8]) -> f64 {
    if seq.len() < 14 {
        tm_wallace(seq)
    } else {
        tm_gc(seq)
    }
}

pub fn gc_pct(seq: &[u8]) -> f64 {
    if seq.is_empty() {
        return 0.0;
    }
    let gc = seq.iter().filter(|&&b| b == b'G' || b == b'C').count();
    100.0 * gc as f64 / seq.len() as f64
}

#[derive(Clone, Debug)]
pub struct Primer {
    pub name: String,
    pub seq: String,
    pub tm: f64,
    pub gc: f64,
    /// at least one G/C within the last 5 bases at the 3' end
    pub gc_clamp: bool,
    pub issues: Vec<String>,
}

impl Primer {
    fn build(name: &str, seq: Vec<u8>) -> Primer {
        let mut issues = Vec::new();
        let n = seq.len();
        if !(18..=30).contains(&n) {
            issues.push(format!("长度 {} nt 不在 18–30 常用区间", n));
        }
        let gc = gc_pct(&seq);
        if !(40.0..=65.0).contains(&gc) {
            issues.push(format!("GC 含量 {:.0}% 偏离 40–65%", gc));
        }
        let tail = &seq[n.saturating_sub(5)..];
        let gc_clamp = tail.iter().any(|&b| b == b'G' || b == b'C');
        if !gc_clamp {
            issues.push("3' 端 5 nt 内无 G/C (无 GC clamp)".into());
        }
        if seq.windows(4).any(|w| w == b"AAAA" || w == b"TTTT" || w == b"GGGG" || w == b"CCCC") {
            issues.push("存在 4 连相同碱基".into());
        }
        if let Some(d) = self_dimer_3prime(&seq) {
            issues.push(format!("3' 端自二聚体风险 ({} bp)", d));
        }
        Primer {
            name: name.into(),
            seq: String::from_utf8_lossy(&seq).to_string(),
            tm: tm(&seq),
            gc,
            gc_clamp,
            issues,
        }
    }
}

/// 3'-end self-dimer: the 3' terminal 6-mer pairing with an internal region
/// of the same primer (complementary match ≥ 5 bp). Returns matched length.
fn self_dimer_3prime(seq: &[u8]) -> Option<usize> {
    let n = seq.len();
    if n < 12 {
        return None;
    }
    let tail = &seq[n - 6..];
    let tail_rc = revcomp(tail);
    for k in 5..=6 {
        for i in 0..=(n - 6 - 1) {
            let w = &seq[i..i + k];
            if *w == tail_rc[..k] {
                return Some(k);
            }
        }
    }
    None
}

/// Cross-dimer between the 3' ends of two primers (complementarity ≥ 5 bp).
pub fn cross_dimer_3prime(a: &[u8], b: &[u8]) -> Option<usize> {
    let ta = &a[a.len().saturating_sub(7)..];
    let tb = &b[b.len().saturating_sub(7)..];
    for k in (5..=ta.len().min(tb.len())).rev() {
        if ta[ta.len() - k..] == revcomp(&tb[tb.len() - k..]) {
            return Some(k);
        }
    }
    None
}

/// Design a PCR primer pair amplifying `start..end` (0-based, start inclusive,
/// end exclusive) on `template`. The binding length is chosen per primer so
/// Tm lands closest to `target_tm`.
pub fn design_primer_pair(
    template: &[u8],
    start: usize,
    end: usize,
    target_tm: f64,
) -> Result<(Primer, Primer, usize), String> {
    let n = template.len();
    if end <= start || end > n {
        return Err(format!("无效区域 {}..{} (序列长度 {})", start, end, n));
    }
    if end - start < 40 {
        return Err("扩增区域过短 (< 40 bp)".into());
    }
    // forward: starts at `start`, extend while tm < target
    let mut fl = 15;
    while fl < 32 && (start + fl) <= n && tm(&template[start..start + fl]) < target_tm {
        fl += 1;
    }
    let fl = fl.min(n - start);
    let fwd_seq = template[start..start + fl].to_vec();

    // reverse: binds the antisense strand ending at `end`
    let rev_end = end.min(n);
    let mut rl = 15;
    while rl < 32 && rl <= rev_end && tm(&template[rev_end - rl..rev_end]) < target_tm {
        rl += 1;
    }
    let rl = rl.min(rev_end);
    let rev_seq = revcomp(&template[rev_end - rl..rev_end]);

    let fwd = Primer::build("Forward", fwd_seq);
    let rev = Primer::build("Reverse", rev_seq);
    Ok((fwd, rev, end - start))
}

// ---------------------------------------------------------------- double digest

/// Pairs of catalog enzymes that each cut `rec` 1–2 times → good cloning pair.
pub fn double_digest_candidates(rec: &SeqRecord) -> Vec<(&'static str, &'static str)> {
    let singles: Vec<&crate::enzymes::Enzyme> = CATALOG
        .iter()
        .filter(|e| (1..=2).contains(&cut_sites(&rec.seq, e).len()))
        .collect();
    let mut out = Vec::new();
    for i in 0..singles.len() {
        for j in i + 1..singles.len() {
            out.push((singles[i].name, singles[j].name));
        }
    }
    out
}

/// Simplified NEB-style buffer compatibility (common enzymes).
pub fn buffer_recommendation(a: &str, b: &str) -> &'static str {
    const HI_FIDELITY: &[&str] = &["EcoRI", "BamHI", "HindIII", "XhoI", "SalI", "PstI", "KpnI", "XbaI", "NdeI", "MluI", "AflII", "NruI"];
    const CUTSMART: &[&str] = &["SmaI", "SacI", "EcoRV", "ClaI", "NcoI", "ApaI", "MfeI", "AatII", "AgeI", "SbfI", "BsaI", "BsmBI", "BbsI", "AvaI", "NotI", "AscI", "PacI", "PmeI", "SwaI", "DraI", "SpeI", "NheI", "BglII"];
    let a_hi = HI_FIDELITY.contains(&a);
    let b_hi = HI_FIDELITY.contains(&b);
    if a_hi && b_hi {
        "CutSmart 缓冲液（两者均高保真兼容）"
    } else if a_hi || b_hi {
        "CutSmart 缓冲液（单侧活性 ≥75%，建议温育时间加倍）"
    } else {
        "CutSmart 缓冲液（两者均为 CutSmart 推荐酶）"
    }
}

// ---------------------------------------------------------------- Gibson

#[derive(Clone, Debug)]
pub struct GibsonResult {
    pub vector_fwd: Primer,
    pub vector_rev: Primer,
    pub insert_fwd: Primer,
    pub insert_rev: Primer,
    /// assembled circular construct
    pub assembled_len: usize,
    pub junctions: Vec<String>,
}

/// In-silico Gibson: cut `vector` (circular) at `cut` (0-based) and insert
/// `insert` between the two vector halves with `overlap` bp homology.
pub fn gibson_design(
    vector: &[u8],
    cut: usize,
    insert: &[u8],
    overlap: usize,
) -> Result<GibsonResult, String> {
    let n = vector.len();
    if n == 0 || cut >= n {
        return Err(format!("无效线性化位点 {} (载体长度 {})", cut, n));
    }
    if insert.len() < 40 {
        return Err("插入片段过短 (< 40 bp)".into());
    }
    let overlap = overlap.clamp(15, 40);
    if n < overlap * 2 || insert.len() < overlap * 2 + 20 {
        return Err("序列过短，无法构建同源臂".into());
    }

    // homology arms
    let up_arm = vector[cut - overlap..cut].to_vec(); // vector upstream of the cut
    let dn_arm = vector[cut..cut + overlap].to_vec(); // vector downstream of the cut

    // insert primers: 5' tail (homology) + binding region (~20 nt)
    let bind = 20.min(insert.len() / 2);
    let ins_fwd = [up_arm.clone(), insert[..bind].to_vec()].concat();
    let ins_rev = [
        revcomp(&dn_arm),
        revcomp(&insert[insert.len() - bind..]),
    ]
    .concat();

    // vector amplification primers (bind at the cut)
    let vbind = 20.min(n / 2);
    let vec_fwd = vector[cut..cut + vbind].to_vec();
    let vec_rev = revcomp(&vector[cut - vbind..cut]);

    let mut junctions = Vec::new();
    junctions.push(format!(
        "5' 接头: …{} | {}… (同源 {} bp)",
        String::from_utf8_lossy(&vector[cut - 6..cut]),
        String::from_utf8_lossy(&insert[..6]),
        overlap
    ));
    junctions.push(format!(
        "3' 接头: …{} | {}… (同源 {} bp)",
        String::from_utf8_lossy(&insert[insert.len() - 6..]),
        String::from_utf8_lossy(&vector[cut..cut + 6]),
        overlap
    ));

    Ok(GibsonResult {
        vector_fwd: Primer::build("Vector-F", vec_fwd),
        vector_rev: Primer::build("Vector-R", vec_rev),
        insert_fwd: Primer::build("Insert-F", ins_fwd),
        insert_rev: Primer::build("Insert-R", ins_rev),
        assembled_len: n + insert.len(),
        junctions,
    })
}

// ---------------------------------------------------------------- Golden Gate

#[derive(Clone, Debug)]
pub struct GoldenGateSite {
    pub enzyme: &'static str,
    pub pos: usize,
    /// 4-bp overhang produced on the top strand after the cut
    pub overhang: String,
}

/// Screen Type IIS enzymes (BsaI / BsmBI / BbsI) in `seq` and list overhangs.
pub fn golden_gate_sites(seq: &[u8], circular: bool) -> Vec<GoldenGateSite> {
    let mut out = Vec::new();
    let mut push = |e: &crate::enzymes::Enzyme| {
        for pos in cut_sites(seq, e) {
            let ov_start = pos + e.cut;
            // 4bp overhang: top strand from cut position
            let mut ov: Vec<u8> = Vec::new();
            for k in 0..4 {
                let idx = ov_start + k;
                let idx = if circular { idx % seq.len() } else { idx };
                if idx >= seq.len() {
                    break;
                }
                ov.push(seq[idx]);
            }
            out.push(GoldenGateSite {
                enzyme: e.name,
                pos,
                overhang: String::from_utf8_lossy(&ov).to_string(),
            });
        }
    };
    for e in CATALOG.iter().filter(|e| matches!(e.name, "BsaI" | "BsmBI" | "BbsI")) {
        push(e);
    }
    out
}

/// Report duplicate overhangs → fusion-compatible junctions.
pub fn golden_gate_overlaps(sites: &[GoldenGateSite]) -> Vec<String> {
    let mut out = Vec::new();
    for i in 0..sites.len() {
        for j in i + 1..sites.len() {
            if sites[i].overhang == sites[j].overhang && !sites[i].overhang.is_empty() {
                out.push(format!(
                    "{}@{} ↔ {}@{} 共享接头 {}",
                    sites[i].enzyme, sites[i].pos + 1, sites[j].enzyme, sites[j].pos + 1, sites[i].overhang
                ));
            }
        }
    }
    out
}

/// Simple sticky-end ligation sanity: overhangs A/T-only are weaker.
pub fn overhang_quality(ov: &str) -> &'static str {
    if ov.len() < 4 {
        return "偏短";
    }
    let gc = ov.bytes().filter(|&b| b == b'G' || b == b'C').count();
    match gc {
        0 => "全 AT，连接效率低",
        1 | 4 => "可用",
        _ => "良好",
    }
}

/// helper re-exported for tools UI
pub fn enzyme_by_name(name: &str) -> Option<&'static crate::enzymes::Enzyme> {
    CATALOG.iter().find(|e| e.name == name)
}

/// reverse complement of an ASCII base (used by tools UI)
pub fn _rc_base(b: u8) -> u8 {
    complement(b)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wallace_tm() {
        // 2×AT×2°C + 2×GC×4°C = 12
        assert_eq!(tm_wallace(b"ATGC"), 12.0);
        assert_eq!(tm_wallace(b"AATT"), 8.0);
        assert_eq!(tm_wallace(b"GGCC"), 16.0);
    }

    #[test]
    fn tm_increases_with_len() {
        let s = b"ATGCGATCGATCGATCGTAGCTAGCTA";
        assert!(tm(s) > tm(&s[..20]));
    }

    #[test]
    fn primer_pair_design() {
        let tpl = SeqRecord::demo_puc19();
        let (f, r, len) = design_primer_pair(&tpl.seq, 100, 700, 60.0).unwrap();
        assert_eq!(len, 600);
        assert!(f.seq.len() >= 15 && f.seq.len() <= 32);
        assert!(r.seq.len() >= 15 && r.seq.len() <= 32);
        // forward primer matches template at 100
        assert_eq!(f.seq.as_bytes(), &tpl.seq[100..100 + f.seq.len()]);
        // reverse primer revcomp-matches template ending at 700
        let rc = revcomp(r.seq.as_bytes());
        assert_eq!(rc, &tpl.seq[700 - r.seq.len()..700]);
    }

    #[test]
    fn gibson_assembly_length() {
        let tpl = SeqRecord::demo_puc19();
        let ins = &tpl.seq[500..1000];
        let res = gibson_design(&tpl.seq, 396, ins, 25).unwrap();
        assert_eq!(res.assembled_len, tpl.seq.len() + ins.len());
        // insert fwd tail must equal vector[cut-25..cut]
        assert_eq!(
            &res.insert_fwd.seq.as_bytes()[..25],
            &tpl.seq[396 - 25..396]
        );
    }

    #[test]
    fn gg_sites_present() {
        let mut seq = b"GGTCTCGAATTC".to_vec();
        seq.extend_from_slice(b"AAAAACGTCTCGGGG");
        let sites = golden_gate_sites(&seq, false);
        assert!(sites.iter().any(|s| s.enzyme == "BsaI"));
        assert!(sites.iter().any(|s| s.enzyme == "BsmBI"));
    }

    #[test]
    fn dd_candidates() {
        let tpl = SeqRecord::demo_puc19();
        let pairs = double_digest_candidates(&tpl);
        assert!(!pairs.is_empty());
        for (a, b) in &pairs {
            let ca = enzyme_by_name(a).map(|e| cut_sites(&tpl.seq, e).len()).unwrap();
            let cb = enzyme_by_name(b).map(|e| cut_sites(&tpl.seq, e).len()).unwrap();
            assert!((1..=2).contains(&ca) && (1..=2).contains(&cb));
        }
    }
}
