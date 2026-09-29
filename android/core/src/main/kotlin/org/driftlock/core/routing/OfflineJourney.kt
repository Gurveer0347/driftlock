package org.driftlock.core.routing

data class JourneyState(val route: Route?,val status: RouteStatus,val message: String,
    val position: RoadPosition?=null,val blocked: Set<String> = emptySet())
class OfflineJourney(val graph: RoadGraph,val destination: Long,val alternatives: List<Route>,
    initialBlocked: Set<String> = emptySet()) {
    private val router=OfflineRouter(graph)
    private var lastT=Double.NEGATIVE_INFINITY
    private var wrongCount=0;private var wrongSince=0.0
    var state=JourneyState(alternatives.firstOrNull{r->r.legs.none{graph.edges.getValue(it.edgeId).segmentId in initialBlocked}},
        RouteStatus.READY,"Route prepared; waiting for a current road match",blocked=initialBlocked); private set
    init {require(destination in graph.nodes);require(alternatives.isNotEmpty());require(initialBlocked.all{s->graph.edges.values.any{it.segmentId==s}})}
    fun observe(t: Double,position: RoadPosition?): JourneyState {
        if(!t.isFinite() || t<0 || t<=lastT)return state
        lastT=t
        if(position==null || position.edgeId !in graph.edges) {
            wrongCount=0;state=state.copy(position=null,message="Current road is uncertain; automatic rerouting paused");return state
        }
        state=state.copy(position=position)
        if(state.route?.legs?.any{it.edgeId==position.edgeId}==true) {wrongCount=0;return state}
        if(wrongCount==0)wrongSince=t
        wrongCount++
        // A brief junction ambiguity cannot force a route switch.
        if(wrongCount>=3 && t-wrongSince>=1.5) {
            apply(router.reroute(position,destination,alternatives,state.blocked));wrongCount=0
        }
        return state
    }
    private fun apply(result:RoutingResult) {state=state.copy(route=result.route,status=result.status,message=result.reason)}
    fun block(segment: String): JourneyState {
        require(graph.edges.values.any{it.segmentId==segment}) {"Unknown physical road segment"}
        state=state.copy(blocked=state.blocked+segment)
        val position=state.position
        if(position==null)state=state.copy(route=null,status=RouteStatus.NO_DOWNLOADED_ROUTE_AVAILABLE,message="Road marked blocked. A clear current road match is required to connect another route.")
        else apply(router.reroute(position,destination,alternatives,state.blocked))
        return state
    }
    fun changeRoute(): JourneyState {
        val position=state.position ?: return state.copy(message="A clear current road match is required").also{state=it}
        val route=state.route
        val index=route?.legs?.indexOfFirst{it.edgeId==position.edgeId} ?: -1
        val next=if(index>=0)route?.legs?.getOrNull(index+1)?.let{graph.edges.getValue(it.edgeId).segmentId} else null
        val result=router.reroute(position,destination,alternatives.filter{it.id!=route?.id},state.blocked+listOfNotNull(next))
        if(result.route!=null)apply(result) else state=state.copy(message="No different legal route in downloaded roads; current route retained")
        return state
    }
    fun select(id: String): JourneyState {
        val route=alternatives.firstOrNull{it.id==id} ?: throw IllegalArgumentException("Unknown route")
        val position=state.position
        if(position!=null)apply(router.reroute(position,destination,listOf(route),state.blocked))
        else if(route.legs.none{graph.edges.getValue(it.edgeId).segmentId in state.blocked})state=state.copy(route=route,status=RouteStatus.READY)
        return state
    }
    fun resetTracking():JourneyState {
        wrongCount=0;wrongSince=0.0;lastT=Double.NEGATIVE_INFINITY
        state=state.copy(position=null,route=alternatives.firstOrNull{r->r.legs.none{graph.edges.getValue(it.edgeId).segmentId in state.blocked}},
            status=RouteStatus.READY,message="Waiting for a fresh road match for this session")
        return state
    }
}
