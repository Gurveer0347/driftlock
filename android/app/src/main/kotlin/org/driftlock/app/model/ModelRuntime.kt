package org.driftlock.app.model

import org.driftlock.core.sensors.ImuWindow
import org.driftlock.core.sensors.SpeedMeasurement
import org.driftlock.core.sensors.validClock
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

data class ModelContract(
    val version: String = "driftlock-ml-2.0-causal-latest", val modelSha256: String,
    val inputName: String, val outputName: String, val windowSamples: Int = 20,
    val sampleHz: Double = 10.0, val reviewEvidence: String,
    val mean: List<Double> = List(6) { 0.0 }, val std: List<Double> = List(6) { 1.0 },
    val maxStandardizedAbs: Double = 20.0, val maxSigmaMps: Double = 5.0,
) {
    fun validate() {
        require(version == "driftlock-ml-2.0-causal-latest") { "Incompatible model contract" }
        require(modelSha256.matches(Regex("[0-9a-f]{64}")) && inputName.isNotBlank() && outputName.isNotBlank())
        require(windowSamples in 2..200 && sampleHz == 10.0 && reviewEvidence.isNotBlank())
        require(mean.size == 6 && std.size == 6 && mean.all { it.isFinite() } && std.all { it.isFinite() && it > 0 })
        require(maxStandardizedAbs.isFinite() && maxStandardizedAbs > 0 && maxSigmaMps.isFinite() && maxSigmaMps > 0)
    }
}

object ModelBundle {
    fun verifyManifest(bytes: ByteArray, approvedHashes: Set<String>): String {
        require(bytes.isNotEmpty() && bytes.size <= 65_536) { "Missing or oversized manifest" }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(hash in approvedHashes) { "Production manifest SHA256 is not approved" }
        return hash
    }
    /** Approval is a separate reviewed hash allowlist, never a model's self-declared status. */
    fun verify(file: File, contract: ModelContract, approvedHashes: Set<String>): ByteArray {
        contract.validate()
        require(contract.modelSha256 in approvedHashes) { "Production model hash is not approved" }
        require(file.isFile && file.length() in 1..16_777_216) { "Missing or oversized model" }
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(digest == contract.modelSha256) { "Model SHA256 mismatch" }
        return bytes
    }
}

data class ModelTelemetry(val modelSha256: String? = null, val requests: Long = 0, val invocations: Long = 0,
                          val eligible: Long = 0, val rejected: Long = 0, val lastLatencyMs: Double? = null,
                          val status: String = "unavailable", val reason: String? = null, val manifestSha256: String? = null)
data class ModelResult(val measurement: SpeedMeasurement, val telemetry: ModelTelemetry)
interface ModelRuntime : AutoCloseable {
    val telemetry: ModelTelemetry
    val windowSamples: Int get() = 20
    fun predict(window: ImuWindow): ModelResult
}

internal fun targetTime(window: ImuWindow): Double {
    val target = window.timestampsS.lastOrNull() ?: throw IllegalArgumentException("Missing window timestamps")
    require(target.isFinite() && target >= 0 && target < 10_000_000) { "No usable boot-clock target" }
    return target
}

class UnavailableModelRuntime(private val unavailableReason: String) : ModelRuntime {
    override var telemetry = ModelTelemetry(reason = unavailableReason); private set
    override fun predict(window: ImuWindow): ModelResult {
        val t = targetTime(window)
        telemetry = telemetry.copy(requests = telemetry.requests + 1, rejected = telemetry.rejected + 1)
        return ModelResult(SpeedMeasurement(t, 0.0, 60.0, false), telemetry)
    }
    override fun close() = Unit
}

object ModelInputGate {
    fun reason(window: ImuWindow, contract: ModelContract): String? {
        val t = window.timestampsS; val x = window.channels
        if (t.size != contract.windowSamples || x.size != contract.windowSamples * 6) return "wrong_window_shape"
        if (t.any { !validClock(it, window.availableT) } || t.asList().zipWithNext().any { (a,b) -> abs((b-a) - 1.0 / contract.sampleHz) > .01 }) return "invalid_window_clock"
        if (x.any { !it.isFinite() }) return "nonfinite_imu"
        for (i in t.indices) {
            val a = sqrt((0..2).sumOf { x[i*6+it].toDouble()*x[i*6+it] })
            val g = sqrt((3..5).sumOf { x[i*6+it].toDouble()*x[i*6+it] })
            if (a < .1 || a > 80) return "acceleration_limit"
            if (g > 5) return "gyro_limit"
            if ((0..5).any { abs((x[i*6+it]-contract.mean[it])/contract.std[it]) > contract.maxStandardizedAbs }) return "input_distribution_limit"
        }
        return null
    }
}
