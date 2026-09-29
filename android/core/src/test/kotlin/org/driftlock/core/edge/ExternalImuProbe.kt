package org.driftlock.core.edge

import org.driftlock.core.nav.*
import java.lang.management.ManagementFactory
import kotlin.math.*

/** Standalone Mac/JVM software timing probe. It does not open a hardware device. */
object ExternalImuProbe {
    @JvmStatic fun main(args:Array<String>) {
        val engine=NavigationEngine(allowMockFixes=true)
        val adapter=ExternalImuAdapter(engine,syntheticDeclaration())
        val (lastT,channels)=SyntheticEdgeFixture.fill(engine,adapter)
        check(engine.snapshot().alignment==AlignmentState.ALIGNED)
        val accel=channels.copyOfRange(0,3);val gyro=channels.copyOfRange(3,6)
        val warmup=2000;val measured=5000;val interval=.005
        for(i in 1..warmup) {
            val t=lastT+i*interval;check(adapter.accept(edgeSample(t,accel,gyro),t+.0005))
        }
        check(engine.snapshot().alignment==AlignmentState.ALIGNED)
        val mx=ManagementFactory.getThreadMXBean()
        val cpuStart=if(mx.isCurrentThreadCpuTimeSupported && mx.isThreadCpuTimeEnabled)mx.currentThreadCpuTime else -1L
        val latency=LongArray(measured);val start=System.nanoTime()
        for(i in 1..measured) {
            val t=lastT+(warmup+i)*interval
            val sample=edgeSample(t,accel,gyro)
            val begin=System.nanoTime();check(adapter.accept(sample,t+.0005));latency[i-1]=System.nanoTime()-begin
        }
        val end=System.nanoTime()
        val cpuEnd=if(cpuStart>=0)mx.currentThreadCpuTime else -1L
        val final=engine.snapshot();check(final.alignment==AlignmentState.ALIGNED)
        check(final.covariance.flatten().all {it.isFinite()})
        val times=latency.sorted();val wallMs=(end-start)/1e6
        val cpuMs=if(cpuStart>=0)(cpuEnd-cpuStart)/1e6 else null
        fun percentile(q:Double)=times[(ceil(q*measured).toInt()-1).coerceIn(times.indices)]/1e6
        println("""{
          "scope":"synthetic Mac JVM software processing only; no hardware or real-time scheduler",
          "java_version":"${System.getProperty("java.version")}",
          "java_vendor":"${System.getProperty("java.vendor")}",
          "os":"${System.getProperty("os.name")}","architecture":"${System.getProperty("os.arch")}",
          "input":"last raw six-channel synthetic maneuver sample held; no ML or GNSS in timed loop",
          "virtual_input_rate_hz":200,"warmup_samples":$warmup,"measured_samples":$measured,
          "virtual_elapsed_seconds":${measured*interval},"wall_processing_ms":$wallMs,
          "current_thread_cpu_ms":$cpuMs,"wall_processing_samples_per_second":${measured*1000.0/wallMs},
          "adapter_and_navigation_p50_ms":${percentile(.50)},"adapter_and_navigation_p95_ms":${percentile(.95)},
          "adapter_and_navigation_p99_ms":${percentile(.99)},"adapter_and_navigation_max_ms":${times.last()/1e6},
          "callbacks_over_5ms":${latency.count {it>5_000_000}},
          "alignment_start":"ALIGNED","alignment_end":"${final.alignment}",
          "gnss_end":"${final.gnss}","model_packets":${final.acceptedSpeeds},
          "delivered_including_fixture":${adapter.status().deliveredSamples},"rejected_samples":${adapter.status().rejectedSamples},
          "hardware_verified":false,"real_navigation_accuracy_measured":false
        }""".trimIndent())
    }
}
