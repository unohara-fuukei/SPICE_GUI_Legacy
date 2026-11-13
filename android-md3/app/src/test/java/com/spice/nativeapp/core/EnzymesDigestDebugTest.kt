package com.spice.nativeapp.core

import org.junit.Test
import org.junit.Assert.assertTrue

/** 验证 pj5_00001 上 EcoRI/BamHI/HindIII 的真实切点数（图谱酶名标签缺失排查） */
class EnzymesDigestDebugTest {

    @Test
    fun digest_pj5_commonEnzymes() {
        val rec = Bio.demoPlasmid()
        val hits = Enzymes.digest(rec.sequence, setOf("EcoRI", "BamHI", "HindIII"), false)
        println("digest linear hits=" + hits.size + " -> " + hits.take(12))
        val hitsAll = Enzymes.digest(
            rec.sequence,
            setOf("EcoRI", "BamHI", "HindIII", "PstI", "XhoI", "KpnI", "SacI", "SpeI", "NotI"),
            false
        )
        println("digest all hits=" + hitsAll.size + " -> " + hitsAll.take(20))
        assertTrue(hitsAll.isNotEmpty())
    }
}
