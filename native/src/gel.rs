//! Agarose gel electrophoresis simulation (P1).

use crate::enzymes::cut_sites;
use crate::seq::SeqRecord;

/// A band: fragment length (bp) + total mass loaded in this band (ng, arbitrary).
#[derive(Clone, Copy, Debug)]
pub struct Band {
    pub bp: usize,
    /// relative brightness 0.0–1.0
    pub intensity: f32,
}

/// A lane of the gel.
#[derive(Clone, Debug)]
pub struct Lane {
    pub label: String,
    pub bands: Vec<Band>,
}

/// Compute digest fragments for one enzyme on `rec`.
/// Returns fragment lengths (circular-aware).
pub fn fragments(rec: &SeqRecord, positions: &[usize]) -> Vec<usize> {
    let n = rec.seq.len();
    if positions.is_empty() {
        return vec![n];
    }
    let mut cuts: Vec<usize> = positions.to_vec();
    cuts.sort_unstable();
    cuts.dedup();
    let mut out = Vec::new();
    if rec.topology == "circular" {
        for w in cuts.windows(2) {
            out.push(w[1] - w[0]);
        }
        out.push(n - cuts.last().unwrap() + cuts[0]); // wrap-around fragment
    } else {
        out.push(cuts[0]);
        for w in cuts.windows(2) {
            out.push(w[1] - w[0]);
        }
        out.push(n - cuts.last().unwrap());
    }
    out.into_iter().filter(|&l| l > 0).collect()
}

/// Standard 1 kb-plus ladder (bp, ng mass for shading).
pub fn ladder() -> Vec<Band> {
    let sizes = [
        (10000, 40.0),
        (8000, 40.0),
        (6000, 50.0),
        (5000, 50.0),
        (4000, 60.0),
        (3000, 70.0),
        (2000, 80.0),
        (1500, 70.0),
        (1000, 100.0),
        (700, 60.0),
        (500, 60.0),
        (400, 40.0),
        (300, 50.0),
        (200, 40.0),
        (100, 30.0),
    ];
    sizes
        .iter()
        .map(|&(bp, ng)| Band {
            bp,
            intensity: (ng / 100.0f32).clamp(0.25, 1.0),
        })
        .collect()
}

/// Build the gel for `rec` given selected enzyme names (one lane each) plus
/// a combined "all selected" lane.
pub fn build_lanes(rec: &SeqRecord, enzymes_on: &[String]) -> Vec<Lane> {
    let mut lanes = vec![Lane {
        label: "Marker".into(),
        bands: ladder(),
    }];
    for name in enzymes_on {
        if let Some(e) = crate::enzymes::CATALOG.iter().find(|e| e.name == *name) {
            let cuts = cut_sites(&rec.seq, e);
            if cuts.is_empty() {
                continue;
            }
            let frags = fragments(rec, &cuts);
            lanes.push(Lane {
                label: format!("{} ({}×)", name, frags.len()),
                bands: frags
                    .iter()
                    .map(|&bp| Band {
                        bp,
                        // equal mass per molecule ⇒ brightness ∝ 1/len, capped
                        intensity: (1500.0 / bp as f32).clamp(0.2, 1.0),
                    })
                    .collect(),
            });
        }
    }
    // combined lane
    let all: Vec<usize> = enzymes_on
        .iter()
        .filter_map(|n| crate::enzymes::CATALOG.iter().find(|e| e.name == *n))
        .flat_map(|e| cut_sites(&rec.seq, e))
        .collect();
    if all.len() > 1 {
        let frags = fragments(rec, &all);
        lanes.push(Lane {
            label: "全酶切".into(),
            bands: frags
                .iter()
                .map(|&bp| Band {
                    bp,
                    intensity: (1500.0 / bp as f32).clamp(0.2, 1.0),
                })
                .collect(),
        });
    }
    lanes
}

/// Map a fragment length to a 0..1 position on the gel (top=1 for large).
/// Log scale between 100 bp (bottom, 0.0) and 10 kb (top, 1.0).
pub fn bp_to_y(bp: usize, top_px: f32, bottom_px: f32) -> f32 {
    let lo = (100f64).log10();
    let hi = (10000f64).log10();
    let t = ((bp as f64).log10() - lo) / (hi - lo);
    let t = t.clamp(0.0, 1.0);
    bottom_px - t as f32 * (bottom_px - top_px)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn circular_single_cut_is_whole() {
        let rec = SeqRecord::demo_puc19();
        assert_eq!(fragments(&rec, &[100]), vec![rec.seq.len()]);
    }

    #[test]
    fn circular_two_cuts() {
        let rec = SeqRecord::demo_puc19();
        let n = rec.seq.len();
        let mut f = fragments(&rec, &[100, 700]);
        f.sort_unstable();
        assert_eq!(f, vec![600, n - 600]);
    }

    #[test]
    fn linear_fragments() {
        let mut rec = SeqRecord::demo_puc19();
        rec.topology = "linear".into();
        let mut f = fragments(&rec, &[100, 700]);
        f.sort_unstable();
        let n = rec.seq.len();
        assert_eq!(f, vec![100, 600, n - 700]);
    }

    #[test]
    fn y_mapping() {
        assert!(bp_to_y(10000, 0.0, 100.0) < 1.0);
        assert!(bp_to_y(100, 0.0, 100.0) > 99.0);
        // monotonic: larger bp → smaller y
        assert!(bp_to_y(5000, 0.0, 100.0) < bp_to_y(500, 0.0, 100.0));
    }
}
