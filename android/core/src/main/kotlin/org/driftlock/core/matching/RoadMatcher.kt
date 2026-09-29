package org.driftlock.core.matching

import org.driftlock.core.routing.*
import kotlin.math.*

data class MatchObservation(val t: Double, val point: Point, val sigmaM: Double,
    val headingRad: Double? = null, val headingSigmaRad: Double = 0.5, val speedMps: Double? = null)
data class RoadHypothesis(val position: RoadPosition,val point: Point,val posterior: Double,val distanceM: Double)
enum class MatchStatus { LOCKED, AMBIGUOUS, NO_MATCH, OUTSIDE_COVERAGE }
data class MatchResult(val status: MatchStatus,val hypotheses: List<RoadHypothesis>,val reason: String) {
    val committed: RoadHypothesis? get()=if(status==MatchStatus.LOCKED) hypotheses.firstOrNull() else null
}
class RoadMatcher(private val graph: RoadGraph) {
    private data class Candidate(val edge: RoadEdge,val fraction: Double,val point: Point,val distance: Double,val heading: Double,val logEmission: Double)
    private var previous=emptyList<Pair<Candidate,Double>>()
    private var lastAccepted: MatchObservation?=null
    private var lastSeenT: Double?=null
    private val router=OfflineRouter(graph)
    private val paths=object: LinkedHashMap<Pair<String,String>,Double?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String,String>,Double?>?) = size>512
    }
    private fun wrap(x: Double)=atan2(sin(x),cos(x))
    private fun project(edge: RoadEdge,o: MatchObservation): Candidate {
        val lengths=edge.geometry.zipWithNext().map{it.first.distance(it.second)}
        val total=lengths.sum();var traversed=0.0;var bestDistance=Double.POSITIVE_INFINITY
        var bestPoint=edge.geometry.first();var bestFraction=0.0;var heading=0.0
        for(i in lengths.indices) {
            val a=edge.geometry[i];val b=edge.geometry[i+1];val dx=b.eastM-a.eastM;val dy=b.northM-a.northM
            val u=if(lengths[i]>0) (((o.point.eastM-a.eastM)*dx+(o.point.northM-a.northM)*dy)/(lengths[i]*lengths[i])).coerceIn(0.0,1.0) else 0.0
            val p=Point(a.eastM+u*dx,a.northM+u*dy);val distance=p.distance(o.point)
            if(distance<bestDistance) { bestDistance=distance;bestPoint=p;bestFraction=(traversed+u*lengths[i])/total;heading=atan2(dy,dx) }
            traversed+=lengths[i]
        }
        val variance=o.sigmaM*o.sigmaM+4.0
        var emission=-0.5*bestDistance*bestDistance/variance
        if(o.headingRad!=null && (o.speedMps ?: 0.0)>1.0) emission-=0.5*(wrap(heading-o.headingRad)/o.headingSigmaRad).pow(2)
        return Candidate(edge,bestFraction,bestPoint,bestDistance,heading,emission)
    }
    private fun pathDistance(a: Candidate,b: Candidate): Double? {
        if(a.edge.id==b.edge.id) {
            val delta=(b.fraction-a.fraction)*a.edge.lengthM
            return if(delta>=-3.0) max(0.0,delta) else null
        }
        val key=a.edge.id to b.edge.id
        val middle=if(paths.containsKey(key)) paths[key] else {
            val route=router.fromPositionToEdge(RoadPosition(a.edge.id,1.0),b.edge.id)
            val distance=route?.let{(it.distanceM-b.edge.lengthM).coerceAtLeast(0.0)}
            paths[key]=distance;distance
        } ?: return null
        return (1-a.fraction)*a.edge.lengthM+middle+b.fraction*b.edge.lengthM
    }
    private fun logSum(values: List<Double>): Double {
        if(values.isEmpty()) return Double.NEGATIVE_INFINITY
        val m=values.max();return m+ln(values.sumOf{exp(it-m)})
    }
    fun update(observation: MatchObservation): MatchResult {
        val o=observation
        fun reject(reason: String,status: MatchStatus=MatchStatus.NO_MATCH)=MatchResult(status,emptyList(),reason)
        if(!o.t.isFinite() || o.t<0 || !o.sigmaM.isFinite() || o.sigmaM<=0 ||
            !o.headingSigmaRad.isFinite() || o.headingSigmaRad<=0 || o.headingRad?.isFinite()==false ||
            o.speedMps?.let{!it.isFinite() || it<0}==true) return reject("Invalid matcher observation")
        if(lastSeenT!=null && o.t<=lastSeenT!!) return reject("Non-increasing observation time")
        lastSeenT=o.t
        if(!graph.coverage.contains(o.point)) { previous=emptyList();lastAccepted=null;return reject("Outside downloaded coverage",MatchStatus.OUTSIDE_COVERAGE) }
        val old=lastAccepted
        if(old!=null && o.t-old.t>5.0) { previous=emptyList();lastAccepted=null }
        val variance=o.sigmaM*o.sigmaM+4.0
        val radius=min(120.0,4*sqrt(variance))
        val candidates=graph.edges.values.asSequence().map{project(it,o)}.filter {
            it.distance<=radius && it.distance*it.distance/variance<=9.21 &&
                (o.headingRad==null || (o.speedMps ?: 0.0)<=1.0 || abs(wrap(it.heading-o.headingRad))<=3*o.headingSigmaRad)
        }.sortedByDescending{it.logEmission}.take(12).toList()
        if(candidates.isEmpty()) return reject("No road passes absolute position and heading gates")
        val before=lastAccepted
        val scores=candidates.map { candidate ->
            val transition=if(previous.isEmpty() || before==null) 0.0 else {
                val dt=o.t-before.t
                val expected=if(o.speedMps!=null) o.speedMps*dt else o.point.distance(before.point)
                val scale=max(5.0,sqrt(variance+before.sigmaM*before.sigmaM)+dt*2.0)
                logSum(previous.sortedByDescending{it.second}.take(4).mapNotNull { (p,probability) ->
                    val d=pathDistance(p,candidate) ?: return@mapNotNull null
                    if(abs(d-expected)>4*scale) null else ln(probability.coerceAtLeast(1e-300))-abs(d-expected)/scale
                })
            }
            candidate to candidate.logEmission+transition
        }.filter{it.second.isFinite()}
        if(scores.isEmpty()) return reject("Road candidates are not connected to previous hypotheses")
        val normalizer=logSum(scores.map{it.second})
        previous=scores.map{it.first to exp(it.second-normalizer)}.sortedByDescending{it.second}
        lastAccepted=o
        val hypotheses=previous.map{(c,p)->RoadHypothesis(RoadPosition(c.edge.id,c.fraction),c.point,p,c.distance)}
        val top=hypotheses[0].posterior;val second=hypotheses.getOrNull(1)?.posterior ?: 0.0
        val locked=top>=0.85 && top-second>=0.2
        return MatchResult(if(locked)MatchStatus.LOCKED else MatchStatus.AMBIGUOUS,hypotheses,
            if(locked)"One connected road hypothesis dominates; posterior is not position accuracy" else "Multiple downloaded roads remain plausible")
    }
    fun reset() { previous=emptyList();lastAccepted=null;lastSeenT=null;paths.clear() }
}
