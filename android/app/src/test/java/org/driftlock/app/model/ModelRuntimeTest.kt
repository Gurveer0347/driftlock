package org.driftlock.app.model

import org.driftlock.core.sensors.ImuWindow
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ModelRuntimeTest {
    private fun window(): ImuWindow = ImuWindow(FloatArray(120) { if (it % 6 == 2) 9.80665f else 0f },
        DoubleArray(20) { 100.0 + it * .1 }, 101.91, 0)

    @Test fun absentModelNeverInvokesOrPublishesUsableSpeed() {
        val runtime = UnavailableModelRuntime("No approved production model")
        val result = runtime.predict(window())
        assertFalse(result.measurement.valid)
        assertEquals(0L, result.telemetry.invocations)
        assertNull(result.telemetry.modelSha256)
        assertEquals(101.9, result.measurement.t, 1e-8)
    }

    @Test fun badHashAndResearchContractsFailBeforeBackendLoading() {
        val root = Files.createTempDirectory("model-gate").toFile()
        try {
            val model = root.resolve("model.onnx").apply { writeBytes(byteArrayOf(1,2,3)) }
            val untrusted = ModelContract("driftlock-iovnbd-recorded-reference-v1", "0".repeat(64), "imu", "speed_sigma",
                reviewEvidence = "research only")
            assertThrows(IllegalArgumentException::class.java) { ModelBundle.verify(model, untrusted, emptySet()) }
            val wrongHash = ModelContract(modelSha256 = "0".repeat(64), inputName = "imu", outputName = "speed_sigma", reviewEvidence = "test gate")
            assertThrows(IllegalArgumentException::class.java) { ModelBundle.verify(model, wrongHash, setOf("0".repeat(64))) }
        } finally { root.deleteRecursively() }
    }

    @Test fun preprocessingAndClockFailuresAreRejectedBeforeInference() {
        val contract = ModelContract(modelSha256 = "a".repeat(64), inputName = "imu", outputName = "speed_sigma", reviewEvidence = "test gate")
        assertNull(ModelInputGate.reason(window(), contract))
        val t = window().timestampsS; t[5] = t[4]
        assertEquals("invalid_window_clock", ModelInputGate.reason(ImuWindow(window().channels, t, 101.91, 0), contract))
        val x = window().channels; x[0] = Float.NaN
        assertEquals("nonfinite_imu", ModelInputGate.reason(ImuWindow(x, window().timestampsS, 101.91, 0), contract))
        val shock = window().channels; shock[0] = 1000f
        assertEquals("acceleration_limit", ModelInputGate.reason(ImuWindow(shock, window().timestampsS, 101.91, 0), contract))
    }

    @Test fun changedManifestCannotReuseAnApprovedModelHash() {
        val original = "approved preprocessing".toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        assertEquals(hash, ModelBundle.verifyManifest(original, setOf(hash)))
        assertThrows(IllegalArgumentException::class.java) { ModelBundle.verifyManifest("changed preprocessing".toByteArray(), setOf(hash)) }
    }
}
