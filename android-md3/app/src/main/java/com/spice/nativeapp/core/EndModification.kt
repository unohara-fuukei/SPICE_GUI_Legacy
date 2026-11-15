package com.spice.nativeapp.core

/**
 * DNA 末端修饰引擎 —— 1:1 移植 main `src/lib/genome/endModification.ts`。
 *
 * 物理模拟 Klenow 补平 / T4 削平 / 磷酸化状态切换，用于非环状载体与插入片段
 * 制备建模。纯函数，无外部依赖。
 */
object EndModification {

    /** 末端类型（对齐 main OverhangType） */
    const val BLUNT = "blunt"
    const val FIVE_OVERHANG = "5-overhang"
    const val THREE_OVERHANG = "3-overhang"

    data class LinearDnaEnd(
        val overhangType: String = BLUNT,
        /** 单链突出端序列 */
        val overhangSeq: String = "",
        /** 5' 端是否带磷酸基团（连接所必需） */
        val phosphorylated: Boolean = true,
    )

    data class LinearDna(
        val sequence: String,
        val leftEnd: LinearDnaEnd,
        val rightEnd: LinearDnaEnd,
    )

    /**
     * 模拟 DNA 聚合酶 I 大片段（Klenow）：补平 5' 突出端，在短链上加上互补核苷酸；
     * 平末端与 3' 突出端保持不变。
     */
    fun fillIn(dna: LinearDna): LinearDna {
        var seq = dna.sequence
        var left = dna.leftEnd
        var right = dna.rightEnd

        // 左端 5' 突出端属于 top 链；Klenow 补平的是 recessed 的 bottom 链，
        // top 链序列（dnaSeq）不变 —— 对齐 main 注释中修正过的语义。
        if (left.overhangType == FIVE_OVERHANG) {
            left = left.copy(overhangType = BLUNT, overhangSeq = "")
        }

        // 右端 5' 突出端属于 bottom 链；Klenow 延伸 top 链 3' 端。两链反向平行，
        // 追加碱基为突出端序列的反向互补（主链互补仅对 AATT 等回文恰好成立）。
        if (right.overhangType == FIVE_OVERHANG && right.overhangSeq.isNotEmpty()) {
            seq = seq + Bio.revComp(right.overhangSeq)
            right = right.copy(overhangType = BLUNT, overhangSeq = "")
        }

        return LinearDna(seq, left, right)
    }

    /**
     * 模拟 T4 DNA 聚合酶：借助强 3'→5' 外切酶活性削平 3' 突出端得到平末端；
     * 平末端与 5' 突出端保持不变。
     */
    fun chewBack(dna: LinearDna): LinearDna {
        var seq = dna.sequence
        var left = dna.leftEnd
        var right = dna.rightEnd

        // 左端 3' 突出端在 bottom 链上 —— 移除它不改变 top 链序列。
        if (left.overhangType == THREE_OVERHANG) {
            left = left.copy(overhangType = BLUNT, overhangSeq = "")
        }

        // 右端 3' 突出端即 top 链自身尾部 —— 外切酶按突出端长度截短序列。
        if (right.overhangType == THREE_OVERHANG) {
            val n = right.overhangSeq.length
            seq = seq.substring(0, maxOf(0, seq.length - n))
            right = right.copy(overhangType = BLUNT, overhangSeq = "")
        }

        return LinearDna(seq, left, right)
    }

    /**
     * 模拟碱性磷酸酶（AP/CIP）或 T4 多核苷酸激酶（PNK）处理：调整 DNA 末端 5' 磷酸化状态。
     */
    fun setPhosphorylation(dna: LinearDna, phosphorylated: Boolean): LinearDna =
        LinearDna(
            sequence = dna.sequence,
            leftEnd = dna.leftEnd.copy(phosphorylated = phosphorylated),
            rightEnd = dna.rightEnd.copy(phosphorylated = phosphorylated),
        )
}
