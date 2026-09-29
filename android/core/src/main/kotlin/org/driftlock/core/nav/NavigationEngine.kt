package org.driftlock.core.nav

import org.driftlock.core.sensors.*
import kotlin.math.*

/** Single serialized caller. No Android, network, ML model, truth, or UI dependency. */
class NavigationEngine(private val cfg:NavConfig=NavConfig(), private val allowMockFixes:Boolean=false,
                       private val mapFeedbackEnabled:Boolean=false):SensorSink {
    private var nav=Esekf(cfg)
    private var tracker=AlignmentTracker(cfg)
    private val shadow=ShadowDr(cfg)
    private val mountMonitor=MountChangeMonitor(cfg)
    private var frame:LocalFrame?=null
    private var clock:Double?=null
    private var previousImu:PhoneImu?=null
    private var alignment=AlignmentState.UNINITIALIZED
    private var gnss=GnssState.UNKNOWN
    private var forwardConfirmed=false
    private var aligned=false
    private var lastFixT:Double?=null
    private var lastGnssInput=Double.NEGATIVE_INFINITY
    private var lastSpeedInput=Double.NEGATIVE_INFINITY
    private var pendingGnss:PhoneGnss?=null
    private var pendingSpeed:SpeedMeasurement?=null
    private var speedHint:SpeedMeasurement?=null
    private var latestFix:PhoneGnss?=null
    private var longitudinalAccel=0.0
    private var lastCourse:Double?=null
    private var lastNhc=Double.NEGATIVE_INFINITY
    private var lastSpeedFusion=Double.NEGATIVE_INFINITY
    private var consecutiveFixes=0
    private val calibrationMonitor=CalibrationMonitor(cfg)
    private var acceptedGnss=0
    private var rejectedGnss=0
    private var acceptedSpeeds=0
    private var rejectedSpeeds=0
    private val audit=ArrayDeque<NavigationEvent>()
    private var magnetic=MagneticStatus.UNAVAILABLE
    private var magneticNorm:Double?=null
    private var magneticAngle:Double?=null
    private var magneticSamples=0
    private var lastMagT=Double.NEGATIVE_INFINITY

    fun confirmForwardMotion() {tracker.clearDirectionalEvents();longitudinalAccel=0.0;forwardConfirmed=true;event("calibration","driver_declared_forward_motion")}

    override fun onImu(sample:PhoneImu) {
        val a=sample.accel;val g=sample.gyro
        if(!sample.valid() || !norm(a).isFinite() || norm(a) !in .1..500.0 || norm(g)>5.0) {onDiscontinuity("invalid_imu",sample.receiptT);return}
        val old=previousImu
        if(old!=null && sample.segmentId!=old.segmentId) {onDiscontinuity("sensor_segment_changed",sample.receiptT)}
        else if(old!=null && sample.t<=old.t) {event("imu_rejected","non_monotonic_imu",sample.t,sample.receiptT);return}
        else if(old!=null && sample.t-old.t>.5+cfg.timeToleranceS) {onDiscontinuity("imu_gap",sample.receiptT);return}
        if(previousImu==null) {
            // First valid IMU can follow an already accepted first fix.
            if(clock!=null && sample.t<clock!!-cfg.timeToleranceS) {event("imu_rejected","before_origin_epoch",sample.t,sample.receiptT);return}
            val initialGap=clock?.let {sample.t-it}
            if(initialGap!=null && initialGap<=.5+cfg.timeToleranceS)advance(sample.t,a,g)
            else {
                if(initialGap!=null)onDiscontinuity("first_imu_far_after_last_epoch",sample.receiptT)
                clock=sample.t;nav.state.t=sample.t
            }
            previousImu=sample
            processPending(sample.t,sample)
            if(alignment==AlignmentState.UNINITIALIZED)alignment=AlignmentState.PARTIAL
            return
        }
        val before=clock!!
        processPending(sample.t,previousImu!!)
        advance(sample.t,a,g)
        previousImu=sample
        val dt=sample.t-before
        refreshHealth()
        val liveSpeed=speedHint?.takeIf {sample.t-it.t in 0.0..cfg.speedHintMaxAgeS}
        val fixSpeed=latestFix?.takeIf {sample.t-it.t in 0.0..1.1}
        val hint=liveSpeed?.speedMps?:fixSpeed?.speedMps
        val age=if(liveSpeed!=null)sample.t-liveSpeed.t else if(fixSpeed!=null)sample.t-fixSpeed.t else Double.POSITIVE_INFINITY
        tracker.push(sample.t,a,g,hint,age,if(forwardConfirmed)longitudinalAccel else 0.0)
        val independentStop=fixSpeed?.let {it.speedMps!=null && it.speedMps<cfg.zuptSpeedThreshold &&
            it.speedAccuracyMps!=null && it.speedAccuracyMps>0 && it.speedAccuracyMps<=cfg.zuptSpeedThreshold}==true
        val stoppedRotation=mountMonitor.observe(sample.t,dt,DoubleArray(3) {g[it]-nav.state.bg[it]},independentStop)
        if(aligned && stoppedRotation!=null)invalidateMount(stoppedRotation,AlignmentState.RECALIBRATING)
        if(aligned && (norm(g)>cfg.mountHandlingGyroThreshold || norm(a)>cfg.mountHandlingAccelThreshold ||
                    (tracker.stationary && (tracker.gravityShift(a)?:0.0)>Math.toRadians(cfg.mountGravityShiftDeg)))) {
            invalidateMount("phone_handling",AlignmentState.RECALIBRATING)
        }
        if(!aligned)attemptAlignment()
        if(tracker.stationary) {nav.updateZupt();nav.updateZaru(g)}
        if(aligned) {
            val yawRate=matVec(nav.state.rVb,DoubleArray(3) {g[it]-nav.state.bg[it]})[2]
            if(!tracker.stationary && sample.t-lastNhc+cfg.timeToleranceS>=1/cfg.nhcRateHz &&
                hypot(nav.state.v[0],nav.state.v[1])>=cfg.nhcMinSpeed && abs(yawRate)<=cfg.nhcMaxYawRate) {
                nav.updateNhc(yawRate);lastNhc=sample.t
                mountMonitor.pushInnovation(sample.t,nav.lastNis)?.let {invalidateMount(it,AlignmentState.RECALIBRATING)}
            }
            if(!aligned)return
            shadow.constraints(g,tracker.stationary,if(liveSpeed!=null)StationarySpeedSource.MODEL else if(fixSpeed!=null)StationarySpeedSource.GNSS else StationarySpeedSource.NONE)
            calibrationMonitor.accumulate(dt,yawRate,
                matVec(nav.state.rVb,DoubleArray(3) {a[it]-nav.state.ba[it]})[0],
                (gnss==GnssState.HEALTHY || gnss==GnssState.STABLE) && (lastFixT?.let {sample.t-it<1.1}==true))
            shadow.tick(sample.t,nav,(gnss==GnssState.HEALTHY || gnss==GnssState.STABLE) && (lastFixT?.let {sample.t-it<1.1}==true),lastNhc,magneticAngle,magneticNorm)
        }
    }

    private fun processPending(until:Double, source:PhoneImu) {
        while(true) {
            val fix=pendingGnss?.takeIf {it.t<=until+cfg.timeToleranceS}
            val speed=pendingSpeed?.takeIf {it.t<=until+cfg.timeToleranceS}
            if(fix==null && speed==null)break
            val target=minOf(fix?.t?:Double.POSITIVE_INFINITY,speed?.t?:Double.POSITIVE_INFINITY)
            if(target>(clock?:target)+cfg.timeToleranceS)advance(target,source.accel,source.gyro)
            if(fix!=null && fix.t==target) {pendingGnss=null;fuseGnss(fix)}
            if(speed!=null && speed.t==target) {pendingSpeed=null;fuseSpeed(speed)}
        }
    }

    private fun advance(target:Double,a:DoubleArray,g:DoubleArray) {
        val previous=clock?:target;val dt=target-previous
        if(dt<=cfg.timeToleranceS) {clock=target;nav.state.t=target;return}
        require(dt<=.5+cfg.timeToleranceS)
        if(aligned) {nav.predict(a,g,dt);shadow.predict(a,g,dt)}
        else {
            val f=identity(N);for(i in 0..2) {nav.state.p[i]+=nav.state.v[i]*dt;f[i][i+3]=dt}
            nav.P=matMul(matMul(f,nav.P),transpose(f));val q=cfg.bootstrapAccelNoiseDensity.pow(2)
            for(i in 0..2) {nav.P[i][i]+=q*dt.pow(3)/3;nav.P[i][i+3]+=q*dt*dt/2;nav.P[i+3][i]+=q*dt*dt/2;nav.P[i+3][i+3]+=q*dt}
        }
        clock=target;nav.state.t=target
    }

    override fun onGnss(fix:PhoneGnss) {
        if(!fix.valid() || fix.isMock && !allowMockFixes || fix.horizontalAccuracyM==null || fix.horizontalAccuracyM<=0.0 ||
            fix.horizontalAccuracyM>cfg.fixAccuracyRejectM || fix.t<=lastGnssInput) {rejectFix("invalid_accuracy_clock_or_mock",fix);return}
        if(fix.receiptT-fix.t>cfg.maxGnssAgeS+cfg.timeToleranceS) {rejectFix("gnss_too_old_at_receipt",fix);return}
        val now=clock
        if(now!=null && fix.t>now+cfg.timeToleranceS) {
            if(fix.t-now>.5+cfg.timeToleranceS) {rejectFix("gnss_ahead_of_sensor_stream",fix);return}
            lastGnssInput=fix.t;pendingGnss=fix
        } else {lastGnssInput=fix.t;fuseGnss(fix)}
    }

    private fun fuseGnss(fix:PhoneGnss) {
        val now=clock?:fix.t;val age=now-fix.t
        if(age< -cfg.timeToleranceS || age>cfg.maxGnssAgeS+cfg.timeToleranceS) {rejectFix("gnss_epoch_outside_bound",fix);return}
        val velocityUsable=fix.speedMps!=null && fix.speedMps in 0.0..60.0 && fix.speedAccuracyMps!=null && fix.speedAccuracyMps>0 &&
            fix.speedAccuracyMps<=2.0 && fix.courseDeg!=null && fix.courseAccuracyDeg!=null && fix.courseAccuracyDeg>0 && fix.courseAccuracyDeg<=20.0
        if(age>cfg.timeToleranceS && !velocityUsable) {rejectFix("late_gnss_missing_velocity_accuracy",fix);return}
        if(age>cfg.timeToleranceS && lastFixT!=null) {
            val bearing=wrap(Math.toRadians(90-fix.courseDeg!!))
            val providerVelocity=doubleArrayOf(fix.speedMps!!*cos(bearing),fix.speedMps*sin(bearing))
            val providerSigma=fix.speedAccuracyMps!!+fix.speedMps*Math.toRadians(fix.courseAccuracyDeg!!)
            // Qualify the velocity used for projection BEFORE changing position
            // or trusted health. The probe has no live-state side effects.
            if(!nav.copy().updateGnssHorizontalVelocity(providerVelocity,providerSigma)) {
                rejectFix("late_gnss_velocity_innovation",fix);return
            }
        }
        if(frame==null)frame=LocalFrame(GeographicPosition(fix.latitudeDeg,fix.longitudeDeg,fix.altitudeM))
        val p=frame!!.toEnu(GeographicPosition(fix.latitudeDeg,fix.longitudeDeg,fix.altitudeM))
        val course=fix.courseDeg?.let {wrap(Math.toRadians(90-it))}
        val speed=fix.speedMps?:0.0
        val velocity=if(velocityUsable)doubleArrayOf(speed*cos(course!!),speed*sin(course)) else null
        if(velocity!=null) {p[0]+=velocity[0]*age;p[1]+=velocity[1]*age}
        var uncertainty=fix.horizontalAccuracyM!!
        if(age>0 && velocityUsable)uncertainty+=age*(fix.speedAccuracyMps!!+speed*Math.toRadians(fix.courseAccuracyDeg!!))+.5*cfg.gnssPropagationAccelBound*age*age
        val returning=gnss in listOf(GnssState.DENIED,GnssState.DEGRADING,GnssState.REACQUIRING)
        val inflation=if(returning && consecutiveFixes+1<cfg.reacquireRequiredFixes)cfg.reacquireInflation/(consecutiveFixes+1) else 1.0
        val accepted=nav.updateGnssHorizontal(p.copyOfRange(0,2),uncertainty,inflation)
        if(!accepted) {rejectFix("gnss_position_innovation",fix);return}
        if(clock==null) {clock=now;nav.state.t=now}
        val firstTrusted=lastFixT==null
        lastFixT=fix.t;acceptedGnss++;consecutiveFixes++
        gnss=if(returning && consecutiveFixes<cfg.reacquireRequiredFixes)GnssState.REACQUIRING else if(consecutiveFixes>=cfg.reacquireRequiredFixes)GnssState.STABLE else GnssState.HEALTHY
        if(alignment==AlignmentState.UNINITIALIZED || alignment==AlignmentState.DEGRADED)alignment=AlignmentState.PARTIAL
        var velocityAccepted=false
        if(velocity!=null) {
            val velocitySigma=maxOf(cfg.gnssVelocitySigmaFloor,fix.speedAccuracyMps!!+speed*Math.toRadians(fix.courseAccuracyDeg!!))
            if(firstTrusted) {
                // There is no observed stationary prior at startup. Initialize the
                // horizontal velocity once from an accepted, accuracy-qualified fix.
                for(i in 0..1) {
                    nav.state.v[i]=velocity[i]
                    for(j in 0 until N) {nav.P[IV+i][j]=0.0;nav.P[j][IV+i]=0.0}
                    nav.P[IV+i][IV+i]=velocitySigma*velocitySigma
                }
                velocityAccepted=true
            } else velocityAccepted=nav.updateGnssHorizontalVelocity(velocity,velocitySigma)
            if(!velocityAccepted)event("gnss_velocity_rejected","velocity_innovation",fix.t,fix.receiptT)
        }
        // Position health and motion observability are distinct. A receiver's
        // unqualified or rejected zero-speed field must never trigger ZUPT/ZARU.
        val qualifiedStop=fix.speedMps!=null && fix.speedMps<cfg.zuptSpeedThreshold &&
            fix.speedAccuracyMps!=null && fix.speedAccuracyMps>0 && fix.speedAccuracyMps<=cfg.zuptSpeedThreshold &&
            hypot(nav.state.v[0],nav.state.v[1])<cfg.zuptSpeedThreshold
        val prior=latestFix
        longitudinalAccel=0.0
        if(velocityAccepted || qualifiedStop) {
            longitudinalAccel=if(prior?.speedMps!=null && fix.speedMps!=null && fix.t-prior.t in .1..2.0)(fix.speedMps-prior.speedMps)/(fix.t-prior.t) else 0.0
            latestFix=fix
            if(velocityAccepted && speed>3.0)lastCourse=course
        }
        event("gnss_accepted",if(age>cfg.timeToleranceS)"propagated_provider_velocity_to_current_epoch" else "position_innovation_passed",fix.t,fix.receiptT)
    }

    private fun rejectFix(reason:String,fix:PhoneGnss) {
        rejectedGnss++;consecutiveFixes=0
        if(gnss!=GnssState.DENIED && gnss!=GnssState.UNKNOWN)gnss=GnssState.DEGRADING
        event("gnss_rejected",reason,fix.t,fix.receiptT);refreshHealth()
    }

    fun onSpeed(packet:SpeedMeasurement):Boolean {
        if(!packet.valid || !packet.t.isFinite() || packet.t<0 || !packet.speedMps.isFinite() || abs(packet.speedMps)>=60 ||
            !packet.sigmaMps.isFinite() || packet.sigmaMps<=0 || !(packet.sigmaMps*packet.sigmaMps).isFinite() || packet.sigmaMps*packet.sigmaMps==0.0) {
            pendingSpeed=null;speedHint=null;return rejectSpeed("invalid_model_packet",packet.t)
        }
        val now=clock
        if(!aligned || !forwardConfirmed) return rejectSpeed("vehicle_alignment_unavailable",packet.t)
        if(now==null || packet.t<now-cfg.timeToleranceS || packet.t>now+.5 || packet.t<=lastSpeedInput) return rejectSpeed("noncurrent_model_target",packet.t)
        lastSpeedInput=packet.t
        if(abs(packet.t-now)<=cfg.timeToleranceS)return fuseSpeed(packet)
        pendingSpeed=packet;return true
    }

    private fun fuseSpeed(packet:SpeedMeasurement):Boolean {
        if(!aligned || abs(packet.t-nav.state.t)>cfg.timeToleranceS)return rejectSpeed("model_target_no_longer_current",packet.t)
        if(packet.t-lastSpeedFusion+cfg.timeToleranceS<1/cfg.speedUpdateRateHz)return rejectSpeed("model_rate_limit",packet.t)
        lastSpeedFusion=packet.t
        val ok=nav.updateForwardSpeed(packet.speedMps,packet.sigmaMps)
        if(ok) {acceptedSpeeds++;speedHint=packet;shadow.speed(packet.t,packet.speedMps,packet.sigmaMps);event("speed_accepted","current_target_innovation_passed",packet.t)}
        else {speedHint=null;return rejectSpeed("speed_innovation",packet.t)}
        return true
    }
    private fun rejectSpeed(reason:String,t:Double):Boolean {rejectedSpeeds++;event("speed_rejected",reason,t.takeIf {it.isFinite()});return false}

    private fun attemptAlignment() {
        if(tracker.up!=null && alignment==AlignmentState.UNINITIALIZED)alignment=AlignmentState.PARTIAL
        if(!forwardConfirmed || lastCourse==null || latestFix?.speedMps?.let {it>3.0}!=true || (clock!!-(latestFix?.t?:Double.NEGATIVE_INFINITY))>1.5)return
        val solution=tracker.solve()?:return
        val positionP=Array(3) {i->DoubleArray(3) {j->nav.P[i][j]}}
        nav.state.qVb=matrixToQuat(solution.rotation);nav.state.qNb=matrixToQuat(matMul(rotateZ(lastCourse!!),solution.rotation))
        nav.state.ba=DoubleArray(3);nav.state.bg=tracker.gyroBias();nav.state.ks=1.0
        nav.P=Esekf(cfg).P
        val mountSigma=solution.residualRad.coerceIn(Math.toRadians(2.0),Math.toRadians(15.0))
        for(i in 0..2) {for(j in 0..2)nav.P[i][j]=positionP[i][j];nav.P[IV+i][IV+i]=1.0;nav.P[IMN+i][IMN+i]=mountSigma*mountSigma}
        nav.P[ITH+2][ITH+2]=Math.toRadians(8.0).pow(2)
        aligned=true;alignment=AlignmentState.ALIGNED;lastNhc=Double.NEGATIVE_INFINITY
        event("alignment","observable_forward_maneuver_and_gravity")
    }

    override fun onMag(sample:PhoneMag) {
        val t=clock;val field=sample.values
        if(!sample.valid() || sample.t<=lastMagT || sample.accuracy<2 || t==null || sample.t>t+cfg.timeToleranceS || t-sample.t>.1 || sample.receiptT-sample.t>.1 || norm(field) !in 25.0..65.0) {
            magnetic=MagneticStatus.REJECTED;event("mag_rejected","clock_accuracy_or_field_magnitude",sample.t,sample.receiptT);return
        }
        lastMagT=sample.t
        if(!aligned) {magnetic=MagneticStatus.CALIBRATING;return}
        val gyroForMag=previousImu?.gyro?:DoubleArray(3)
        magneticAngle?.let {shadow.magnetic(field,it,Math.toRadians(10.0),gyroForMag,t-sample.t)}
        val previousNorm=magneticNorm
        if(previousNorm!=null && abs(norm(field)-previousNorm)>previousNorm*.15) {magnetic=MagneticStatus.REJECTED;event("mag_rejected","field_change",sample.t,sample.receiptT);return}
        val age=t-sample.t;val gyro=previousImu?.gyro?:DoubleArray(3)
        val corrected=matVec(quatToMatrix(quatFromRotVec(DoubleArray(3) {-(gyro[it]-nav.state.bg[it])*age})),field)
        val world=matVec(nav.state.rNb,corrected);val angle=atan2(world[1],world[0])
        val composed=matMul(nav.state.rNb,transpose(nav.state.rVb));val heading=atan2(composed[1][0],composed[0][0])
        if(magneticAngle==null) {
            if(lastCourse==null || t-(latestFix?.t?:Double.NEGATIVE_INFINITY)>1.1 || abs(wrap(heading-lastCourse!!))>Math.toRadians(15.0)) {magnetic=MagneticStatus.CALIBRATING;return}
            magneticNorm=norm(field);magneticSamples++
            if(magneticSamples>=10)magneticAngle=angle
            magnetic=MagneticStatus.CALIBRATING;return
        }
        if(abs(wrap(angle-magneticAngle!!))>Math.toRadians(20.0)) {magnetic=MagneticStatus.REJECTED;event("mag_rejected","independent_heading_disagreement",sample.t,sample.receiptT);return}
        magnetic=if(nav.updateMagneticDirection(corrected,magneticAngle!!,Math.toRadians(10.0)))MagneticStatus.ACCEPTED else MagneticStatus.REJECTED
        event("mag_${magnetic.name.lowercase()}","locally_calibrated_direction",sample.t,sample.receiptT)
    }

    override fun onDiscontinuity(reason:String,receiptT:Double) {
        pendingGnss=null;pendingSpeed=null;speedHint=null;previousImu=null
        invalidateMount(reason,AlignmentState.DEGRADED)
        for(i in 0..2) {nav.P[IP+i][IP+i]+=10000;nav.P[IV+i][IV+i]+=25}
        gnss=if(lastFixT==null)GnssState.UNKNOWN else GnssState.DENIED
        event("discontinuity",reason,receiptT=receiptT.takeIf {it.isFinite()})
    }
    fun onDiscontinuity(reason:String)=onDiscontinuity(reason,clock?:0.0)
    private fun invalidateMount(reason:String,state:AlignmentState) {
        aligned=false;alignment=state;forwardConfirmed=false;tracker=AlignmentTracker(cfg)
        pendingSpeed=null;speedHint=null;lastCourse=null;longitudinalAccel=0.0
        magneticAngle=null;magneticNorm=null;magneticSamples=0;magnetic=MagneticStatus.UNAVAILABLE
        calibrationMonitor.clear();shadow.clear();mountMonitor.resetEvidence()
        for(i in 0..2)nav.P[IMN+i][IMN+i]+=cfg.mountResetSigma.pow(2)
        event("alignment",reason)
    }

    fun onMapCorrection(targetT:Double,positionEnuM:DoubleArray,posterior:Double,origin:GeographicPosition):Boolean {
        if(!mapFeedbackEnabled || !aligned || !targetT.isFinite() || abs(targetT-nav.state.t)>cfg.timeToleranceS ||
            origin!=frame?.origin || !posterior.isFinite() || posterior !in cfg.mapConfidenceGate..1.0 ||
            positionEnuM.size!=3 || positionEnuM.any {!it.isFinite()}) {event("map_rejected","disabled_or_invalid_current_origin_gate",targetT.takeIf {it.isFinite()});return false}
        return nav.updateMapPosition(positionEnuM.copyOfRange(0,2),cfg.mapSigmaFloorM+cfg.mapSigmaScaleM*(1-posterior))
    }
    private fun refreshHealth() {
        val t=clock?:return;val fix=lastFixT?:return
        if(t-fix>cfg.fixTimeoutS) {gnss=GnssState.DENIED;consecutiveFixes=0}
    }
    private fun event(kind:String,reason:String,measurementT:Double?=null,receiptT:Double?=null) {
        audit.addLast(NavigationEvent(clock,kind,reason,measurementT,receiptT));while(audit.size>cfg.maxAuditEvents)audit.removeFirst()
    }
    fun snapshot():NavigationSnapshot {
        refreshHealth()
        val age=lastFixT?.let {maxOf(0.0,(clock?:it)-it)}
        val p=frame?.fromEnu(nav.state.p)
        val sigma=frame?.let {nav.horizontalPositionSigma}
        val composition=matMul(nav.state.rNb,transpose(nav.state.rVb))
        val heading=if(aligned)atan2(composition[1][0],composition[0][0]) else null
        val headingSigma=if(aligned)nav.yawSigma.takeIf {it.isFinite()} else null
        val predicted=if(aligned)shadow.predicted(age?:0.0) else null
        val calibration=if(!aligned)CalibrationState.UNCALIBRATED else calibrationMonitor.grade(nav)
        val horizon=when {
            !aligned || sigma==null || sigma>100 || headingSigma==null || alignment!=AlignmentState.ALIGNED->Horizon.UNRELIABLE
            gnss==GnssState.DENIED && (predicted==null || sigma>35 || (age?:0.0)>30)->Horizon.LOW
            sigma>20 || gnss==GnssState.REACQUIRING || gnss==GnssState.DEGRADING->Horizon.LOW
            gnss==GnssState.STABLE && sigma<10->Horizon.HIGH
            else->Horizon.MODERATE
        }
        return NavigationSnapshot(clock,frame?.origin,p,frame?.let {nav.state.p.toList()},nav.state.v.toList(),hypot(nav.state.v[0],nav.state.v[1]),
            heading,headingSigma,sigma,nav.P.map {it.toList()},alignment,gnss,horizon,age,predicted,calibration,magnetic,
            acceptedGnss,rejectedGnss,acceptedSpeeds,rejectedSpeeds,shadow.completed,audit.toList(),shadow.comparison())
    }
}
