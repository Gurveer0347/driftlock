// Adapted from preserved team source data/raw/team_archives_20260909/extracted/DRIFTLOCK/kotlin/Esekf.kt
// Original SHA-256: e1c784e472110f4f605c16020a8a12cd9d52e5e1aca83ab8a563a2eab324208d
// Native 2026-09-20 corrections are documented in docs/NAVIGATION_ENGINE.md.
package org.driftlock.core.nav

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin mirror of driftlock_nav/filter.py and state.py.
 *
 * Deliberately written with plain double arrays and no linear algebra library.
 * Nineteen by nineteen is small enough that a dependency would buy nothing and
 * cost an argument about which one, and a hand written implementation can be
 * compared against the Python line by line, which is the entire point of keeping
 * a mirror.
 *
 * Error state order, identical to ERROR_INDEX in state.py:
 *   0..2   position error, East-North-Up
 *   3..5   velocity error, East-North-Up
 *   6..8   attitude error, local rotation vector
 *   9..11  accelerometer bias error
 *   12..14 gyroscope bias error
 *   15..17 mount error
 *   18     forward speed scale error
 */

const val N = 19
const val IP = 0
const val IV = 3
const val ITH = 6
const val IBA = 9
const val IBG = 12
const val IMN = 15
const val IKS = 18
const val GRAVITY = 9.80665

typealias Mat = Array<DoubleArray>

fun zeros(r: Int, c: Int): Mat = Array(r) { DoubleArray(c) }

fun identity(n: Int): Mat = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }

fun matMul(a: Mat, b: Mat): Mat {
    val r = a.size; val k = b.size; val c = b[0].size
    val out = zeros(r, c)
    for (i in 0 until r) for (m in 0 until k) {
        val aim = a[i][m]
        if (aim == 0.0) continue
        for (j in 0 until c) out[i][j] += aim * b[m][j]
    }
    return out
}

fun transpose(a: Mat): Mat {
    val out = zeros(a[0].size, a.size)
    for (i in a.indices) for (j in a[0].indices) out[j][i] = a[i][j]
    return out
}

fun skew(v: DoubleArray): Mat = arrayOf(
    doubleArrayOf(0.0, -v[2], v[1]),
    doubleArrayOf(v[2], 0.0, -v[0]),
    doubleArrayOf(-v[1], v[0], 0.0),
)

/** Gauss-Jordan inverse.  Only ever called on matrices of size one to three. */
fun invert(a: Mat): Mat? {
    val n = a.size
    val m = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a[i][j] else if (j - n == i) 1.0 else 0.0 } }
    for (col in 0 until n) {
        var pivot = col
        for (r in col until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
        if (abs(m[pivot][col]) < 1e-12) return null
        val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
        val d = m[col][col]
        for (j in 0 until 2 * n) m[col][j] /= d
        for (r in 0 until n) if (r != col) {
            val f = m[r][col]
            if (f == 0.0) continue
            for (j in 0 until 2 * n) m[r][j] -= f * m[col][j]
        }
    }
    return Array(n) { i -> DoubleArray(n) { j -> m[i][j + n] } }
}

// ---------------------------------------------------------------------------
// Quaternions, scalar first, unit norm
// ---------------------------------------------------------------------------

fun quatMultiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
    a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
    a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
    a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
    a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
)

fun quatNormalise(q: DoubleArray): DoubleArray {
    require(q.size == 4 && q.all { it.isFinite() })
    val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
    require(n.isFinite() && n > 0.0)
    val s = if (q[0] >= 0) 1.0 / n else -1.0 / n
    return doubleArrayOf(q[0] * s, q[1] * s, q[2] * s, q[3] * s)
}

fun quatFromRotVec(v: DoubleArray): DoubleArray {
    val angle = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    if (angle < 1e-9) return quatNormalise(doubleArrayOf(1.0, 0.5 * v[0], 0.5 * v[1], 0.5 * v[2]))
    val h = 0.5 * angle
    val s = sin(h) / angle
    return doubleArrayOf(cos(h), v[0] * s, v[1] * s, v[2] * s)
}

fun quatToMatrix(q: DoubleArray): Mat {
    val (w, x, y, z) = q
    return arrayOf(
        doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)),
        doubleArrayOf(2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)),
        doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)),
    )
}

private operator fun DoubleArray.component4() = this[3]

