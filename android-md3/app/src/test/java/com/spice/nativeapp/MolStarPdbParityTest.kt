package com.spice.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test
import com.spice.nativeapp.core.MolStarPdb

/**
 * T7-14 parity：main src/lib/backend/preprocess.ts pdbFromCoords/oneToThree/cleanSeq。
 * 期望值由 tsx 直跑 main 真实模块生成（/tmp/msv14/gen.ts → ref.json）。
 */
class MolStarPdbParityTest {

    @Test fun oneToThreeTable() {
        assertEquals("ALA", MolStarPdb.oneToThree('A'))
        assertEquals("CYS", MolStarPdb.oneToThree('C'))
        assertEquals("ASP", MolStarPdb.oneToThree('D'))
        assertEquals("GLU", MolStarPdb.oneToThree('E'))
        assertEquals("PHE", MolStarPdb.oneToThree('F'))
        assertEquals("GLY", MolStarPdb.oneToThree('G'))
        assertEquals("HIS", MolStarPdb.oneToThree('H'))
        assertEquals("ILE", MolStarPdb.oneToThree('I'))
        assertEquals("LYS", MolStarPdb.oneToThree('K'))
        assertEquals("LEU", MolStarPdb.oneToThree('L'))
        assertEquals("MET", MolStarPdb.oneToThree('M'))
        assertEquals("ASN", MolStarPdb.oneToThree('N'))
        assertEquals("PRO", MolStarPdb.oneToThree('P'))
        assertEquals("GLN", MolStarPdb.oneToThree('Q'))
        assertEquals("ARG", MolStarPdb.oneToThree('R'))
        assertEquals("SER", MolStarPdb.oneToThree('S'))
        assertEquals("THR", MolStarPdb.oneToThree('T'))
        assertEquals("VAL", MolStarPdb.oneToThree('V'))
        assertEquals("TRP", MolStarPdb.oneToThree('W'))
        assertEquals("TYR", MolStarPdb.oneToThree('Y'))
        assertEquals("SEC", MolStarPdb.oneToThree('U'))
        assertEquals("UNK", MolStarPdb.oneToThree('X'))
        assertEquals("UNK", MolStarPdb.oneToThree('Z'))
        assertEquals("UNK", MolStarPdb.oneToThree('*'))
    }

    @Test fun pdbMini() {
        val seq = "AAAAKAAAAKAAAAKAAAAK"
        val coords = DoubleArray(21 * 3) { i -> kotlin.math.sin(i * 0.7) * 12 + i * 0.3 }
        assertEquals("AAAAKAAAAKAAAAKAAAAK", MolStarPdb.cleanSeq(seq))
        val pdb = MolStarPdb.pdbFromCoords(seq, coords)
        val expected = """SEQRES 1 A 20 ALA ALA ALA ALA LYS ALA ALA ALA ALA LYS ALA ALA ALA ALA LYS ALA ALA
SEQRES 2 A 20 ALA ALA LYS
ATOM      1 CA   ALA A   1       0.000   8.031  12.425  1.00  0.00           C
ATOM      2 CA   ALA A   2      11.259   5.220  -2.709  1.00  0.00           C
ATOM      3 CA   ALA A   3      -8.659  -9.689  -5.175  1.00  0.00           C
ATOM      4 CA   ALA A   4       2.902  10.884  15.158  1.00  0.00           C
ATOM      5 CA   LYS A   5      13.855   7.729  -0.198  1.00  0.00           C
ATOM      6 CA   ALA A   6      -6.056  -6.950  -2.318  1.00  0.00           C
ATOM      7 CA   ALA A   7       5.803  13.735  17.887  1.00  0.00           C
ATOM      8 CA   ALA A   8      16.449  10.237   2.315  1.00  0.00           C
ATOM      9 CA   ALA A   9      -3.451  -4.208   0.542  1.00  0.00           C
ATOM     10 CA   LYS A  10       8.705  16.584  20.613  1.00  0.00           C
ATOM     11 CA   ALA A  11      19.040  12.745   4.829  1.00  0.00           C
ATOM     12 CA   ALA A  12      -0.842  -1.462   3.404  1.00  0.00           C
ATOM     13 CA   ALA A  13      11.606  19.430  23.336  1.00  0.00           C
ATOM     14 CA   ALA A  14      21.628  15.251   7.345  1.00  0.00           C
ATOM     15 CA   LYS A  15       1.769   1.288   6.267  1.00  0.00           C
ATOM     16 CA   ALA A  16      14.508  22.274  26.055  1.00  0.00           C
ATOM     17 CA   ALA A  17      24.213  17.756   9.862  1.00  0.00           C
ATOM     18 CA   ALA A  18       4.384   4.040   9.133  1.00  0.00           C
ATOM     19 CA   ALA A  19      17.409  25.116  28.771  1.00  0.00           C
ATOM     20 CA   LYS A  20      26.796  20.261  12.380  1.00  0.00           C
TER
END"""
        assertEquals(expected, pdb)
    }

