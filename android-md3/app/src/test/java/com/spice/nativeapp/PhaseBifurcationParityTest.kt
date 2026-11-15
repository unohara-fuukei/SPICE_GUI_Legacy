package com.spice.nativeapp

import com.spice.nativeapp.core.PhaseBifurcation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * T7-16 solve_phase_bifurcation（cloning.rs:2405）parity：基准由独立 cargo bin
 * 直跑 rust 真身生成（3 组拟合 + <6 点错误路径）。
 */
class PhaseBifurcationParityTest {

    @Test
    fun testPhaseCase1() {
        val pts = listOf(PhaseBifurcation.ScanPoint(300.0, 5.0, false, true, false), PhaseBifurcation.ScanPoint(300.0, 6.5, false, false, false), PhaseBifurcation.ScanPoint(300.0, 8.0, false, false, false), PhaseBifurcation.ScanPoint(320.0, 5.0, false, false, false), PhaseBifurcation.ScanPoint(320.0, 6.5, true, false, false), PhaseBifurcation.ScanPoint(320.0, 8.0, false, false, false), PhaseBifurcation.ScanPoint(340.0, 5.0, false, false, false), PhaseBifurcation.ScanPoint(340.0, 6.5, false, false, false), PhaseBifurcation.ScanPoint(340.0, 8.0, false, true, false))
        val out = PhaseBifurcation.solvePhaseBifurcation(pts)
        assertEquals(320.0, out.optimalTempK, 1e-9)
        assertEquals(6.5, out.optimalPh, 1e-9)
        assertEquals(true, out.isSaddle)
        assertEquals(6, out.coefficients.size)
        assertEquals(-1.166700005531311, out.coefficients[0], 1e-9)
        assertEquals(0.0, out.coefficients[1], 1e-9)
        assertEquals(0.666700005531311, out.coefficients[2], 1e-9)
        assertEquals(0.0, out.coefficients[3], 1e-9)
        assertEquals(0.666700005531311, out.coefficients[4], 1e-9)
        assertEquals(0.5, out.coefficients[5], 1e-9)
        assertEquals(320.0, out.meanT, 1e-9)
        assertEquals(16.329931259155273, out.stdT, 1e-9)
        assertEquals(6.5, out.meanPh, 1e-9)
        assertEquals(1.2247449159622192, out.stdPh, 1e-9)
        assertEquals(58, out.boundaryCurve.size)
        assertEquals(297.1380920410156, out.boundaryCurve[0].first, 1e-5)
        assertEquals(6.8292436599731445, out.boundaryCurve[0].second, 1e-5)
        assertEquals(297.1380920410156, out.boundaryCurve[1].first, 1e-5)
        assertEquals(7.456738471984863, out.boundaryCurve[1].second, 1e-5)
        assertEquals(298.7710876464844, out.boundaryCurve[2].first, 1e-5)
        assertEquals(6.428868293762207, out.boundaryCurve[2].second, 1e-5)
        assertEquals(298.7710876464844, out.boundaryCurve[3].first, 1e-5)
        assertEquals(7.765257835388184, out.boundaryCurve[3].second, 1e-5)
        assertEquals(300.4040832519531, out.boundaryCurve[4].first, 1e-5)
        assertEquals(6.174351215362549, out.boundaryCurve[4].second, 1e-5)
        assertEquals(300.4040832519531, out.boundaryCurve[5].first, 1e-5)
        assertEquals(7.927918910980225, out.boundaryCurve[5].second, 1e-5)
        assertEquals(320.0, out.deepestWellT, 1e-9)
        assertEquals(6.5, out.deepestWellPh, 1e-9)
        assertEquals(297.1000061035156, out.bifurcationT, 1e-9)
        assertEquals(7.460000038146973, out.bifurcationPh, 1e-9)
    }

    @Test
    fun testPhaseCase2() {
        val pts = listOf(PhaseBifurcation.ScanPoint(310.0, 6.0, true, false, false), PhaseBifurcation.ScanPoint(320.0, 6.5, true, false, false), PhaseBifurcation.ScanPoint(330.0, 7.0, true, false, false), PhaseBifurcation.ScanPoint(350.0, 8.0, false, true, false), PhaseBifurcation.ScanPoint(300.0, 5.0, false, true, false), PhaseBifurcation.ScanPoint(340.0, 9.0, false, false, true))
        val out = PhaseBifurcation.solvePhaseBifurcation(pts)
        assertEquals(284.5, out.optimalTempK, 1e-9)
        assertEquals(7.550000190734863, out.optimalPh, 1e-9)
        assertEquals(true, out.isSaddle)
        assertEquals(6, out.coefficients.size)
        assertEquals(-1.8133000135421753, out.coefficients[0], 1e-9)
        assertEquals(1.8009999990463257, out.coefficients[1], 1e-9)
        assertEquals(0.3801000118255615, out.coefficients[2], 1e-9)
        assertEquals(-1.388700008392334, out.coefficients[3], 1e-9)
        assertEquals(1.4333000183105469, out.coefficients[4], 1e-9)
        assertEquals(0.0, out.coefficients[5], 1e-9)
        assertEquals(325.0, out.meanT, 1e-9)
        assertEquals(17.078250885009766, out.stdT, 1e-9)
        assertEquals(6.916666507720947, out.meanPh, 1e-9)
        assertEquals(1.304373025894165, out.stdPh, 1e-9)
        assertEquals(100, out.boundaryCurve.size)
        assertEquals(256.6869812011719, out.boundaryCurve[0].first, 1e-5)
        assertEquals(5.577519416809082, out.boundaryCurve[0].second, 1e-5)
        assertEquals(256.6869812011719, out.boundaryCurve[1].first, 1e-5)
        assertEquals(9.51960277557373, out.boundaryCurve[1].second, 1e-5)
        assertEquals(258.3948059082031, out.boundaryCurve[2].first, 1e-5)
        assertEquals(5.541667938232422, out.boundaryCurve[2].second, 1e-5)
        assertEquals(258.3948059082031, out.boundaryCurve[3].first, 1e-5)
        assertEquals(9.55545425415039, out.boundaryCurve[3].second, 1e-5)
        assertEquals(260.1026306152344, out.boundaryCurve[4].first, 1e-5)
        assertEquals(5.5086565017700195, out.boundaryCurve[4].second, 1e-5)
        assertEquals(260.1026306152344, out.boundaryCurve[5].first, 1e-5)
        assertEquals(9.588465690612793, out.boundaryCurve[5].second, 1e-5)
        assertEquals(284.5, out.deepestWellT, 1e-9)
        assertEquals(7.550000190734863, out.deepestWellPh, 1e-9)
        assertEquals(340.3999938964844, out.bifurcationT, 1e-9)
        assertEquals(7.039999961853027, out.bifurcationPh, 1e-9)
    }

