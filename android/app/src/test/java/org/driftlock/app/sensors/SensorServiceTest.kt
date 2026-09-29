package org.driftlock.app.sensors

import android.Manifest
import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.driftlock.core.sensors.*
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensorServiceTest {
    @Test fun modelCompletionUsesProcessingClockAndPrecedesTriggeringRawTick() {
        val controller = Robolectric.buildService(SensorService::class.java).create()
        val service = controller.get()
        val binder = service.onBind(null)
        val events = mutableListOf<String>()
        var available = 0.0
        val done = CountDownLatch(1)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        binder.attach(object : SensorSink {
            override fun onImu(sample: PhoneImu) { events.add("imu") }
            override fun onGnss(fix: PhoneGnss) = Unit
            override fun onMag(sample: PhoneMag) = Unit
            override fun onDiscontinuity(reason: String, receiptT: Double) = Unit
        }, { result, receipt -> assertFalse(result.measurement.valid); available = receipt; events.add("model") })
        binder.dispatch {
            repeat(20) { i ->
                val t = 1.0 + i*.1
                service.processImu(PhoneImu(t,t+.01,doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(0.0,0.0,0.0),0))
            }
            done.countDown()
        }
        assertTrue(done.await(5,TimeUnit.SECONDS))
        assertEquals(listOf("model","imu"),events.takeLast(2))
        assertTrue("Completion must include processing/queue time, not reuse 2.91s sensor receipt",available >= 10.0)
        controller.destroy()
    }
    @Test fun permissionDenialCannotStartRealRecording() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val controller = Robolectric.buildService(SensorService::class.java).create()
        val service = controller.get()
        service.onStartCommand(Intent().setAction(SensorService.ACTION_START), 0, 1)
        assertFalse(service.states.value.active)
        assertTrue(service.states.value.status.contains("permission"))
        assertEquals(0L, service.states.value.model.invocations)
        controller.destroy()
    }
}