fun matVec(m: Mat, v: DoubleArray): DoubleArray {
    val out = DoubleArray(m.size)
    for (i in m.indices) { var s = 0.0; for (j in v.indices) s += m[i][j] * v[j]; out[i] = s }
    return out
}

// ---------------------------------------------------------------------------
// Nominal state
// ---------------------------------------------------------------------------

class NominalState {
    var t = 0.0
    var p = DoubleArray(3)
    var v = DoubleArray(3)
    var qNb = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    var ba = DoubleArray(3)
    var bg = DoubleArray(3)
    var qVb = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    var ks = 1.0

    val rNb: Mat get() = quatToMatrix(qNb)
    val rVb: Mat get() = quatToMatrix(qVb)

    /** Strapdown mechanisation, mirroring NominalState.propagate in state.py. */
    fun propagate(accel: DoubleArray, gyro: DoubleArray, dt: Double) {
        val ab = DoubleArray(3) { accel[it] - ba[it] }
        val an = matVec(rNb, ab)
        an[2] -= GRAVITY
        for (i in 0..2) {
            p[i] += v[i] * dt + 0.5 * an[i] * dt * dt
            v[i] += an[i] * dt
        }
        val wb = DoubleArray(3) { gyro[it] - bg[it] }
        qNb = quatNormalise(quatMultiply(qNb, quatFromRotVec(doubleArrayOf(wb[0] * dt, wb[1] * dt, wb[2] * dt))))
        t += dt
    }

    fun inject(dx: DoubleArray) {
        for (i in 0..2) { p[i] += dx[IP + i]; v[i] += dx[IV + i]; ba[i] += dx[IBA + i]; bg[i] += dx[IBG + i] }
        qNb = quatNormalise(quatMultiply(qNb, quatFromRotVec(doubleArrayOf(dx[ITH], dx[ITH + 1], dx[ITH + 2]))))
        qVb = quatNormalise(quatMultiply(qVb, quatFromRotVec(doubleArrayOf(dx[IMN], dx[IMN + 1], dx[IMN + 2]))))
        ks += dx[IKS]
    }
}

// ---------------------------------------------------------------------------
// The filter
// ---------------------------------------------------------------------------

class Esekf(val cfg: NavConfig = NavConfig()) {
    val state = NominalState()
    var P: Mat = initialCovariance()
    var lastNis: Double? = null
        private set

    private fun initialCovariance(): Mat {
        val p = zeros(N, N)
        for (i in 0..2) {
            p[IP + i][IP + i] = cfg.initPositionSigma * cfg.initPositionSigma
            p[IV + i][IV + i] = cfg.initVelocitySigma * cfg.initVelocitySigma
            p[IBA + i][IBA + i] = cfg.initAccelBiasSigma * cfg.initAccelBiasSigma
            p[IBG + i][IBG + i] = cfg.initGyroBiasSigma * cfg.initGyroBiasSigma
            p[IMN + i][IMN + i] = cfg.initMountSigma * cfg.initMountSigma
        }
        p[ITH][ITH] = cfg.initRollPitchSigma * cfg.initRollPitchSigma
        p[ITH + 1][ITH + 1] = cfg.initRollPitchSigma * cfg.initRollPitchSigma
        p[ITH + 2][ITH + 2] = cfg.initYawSigma * cfg.initYawSigma
        p[IKS][IKS] = cfg.initSpeedScaleSigma * cfg.initSpeedScaleSigma
        return p
    }