    @Test
    fun testPhaseCase3() {
        val pts = listOf(PhaseBifurcation.ScanPoint(300.0, 4.0, false, true, false), PhaseBifurcation.ScanPoint(300.0, 7.0, true, false, false), PhaseBifurcation.ScanPoint(300.0, 10.0, false, true, false), PhaseBifurcation.ScanPoint(330.0, 4.0, true, false, false), PhaseBifurcation.ScanPoint(330.0, 10.0, true, false, false), PhaseBifurcation.ScanPoint(360.0, 4.0, false, true, false), PhaseBifurcation.ScanPoint(360.0, 7.0, true, false, false), PhaseBifurcation.ScanPoint(360.0, 10.0, false, true, false))
        val out = PhaseBifurcation.solvePhaseBifurcation(pts)
        assertEquals(330.0, out.optimalTempK, 1e-9)
        assertEquals(7.0, out.optimalPh, 1e-9)
        assertEquals(true, out.isSaddle)
        assertEquals(6, out.coefficients.size)
        assertEquals(-4.5, out.coefficients[0], 1e-9)
        assertEquals(0.0, out.coefficients[1], 1e-9)
        assertEquals(2.25, out.coefficients[2], 1e-9)
        assertEquals(0.0, out.coefficients[3], 1e-9)
        assertEquals(2.25, out.coefficients[4], 1e-9)
        assertEquals(0.0, out.coefficients[5], 1e-9)
        assertEquals(330.0, out.meanT, 1e-9)
        assertEquals(25.980762481689453, out.stdT, 1e-9)
        assertEquals(7.0, out.meanPh, 1e-9)
        assertEquals(2.598076105117798, out.stdPh, 1e-9)
        assertEquals(58, out.boundaryCurve.size)
        assertEquals(293.6269226074219, out.boundaryCurve[0].first, 1e-5)
        assertEquals(6.480384826660156, out.boundaryCurve[0].second, 1e-5)
        assertEquals(293.6269226074219, out.boundaryCurve[1].first, 1e-5)
        assertEquals(7.519615173339844, out.boundaryCurve[1].second, 1e-5)
        assertEquals(296.2250061035156, out.boundaryCurve[2].first, 1e-5)
        assertEquals(5.553452491760254, out.boundaryCurve[2].second, 1e-5)
        assertEquals(296.2250061035156, out.boundaryCurve[3].first, 1e-5)
        assertEquals(8.446547508239746, out.boundaryCurve[3].second, 1e-5)
        assertEquals(298.8230895996094, out.boundaryCurve[4].first, 1e-5)
        assertEquals(5.0557780265808105, out.boundaryCurve[4].second, 1e-5)
        assertEquals(298.8230895996094, out.boundaryCurve[5].first, 1e-5)
        assertEquals(8.944222450256348, out.boundaryCurve[5].second, 1e-5)
        assertEquals(330.0, out.deepestWellT, 1e-9)
        assertEquals(7.0, out.deepestWellPh, 1e-9)
        assertEquals(293.6000061035156, out.bifurcationT, 1e-9)
        assertEquals(6.480000019073486, out.bifurcationPh, 1e-9)
    }

    @Test
    fun testPhaseTooFewPoints() {
        val pts = listOf(
            PhaseBifurcation.ScanPoint(310.0, 6.0, true, false, false),
            PhaseBifurcation.ScanPoint(320.0, 6.5, true, false, false),
            PhaseBifurcation.ScanPoint(330.0, 7.0, true, false, false),
            PhaseBifurcation.ScanPoint(350.0, 8.0, false, true, false),
            PhaseBifurcation.ScanPoint(300.0, 5.0, false, true, false)
        )
        try {
            PhaseBifurcation.solvePhaseBifurcation(pts)
            fail("expected NeedsSixPointsException")
        } catch (e: PhaseBifurcation.NeedsSixPointsException) {
            assertEquals("i18n:beErrPhaseFitNeedsSixPoints", e.message)
        }
    }
}
