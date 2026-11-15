package com.spice.nativeapp.tools

import androidx.test.core.app.ApplicationProvider
import com.spice.nativeapp.tools.annotate.createDefaultProject
import com.spice.nativeapp.tools.annotate.loadSnapshot as annotateLoad
import com.spice.nativeapp.tools.annotate.saveSnapshot as annotateSave
import com.spice.nativeapp.tools.blot.BlotStorage
import com.spice.nativeapp.tools.blot.emptyProject as blotEmpty
import com.spice.nativeapp.tools.colony.emptyProject as colonyEmpty
import com.spice.nativeapp.tools.colony.loadSnapshot as colonyLoad
import com.spice.nativeapp.tools.colony.saveSnapshot as colonySave
import com.spice.nativeapp.tools.gel.GelStorage
import com.spice.nativeapp.tools.gel.GelTypes
import com.spice.nativeapp.tools.qpcr.QpcrStorage
import com.spice.nativeapp.tools.qpcr.emptyProject as qpcrEmpty
import com.spice.nativeapp.tools.qpcr.projectToJson
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Autosave round-trip audit: every tool's default/empty project must persist
 * through SharedPreferences and reload without a serialization exception.
 * Regression for the qPCR "storage full" false alarm, which was a JSONException
 * thrown inside mapping.toJson() and swallowed by the saveSnapshot catch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StorageRoundTripTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun qpcr_empty_project_round_trips() {
        val p = qpcrEmpty()
        assertTrue("qPCR save failed: ${projectToJson(p)}", QpcrStorage.saveSnapshot(ctx, p))
        assertNotNull("qPCR reload failed", QpcrStorage.loadSnapshot(ctx))
    }

    @Test
    fun gel_empty_project_round_trips() {
        val p = GelTypes.emptyProject()
        assertTrue("gel save failed", GelStorage.saveSnapshot(ctx, p))
        assertNotNull("gel reload failed", GelStorage.loadSnapshot(ctx))
    }

    @Test
    fun blot_empty_project_round_trips() {
        val p = blotEmpty()
        p.imageDataUrl = "data:image/png;base64,iVBORw0KGgo="
        p.sourceWidth = 1
        p.sourceHeight = 1
        assertTrue("blot save failed", BlotStorage.saveSnapshot(ctx, p))
        assertNotNull("blot reload failed", BlotStorage.loadSnapshot(ctx))
    }

    @Test
    fun colony_empty_project_round_trips() {
        val p = colonyEmpty()
        assertTrue("colony save failed", colonySave(ctx, p))
        assertNotNull("colony reload failed", colonyLoad(ctx))
    }

    @Test
    fun annotate_default_project_round_trips() {
        val p = createDefaultProject()
        assertTrue("annotate save failed", annotateSave(ctx, p))
        assertNotNull("annotate reload failed", annotateLoad(ctx))
    }
}
