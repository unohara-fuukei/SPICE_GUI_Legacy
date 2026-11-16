#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
蛋白理化常数对照复核脚本。

基准来源：
- 游离氨基酸分子量：Lehninger 第七版 表 3-1（Mr，两位小数）；多肽质量 = Σ游离 + 18.015（一分子水）。
- Kyte-Doolittle 疏水性指数：Kyte & Doolittle, J. Mol. Biol. 157:105, 1982（教材表 3-5 同源引用）。
- pKa（EMBOSS proteincharge 口径）：Cterm 3.55 / Nterm 7.50 / D 4.05 / E 4.45 /
  C 9.00 / Y 10.00 / H 5.98 / K 10.00 / R 12.00。

解析 android 侧 ProteinCore.kt 的 MW / KD / PKA 三张表逐项比对，容差 0.011（两位小数四舍五入）。
运行：在 android-md3/ 目录下执行 `python3 docs/audit/verify_protein_tables.py`。
退出码：0 = 全部吻合；1 = 存在偏差。
"""

import re
import sys
from pathlib import Path

CORE = Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "java" / "com" / "spice" / "nativeapp" / "core"

# 标准游离氨基酸分子量（g/mol，两位小数）
MW_STD = {
    "A": 89.09, "R": 174.20, "N": 132.12, "D": 133.10, "C": 121.15,
    "E": 147.13, "Q": 146.15, "G": 75.07, "H": 155.16, "I": 131.17,
    "L": 131.17, "K": 146.19, "M": 149.21, "F": 165.19, "P": 115.13,
    "S": 105.09, "T": 119.12, "W": 204.23, "Y": 181.19, "V": 117.15,
}

# Kyte-Doolittle 原始疏水性指数
KD_STD = {
    "A": 1.8, "R": -4.5, "N": -3.5, "D": -3.5, "C": 2.5,
    "E": -3.5, "Q": -3.5, "G": -0.4, "H": -3.2, "I": 4.5,
    "L": 3.8, "K": -3.9, "M": 1.9, "F": 2.8, "P": -1.6,
    "S": -0.8, "T": -0.7, "W": -0.9, "Y": -1.3, "V": 4.2,
}

# EMBOSS 口径 pKa
PKA_STD = {
    "Cterm": 3.55, "Nterm": 7.50, "D": 4.05, "E": 4.45,
    "C": 9.00, "Y": 10.00, "H": 5.98, "K": 10.00, "R": 12.00,
}

TOL = 0.011


def read() -> str:
    return (CORE / "ProteinCore.kt").read_text(encoding="utf-8")


def _table_block(text: str, table_name: str) -> str:
    m = re.search(table_name + r"\s*=\s*mapOf\((.*?)\n    \)", text, re.S)
    return m.group(1) if m else ""


def map_char_values(text: str, table_name: str) -> dict:
    """解析 'A' to 89.09 形式的 char -> number 表。"""
    block = _table_block(text, table_name)
    return {aa: float(v) for aa, v in re.findall(r"'([A-Z])'\s*to\s*(-?[0-9.]+)", block)}


def map_key_values(text: str, table_name: str) -> dict:
    """解析 \"Cterm\" to 3.55 / 'D' to 4.05 混合键表（pKa）。"""
    block = _table_block(text, table_name)
    out = {k: float(v) for k, v in re.findall(r'"([A-Za-z]+)"\s*to\s*(-?[0-9.]+)', block)}
    out.update({aa: float(v) for aa, v in re.findall(r"'([A-Z])'\s*to\s*(-?[0-9.]+)", block)})
    return out


def diff(label: str, got: dict, std: dict) -> list:
    problems = []
    for aa, std_val in std.items():
        if aa not in got:
            problems.append(f"[缺失] {label}: {aa} 未收录")
        elif abs(got[aa] - std_val) > TOL:
            problems.append(f"[偏差] {label}: {aa} 标准 {std_val}，软件 {got[aa]}")
    for aa in got:
        if aa not in std:
            problems.append(f"[多余] {label}: {aa}")
    return problems


def main() -> int:
    text = read()
    mw = map_char_values(text, "MW")
    kd = map_char_values(text, "KD")
    pka = map_key_values(text, "PKA")

    problems = []
    problems += diff("MW 游离氨基酸", mw, MW_STD)
    problems += diff("KD 疏水性", kd, KD_STD)
    problems += diff("pKa", pka, PKA_STD)

    print(f"MW 收录：{len(mw)}/20；KD 收录：{len(kd)}/20；pKa 收录：{len(pka)}/{len(PKA_STD)}")
    print("-" * 60)
    if problems:
        print(f"发现 {len(problems)} 项问题：")
        for p in problems:
            print("  - " + p)
        return 1
    print("全部吻合：MW / KD / pKa 三张表逐项在容差内一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
