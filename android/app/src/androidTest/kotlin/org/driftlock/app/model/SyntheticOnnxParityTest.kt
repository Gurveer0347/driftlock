package org.driftlock.app.model

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.abs

/** Test-APK assets only. Never loads, approves or publishes a production model. */
@RunWith(AndroidJUnit4::class)
class SyntheticOnnxParityTest {
    @Test fun untrainedSyntheticCpuNumericalParityOnly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        fun bytes(name: String) = assets.open("synthetic_mobile_parity/$name").use { it.readBytes() }
        fun hash(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
        fun floats(value: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        val metadataBytes = bytes("metadata.json")
        val metadata = JSONObject(metadataBytes.toString(Charsets.UTF_8))
        assertEquals("UNTRAINED_SYNTHETIC_NUMERICAL_PARITY_ONLY", metadata.getString("purpose"))
        assertFalse(metadata.getBoolean("production_approved"))
        val hashes = metadata.getJSONObject("hashes")
        for (name in hashes.keys()) assertEquals("Asset hash: $name", hashes.getString(name), hash(bytes(name)))
        val manifest = JSONObject(bytes("manifest.json").toString(Charsets.UTF_8))
        assertEquals("SOFTWARE_CHECK_ONLY", manifest.getString("review_status"))
        assertEquals("synthetic_software_check", manifest.getString("source_type"))
        assertFalse(manifest.getBoolean("uncertainty_calibrated"))
        assertEquals("driftlock-ml-2.0-causal-latest", manifest.getString("model_contract_version"))
        assertEquals(hash(bytes("model.onnx")), manifest.getString("model_sha256"))
        val inputs = floats(bytes("inputs.bin")); val expected = floats(bytes("outputs.bin"))
        assertEquals(4 * 20 * 6, inputs.size); assertEquals(8, expected.size)
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
        val session = options.use { environment.createSession(bytes("model.onnx"), it) }
        var invocations = 0; var maximumError = 0.0
        val latency = mutableListOf<Double>(); val actual = JSONArray()
        session.use {
            fun infer(index: Int): FloatArray {
                val start = index * 120
                val values = inputs.copyOfRange(start, start + 120)
                OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), longArrayOf(1, 20, 6)).use { input ->
                    invocations++
                    session.run(mapOf("imu" to input)).use { result ->
                        val output = result.get("speed_sigma").orElseThrow() as OnnxTensor
                        return FloatArray(2).also { output.floatBuffer.get(it) }
                    }
                }
            }
            repeat(4) { sample ->
                val output = infer(sample)
                repeat(2) { channel ->
                    val reference = expected[2 * sample + channel].toDouble()
                    val error = abs(output[channel] - reference)
                    maximumError = maxOf(maximumError, error)
                    assertTrue("Nonfinite output", output[channel].isFinite())
                    assertTrue("CPU numerical parity failed: $error", error <= 2e-5 + 2e-5 * abs(reference))
                }
                assertTrue(output[1] > 0f)
                actual.put(JSONArray(output.map { value -> value.toDouble() }))
            }
            repeat(5) { infer(0) }
            repeat(100) {
                val started = System.nanoTime(); infer(it % 4)
                latency.add((System.nanoTime() - started) / 1e6)
            }
        }
        assertEquals(109, invocations)
        val sorted = latency.sorted()
        val report = JSONObject().put("purpose", metadata.getString("purpose"))
            .put("source_type", "synthetic_software_check").put("training_status", "UNTRAINED")
            .put("production_approved", false).put("vehicle_accuracy_measured", false)
            .put("android_model_runtime_gate_bypassed_for_test_only", false)
            .put("execution_path", "instrumentation_direct_ort_cpu_test_assets_only")
            .put("model_sha256", hash(bytes("model.onnx"))).put("manifest_sha256", hash(bytes("manifest.json")))
            .put("test_metadata_sha256", hash(metadataBytes)).put("asset_hashes", hashes)
            .put("onnxruntime_dependency", "1.22.0").put("cpu_threads", 1)
            .put("device_manufacturer", Build.MANUFACTURER).put("device_model", Build.MODEL)
            .put("android_sdk", Build.VERSION.SDK_INT).put("android_release", Build.VERSION.RELEASE)
            .put("supported_abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("onnx_invocations", invocations).put("parity_invocations", 4).put("warmup_invocations", 5)
            .put("latency_invocations", 100).put("maximum_absolute_error", maximumError)
            .put("latency_ms", JSONArray(latency)).put("median_latency_ms", (sorted[49] + sorted[50]) / 2)
            .put("p95_latency_ms", sorted[94]).put("actual_outputs", actual)
        val output = File(instrumentation.targetContext.cacheDir, "synthetic_mobile_parity.json")
        output.writeText(report.toString(2))
        Log.i("DRIFTLOCK_SYNTHETIC_PARITY", report.toString())
        instrumentation.sendStatus(0, Bundle().apply { putString("synthetic_parity_report", output.absolutePath) })
    }
}
