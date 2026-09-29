package org.driftlock.app.storage

import org.driftlock.core.sensors.PhoneGnss
import org.driftlock.core.sensors.TimedVector
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class SessionLoggerTest {
    @Test fun recordsMeasurementAndReceiptClocksAndExportsOnlyClosedSession() {
        val root = Files.createTempDirectory("session-test").toFile()
        try {
            val s = SessionLogger(root, "{\"source\":\"real_phone\"}")
            s.sensor("accelerometer", TimedVector(1.0, 1.02, doubleArrayOf(1.0, 2.0, 9.8)), 1000)
            s.gnss(PhoneGnss(1.0, 1.2, 30.0, 76.0, null, 4.0, null, null, null, null, "gps", false))
            assertThrows(IllegalStateException::class.java) { s.exportZip(root.resolve("bad.zip")) }
            s.close("user_stopped")
            val imu = s.directory.resolve("imu.csv").readLines()
            assertEquals(2, imu.size)
            assertTrue(imu[1].startsWith("1.0,1.02,1000,accelerometer,"))
            assertTrue(s.directory.resolve("gnss.csv").readLines()[1].contains("30.0,76.0,,4.0"))
            val zip = s.exportZip(root.resolve("session.zip"))
            ZipFile(zip).use { assertNotNull(it.getEntry("session.json")); assertNotNull(it.getEntry("imu.csv")) }
            assertThrows(IllegalStateException::class.java) { s.sensor("gyro", TimedVector(2.0, 2.01, doubleArrayOf(0.0,0.0,0.0)), 2000) }
            assertThrows(IllegalArgumentException::class.java) { s.exportZip(root.resolve("session.zip")) }
        } finally { root.deleteRecursively() }
    }
}