    /**
     * Error state transition and process noise, mirroring transition_matrices.
     *
     *   d(dv)/dt     = -R skew(a_b) dtheta - R db_a - R n_a
     *   d(dtheta)/dt = -skew(w_b) dtheta   - db_g   - n_g
     */
    fun predict(accel: DoubleArray, gyro: DoubleArray, dtIn: Double) {
        vector(accel,3); vector(gyro,3)
        require(dtIn.isFinite() && dtIn > 0.0 && dtIn <= .5+cfg.timeToleranceS) { "Unsupported IMU interval" }
        val dt = dtIn
        val r = state.rNb
        val ab = DoubleArray(3) { accel[it] - state.ba[it] }
        val wb = DoubleArray(3) { gyro[it] - state.bg[it] }

        val a = zeros(N, N)
        for (i in 0..2) a[IP + i][IV + i] = 1.0
        val negRSkewA = matMul(r, skew(ab))
        for (i in 0..2) for (j in 0..2) {
            a[IV + i][ITH + j] = -negRSkewA[i][j]
            a[IV + i][IBA + j] = -r[i][j]
        }
        val sw = skew(wb)
        for (i in 0..2) for (j in 0..2) a[ITH + i][ITH + j] = -sw[i][j]
        for (i in 0..2) a[ITH + i][IBG + i] = -1.0

        val f = identity(N)
        val aa=matMul(a,a)
        for (i in 0 until N) for (j in 0 until N) f[i][j] += a[i][j] * dt + .5*aa[i][j]*dt*dt

        state.propagate(accel, gyro, dt)

        val fp = matMul(f, P)
        P = matMul(fp, transpose(f))
        for (i in 0..2) {
            P[IV + i][IV + i] += cfg.accelNoiseDensity * cfg.accelNoiseDensity * dt
            P[ITH + i][ITH + i] += cfg.gyroNoiseDensity * cfg.gyroNoiseDensity * dt
            P[IBA + i][IBA + i] += cfg.accelBiasWalk * cfg.accelBiasWalk * dt
            P[IBG + i][IBG + i] += cfg.gyroBiasWalk * cfg.gyroBiasWalk * dt
            P[IMN + i][IMN + i] += cfg.mountWalk * cfg.mountWalk * dt
        }
        P[IKS][IKS] += cfg.speedScaleWalk * cfg.speedScaleWalk * dt
        symmetrise()
    }

    private fun symmetrise() {
        for (i in 0 until N) for (j in i + 1 until N) {
            val m = 0.5 * (P[i][j] + P[j][i]); P[i][j] = m; P[j][i] = m
        }
    }

    /** Joseph form correction with a chi square innovation gate. */
    fun update(y: DoubleArray, H: Mat, R: Mat, gate: Double): Boolean {
        require(y.isNotEmpty() && y.all { it.isFinite() } && gate.isFinite() && gate > 0)
        require(H.size==y.size && H.all { it.size==N && it.all(Double::isFinite) })
        require(R.size==y.size && R.all { it.size==y.size && it.all(Double::isFinite) })
        val ht = transpose(H)
        val s = matMul(matMul(H, P), ht)
        for (i in R.indices) for (j in R.indices) s[i][j] += R[i][j]
        if(s.any { row -> row.any { !it.isFinite() } }) { lastNis=Double.POSITIVE_INFINITY;return false }
        val sInv = invert(s) ?: return false

        var nis = 0.0
        for (i in y.indices) for (j in y.indices) nis += y[i] * sInv[i][j] * y[j]
        lastNis=nis
        if (!nis.isFinite() || nis > gate) return false

        val k = matMul(matMul(P, ht), sInv)
        val dx = matVec(k, y)

        val ikh = identity(N)
        val kh = matMul(k, H)
        for (i in 0 until N) for (j in 0 until N) ikh[i][j] -= kh[i][j]
        P = matMul(matMul(ikh, P), transpose(ikh))
        val krk = matMul(matMul(k, R), transpose(k))
        for (i in 0 until N) for (j in 0 until N) P[i][j] += krk[i][j]

        state.inject(dx)

        // Covariance reset: the frame the attitude error lives in has moved.
        val g = identity(N)
        val half = doubleArrayOf(0.5 * dx[ITH], 0.5 * dx[ITH + 1], 0.5 * dx[ITH + 2])
        val sh = skew(half)
        for (i in 0..2) for (j in 0..2) g[ITH + i][ITH + j] -= sh[i][j]
        val halfM = doubleArrayOf(0.5 * dx[IMN], 0.5 * dx[IMN + 1], 0.5 * dx[IMN + 2])
        val shm = skew(halfM)
        for (i in 0..2) for (j in 0..2) g[IMN + i][IMN + j] -= shm[i][j]
        P = matMul(matMul(g, P), transpose(g))
        symmetrise()
        return true
    }

    // -- measurement models -------------------------------------------------

