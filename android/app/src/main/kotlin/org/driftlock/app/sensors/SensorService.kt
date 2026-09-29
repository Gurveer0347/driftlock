package org.driftlock.app.sensors

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.driftlock.app.model.*
import org.driftlock.app.storage.SessionLogger
import org.driftlock.core.sensors.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class SensorSessionState(
    val active: Boolean = false, val status: String = "Stopped", val sessionId: String? = null,
    val sensorAvailability: Map<String, String> = emptyMap(), val sensorRows: Long = 0, val gnssRows: Long = 0,
    val droppedEvents: Long = 0, val lastImu: PhoneImu? = null, val lastGnss: PhoneGnss? = null,
    val model: ModelTelemetry = ModelTelemetry(reason = "No approved production model"),
)

/** Capture callbacks copy immediately; a bounded queue feeds one serial navigation/logging worker. */
class SensorService : Service(), SensorEventListener, LocationListener {
    private val state = MutableStateFlow(SensorSessionState())
    val states = state.asStateFlow()
    private lateinit var captureThread: HandlerThread
    private lateinit var processingThread: HandlerThread
    private lateinit var capture: Handler
    private lateinit var worker: Handler
    private lateinit var sensors: SensorManager
    private var locations: LocationManager? = null
    private val active = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val queue = BoundedCaptureQueue<CaptureEvent>(512)
    private val pairer = CausalImuPairer()
    private var windows = CausalWindowAssembler()
    private var session: SessionLogger? = null
    private var lastSession: SessionLogger? = null
    private var sink: SensorSink? = null
    private var speedSink: ((ModelResult, Double) -> Unit)? = null
    private var model: ModelRuntime = UnavailableModelRuntime("No approved production model")
    private var lastGnssT: Double? = null
    private var lastMagT: Double? = null
    private var lastSegment = -1L
    @Volatile private var startedT = Double.POSITIVE_INFINITY

