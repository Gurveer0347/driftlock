package org.driftlock.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedTimelineTest {
    @Test fun outageClosureChoiceAndRecoveryHaveDistinctOrderedStages() {
        assertEquals(GuidedStage.DEPARTING,GuidedTimeline.stage(0.0,false))
        assertEquals(GuidedStage.CRUISE,GuidedTimeline.stage(6.0,false))
        assertEquals(GuidedStage.OFFLINE,GuidedTimeline.stage(9.0,false))
        assertEquals(GuidedStage.ROADBLOCK,GuidedTimeline.stage(17.0,false))
        assertEquals(GuidedStage.ROADBLOCK,GuidedTimeline.stage(40.0,false))
        assertEquals(GuidedStage.TURNBACK,GuidedTimeline.stage(18.0,true))
        assertEquals(GuidedStage.DETOUR,GuidedTimeline.stage(26.0,true))
        assertEquals(GuidedStage.RECOVERY,GuidedTimeline.stage(39.0,true))
        assertEquals(GuidedStage.ARRIVED,GuidedTimeline.stage(45.0,true))
        assertEquals(GuidedStage.ARRIVED,GuidedTimeline.stage(44.9,true,true))
    }

    @Test fun visualProgressStopsAtClosureUntilAlternativeIsChosen() {
        assertEquals(0.5,GuidedTimeline.primaryProgress(8.5),1e-9)
        assertEquals(1.0,GuidedTimeline.primaryProgress(40.0),1e-9)
        assertEquals(0.0,GuidedTimeline.detourProgress(17.0),1e-9)
        assertEquals(0.0,GuidedTimeline.detourProgress(17.0,false),1e-9)
        assertEquals(1.0,GuidedTimeline.detourProgress(45.0,true),1e-9)
    }
}
