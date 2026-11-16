#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
教材对照复核脚本（Lehninger Principles of Biochemistry 第七版，第 27 章遗传密码）。

用途：把 android 侧 Kotlin 源码中固化的遗传密码表 / 同义密码子分组 / 起始终止密码子
解析出来，与标准遗传密码（DNA 形式，U→T）逐项比对，输出错配、缺失、多余。

标准表依据（教材 ch037，图 27-7 密码子字典；表 27-3 简并性）：
- 64 个三联体，61 编码 + 3 终止（TAA/TAG/TGA）。
- 起始 ATG（原核可 GTG/TTG 编 Met；软件 ORF 起始口径在注释中说明）。
- Met、Trp 单密码子；Arg、Leu、Ser 各 6 个。
- 同组密码子仅在第三位碱基不同（简并性 / 摆动假说）。

运行：在 android-md3/ 目录下执行  `python3 docs/audit/verify_codon_tables.py`
退出码：0 = 全部一致；1 = 存在错配/缺失/多余。
"""

import re
import sys
from pathlib import Path

CORE = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java" / "com" / "spice" / "nativeapp" / "core"

# 标准遗传密码（DNA，5'→3'）。键为密码子，值为单字母氨基酸，终止记 '*'。
STANDARD = {}
_GROUPS = {
    "F": ["TTT", "TTC"],
    "L": ["TTA", "TTG", "CTT", "CTC", "CTA", "CTG"],
    "I": ["ATT", "ATC", "ATA"],
    "M": ["ATG"],
    "V": ["GTT", "GTC", "GTA", "GTG"],
    "S": ["TCT", "TCC", "TCA", "TCG", "AGT", "AGC"],
    "P": ["CCT", "CCC", "CCA", "CCG"],
    "T": ["ACT", "ACC", "ACA", "ACG"],
    "A": ["GCT", "GCC", "GCA", "GCG"],
    "Y": ["TAT", "TAC"],
    "H": ["CAT", "CAC"],
    "Q": ["CAA", "CAG"],
    "N": ["AAT", "AAC"],
    "K": ["AAA", "AAG"],
    "D": ["GAT", "GAC"],
    "E": ["GAA", "GAG"],
    "C": ["TGT", "TGC"],
    "R": ["CGT", "CGC", "CGA", "CGG", "AGA", "AGG"],
    "G": ["GGT", "GGC", "GGA", "GGG"],
    "W": ["TGG"],
    "*": ["TAA", "TAG", "TGA"],
}
for _aa, _codons in _GROUPS.items():
    for _c in _codons:
        STANDARD[_c] = _aa

BASES = ["T", "C", "A", "G"]


def read(name: str) -> str:
    return (CORE / name).read_text(encoding="utf-8")


def check_codon_to_aa(label: str, mapping: dict) -> list:
    """codon -> AA 映射与标准表比对，返回问题列表。"""
    problems = []
    for codon, aa in sorted(mapping.items()):
        if codon not in STANDARD:
            problems.append(f"[多余] {label}: {codon} 不在标准 64 密码子内")
        elif STANDARD[codon] != aa:
            problems.append(f"[错配] {label}: {codon} 应为 {STANDARD[codon]}，软件记为 {aa}")
    for codon in STANDARD:
        if codon not in mapping:
            problems.append(f"[缺失] {label}: {codon} 未收录（标准编码 {STANDARD[codon]}）")
    return problems


def parse_codon_to_aa_file(filename: str, label: str) -> dict:
    """解析 `"TTT" to "F"` 或 `"TTT" to 'F'` 两种写法。"""
    text = read(filename)
    mapping = {}
    for m in re.finditer(r'"([ATCG]{3})"\s*to\s*[\'"]([A-Z*])[\'"]', text):
        mapping[m.group(1)] = m.group(2)
    return mapping


def parse_code_sign_puts() -> dict:
    """解析 CodeSign.kt 内联 `put('F', "TTT", "TTC", ...)` 形式。"""
    text = read("CodeSign.kt")
    mapping = {}
    for m in re.finditer(r"put\('([A-Z*])',\s*([^)]*)\)", text):
        aa = m.group(1)
        for c in re.findall(r'"([ATCG]{3})"', m.group(2)):
            mapping[c] = aa
    return mapping


def parse_bio_table() -> dict:
    """解析 Bio.kt 四行 16 字符 AA_TABLE（BASES=T,C,A,G，行内同序）。"""
    text = read("Bio.kt")
    rows = re.findall(r'"([A-Z*]{16})"', text)
    if len(rows) != 4:
        return {}
    mapping = {}
    for i1, row in enumerate(rows):
        for i2, ch2 in enumerate(BASES):
            for i3, ch3 in enumerate(BASES):
                codon = BASES[i1] + ch2 + ch3
                mapping[codon] = row[i2 * 4 + i3]
    return mapping


def parse_aa_to_codons(filename: str) -> dict:
    """解析 `"F" to listOf("TTT", "TTC")`（AA -> 密码子列表）。"""
    text = read(filename)
    mapping = {}
    for m in re.finditer(r'"([A-Z*])"\s*to\s*listOf\(([^)]*)\)', text):
        aa = m.group(1)
        codons = re.findall(r'"([ATCG]{3})"', m.group(2))
        mapping[aa] = codons
    return mapping


def check_aa_to_codons(label: str, mapping: dict) -> list:
    problems = []
    # 标准分组反查表：codon -> aa
    for aa, codons in mapping.items():
        for c in codons:
            if c not in STANDARD:
                problems.append(f"[多余] {label}: {aa} 组含非法密码子 {c}")
            elif STANDARD[c] != aa:
                problems.append(f"[错配] {label}: {c} 标准属 {STANDARD[c]}，被归入 {aa} 组")
    for aa, codons in _GROUPS.items():
        got = set(mapping.get(aa, []))
        want = set(codons)
        if got != want:
            missing = want - got
            extra = got - want
            for c in sorted(missing):
                problems.append(f"[缺失] {label}: {aa} 组缺 {c}")
            for c in sorted(extra):
                problems.append(f"[多余] {label}: {aa} 组多出 {c}")
    return problems


def check_synonym_map(filename: str, label: str) -> list:
    """同义突变 map：键/值密码子必须同属一个氨基酸（或同属终止）。"""
    text = read(filename)
    problems = []
    for m in re.finditer(r'"([ATCG]{3})"\s*to\s*listOf\(([^)]*)\)', text):
        key, val_block = m.group(1), m.group(2)
        if key not in STANDARD:
            problems.append(f"[非法键] {label}: {key}")
            continue
        key_aa = STANDARD[key]
        for c in re.findall(r'"([ATCG]{3})"', val_block):
            if c not in STANDARD:
                problems.append(f"[多余] {label}: {key} 同义列表含非法 {c}")
            elif STANDARD[c] != key_aa:
                problems.append(f"[跨氨基酸] {label}: {key}({key_aa}) 同义列表含 {c}({STANDARD[c]})")
    return problems


def check_start_stop(filename: str) -> list:
    text = read(filename)
    problems = []
    starts = re.findall(r'startCodons\s*=\s*listOf\(([^)]*)\)', text)
    stops = re.findall(r'stopCodons\s*=\s*listOf\(([^)]*)\)', text)
    if starts:
        got = set(re.findall(r'"([ATCG]{3})"', starts[0]))
        want = {"ATG", "GTG", "TTG"}  # 原核起始备选口径（教材 ch037：GUG 可作起始编 Met）
        if got != want:
            problems.append(f"[起始] NgsQc 起始集 {sorted(got)}，口径 {sorted(want)}")
    if stops:
        got = set(re.findall(r'"([ATCG]{3})"', stops[0]))
        want = {"TAA", "TAG", "TGA"}
        if got != want:
            problems.append(f"[终止] NgsQc 终止集 {sorted(got)}，应为 {sorted(want)}")
    return problems


def main() -> int:
    all_problems = []

    bio = parse_bio_table()
    all_problems += check_codon_to_aa("Bio.AA_TABLE", bio)

    for fn, label in [
        ("CodonOpt.kt", "CodonOpt.AA_OF_CODON"),
        ("SyntheticGeneQc.kt", "SyntheticGeneQc.CODON_TO_AA"),
    ]:
        mapping = parse_codon_to_aa_file(fn, label)
        all_problems += check_codon_to_aa(label, mapping)

    all_problems += check_codon_to_aa("CodeSign.CODON_TO_AA", parse_code_sign_puts())

    aa_groups = parse_aa_to_codons("ReverseTranslate.kt")
    all_problems += check_aa_to_codons("ReverseTranslate.SYNONYMOUS_BY_AA", aa_groups)

    all_problems += check_synonym_map("Crispr.kt", "Crispr.CODON_MAP")
    all_problems += check_start_stop("NgsQc.kt")

    print(f"标准密码子数：{len(STANDARD)}（61 编码 + 3 终止）")
    print(f"Bio 表收录：{len(bio)}")
    print(f"CodonOpt 收录：{len(parse_codon_to_aa_file('CodonOpt.kt', ''))}")
    print(f"CodeSign 收录：{len(parse_code_sign_puts())}")
    print(f"ReverseTranslate 分组氨基酸数：{len(aa_groups)}")
    print("-" * 60)
    if all_problems:
        print(f"发现 {len(all_problems)} 项问题：")
        for p in all_problems:
            print("  - " + p)
        return 1
    print("全部一致：0 错配 / 0 缺失 / 0 多余。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
