//! Restriction enzyme catalog and cut-site computation (IUPAC-aware, circular).

#[derive(Clone, Copy, Debug)]
pub struct Enzyme {
    pub name: &'static str,
    /// recognition sequence (IUPAC), upper-case
    pub site: &'static str,
    /// 0-based offset of the cut on the TOP strand, relative to the match start
    pub cut: usize,
}

pub const CATALOG: &[Enzyme] = &[
    Enzyme { name: "EcoRI", site: "GAATTC", cut: 1 },
    Enzyme { name: "BamHI", site: "GGATCC", cut: 1 },
    Enzyme { name: "HindIII", site: "AAGCTT", cut: 1 },
    Enzyme { name: "XhoI", site: "CTCGAG", cut: 1 },
    Enzyme { name: "SalI", site: "GTCGAC", cut: 1 },
    Enzyme { name: "PstI", site: "CTGCAG", cut: 5 },
    Enzyme { name: "SmaI", site: "CCCGGG", cut: 3 },
    Enzyme { name: "KpnI", site: "GGTACC", cut: 5 },
    Enzyme { name: "NcoI", site: "CCATGG", cut: 1 },
    Enzyme { name: "NdeI", site: "CATATG", cut: 2 },
    Enzyme { name: "XbaI", site: "TCTAGA", cut: 1 },
    Enzyme { name: "SpeI", site: "ACTAGT", cut: 1 },
    Enzyme { name: "NheI", site: "GCTAGC", cut: 4 },
    Enzyme { name: "SacI", site: "GAGCTC", cut: 5 },
    Enzyme { name: "BglII", site: "AGATCT", cut: 1 },
    Enzyme { name: "EcoRV", site: "GATATC", cut: 3 },
    Enzyme { name: "ClaI", site: "ATCGAT", cut: 2 },
    Enzyme { name: "AvaI", site: "CYCGRG", cut: 1 },
    Enzyme { name: "NotI", site: "GCGGCCGC", cut: 2 },
    Enzyme { name: "AatII", site: "GACGTC", cut: 4 },
    Enzyme { name: "AgeI", site: "ACCGGT", cut: 1 },
    Enzyme { name: "AscI", site: "GGCGCGCC", cut: 2 },
    Enzyme { name: "MluI", site: "ACGCGT", cut: 1 },
    Enzyme { name: "PacI", site: "TTAATTAA", cut: 5 },
    Enzyme { name: "PmeI", site: "GTTTAAAC", cut: 4 },
    Enzyme { name: "SwaI", site: "ATTTAAAT", cut: 4 },
    Enzyme { name: "ApaI", site: "GGGCCC", cut: 4 },
    Enzyme { name: "MfeI", site: "CAATTG", cut: 1 },
    Enzyme { name: "DraI", site: "TTTAAA", cut: 3 },
    Enzyme { name: "AflII", site: "CTTAAG", cut: 1 },
    Enzyme { name: "NruI", site: "TCGCGA", cut: 2 },
    Enzyme { name: "SbfI", site: "CCTGCAGG", cut: 6 },
    Enzyme { name: "BsaI", site: "GGTCTC", cut: 7 },
    Enzyme { name: "BsmBI", site: "CGTCTC", cut: 5 },
    Enzyme { name: "BbsI", site: "GAAGAC", cut: 8 },
];

/// IUPAC single-base match.
fn iupac_eq(pattern: u8, base: u8) -> bool {
    match pattern {
        b'A' => base == b'A',
        b'C' => base == b'C',
        b'G' => base == b'G',
        b'T' => base == b'T',
        b'R' => base == b'A' || base == b'G',
        b'Y' => base == b'C' || base == b'T',
        b'S' => base == b'G' || base == b'C',
        b'W' => base == b'A' || base == b'T',
        b'K' => base == b'G' || base == b'T',
        b'M' => base == b'A' || base == b'C',
        b'B' => base != b'A',
        b'D' => base != b'C',
        b'H' => base != b'G',
        b'V' => base != b'T',
        b'N' => true,
        _ => false,
    }
}

/// Circular search: returns match start offsets (0-based, may wrap origin).
pub fn cut_sites(seq: &[u8], e: &Enzyme) -> Vec<usize> {
    let n = seq.len();
    let m = e.site.len();
    if n == 0 || m == 0 || n < m {
        return Vec::new();
    }
    let pat = e.site.as_bytes();
    let mut out = Vec::new();
    for i in 0..n {
        let mut ok = true;
        for k in 0..m {
            let b = seq[(i + k) % n];
            if !iupac_eq(pat[k], b) {
                ok = false;
                break;
            }
        }
        if ok {
            out.push(i);
        }
    }
    out
}
