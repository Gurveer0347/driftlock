package org.driftlock.app

internal enum class GuidedStage { DEPARTING, CRUISE, OFFLINE, ROADBLOCK, TURNBACK, DETOUR, RECOVERY, ARRIVED }

/** Story timing only. The native estimator still consumes its own generated IMU/GNSS replay. */
internal object GuidedTimeline {
    const val GNSS_CUT_S=8.0
    const val ROADBLOCK_S=17.0
    const val GNSS_RETURN_S=38.0
    const val END_S=45.0

    fun stage(seconds:Double,detourChosen:Boolean,finished:Boolean=false):GuidedStage=when {
        finished && detourChosen->GuidedStage.ARRIVED
        seconds<3.0->GuidedStage.DEPARTING
        seconds<GNSS_CUT_S->GuidedStage.CRUISE
        seconds<ROADBLOCK_S->GuidedStage.OFFLINE
        !detourChosen->GuidedStage.ROADBLOCK
        seconds<24.0->GuidedStage.TURNBACK
        seconds<GNSS_RETURN_S->GuidedStage.DETOUR
        seconds<END_S->GuidedStage.RECOVERY
        else->GuidedStage.ARRIVED
    }

    fun primaryProgress(seconds:Double)=(seconds/ROADBLOCK_S).coerceIn(0.0,1.0)
    fun detourProgress(seconds:Double,chosen:Boolean=true)=if(chosen)
        ((seconds-ROADBLOCK_S)/(END_S-ROADBLOCK_S)).coerceIn(0.0,1.0) else 0.0
}