    private sealed interface CaptureEvent {
        data class Sensor(val type: Int, val sample: TimedVector, val wallMs: Long) : CaptureEvent
        data class Gnss(val fix: PhoneGnss) : CaptureEvent
    }
    inner class LocalBinder : Binder() {
        val service get() = this@SensorService
        fun attach(sensors: SensorSink?, speed: ((ModelResult, Double) -> Unit)? = null) {
            worker.post { sink = sensors; speedSink = speed }
        }
        /** Navigation-only commands run on the same owner as sensor delivery. */
        fun dispatch(task: () -> Unit) { worker.post { task() } }
        fun loadApprovedModel(file: File, manifest: File, approvedHashes: Set<String>) {
            val hashes = approvedHashes.toSet()
            worker.post {
                model.close(); model = OnnxModelRuntime.open(file, manifest, hashes)
                windows = CausalWindowAssembler(model.windowSamples)
                state.value = state.value.copy(model = model.telemetry)
            }
        }
        fun recordNavigation(receiptT: Double, json: String) { worker.post { write { it.event(receiptT, "navigation", json) } } }
        fun recordDiscontinuity(receiptT: Double, reason: String) {
            require(receiptT.isFinite() && receiptT >= 0 && reason.isNotBlank())
            worker.post { write { it.event(receiptT, "discontinuity", reason) } }
        }
        fun exportLastSession(onResult: (File?, String?) -> Unit) {
            worker.post {
                val result = runCatching {
                    check(session == null) { "Stop recording before export" }
                    val saved = checkNotNull(lastSession) { "No completed session" }
                    saved.exportZip(File(filesDir, "session_exports/${saved.directory.name}_${System.nanoTime()}.zip"))
                }
                Handler(mainLooper).post { onResult(result.getOrNull(), result.exceptionOrNull()?.message) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        captureThread = HandlerThread("DriftlockCapture").apply { start() }
        processingThread = HandlerThread("DriftlockProcessing").apply { start() }
        capture = Handler(captureThread.looper); worker = Handler(processingThread.looper)
        sensors = getSystemService(SensorManager::class.java)
        locations = getSystemService(LocationManager::class.java)
    }
    override fun onBind(intent: Intent?) = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { worker.post { finish("Stopped by user"); stopSelf() }; return START_NOT_STICKY }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            state.value = state.value.copy(active = false, status = "Location permission required before starting recording")
            stopSelf(); return START_NOT_STICKY
        }
        val locationEnabled = if (Build.VERSION.SDK_INT >= 28) locations?.isLocationEnabled == true else
            locations?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        if (!locationEnabled) { state.value = state.value.copy(status = "Enable phone Location before starting"); stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Navigation recording", NotificationManager.IMPORTANCE_LOW))
            val stopIntent = PendingIntent.getService(this, 1, Intent(this, SensorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, CHANNEL).setContentTitle("DRIFTLOCK recording")
                .setContentText("Phone sensor recording is active. Tap Stop to finish.")
                .setSmallIcon(`in`.driftlock.ui.R.drawable.ic_driftlock).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build()).build()
            startForeground(NOTIFICATION, notification)
            worker.post { startRecording() }
        } catch (e: RuntimeException) {
            state.value = state.value.copy(active = false, status = "Cannot start foreground recording: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (session != null) return
        try {
            pairer.reset(); windows.reset(); queue.clear(resetCounts = true)
            lastGnssT = null; lastMagT = null; lastSegment = -1
            startedT = SystemClock.elapsedRealtimeNanos()/1e9
            val config = JSONObject().put("source", "real_phone").put("time_base", "seconds_since_boot")
                .put("sensor_request_hz", 50).put("model_sample_hz", 10).put("window_samples", model.windowSamples)
                .put("model_sha256", model.telemetry.modelSha256 ?: JSONObject.NULL)
                .put("manifest_sha256", model.telemetry.manifestSha256 ?: JSONObject.NULL)
                .put("max_pair_age_s", .05).put("max_raw_gap_s", .15)
                .put("model_status", model.telemetry.status).put("max_queue_events", 512)
                .put("preprocessing", "past_only_hold_raw_phone_gravity_included_no_antialias")
            session = SessionLogger(File(filesDir, "recordings"), config.toString(2))
            active.set(true)
            val availability = listOf(Sensor.TYPE_ACCELEROMETER,Sensor.TYPE_GYROSCOPE,Sensor.TYPE_MAGNETIC_FIELD).associate { type ->
                val sensor = sensors.getDefaultSensor(type)
                val result = if (sensor == null) "unavailable" else if (sensors.registerListener(this, sensor, 20_000, capture)) "listening" else "registration_failed"
                sensorName(type) to result
            }
            if (locations?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locations?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, capture.looper)
            }
            state.value = SensorSessionState(true, "Recording; model ${model.telemetry.status}", session!!.directory.name, availability, model = model.telemetry)
            worker.postDelayed(tick, 1000)
        } catch (e: Exception) { finish("Recording start failed: ${e.message}"); stopSelf() }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active.get() || event.values.size < 3) return
        val sample = TimedVector(event.timestamp/1e9, SystemClock.elapsedRealtimeNanos()/1e9,
            doubleArrayOf(event.values[0].toDouble(),event.values[1].toDouble(),event.values[2].toDouble()), event.accuracy)
        if (sample.t < startedT) return
        enqueue(CaptureEvent.Sensor(event.sensor.type, sample, System.currentTimeMillis()))
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    @Suppress("DEPRECATION")
    override fun onLocationChanged(location: Location) {
        if (!active.get() || location.elapsedRealtimeNanos/1e9 < startedT) return
        enqueue(CaptureEvent.Gnss(PhoneGnss(location.elapsedRealtimeNanos/1e9, SystemClock.elapsedRealtimeNanos()/1e9,
            location.latitude, location.longitude, if (location.hasAltitude()) location.altitude else null,
            if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            if (location.hasSpeed()) location.speed.toDouble() else null,
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else null,
            if (location.hasBearing()) location.bearing.toDouble() else null,
            if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees.toDouble() else null,
            location.provider ?: "unknown", location.isFromMockProvider,
            if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else null)))
    }
    private fun enqueue(event: CaptureEvent) {
        queue.offer(event)
        if (scheduled.compareAndSet(false, true)) worker.post(drain)
    }
    private val drain = object : Runnable {
        override fun run() {
            try {
                repeat(64) {
                    queue.poll()?.let { item ->
                        if (active.get()) {
                            if (item.discontinuity) { pairer.reset(); discontinuity("capture_queue_overflow", SystemClock.elapsedRealtimeNanos()/1e9) }
                            item.event?.let(::process)
                        }
                    }
                }
            } catch (e: Exception) { finish("Processing failed: ${e.message}"); stopSelf() }
            scheduled.set(false)
            if (!queue.isEmpty() && scheduled.compareAndSet(false, true)) worker.post(this)
        }
    }
    private fun process(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Sensor -> {
                val s = event.sample
                write { it.sensor(sensorName(event.type), s, event.wallMs) }
                when (event.type) {
                    Sensor.TYPE_ACCELEROMETER -> pairer.accelerometer(s)?.let { discontinuity(it,s.receiptT) }
                    Sensor.TYPE_GYROSCOPE -> {
                        val result = pairer.gyroscope(s)
                        if (result.imu == null) discontinuity(result.reason ?: "imu_unavailable", s.receiptT)
                        result.imu?.let(::processImu)
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> if (s.valid() && (lastMagT == null || s.t > lastMagT!!)) {
                        lastMagT = s.t; sink?.onMag(s) // Field/heading agreement gate belongs to navigation.
                    }
                }
            }
            is CaptureEvent.Gnss -> {
                val g = event.fix; write { it.gnss(g) }
                if (g.valid() && !g.isMock && (lastGnssT == null || g.t > lastGnssT!!)) {
                    lastGnssT = g.t; sink?.onGnss(g); state.value = state.value.copy(lastGnss = g)
                } else write { it.event(g.receiptT, "gnss_rejected", "invalid_repeated_or_mock") }
            }
        }
    }
    internal fun processImu(imu: PhoneImu) {
        if (lastSegment != imu.segmentId) { discontinuity("imu_segment_changed", imu.receiptT); lastSegment = imu.segmentId }
        windows.push(imu).forEach { window ->
            val prediction = model.predict(window)
            val availableT = SystemClock.elapsedRealtimeNanos()/1e9
            write { it.event(availableT, "model", "t=${prediction.measurement.t};valid=${prediction.measurement.valid};speed_mps=${prediction.measurement.speedMps};sigma_mps=${prediction.measurement.sigmaMps};invocations=${prediction.telemetry.invocations};reason=${prediction.telemetry.reason}") }
            speedSink?.invoke(prediction, availableT)
        }
        // Deliver model grid targets before the raw tick that makes their input complete.
        sink?.onImu(imu)
        state.value = state.value.copy(lastImu = imu, model = model.telemetry)
    }
    private fun discontinuity(reason: String, receiptT: Double) {
        windows.reset(); sink?.onDiscontinuity(reason, receiptT)
        write { it.event(receiptT, "discontinuity", reason) }
    }
    private fun write(block: (SessionLogger) -> Unit) { session?.let(block) }
    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            try {
                s.flush(); state.value = state.value.copy(sensorRows = s.sensorRows, gnssRows = s.gnssRows, droppedEvents = queue.droppedEvents)
                worker.postDelayed(this,1000)
            } catch (e: Exception) { finish("Logging failed: ${e.message}"); stopSelf() }
        }
    }
    private fun finish(reason: String) {
        active.set(false); startedT = Double.POSITIVE_INFINITY; worker.removeCallbacks(tick)
        sensors.unregisterListener(this); runCatching { locations?.removeUpdates(this) }
        queue.clear(); pairer.reset(); windows.reset()
        val s = session; session = null; lastSession = s ?: lastSession
        val closeError = runCatching { s?.close(reason) }.exceptionOrNull()
        state.value = state.value.copy(active = false, status = closeError?.let { "Save error: ${it.message}" } ?: reason,
            sensorRows = s?.sensorRows ?: state.value.sensorRows, gnssRows = s?.gnssRows ?: state.value.gnssRows,
            droppedEvents = queue.droppedEvents, model = model.telemetry)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() {
        active.set(false)
        worker.post { finish("Service stopped"); model.close(); sink = null; speedSink = null; captureThread.quitSafely(); processingThread.quitSafely() }
        super.onDestroy()
    }
    override fun onProviderDisabled(provider: String) { worker.post { write { it.event(SystemClock.elapsedRealtimeNanos()/1e9,"provider_disabled",provider) } } }
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    companion object {
        const val ACTION_START = "org.driftlock.START_RECORDING"
        const val ACTION_STOP = "org.driftlock.STOP_RECORDING"
        private const val CHANNEL = "driftlock_recording"
        private const val NOTIFICATION = 168
        private fun sensorName(type: Int) = when(type) {
            Sensor.TYPE_ACCELEROMETER -> "accelerometer"
            Sensor.TYPE_GYROSCOPE -> "gyroscope"
            Sensor.TYPE_MAGNETIC_FIELD -> "magnetometer"
            else -> "unknown"
        }
    }
}
