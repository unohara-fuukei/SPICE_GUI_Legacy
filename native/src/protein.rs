//! P2 protein module: physico-chemical properties, six-frame translation.

/// Residue average residue masses (Da), plus water for termini.
fn res_mass(a: u8) -> Option<f64> {
    Some(match a {
        b'A' => 71.0788, b'R' => 156.1875, b'N' => 114.1038, b'D' => 115.0886,
        b'C' => 103.1388, b'E' => 129.1155, b'Q' => 128.1307, b'G' => 57.0519,
        b'H' => 137.1411, b'I' => 113.1594, b'L' => 113.1594, b'K' => 128.1741,
        b'M' => 131.1926, b'F' => 147.1766, b'P' => 97.1167, b'S' => 87.0782,
        b'T' => 101.1051, b'W' => 186.2132, b'Y' => 163.1760, b'V' => 99.1326,
        _ => return None,
    })
}

/// Molecular weight (kDa) of a polypeptide.
pub fn mw(seq: &[u8]) -> f64 {
    let mut m = 18.0153; // terminal water
    let mut n = 0;
    for &a in seq {
        if let Some(mm) = res_mass(a) {
            m += mm;
            n += 1;
        }
    }
    let _ = n;
    m / 1000.0
}

/// Kyte-Doolittle hydropathy, averaged.
pub fn gravy(seq: &[u8]) -> f64 {
    fn kd(a: u8) -> Option<f64> {
        Some(match a {
            b'A' => 1.8, b'R' => -4.5, b'N' => -3.5, b'D' => -3.5, b'C' => 2.5,
            b'Q' => -3.5, b'E' => -3.5, b'G' => -0.4, b'H' => -3.2, b'I' => 4.5,
            b'L' => 3.8, b'K' => -3.9, b'M' => 1.9, b'F' => 2.8, b'P' => -1.6,
            b'S' => -0.8, b'T' => -0.7, b'W' => -0.9, b'Y' => -1.3, b'V' => 4.2,
            _ => return None,
        })
    }
    let vals: Vec<f64> = seq.iter().filter_map(|&a| kd(a)).collect();
    if vals.is_empty() {
        return 0.0;
    }
    vals.iter().sum::<f64>() / vals.len() as f64
}

/// Extinction coefficient at 280 nm (M⁻¹cm⁻¹), assuming all Cys form cystines.
pub fn extinction_280(seq: &[u8]) -> usize {
    let w = seq.iter().filter(|&&a| a == b'W').count();
    let y = seq.iter().filter(|&&a| a == b'Y').count();
    let c = seq.iter().filter(|&&a| a == b'C').count();
    w * 5500 + y * 1490 + (c / 2) * 125
}

/// Net charge at given pH (N/C termini + side chains, EMBOSS-style pKa).
fn net_charge(seq: &[u8], ph: f64) -> f64 {
    let pos = |pka: f64| 1.0 / (1.0 + 10f64.powf(ph - pka));
    let neg = |pka: f64| -1.0 / (1.0 + 10f64.powf(pka - ph));
    let mut q = pos(9.69) + neg(2.34); // termini
    for &a in seq {
        q += match a {
            b'K' => pos(10.54),
            b'R' => pos(12.48),
            b'H' => pos(6.05),
            b'D' => neg(3.86),
            b'E' => neg(4.25),
            b'C' => neg(8.33),
            b'Y' => neg(10.07),
            _ => 0.0,
        };
    }
    q
}

/// Isoelectric point by bisection (precision 0.01).
pub fn pi(seq: &[u8]) -> f64 {
    let (mut lo, mut hi) = (0.0, 14.0);
    for _ in 0..60 {
        let mid = (lo + hi) / 2.0;
        if net_charge(seq, mid) > 0.0 {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    (lo + hi) / 2.0
}

/// Six-frame translation: returns (frame +1/+2/+3/-1/-2/-3, protein).
pub fn six_frame(seq: &[u8]) -> Vec<(i8, Vec<u8>)> {
    let rc = crate::seq::revcomp(seq);
    let mut out = Vec::new();
    for f in 0..3 {
        out.push((f as i8 + 1, crate::seq::translate(&seq[f..])));
        out.push((-(f as i8) - 1, crate::seq::translate(&rc[f..])));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mw_gly_gly() {
        // G-G: 57.0519*2 + 18.0153 = 132.12 Da
        assert!((mw(b"GG") - 0.13212).abs() < 1e-4);
    }

    #[test]
    fn mw_insulin_a_chain() {
        // GIVEQCCTSICSLYQLENYCN (21 aa) average mass ≈ 2.384 kDa
        let s = b"GIVEQCCTSICSLYQLENYCN";
        assert!((mw(s) - 2.384).abs() < 0.01);
    }

    #[test]
    fn gravy_signs() {
        assert!(gravy(b"IIIIIIIIII") > 3.0);
        assert!(gravy(b"DDDDDDDDDD") < -3.0);
    }

    #[test]
    fn pi_rough_range() {
        // acidic protein → low pI; basic → high
        assert!(pi(b"DDDDDDDDDD") < 4.0);
        assert!(pi(b"KKKKKKKKKK") > 10.0);
    }

    #[test]
    fn six_frames_count() {
        let frames = six_frame(b"ATGAAATAAATGAAATAA");
        assert_eq!(frames.len(), 6);
        assert!(frames.contains(&(1, b"MK*MK*".to_vec())));
    }

    #[test]
    fn extinction_w_y() {
        assert_eq!(extinction_280(b"WY"), 5500 + 1490);
        assert_eq!(extinction_280(b"CC"), 125);
    }
}
