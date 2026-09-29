package org.driftlock.app

/** Virtual generated-input clock. Wall time schedules playback, never retimestamps source rows.
 * Presenter pauses freeze both synthetic acquisition and receipt time. Real phone clocks do not use this.
 */
internal class DemoReplayClock(private val originS:Double) {
    var elapsedS=0.0
        private set
    private var lastWallS=originS
    fun advance(wallS:Double,paused:Boolean):Double {
        val step=(wallS-lastWallS).coerceIn(0.0,.25)
        lastWallS=wallS
        if(!paused)elapsedS+=step
        return elapsedS
    }
    fun pauseAt(seconds:Double) {elapsedS=seconds}
    fun sourceTimestamp(relativeS:Double)=originS+relativeS
}
