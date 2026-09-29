package org.driftlock.app.model

import ai.onnxruntime.*
import org.driftlock.core.sensors.ImuWindow
import org.driftlock.core.sensors.SpeedMeasurement
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs

/** CPU only. Owned by the service's serial processing thread; all native results are closed. */
class OnnxModelRuntime private constructor(private val session: OrtSession, private val contract: ModelContract, manifestHash: String) : ModelRuntime {
    override var telemetry = ModelTelemetry(modelSha256 = contract.modelSha256, status = "ready", manifestSha256 = manifestHash); private set
    override val windowSamples get() = contract.windowSamples
    private var closed = false
    private val environment = OrtEnvironment.getEnvironment()
    override fun predict(window: ImuWindow): ModelResult {
        val t = targetTime(window)
        telemetry = telemetry.copy(requests = telemetry.requests + 1)
        fun reject(reason: String): ModelResult {
            telemetry = telemetry.copy(rejected = telemetry.rejected + 1, reason = reason)
            return ModelResult(SpeedMeasurement(t, 0.0, 60.0, false), telemetry)
        }
        if (closed) return reject("runtime_closed")
        ModelInputGate.reason(window, contract)?.let { return reject(it) }
        val start = System.nanoTime()
        try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(window.channels), longArrayOf(1, contract.windowSamples.toLong(), 6)).use { tensor ->
                telemetry = telemetry.copy(invocations = telemetry.invocations + 1)
                session.run(mapOf(contract.inputName to tensor)).use { result ->
                    val output = result.get(contract.outputName).orElseThrow() as OnnxTensor
                    val values = output.floatBuffer
                    val speed = values.get(0).toDouble(); val sigma = values.get(1).toDouble()
                    telemetry = telemetry.copy(lastLatencyMs = (System.nanoTime()-start)/1e6)
                    if (!speed.isFinite() || abs(speed) >= 60) return reject("invalid_output_speed")
                    if (!sigma.isFinite() || sigma <= 0 || sigma > contract.maxSigmaMps) return reject("uncertainty_limit")
                    telemetry = telemetry.copy(eligible = telemetry.eligible + 1, reason = null)
                    return ModelResult(SpeedMeasurement(t, speed, sigma, true), telemetry)
                }
            }
        } catch (e: Exception) {
            telemetry = telemetry.copy(lastLatencyMs = (System.nanoTime()-start)/1e6)
            return reject("inference_failed:${e.javaClass.simpleName}")
        }
    }
    override fun close() { if (!closed) { closed = true; session.close(); telemetry = telemetry.copy(status = "closed") } }

    companion object {
        fun open(model: File, manifest: File, approvedHashes: Set<String> = emptySet()): ModelRuntime {
            return try {
                require(manifest.isFile && manifest.length() <= 65_536) { "Missing or oversized manifest" }
                val manifestBytes = manifest.readBytes()
                val manifestHash = ModelBundle.verifyManifest(manifestBytes, approvedHashes)
                val j = JSONObject(manifestBytes.toString(Charsets.UTF_8))
                require(j.getString("review_status") == "APPROVED_FOR_NAVIGATION")
                require(j.getString("time_base") == "seconds_since_boot" && j.getString("prediction_timestamp") == "latest_sample")
                require(j.getString("frame") == "phone" && j.getBoolean("acceleration_includes_gravity"))
                require(j.getString("normalization") == "embedded_in_model_do_not_normalize_twice")
                require(j.getString("speed_semantics") == "vehicle_forward_signed")
                require(j.getString("acceleration_unit") == "m/s2" && j.getString("gyro_unit") == "rad/s" && j.getString("speed_unit") == "m/s")
                require(j.getString("recurrent_state") == "reset_for_each_complete_window")
                require(j.getString("input_dtype") == "float32" && j.getString("time_order") == "oldest_to_newest")
                fun strings(name: String) = j.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) } }
                fun numbers(name: String) = j.getJSONArray(name).let { a -> List(a.length()) { a.getDouble(it) } }
                require(strings("channel_order") == listOf("ax","ay","az","gx","gy","gz"))
                require(strings("output_order") == listOf("speed_mps","sigma_mps"))
                require(j.getBoolean("uncertainty_calibrated"))
                val c = ModelContract(j.getString("model_contract_version"), j.getString("model_sha256"),
                    j.getString("input_name"), j.getString("output_name"), j.getInt("window_samples"),
                    j.getDouble("sample_hz"), j.getString("review_evidence"), numbers("input_mean"), numbers("input_std"),
                    j.getDouble("max_standardized_abs"), j.getDouble("max_sigma_mps"))
                val bytes = ModelBundle.verify(model, c, approvedHashes)
                val session = OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
                    OrtEnvironment.getEnvironment().createSession(bytes, options)
                }
                try {
                    require(session.inputNames == setOf(c.inputName) && session.outputNames == setOf(c.outputName))
                    val input = session.inputInfo.getValue(c.inputName).info as TensorInfo
                    val output = session.outputInfo.getValue(c.outputName).info as TensorInfo
                    require(input.type == OnnxJavaType.FLOAT && input.shape.contentEquals(longArrayOf(1,c.windowSamples.toLong(),6)))
                    require(output.type == OnnxJavaType.FLOAT && output.shape.contentEquals(longArrayOf(1,2)))
                    OnnxModelRuntime(session, c, manifestHash)
                } catch (e: Exception) { session.close(); throw e }
            } catch (e: Exception) { UnavailableModelRuntime("Model unavailable: ${e.message ?: e.javaClass.simpleName}") }
            catch (e: LinkageError) { UnavailableModelRuntime("ONNX native library unavailable: ${e.javaClass.simpleName}") }
        }
    }
}
