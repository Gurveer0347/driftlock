package org.driftlock.core.sensors

/** Latest-past hold on a 10 Hz grid, with raw-gap segmentation. No anti-aliasing is claimed. */
class CausalWindowAssembler(private val windowSamples: Int = 20, private val periodS: Double = .1,
                            private val maxGapS: Double = .15) {
    private var previous: PhoneImu? = null
    private var origin = 0.0
    private var nextTick = 0L
    private val rows = ArrayDeque<FloatArray>()
    private val times = ArrayDeque<Double>()
    init { require(windowSamples >= 2 && windowSamples <= 1000 && periodS > 0 && maxGapS >= periodS) }
    fun reset() { previous = null; rows.clear(); times.clear(); nextTick = 0 }
    fun push(sample: PhoneImu): List<ImuWindow> {
        if (!sample.valid()) { reset(); return emptyList() }
        var old = previous
        if (old != null && (sample.segmentId != old.segmentId || sample.t <= old.t || sample.t - old.t > maxGapS + 1e-8)) {
            reset(); old = null
        }
        if (old == null) { origin = sample.t; nextTick = 0 }
        val out = mutableListOf<ImuWindow>()
        var tick = origin + nextTick * periodS
        // Cadence tolerance must never relax source-time or availability causality.
        while (tick <= sample.t) {
            val source = if (tick == sample.t) sample else old
            if (source == null || tick - source.t > maxGapS + 1e-8 || tick < source.t) {
                rows.clear(); times.clear()
            } else {
                rows.addLast(source.channels()); times.addLast(tick)
                if (rows.size > windowSamples) { rows.removeFirst(); times.removeFirst() }
                if (rows.size == windowSamples) out.add(ImuWindow(rows.flatMap { it.asIterable() }.toFloatArray(), times.toDoubleArray(), sample.receiptT, sample.segmentId))
            }
            nextTick++; tick = origin + nextTick * periodS
        }
        previous = sample
        return out
    }
}