    @Test fun pdbLysozyme() {
        val seq = "ACDEFGHIKLMNPQRSTVWY"
        val coords = DoubleArray(20 * 3) { i -> kotlin.math.cos(i * 1.3) * 9 - i * 0.15 }
        assertEquals("ACDEFGHIKLMNPQRSTVWY", MolStarPdb.cleanSeq(seq))
        val pdb = MolStarPdb.pdbFromCoords(seq, coords)
        val expected = """SEQRES 1 A 20 ALA CYS ASP GLU PHE GLY HIS ILE LYS LEU MET ASN PRO GLN ARG SER THR
SEQRES 2 A 20 VAL TRP TYR
ATOM      1 CA   ALA A   1       9.000   2.257  -8.012  1.00  0.00           C
ATOM      2 CA   CYS A   2      -6.983   3.617   8.039  1.00  0.00           C
ATOM      3 CA   ASP A   3      -0.414  -9.579  -6.249  1.00  0.00           C
ATOM      4 CA   GLU A   4       4.478   6.667  -3.109  1.00  0.00           C
ATOM      5 CA   PHE A   5     -10.748  -5.278   5.067  1.00  0.00           C
ATOM      6 CA   GLY A   6       4.912  -5.735 -11.497  1.00  0.00           C
ATOM      7 CA   HIS A   7      -4.151   5.320   2.822  1.00  0.00           C
ATOM      8 CA   ILE A   8      -8.205 -11.827  -2.956  1.00  0.00           C
ATOM      9 CA   LYS A   9       5.191   0.460 -10.439  1.00  0.00           C
ATOM     10 CA   LEU A  10     -11.758  -1.785   4.650  1.00  0.00           C
ATOM     11 CA   MET A  11      -2.100 -12.366 -11.328  1.00  0.00           C
ATOM     12 CA   ASN A  12      -0.726   3.688  -4.772  1.00  0.00           C
ATOM     13 CA   PRO A  13     -13.932 -10.592   0.134  1.00  0.00           C
ATOM     14 CA   GLN A  14       2.314  -7.467 -15.098  1.00  0.00           C
ATOM     15 CA   ARG A  15      -9.620   0.722   0.557  1.00  0.00           C
ATOM     16 CA   SER A  16     -10.093 -15.846  -8.493  1.00  0.00           C
ATOM     17 CA   THR A  17       0.974  -1.534 -12.562  1.00  0.00           C
ATOM     18 CA   VAL A  18     -16.174  -7.298   0.843  1.00  0.00           C
ATOM     19 CA   TRP A  19      -3.897 -14.794 -16.104  1.00  0.00           C
ATOM     20 CA   TYR A  20      -6.127   0.300  -6.458  1.00  0.00           C
TER
END"""
        assertEquals(expected, pdb)
    }

    @Test fun pdbDirty() {
        val seq = "MKTa\nVILA G12X"
        val coords = doubleArrayOf(1.5, -2.25, 3.125, -4.5, 0.75, 9.875)
        assertEquals("MKTAVILAGX", MolStarPdb.cleanSeq(seq))
        val pdb = MolStarPdb.pdbFromCoords(seq, coords)
        val expected = """SEQRES 1 A 2 MET LYS
ATOM      1 CA   MET A   1       1.500  -2.250   3.125  1.00  0.00           C
ATOM      2 CA   LYS A   2      -4.500   0.750   9.875  1.00  0.00           C
TER
END"""
        assertEquals(expected, pdb)
    }

    @Test fun pdbTrunc() {
        val seq = "AAAAKAAAAKAAAAKAAAAK"
        val coords = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        assertEquals("AAAAKAAAAKAAAAKAAAAK", MolStarPdb.cleanSeq(seq))
        val pdb = MolStarPdb.pdbFromCoords(seq, coords)
        val expected = """SEQRES 1 A 2 ALA ALA
ATOM      1 CA   ALA A   1       0.000   0.000   0.000  1.00  0.00           C
ATOM      2 CA   ALA A   2       1.000   1.000   1.000  1.00  0.00           C
TER
END"""
        assertEquals(expected, pdb)
    }

    @Test fun pdbUnknown() {
        val seq = "UXBZ"
        val coords = doubleArrayOf(0.1, 0.2, 0.3, 1.4, 1.5, 1.6, 2.7, 2.8, 2.9, 3.0, 3.1, 3.2)
        assertEquals("UXBZ", MolStarPdb.cleanSeq(seq))
        val pdb = MolStarPdb.pdbFromCoords(seq, coords)
        val expected = """SEQRES 1 A 4 SEC UNK UNK UNK
ATOM      1 CA   SEC A   1       0.100   0.200   0.300  1.00  0.00           C
ATOM      2 CA   UNK A   2       1.400   1.500   1.600  1.00  0.00           C
ATOM      3 CA   UNK A   3       2.700   2.800   2.900  1.00  0.00           C
ATOM      4 CA   UNK A   4       3.000   3.100   3.200  1.00  0.00           C
TER
END"""
        assertEquals(expected, pdb)
    }
}
