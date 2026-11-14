package com.spice.nativeapp

import com.spice.nativeapp.core.Enzymes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.7.15：图谱切点酶集对齐 main（+page.svelte:1992 COMMON_ENZYMES / getInitialSelectedEnzymes verbatim） */
class EnzymeSetsTest {

    @Test
    fun `mapCommon 是 main COMMON_ENZYMES 47 项 verbatim`() {
        assertEquals(47, Enzymes.mapCommon.size)
        assertTrue(Enzymes.mapCommon.contains("AatII"))
        val expected = setOf(
            "EcoRI", "BamHI", "HindIII", "XhoI", "NdeI", "SacI", "SalI", "KpnI",
            "EcoRV", "XbaI", "SpeI", "PstI", "SphI", "NotI", "AatII", "BglII",
            "SmaI", "XmaI", "NcoI", "NheI", "AflII", "AvrII", "BspEI", "BstBI",
            "ClaI", "DraI", "HaeIII", "HpaI", "MluI", "MscI", "NruI", "PvuII",
            "SacII", "ScaI", "StuI", "SwaI", "BsaI", "BbsI", "BsmBI", "BtgZI",
            "SapI", "BspQI", "AarI", "FokI", "EcoO109I", "HincII", "Sse8387I"
        )
        assertEquals(expected, Enzymes.mapCommon)
    }

    @Test
    fun `common 默认勾选是 main getInitialSelectedEnzymes 8 项`() {
        assertEquals(
            setOf("EcoRI", "BamHI", "HindIII", "XhoI", "NdeI", "SacI", "SalI", "KpnI"),
            Enzymes.common
        )
    }

    @Test
    fun `mapCommon 全部 47 项可在酶库解析（digest 不漏酶）`() {
        Enzymes.mapCommon.forEach { name ->
            assertTrue("未解析: $name", Enzymes.byName(name) != null)
        }
    }
}
