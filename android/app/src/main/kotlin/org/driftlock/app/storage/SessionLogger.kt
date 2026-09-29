package org.driftlock.app.storage

import org.driftlock.core.sensors.*
import java.io.BufferedWriter
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Buffered writes owned by one processing thread. Export is an explicit caller action. */
class SessionLogger(root: File, configJson: String) {
    val directory = File(root, "session_${System.currentTimeMillis()}_${UUID.randomUUID()}")
    private val writers = mutableListOf<BufferedWriter>()
    private var closed = false
    private val imu: BufferedWriter
    private val gnss: BufferedWriter
    private val events: BufferedWriter
    var sensorRows = 0L; private set
    var gnssRows = 0L; private set
    init {
        check(directory.mkdirs()) { "Cannot create session directory" }
        try {
            directory.resolve("session.json").writeText(configJson)
            directory.resolve("status.txt").writeText("OPEN — interrupted tails may be incomplete\n")
            imu = writer("imu.csv", "t_s,receipt_t_s,wall_time_ms,sensor,x,y,z,accuracy")
            gnss = writer("gnss.csv", "t_s,receipt_t_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,speed_accuracy_mps,course_deg,course_accuracy_deg,provider,is_mock,vertical_accuracy_m")
            events = writer("events.csv", "receipt_t_s,kind,detail")
            flush()
        } catch (e: Exception) { writers.forEach { runCatching { it.close() } }; throw e }
    }
    private fun writer(name: String, header: String) = directory.resolve(name).bufferedWriter().also { writers.add(it); it.write(header+"\n") }
    fun sensor(kind: String, sample: TimedVector, wallMs: Long) {
        check(!closed); val v = sample.values
        require(v.size == 3)
        imu.write(row(sample.t,sample.receiptT,wallMs,kind,v[0],v[1],v[2],sample.accuracy)); sensorRows++
    }
    fun gnss(fix: PhoneGnss) {
        check(!closed)
        gnss.write(row(fix.t,fix.receiptT,fix.latitudeDeg,fix.longitudeDeg,fix.altitudeM,fix.horizontalAccuracyM,
            fix.speedMps,fix.speedAccuracyMps,fix.courseDeg,fix.courseAccuracyDeg,fix.provider,fix.isMock,fix.verticalAccuracyM)); gnssRows++
    }
    fun event(receiptT: Double, kind: String, detail: String) { check(!closed); events.write(row(receiptT,kind,detail)) }
    fun flush() { check(!closed); writers.forEach { it.flush() } }
    fun close(reason: String) {
        if (closed) return
        closed = true
        var failure: Exception? = null
        writers.forEach { try { it.close() } catch (e: Exception) { failure = e } }
        directory.resolve("status.txt").writeText("${if (failure == null) reason else "IO_ERROR"}\nsensor_rows=$sensorRows\ngnss_rows=$gnssRows\n")
        failure?.let { throw it }
    }
    fun exportZip(destination: File): File {
        check(closed) { "Stop recording before export" }
        require(!destination.exists()) { "Export destination exists" }
        require(destination.canonicalFile.parentFile != directory.canonicalFile) { "Export outside session directory" }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            for (name in listOf("session.json","status.txt","imu.csv","gnss.csv","events.csv")) {
                zip.putNextEntry(ZipEntry(name)); directory.resolve(name).inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
        return destination
    }
    companion object {
        private fun row(vararg values: Any?) = values.joinToString(",") {
            val s = it?.toString() ?: ""
            if (s.any { c -> c in ",\"\n\r" }) "\"${s.replace("\"","\"\"")}\"" else s
        } + "\n"
    }
}