    fun updateGnssPosition(pEnu: DoubleArray, sigmaH: Double, sigmaV: Double, inflate: Double = 1.0): Boolean {
        vector(pEnu,3); sigma(sigmaH);sigma(sigmaV);sigma(inflate)
        val sh = maxOf(sigmaH, cfg.gnssPositionSigmaFloor) * inflate
        val sv = maxOf(sigmaV, 2.0 * cfg.gnssPositionSigmaFloor) * inflate
        val h = zeros(3, N)
        for (i in 0..2) h[i][IP + i] = 1.0
        val r = zeros(3, 3)
        r[0][0] = sh * sh; r[1][1] = sh * sh; r[2][2] = sv * sv
        val y = DoubleArray(3) { pEnu[it] - state.p[it] }
        return update(y, h, r, cfg.gateChi2Dof3)
    }

    fun updateGnssVelocity(vEnu: DoubleArray, sigmaIn: Double): Boolean {
        vector(vEnu,3); sigma(sigmaIn)
        val sigma = maxOf(sigmaIn, cfg.gnssVelocitySigmaFloor)
        val h = zeros(3, N)
        for (i in 0..2) h[i][IV + i] = 1.0
        val r = zeros(3, 3)
        for (i in 0..2) r[i][i] = sigma * sigma
        val y = DoubleArray(3) { vEnu[it] - state.v[it] }
        return update(y, h, r, cfg.gateChi2Dof3)
    }

    /**
     * Vehicle frame velocity and its Jacobian, the single derivative both the
     * constraints and the odometer depend on.
     *
     *   dv_v = R_vb R_nb^T dv + R_vb skew(v_b) dtheta - R_vb skew(v_b) dmount
     */
    fun vehicleVelocityJacobian(): Pair<DoubleArray, Mat> {
        val rNb = state.rNb
        val rVb = state.rVb
        val vb = matVec(transpose(rNb), state.v)
        val vv = matVec(rVb, vb)
        val h = zeros(3, N)
        val a = matMul(rVb, transpose(rNb))
        val b = matMul(rVb, skew(vb))
        for (i in 0..2) for (j in 0..2) {
            h[i][IV + j] = a[i][j]
            h[i][ITH + j] = b[i][j]
            h[i][IMN + j] = -b[i][j]
        }
        return Pair(vv, h)
    }

    /** A car does not slide sideways or fly.  Two free measurements per sample. */
    fun updateNhc(yawRate: Double): Boolean {
        val speed = sqrt(state.v[0] * state.v[0] + state.v[1] * state.v[1] + state.v[2] * state.v[2])
        if (speed < cfg.nhcMinSpeed || abs(yawRate) > cfg.nhcMaxYawRate) return false
        val (vv, hFull) = vehicleVelocityJacobian()
        val h = arrayOf(hFull[1], hFull[2])
        val r = zeros(2, 2)
        r[0][0] = cfg.nhcLateralSigma * cfg.nhcLateralSigma
        r[1][1] = cfg.nhcVerticalSigma * cfg.nhcVerticalSigma
        return update(doubleArrayOf(-vv[1], -vv[2]), h, r, cfg.gateChi2Dof2)
    }

    /** The learned virtual odometer, the measurement that carries a blackout. */
    fun updateForwardSpeed(speedMps: Double, sigma: Double): Boolean {
        require(speedMps.isFinite() && abs(speedMps)<60.0); sigma(sigma)
        val (vv, hFull) = vehicleVelocityJacobian()
        val h = zeros(1, N)
        for (j in 0 until N) h[0][j] = state.ks * hFull[0][j]
        h[0][IKS] = vv[0]
        val r = arrayOf(doubleArrayOf(sigma * sigma))
        return update(doubleArrayOf(speedMps - state.ks * vv[0]), h, r, cfg.gateChi2Dof1)
    }

    fun updateZupt(): Boolean {
        val h = zeros(3, N)
        for (i in 0..2) h[i][IV + i] = 1.0
        val r = zeros(3, 3)
        for (i in 0..2) r[i][i] = cfg.zuptSigma * cfg.zuptSigma
        return update(DoubleArray(3) { -state.v[it] }, h, r, cfg.gateChi2Dof3)
    }

    fun updateZaru(gyro: DoubleArray): Boolean {
        vector(gyro,3)
        val h = zeros(3, N)
        for (i in 0..2) h[i][IBG + i] = 1.0
        val r = zeros(3, 3)
        for (i in 0..2) r[i][i] = cfg.zaruSigma * cfg.zaruSigma
        return update(DoubleArray(3) { gyro[it] - state.bg[it] }, h, r, cfg.gateChi2Dof3)
    }

