//! Minimal PDB parser: CA atoms of the first model, first chain.

#[derive(Clone, Copy, Debug)]
pub struct Atom {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    /// one-letter residue code ('?' for non-standard)
    pub res: u8,
    pub resi: u32,
}

#[derive(Clone, Debug)]
pub struct Pdb {
    pub name: String,
    pub atoms: Vec<Atom>,
}

fn three_to_one(three: &str) -> u8 {
    match three {
        "ALA" => b'A', "ARG" => b'R', "ASN" => b'N', "ASP" => b'D', "CYS" => b'C',
        "GLN" => b'Q', "GLU" => b'E', "GLY" => b'G', "HIS" => b'H', "ILE" => b'I',
        "LEU" => b'L', "LYS" => b'K', "MET" => b'M', "PHE" => b'F', "PRO" => b'P',
        "SER" => b'S', "THR" => b'T', "TRP" => b'W', "TYR" => b'Y', "VAL" => b'V',
        _ => b'?',
    }
}

pub fn parse(text: &str) -> Result<Pdb, String> {
    let mut atoms = Vec::new();
    let mut name = String::new();
    for line in text.lines() {
        let rec = &line[..line.len().min(6)];
        if rec.starts_with("HEADER") {
            if name.is_empty() {
                name = line[10..62.min(line.len())].trim().to_string();
            }
        } else if rec.starts_with("ENDMDL") {
            break;
        } else if rec.starts_with("ATOM") || rec.starts_with("HETATM") {
            let is_ca = line.len() >= 16 && line[12..16].trim() == "CA";
            if !is_ca {
                continue;
            }
            if line.len() < 54 {
                return Err("ATOM 记录过短".into());
            }
            let x: f32 = line[30..38]
                .trim()
                .parse()
                .map_err(|_| format!("坐标解析失败: {}", line))?;
            let y: f32 = line[38..46].trim().parse().map_err(|_| "y 坐标解析失败")?;
            let z: f32 = line[46..54].trim().parse().map_err(|_| "z 坐标解析失败")?;
            let res = three_to_one(line[17..20].trim());
            let resi: u32 = line[22..26].trim().parse().unwrap_or(0);
            atoms.push(Atom { x, y, z, res, resi });
        }
    }
    if atoms.is_empty() {
        return Err("未找到 CA 原子记录".into());
    }
    Ok(Pdb {
        name: if name.is_empty() { "structure".into() } else { name },
        atoms,
    })
}

impl Pdb {
    /// Geometric center of the CA trace.
    pub fn center(&self) -> [f32; 3] {
        let n = self.atoms.len() as f32;
        let mut c = [0.0f32; 3];
        for a in &self.atoms {
            c[0] += a.x;
            c[1] += a.y;
            c[2] += a.z;
        }
        [c[0] / n, c[1] / n, c[2] / n]
    }

    /// Max distance from center (for view scaling).
    pub fn radius(&self) -> f32 {
        let c = self.center();
        self.atoms
            .iter()
            .map(|a| ((a.x - c[0]).powi(2) + (a.y - c[1]).powi(2) + (a.z - c[2]).powi(2)).sqrt())
            .fold(0.0, f32::max)
            .max(1.0)
    }

    /// One-letter sequence of the chain (for property panel reuse).
    pub fn sequence(&self) -> Vec<u8> {
        self.atoms.iter().map(|a| a.res).collect()
    }

    /// Synthetic demo: 2 α-helices + short loop (idealized geometry, 40 res).
    pub fn demo_helix_bundle() -> Pdb {
        let mut atoms = Vec::new();
        let mut push = |res: u8, i: u32, x: f32, y: f32, z: f32| {
            atoms.push(Atom { x, y, z, res, resi: i });
        };
        // ideal α-helix: rise 1.5 Å/res, radius 2.3 Å, 100°/res
        let seq1 = b"AEAAAKAAEAAAKAAEAAAKA";
        for (i, &r) in seq1.iter().enumerate() {
            let ang = (i as f32) * 100.0f32.to_radians();
            push(r, i as u32 + 1, 2.3 * ang.cos(), i as f32 * 1.5, 2.3 * ang.sin());
        }
        // loop
        let seq2 = b"GGGS";
        for (i, &r) in seq2.iter().enumerate() {
            push(r, 22 + i as u32, 6.0 + i as f32 * 1.2, 31.0, 0.0);
        }
        // second helix
        let seq3 = b"AEAAAKAAEAAAKAAEAAAKA";
        for (i, &r) in seq3.iter().enumerate() {
            let ang = (i as f32) * 100.0f32.to_radians();
            push(
                r,
                26 + i as u32,
                11.0 + 2.3 * ang.cos(),
                31.0 + i as f32 * 1.5,
                2.3 * ang.sin(),
            );
        }
        Pdb { name: "demo helix-turn-helix".into(), atoms }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_minimal_pdb() {
        let text = "TITLE      DEMO\n\
                    ATOM      1  CA  ALA A   1       1.000   2.000   3.000  1.00  0.00           C\n\
                    ATOM      2  CA  GLY A   2       4.000   5.000   6.000  1.00  0.00           C\n\
                    ATOM      3  CB  ALA A   3       7.000   8.000   9.000  1.00  0.00           C\n\
                    END\n";
        let p = parse(text).unwrap();
        assert_eq!(p.atoms.len(), 2); // CB skipped
        assert_eq!(p.atoms[0].res, b'A');
        assert_eq!(p.atoms[1].res, b'G');
        assert_eq!(p.sequence(), b"AG");
    }

    #[test]
    fn parse_rejects_empty() {
        assert!(parse("HEADER nothing\n").is_err());
    }

    #[test]
    fn demo_geometry() {
        let p = Pdb::demo_helix_bundle();
        assert_eq!(p.atoms.len(), 46);
        let c = p.center();
        // centered box roughly around x≈5.5
        assert!(c[0] > 0.0 && c[0] < 12.0);
        assert!(p.radius() > 5.0);
    }

    #[test]
    fn endmdl_stops() {
        let text = "ATOM      1  CA  ALA A   1       0.000   0.000   0.000  1.00  0.00           C\n\
                    ENDMDL\n\
                    ATOM      2  CA  GLY A   2       1.000   1.000   1.000  1.00  0.00           C\n";
        let p = parse(text).unwrap();
        assert_eq!(p.atoms.len(), 1);
    }
}
