package org.driftlock.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class ResearchEvidenceTest {
    @Test fun bundledValidationEvidenceIsResearchOnlyAndPreservesBaseline() {
        val assets=RuntimeEnvironment.getApplication().assets
        val replay=assets.open("research/validation_replay.json").bufferedReader().use{it.readText()}
        val metrics=assets.open("research/validation_metrics.json").bufferedReader().use{it.readText()}
        val evidence=ResearchEvidence.parse(replay,metrics)
        assertEquals(360,evidence.points.size)
        assertEquals(78931,evidence.windows)
        assertEquals(4.886360097200595,evidence.rmse,1e-12)
        assertEquals(4.4716987502914565,evidence.modelHeldCutRmse,1e-12)
        assertEquals(2.302952869382203,evidence.holdLastRmse,1e-12)
        assertFalse(evidence.validForNavigation)
    }

    @Test fun failsClosedIfNavigationValidityOrSplitChanges() {
        val assets=RuntimeEnvironment.getApplication().assets
        val replay=assets.open("research/validation_replay.json").bufferedReader().use{it.readText()}
        val metrics=assets.open("research/validation_metrics.json").bufferedReader().use{it.readText()}
        val badReplay=JSONObject(replay).apply{getJSONObject("metadata").put("valid_for_navigation",true)}.toString()
        val badMetrics=JSONObject(metrics).apply{put("split","test")}.toString()
        assertThrows(IllegalArgumentException::class.java){ResearchEvidence.parse(badReplay,metrics)}
        assertThrows(IllegalArgumentException::class.java){ResearchEvidence.parse(replay,badMetrics)}
    }
}
