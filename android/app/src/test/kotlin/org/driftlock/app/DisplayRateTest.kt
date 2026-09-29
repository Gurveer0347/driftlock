package org.driftlock.app
import org.junit.Test
import org.junit.Assert.*
class DisplayRateTest {
    @Test fun requestsSupported120HzWithoutChangingResolutionOrSystemSettings() {
        assertEquals(120.00001f,preferredSmoothRefreshRate(floatArrayOf(60f,90f,120.00001f)),.001f)
    }
    @Test fun lowerRatePhonesRetainTheirHighestSupportedRate() {
        assertEquals(60f,preferredSmoothRefreshRate(floatArrayOf(60f)),.001f)
        assertEquals(90f,preferredSmoothRefreshRate(floatArrayOf(60f,90f)),.001f)
        assertEquals(0f,preferredSmoothRefreshRate(floatArrayOf()),.001f)
    }
}