    val horizontalPositionSigma: Double get() = sqrt(maxOf(P[0][0] + P[1][1], 0.0))

    fun updateGnssHorizontal(p:DoubleArray,sigmaH:Double,inflate:Double=1.0):Boolean {
        vector(p,2);sigma(sigmaH);sigma(inflate)
        val s=maxOf(sigmaH,cfg.gnssPositionSigmaFloor)*inflate
        val h=zeros(2,N);h[0][0]=1.0;h[1][1]=1.0
        return update(doubleArrayOf(p[0]-state.p[0],p[1]-state.p[1]),h,arrayOf(doubleArrayOf(s*s,0.0),doubleArrayOf(0.0,s*s)),cfg.gateChi2Dof2)
    }
    fun updateGnssHorizontalVelocity(v:DoubleArray,sigmaIn:Double):Boolean {
        vector(v,2);sigma(sigmaIn)
        val s=maxOf(sigmaIn,cfg.gnssVelocitySigmaFloor)
        val h=zeros(2,N);h[0][3]=1.0;h[1][4]=1.0
        return update(doubleArrayOf(v[0]-state.v[0],v[1]-state.v[1]),h,arrayOf(doubleArrayOf(s*s,0.0),doubleArrayOf(0.0,s*s)),cfg.gateChi2Dof2)
    }
    fun updateMapPosition(p:DoubleArray,sigmaM:Double):Boolean {
        vector(p,2);sigma(sigmaM)
        val s=maxOf(3.0,sigmaM);val h=zeros(2,N);h[0][0]=1.0;h[1][1]=1.0
        return update(doubleArrayOf(p[0]-state.p[0],p[1]-state.p[1]),h,arrayOf(doubleArrayOf(s*s,0.0),doubleArrayOf(0.0,s*s)),cfg.gateChi2Dof2)
    }
    val yawSigma:Double get() {
        val body=matVec(transpose(state.rVb),doubleArrayOf(1.0,0.0,0.0))
        val world=matVec(state.rNb,body);val d=world[0]*world[0]+world[1]*world[1]
        if(d<1e-12)return Double.POSITIVE_INFINITY
        val gradient=doubleArrayOf(-world[1]/d,world[0]/d,0.0)
        val rotated=matMul(state.rNb,skew(body));val h=DoubleArray(N)
        for(j in 0..2) { h[ITH+j]=-(0..2).sumOf { gradient[it]*rotated[it][j] };h[IMN+j]=-h[ITH+j] }
        return sqrt(maxOf(0.0,h.indices.sumOf { i -> h.indices.sumOf { j -> h[i]*P[i][j]*h[j] } }))
    }
    fun updateMagneticDirection(fieldBody:DoubleArray,referenceAngle:Double,sigmaRad:Double):Boolean {
        vector(fieldBody,3);sigma(sigmaRad);require(referenceAngle.isFinite())
        val field=matVec(state.rNb,fieldBody);val d=field[0]*field[0]+field[1]*field[1]
        if(d<1e-8)return false
        val grad=doubleArrayOf(-field[1]/d,field[0]/d,0.0)
        val rotated=matMul(state.rNb,skew(fieldBody));val h=zeros(1,N)
        for(j in 0..2)h[0][ITH+j]=-(0..2).sumOf { grad[it]*rotated[it][j] }
        val y=wrap(referenceAngle-kotlin.math.atan2(field[1],field[0]))
        return update(doubleArrayOf(y),h,arrayOf(doubleArrayOf(sigmaRad*sigmaRad)),cfg.gateChi2Dof1)
    }
    fun copy():Esekf {
        val out=Esekf(cfg);out.P=Array(N) { P[it].copyOf() }
        out.state.t=state.t;out.state.p=state.p.copyOf();out.state.v=state.v.copyOf()
        out.state.qNb=state.qNb.copyOf();out.state.qVb=state.qVb.copyOf()
        out.state.ba=state.ba.copyOf();out.state.bg=state.bg.copyOf();out.state.ks=state.ks
        return out
    }
    companion object {
        private fun vector(x:DoubleArray,n:Int) { require(x.size==n && x.all { it.isFinite() }) }
        private fun sigma(x:Double) { require(x.isFinite() && x>0.0 && (x*x).isFinite() && x*x>0.0) }
    }
}
