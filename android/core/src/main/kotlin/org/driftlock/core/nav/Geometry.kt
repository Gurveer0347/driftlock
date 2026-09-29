package org.driftlock.core.nav

import kotlin.math.*

fun wrap(angle:Double):Double = atan2(sin(angle),cos(angle))
internal fun norm(x:DoubleArray)=sqrt(x.sumOf { it*it })
internal fun dot(a:DoubleArray,b:DoubleArray)=a.indices.sumOf { a[it]*b[it] }
internal fun unit(x:DoubleArray):DoubleArray { val n=norm(x);require(n.isFinite() && n>1e-10);return DoubleArray(x.size) {x[it]/n} }
internal fun cross(a:DoubleArray,b:DoubleArray)=doubleArrayOf(a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0])
internal fun mean(rows:Collection<DoubleArray>)=DoubleArray(3) { j->rows.sumOf {it[j]}/rows.size }
internal fun std(values:List<Double>):Double {val m=values.average();return sqrt(values.sumOf {(it-m)*(it-m)}/values.size)}
internal fun rotateZ(yaw:Double):Mat = arrayOf(doubleArrayOf(cos(yaw),-sin(yaw),0.0),doubleArrayOf(sin(yaw),cos(yaw),0.0),doubleArrayOf(0.0,0.0,1.0))
internal fun matrixToQuat(r:Mat):DoubleArray {
    val q=DoubleArray(4);val trace=r[0][0]+r[1][1]+r[2][2]
    if(trace>0) {
        val s=sqrt(trace+1.0)*2;q[0]=.25*s;q[1]=(r[2][1]-r[1][2])/s;q[2]=(r[0][2]-r[2][0])/s;q[3]=(r[1][0]-r[0][1])/s
    } else {
        val i=(0..2).maxBy {r[it][it]};val j=(i+1)%3;val k=(i+2)%3
        val s=sqrt(maxOf(0.0,1+r[i][i]-r[j][j]-r[k][k]))*2
        q[i+1]=.25*s;q[0]=(r[k][j]-r[j][k])/s;q[j+1]=(r[j][i]+r[i][j])/s;q[k+1]=(r[k][i]+r[i][k])/s
    }
    return quatNormalise(q)
}

/** WGS84 ECEF/ENU, fixed at a trusted drive origin; no phone axis remapping. */
class LocalFrame(val origin:GeographicPosition) {
    private val lat=Math.toRadians(origin.latitudeDeg)
    private val lon=Math.toRadians(origin.longitudeDeg)
    private val ecefOrigin=ecef(origin.latitudeDeg,origin.longitudeDeg,origin.altitudeM?:0.0)
    private val r=arrayOf(doubleArrayOf(-sin(lon),cos(lon),0.0),
        doubleArrayOf(-sin(lat)*cos(lon),-sin(lat)*sin(lon),cos(lat)),
        doubleArrayOf(cos(lat)*cos(lon),cos(lat)*sin(lon),sin(lat)))
    init {require(origin.latitudeDeg.isFinite() && origin.latitudeDeg in -90.0..90.0);require(origin.longitudeDeg.isFinite() && origin.longitudeDeg in -180.0..180.0)}
    fun toEnu(p:GeographicPosition):DoubleArray {
        val xyz=ecef(p.latitudeDeg,p.longitudeDeg,p.altitudeM?:origin.altitudeM?:0.0)
        return matVec(r,DoubleArray(3) {xyz[it]-ecefOrigin[it]})
    }
    fun fromEnu(p:DoubleArray):GeographicPosition {
        require(p.size==3 && p.all {it.isFinite()})
        val d=matVec(transpose(r),p);val x=ecefOrigin[0]+d[0];val y=ecefOrigin[1]+d[1];val z=ecefOrigin[2]+d[2]
        val horizontal=hypot(x,y);val longitude=atan2(y,x)
        if(horizontal<1e-9)return GeographicPosition(if(z>=0)90.0 else -90.0,origin.longitudeDeg,origin.altitudeM?.let {abs(z)-A*sqrt(1-E2)})
        var latitude=atan2(z,horizontal*(1-E2));var height=0.0
        repeat(12) {val n=A/sqrt(1-E2*sin(latitude)*sin(latitude));height=horizontal/cos(latitude)-n;latitude=atan2(z,horizontal*(1-E2*n/(n+height)))}
        return GeographicPosition(Math.toDegrees(latitude),Math.toDegrees(longitude),if(origin.altitudeM==null)null else height)
    }
    companion object {
        private const val A=6378137.0
        private const val F=1.0/298.257223563
        private const val E2=F*(2-F)
        private fun ecef(latDeg:Double,lonDeg:Double,h:Double):DoubleArray {
            require(listOf(latDeg,lonDeg,h).all {it.isFinite()})
            val lat=Math.toRadians(latDeg);val lon=Math.toRadians(lonDeg);val n=A/sqrt(1-E2*sin(lat)*sin(lat))
            return doubleArrayOf((n+h)*cos(lat)*cos(lon),(n+h)*cos(lat)*sin(lon),(n*(1-E2)+h)*sin(lat))
        }
    }
}
