package org.driftlock.app

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.driftlock.app.sensors.*
import org.driftlock.app.trips.*
import org.driftlock.core.sensors.*
import org.driftlock.core.nav.*
import org.driftlock.core.routing.*
import org.driftlock.core.matching.*
import org.driftlock.core.demo.SyntheticReplay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

data class NativeState(
    val page:String="Home",val busy:Boolean=false,val message:String="Choose an offline region to prepare a journey.",
    val graph:RoadGraph?=null,val mapScene:MapScene=MapScene(emptyList()),val graphBytes:Long=0,val warnings:List<String> = emptyList(),
    val start:Long?=null,val destination:Long?=null,val routes:List<Route> = emptyList(),val selectedRoute:String?=null,
    val packs:List<TripRecord> = emptyList(),val packId:String?=null,val journey:JourneyState?=null,
    val sensor:SensorSessionState=SensorSessionState(),val nav:NavigationSnapshot?=null,val match:MatchResult?=null,
    val navigationHz:Double?=null,val navTickMs:Double?=null,val demo:Boolean=false,val demoGnss:String="NORMAL",
    val demoElapsedS:Double=0.0,val demoOutageS:Double=0.0,val demoFinished:Boolean=false,val demoBlockedRoad:String?=null,
    val demoPreviewRoute:Route?=null,val demoPreviewBlockedSegment:String?=null,val demoPreviewAnchor:String?=null,
    val demoPreviewDeltaM:Double?=null,
    val guidedPrepared:Boolean=false,val guidedPlan:GuidedDetour?=null,val guidedWaiting:Boolean=false,
    val guidedDetourChosen:Boolean=false,
)

/** One application-lifetime controller. Engine commands use the service worker; maps use a separate serial worker. */
class NativeController(private val context:Context) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val mapDispatcher=Executors.newSingleThreadExecutor{r->Thread(r,"DriftlockRoads")}.asCoroutineDispatcher()
    private val mutable=MutableStateFlow(NativeState());val state=mutable.asStateFlow()
    private val repository=TripRepository(context)
    private var binder:SensorService.LocalBinder?=null
    private var sensorJob:Job?=null
    private var engine=NavigationEngine()
    private var graphSource="";private var graphBytes=byteArrayOf()
    private var journey:OfflineJourney?=null;private var matcher:RoadMatcher?=null
    private val matchQueue=Channel<Pair<Long,NavigationSnapshot>>(Channel.CONFLATED)
    private val graphGeneration=AtomicLong()
    private enum class Source { IDLE, PHONE, SYNTHETIC }
    private data class SessionToken(val id:Long,val source:Source)
    private val authorityLock=Any()
    @Volatile private var authority=SessionToken(0,Source.IDLE)
    private var engineOwner=authority
    private var phoneStartedAt:Double?=null
    private var lastAcceptedPhoneImuT:Double?=null
    private var phoneStreamStalled=false
    private var stallEpisode=0L
    private var matcherGeneration=-1L
    private var lastPublish=Double.NEGATIVE_INFINITY;private var lastMatch=Double.NEGATIVE_INFINITY
    private var rateStart=0.0;private var rateTicks=0
    private var demoJob:Job?=null
    private var demoCutStartedAt:Double?=null
    private var demoMountRotated=false
    private var preDemoJourney:OfflineJourney?=null
    private var demoRoadToken:SessionToken?=null
    private val sink=object:SensorSink {
        override fun onImu(sample:PhoneImu) {
            val token=authority
            if(token.source!=Source.PHONE || token!=engineOwner)return
            val start=System.nanoTime();engine.onImu(sample);publish(sample.t,(System.nanoTime()-start)/1e6,token)
        }
        override fun onGnss(fix:PhoneGnss) { if(authority.source==Source.PHONE && authority==engineOwner)engine.onGnss(fix) }
        override fun onMag(sample:PhoneMag) { if(authority.source==Source.PHONE && authority==engineOwner)engine.onMag(sample) }
        override fun onDiscontinuity(reason:String,receiptT:Double) { if(authority.source==Source.PHONE && authority==engineOwner)engine.onDiscontinuity(reason,receiptT) }
    }
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,service:IBinder) {
            val local=service as SensorService.LocalBinder;binder=local
            local.attach(sink){result,_->if(authority.source==Source.PHONE && authority==engineOwner)engine.onSpeed(result.measurement)}
            sensorJob?.cancel();sensorJob=scope.launch{local.service.states.collect{sensor->
                val stopped=state.value.sensor.active && !sensor.active
                mutable.update{it.copy(sensor=sensor)}
                if(stopped && authority.source==Source.PHONE)endSession()
            }}
        }
        override fun onServiceDisconnected(name:ComponentName) {
            binder=null;endSession()
            mutable.update{it.copy(sensor=it.sensor.copy(active=false),message="Sensor service disconnected; start a new recording.")}
        }
    }
    init {
        context.bindService(Intent(context,SensorService::class.java),connection,Context.BIND_AUTO_CREATE)
        scope.launch(mapDispatcher) {
            for((generation,snapshot) in matchQueue) {
                if(generation!=graphGeneration.get())continue
                val graph=state.value.graph ?: continue;val position=snapshot.position ?: continue;val t=snapshot.t ?: continue
                if(matcherGeneration!=generation){matcher?.reset();journey?.resetTracking();matcherGeneration=generation}
                val point=geoPoint(graph,position.latitudeDeg,position.longitudeDeg)
                val result=matcher?.update(MatchObservation(t,point,snapshot.positionSigmaM ?: 100.0,snapshot.headingRad,
                    snapshot.headingSigmaRad?.coerceAtLeast(.05) ?: 1.5,snapshot.speedMps)) ?: continue
                synchronized(authorityLock) {
                    if(generation==graphGeneration.get()) {
                        val current=journey?.observe(t,result.committed?.position)
                        mutable.update{it.copy(match=result,journey=current ?: it.journey)}
                    }
                }
            }
        }
        scope.launch {while(isActive){delay(250);checkPhoneFreshness()}}
        work{mutable.update{it.copy(packs=repository.list())}}
    }
    private fun publish(t:Double,tickMs:Double,token:SessionToken)=synchronized(authorityLock) {
        if(token!=authority || token.source==Source.IDLE || token!=engineOwner)return@synchronized
        if(t-lastPublish<.08)return@synchronized
        lastPublish=t;val now=SystemClock.elapsedRealtimeNanos()/1e9
        if(rateStart==0.0)rateStart=now
        rateTicks++;val hz=if(now-rateStart>=1.0)rateTicks/(now-rateStart) else state.value.navigationHz
        if(now-rateStart>=1.0){rateStart=now;rateTicks=0}
        val snapshot=engine.snapshot();var resumed=false
        if(token.source==Source.PHONE) {
            // A fresh receipt cannot make an old queued hardware epoch current.
            if(snapshot.t?.let{abs(it-t)<1e-6 && now-it in 0.0..1.0}==true) {
                resumed=phoneStreamStalled;lastAcceptedPhoneImuT=t;phoneStreamStalled=false
            } else if(phoneStreamStalled)return@synchronized
        }
        mutable.update{it.copy(nav=snapshot,navigationHz=hz,navTickMs=tickMs,
            message=if(resumed)"Phone IMU updates resumed. Recheck mount alignment before relying on navigation." else it.message)}
        if(t-lastMatch>=.5){lastMatch=t;matchQueue.trySend(graphGeneration.get() to snapshot)}
        if(token.source==Source.PHONE) binder?.recordNavigation(now,JSONObject().put("target_t",t).put("receipt_t",now)
            .put("receipt_target_lag_ms",(now-t)*1000).put("tick_ms",tickMs).put("tick_scope","engine_on_imu_only")
            .put("source","real_phone").put("session_token",token.id).put("gnss_state",snapshot.gnss.name)
            .put("alignment",snapshot.alignment.name).put("horizon",snapshot.horizon.name)
            .put("lat",snapshot.position?.latitudeDeg ?: JSONObject.NULL).put("lon",snapshot.position?.longitudeDeg ?: JSONObject.NULL)
            .put("position_sigma_m",snapshot.positionSigmaM ?: JSONObject.NULL).put("speed_mps",snapshot.speedMps)
            .put("route_id",state.value.journey?.route?.id ?: JSONObject.NULL).put("match",state.value.match?.status?.name ?: "UNAVAILABLE").toString())
    }
    private fun work(action:suspend ()->Unit) {
        scope.launch(mapDispatcher) {
            mutable.update{it.copy(busy=true)}
            try {action()} catch(e:Exception){mutable.update{it.copy(message=e.message ?: e.javaClass.simpleName)}}
            finally {mutable.update{it.copy(busy=false)}}
        }
    }
    fun page(page:String){mutable.update{it.copy(page=page)}}
    fun message(text:String){mutable.update{it.copy(message=text)}}
    /** Revoke old publications synchronously, even while either worker is busy. */
    private fun beginSession(source:Source):SessionToken=synchronized(authorityLock) {
        val token=SessionToken(authority.id+1,source);authority=token;graphGeneration.incrementAndGet()
        phoneStartedAt=if(source==Source.PHONE)SystemClock.elapsedRealtimeNanos()/1e9 else null
        lastAcceptedPhoneImuT=null;phoneStreamStalled=false;stallEpisode=0
        mutable.update{it.copy(demo=source==Source.SYNTHETIC,nav=null,match=null,
            journey=it.journey?.copy(position=null),navigationHz=null,navTickMs=null,
            demoPreviewRoute=null,demoPreviewBlockedSegment=null,demoPreviewAnchor=null,demoPreviewDeltaM=null,
            guidedPlan=null,guidedWaiting=false,guidedDetourChosen=false)}
        token
    }
    private fun checkPhoneFreshness() {
        val loss=synchronized(authorityLock) {
            val token=authority;val now=SystemClock.elapsedRealtimeNanos()/1e9
            val last=lastAcceptedPhoneImuT ?: phoneStartedAt
            if(token.source!=Source.PHONE || !state.value.sensor.active || phoneStreamStalled || last==null || now-last<=1.0)null
            else {
                phoneStreamStalled=true;stallEpisode++;graphGeneration.incrementAndGet()
                mutable.update{it.copy(nav=null,match=null,journey=it.journey?.copy(position=null),navigationHz=null,navTickMs=null,
                    message="Phone navigation paused: no fresh IMU updates for over one second.")}
                Triple(token,now,stallEpisode)
            }
        } ?: return
        val (token,receiptT,episode)=loss
        binder?.dispatch {synchronized(authorityLock) {
            if(token==authority && token==engineOwner && episode==stallEpisode) {
                if(phoneStreamStalled)engine.onDiscontinuity("phone_imu_stream_stalled",receiptT)
                binder?.recordDiscontinuity(receiptT,"phone_imu_stream_stalled")
            }
        }}
        scope.launch(mapDispatcher) {synchronized(authorityLock) {
            if(token==authority && phoneStreamStalled && episode==stallEpisode) {
                matcher?.reset();val cleared=journey?.resetTracking()
                mutable.update{it.copy(match=null,journey=cleared)}
            }
        }}
    }
    /** This job belongs to the controller scope, so cancelling replay cannot skip cleanup. */
    private fun prepareRoadSession(token:SessionToken)=scope.async(mapDispatcher) {
        synchronized(authorityLock) {
            if(token!=authority)return@synchronized
            if(demoRoadToken!=null){journey=preDemoJourney;preDemoJourney=null;demoRoadToken=null}
            if(token.source==Source.SYNTHETIC) {
                preDemoJourney=journey;demoRoadToken=token
                journey=journey?.let{OfflineJourney(it.graph,it.destination,it.alternatives,it.state.blocked)}
            }
            matcher?.reset();val cleared=journey?.resetTracking()
            mutable.update{it.copy(match=null,journey=cleared)}
        }
    }
    private fun resetEngine(token:SessionToken) {
        if(token!=authority)return
        engine=NavigationEngine(allowMockFixes=token.source==Source.SYNTHETIC);engineOwner=token
        if(token.source==Source.SYNTHETIC)engine.confirmForwardMotion()
        demoMountRotated=false;lastPublish=Double.NEGATIVE_INFINITY;lastMatch=Double.NEGATIVE_INFINITY;rateStart=0.0;rateTicks=0
    }
    private fun endSession() {
        demoJob?.cancel();demoJob=null
        val token=beginSession(Source.IDLE);prepareRoadSession(token);binder?.dispatch{resetEngine(token)}
    }
    private fun installGraph(bytes:ByteArray,source:String,id:String) {
        val parsed=OsmImporter.parse(bytes,id,source);graphBytes=bytes;graphSource=source;graphGeneration.incrementAndGet()
        val sceneResult=runCatching{MapScene.parse(bytes,parsed.graph)}
        val scene=sceneResult.getOrElse{MapScene(emptyList())}
        val displayWarnings=parsed.warnings+sceneResult.exceptionOrNull()?.let{listOf("Map footprints unavailable: ${it.message}")}.orEmpty()
        matcher=RoadMatcher(parsed.graph);journey=null
        mutable.update{it.copy(graph=parsed.graph,mapScene=scene,graphBytes=bytes.size.toLong(),warnings=displayWarnings,start=null,destination=null,
            routes=emptyList(),selectedRoute=null,packId=null,journey=null,match=null,guidedPrepared=false,
            message="Region loaded. Tap a start road, then a destination road.")}
    }
    fun sampleMap() {
        stopDemo()
        work {installGraph(context.assets.open("maps/chandigarh.osm").use{it.readBytes()},
            "https://api.openstreetmap.org/api/0.6/map?bbox=76.771,30.730,76.791,30.746","chandigarh");page("Prepare")}
    }
    /** Selects fixture endpoints only for route preparation; replay truth never moves the map marker. */
    fun prepareDemoCorridor()=work {
        check(!state.value.demo){"Exit simulation before preparing another journey"}
        val graph=state.value.graph ?: error("Load the offline Chandigarh region first")
        require(graph.id=="chandigarh") {"The guided corridor is available only with the bundled Chandigarh map"}
        val replay=SyntheticReplay(context.assets.open("demo/maneuver.csv").bufferedReader().use{it.readText()})
        val fixes=replay.rows.filter{it.kind=="G"}
        require(fixes.size>=2){"Demo fixture needs two recorded GNSS endpoints"}
        fun closest(lat:Double,lon:Double,start:Boolean):Long {
            val p=geoPoint(graph,lat,lon)
            val node=graph.nodes.values.filter{!start || it.id in graph.outgoing}.minByOrNull{it.point.distance(p)}
                ?: error("No nearby drivable road node")
            require(node.point.distance(p)<=100.0){"Fixture endpoint is not within 100 metres of a mapped road node"}
            return node.id
        }
        val from=closest(fixes.first().values[0],fixes.first().values[1],true)
        // Bundled OSM node, 104 m from the fixture's final GNSS point. It is a
        // selectable mapped destination with a legal alternate, not replay truth.
        val to=1430552905L
        require(to in graph.nodes && graph.nodes.getValue(to).point.distance(geoPoint(graph,fixes.last().values[0],fixes.last().values[1]))<120.0)
        val routes=OfflineRouter(graph).alternatives(from,to)
        require(routes.size>=2){ "Bundled guided corridor no longer has a legal alternate; select roads manually" }
        mutable.update{it.copy(start=from,destination=to,routes=routes,selectedRoute=routes.minBy{r->r.distanceM}.id,
            guidedPrepared=true,
            message="Scenario route ready. Tap the shortest route to begin the guided drive.")}
    }
    fun downloadRegion(lat:Double,lon:Double)=work {
        check(!state.value.demo){"Exit simulation before downloading a region"}
        val bounds=GeoBounds(lat-.008,lon-.01,lat+.008,lon+.01)
        mutable.update{it.copy(message="Downloading the selected OSM region. Maximum 20 MB; actual size will be shown.")}
        val (bytes,source)=MapDownload.fetch(bounds);installGraph(bytes,source,"region-${System.currentTimeMillis()}");page("Prepare")
    }
    fun selectPoint(point:Point)=work {
        check(!state.value.demo){"Exit simulation before preparing another journey"}
        val s=state.value;val graph=s.graph ?: return@work
        val selectingStart=s.start==null || s.destination!=null
        val node=graph.nodes.values.filter{!selectingStart || it.id in graph.outgoing}.minByOrNull{it.point.distance(point)} ?: return@work
        require(graph.coverage.contains(point) && node.point.distance(point)<=100.0){"Tap a downloaded road within 100 metres of a road node"}
        if(selectingStart) mutable.update{it.copy(start=node.id,destination=null,routes=emptyList(),guidedPrepared=false,message="Start selected. Tap your destination.")}
        else {
            val routes=OfflineRouter(graph).alternatives(s.start!!,node.id)
            mutable.update{it.copy(destination=node.id,routes=routes,selectedRoute=routes.firstOrNull()?.id,
                message=if(routes.isEmpty())"No connected downloaded route. Choose another road or a larger region." else "${routes.size} legal route(s) found in this region. Save the trip pack before departure.")}
        }
    }
    fun chooseRoute(id:String) {mutable.update{it.copy(selectedRoute=id)}}
    fun startGuidedRoute(id:String) {
        val s=state.value
        require(s.guidedPrepared && s.routes.minByOrNull{it.distanceM}?.id==id){"Choose the shortest guided route"}
        chooseRoute(id)
        savePack(guided=true)
    }
    fun savePack(guided:Boolean=false)=work {
        check(!state.value.demo){"Exit simulation before saving a real trip pack"}
        val s=state.value;val graph=s.graph ?: return@work;val destination=s.destination ?: return@work
        require(s.routes.isNotEmpty()){ "Plan a route first" }
        val routes=s.routes.sortedBy{if(it.id==s.selectedRoute)0 else 1}
        val pack=repository.create("trip-${System.currentTimeMillis()}","${graph.id} journey",graphBytes,graphSource,routes,destination)
        journey=OfflineJourney(pack.graph,destination,pack.routes);matcher=RoadMatcher(pack.graph)
        mutable.update{it.copy(packId=pack.id,journey=journey!!.state,packs=repository.list(),
            message="Offline journey ready • ${pack.byteSize} bytes saved",page=if(guided)"Demo" else "Calibration")}
        if(guided)startDemo()
    }
    fun loadPack(id:String)=work {
        check(!state.value.demo){"Exit simulation before loading another trip pack"}
        val loaded=repository.loadForPlanning(id);val pack=loaded.pack
        graphBytes=loaded.osm;graphSource=pack.graph.source;graphGeneration.incrementAndGet();matcher=RoadMatcher(pack.graph)
        journey=OfflineJourney(pack.graph,pack.destination,pack.routes,pack.blockedSegments)
        val sceneResult=runCatching{MapScene.parse(loaded.osm,pack.graph)}
        val scene=sceneResult.getOrElse{MapScene(emptyList())}
        val displayWarnings=pack.warnings+sceneResult.exceptionOrNull()?.let{listOf("Map footprints unavailable: ${it.message}")}.orEmpty()
        mutable.update{it.copy(graph=pack.graph,mapScene=scene,graphBytes=pack.byteSize,warnings=displayWarnings,routes=pack.routes,
            start=null,destination=pack.destination,selectedRoute=pack.routes.firstOrNull()?.id,packId=id,journey=journey!!.state,match=null,guidedPrepared=false,
            message="Saved journey loaded without network access",page="Driver")}
    }
    fun deletePack(id:String)=work {
        check(!state.value.demo){"Exit simulation before deleting a trip pack"}
        repository.delete(id)
        if(state.value.packId==id){journey=null;mutable.update{it.copy(packId=null,journey=null)}}
        mutable.update{it.copy(packs=repository.list(),message="Trip pack deleted")}
    }
    fun upcomingSegment():RoadEdge? {
        val s=state.value;val graph=s.graph ?: return null;val route=s.journey?.route ?: return null
        val current=s.journey.position?.edgeId ?: return null
        val i=route.legs.indexOfFirst{it.edgeId==current}
        return route.legs.getOrNull(i+1)?.let{graph.edges[it.edgeId]}
    }
    fun block(segment:String) {
        val token=authority
        work {
            if(token!=authority)return@work
            val j=journey ?: return@work;val result=j.block(segment)
            if(token.source!=Source.SYNTHETIC)state.value.packId?.let{repository.block(it,result.blocked)}
            val road=state.value.graph?.edges?.values?.firstOrNull{it.segmentId==segment}?.name
            synchronized(authorityLock){if(token==authority)mutable.update{it.copy(journey=result,message=result.message,demoPreviewRoute=null,
                demoPreviewBlockedSegment=null,demoPreviewAnchor=null,demoPreviewDeltaM=null,
                demoBlockedRoad=if(token.source==Source.SYNTHETIC)road ?: segment else it.demoBlockedRoad)}}
        }
    }
    /** A hypothetical closure from a mapped route point when no upcoming route segment is established. */
    fun previewDemoBlockage()=work {
        require(state.value.demo){"Start the simulated replay first"}
        val j=journey ?: error("Save an offline journey first")
        val route=j.state.route ?: error("No downloaded route is established")
        val current=j.state.position
        val index=route.legs.indexOfFirst{it.edgeId==current?.edgeId}
        require(index<0 || index==route.legs.lastIndex){"An upcoming segment is established; use the matched road control"}
        val graph=j.graph
        val middle=(route.legs.lastIndex/3.0)
        val candidate=(0 until route.legs.lastIndex).sortedBy{abs(it-middle)}.firstNotNullOfOrNull{i->
            val current=graph.edges.getValue(route.legs[i].edgeId)
            val next=graph.edges.getValue(route.legs[i+1].edgeId)
            val result=OfflineRouter(graph).reroute(RoadPosition(current.id,0.5),j.destination,j.alternatives,j.state.blocked+next.segmentId)
            result.route?.let{alternate->
                val originalRemaining=current.lengthM*0.5+route.legs.drop(i+1).sumOf{leg->
                    val e=graph.edges.getValue(leg.edgeId);e.lengthM*(leg.endFraction-leg.startFraction)
                }
                Triple(current,next,alternate to (alternate.distanceM-originalRemaining))
            }
        }
        if(candidate==null){message("No legal alternate from the mapped route points checked. Current position remains unknown.");return@work}
        val (anchor,blocked,planned)=candidate
        val (alternate,delta)=planned
        mutable.update{it.copy(demoPreviewRoute=alternate,demoPreviewBlockedSegment=blocked.segmentId,demoPreviewDeltaM=delta,
            demoPreviewAnchor=anchor.name,demoBlockedRoad=blocked.name,
            message="WHAT-IF ONLY: blocked ${blocked.name} in both directions and planned a connected alternate from a mapped route point. The phone's current road remains unverified.")}
    }
    fun changeRoute() {
        val token=authority
        work {if(token==authority)journey?.changeRoute()?.let{result->
            synchronized(authorityLock){if(token==authority)mutable.update{it.copy(journey=result,message=result.message)}}
        }}
    }
    fun acceptGuidedDetour() {
        val s=state.value
        if(!s.demo || !s.guidedWaiting || s.guidedPlan==null)return
        mutable.update{it.copy(guidedWaiting=false,guidedDetourChosen=true,
            message="Alternative selected from the offline road graph. The scenario vehicle follows mapped roads.")}
    }
    fun startSensors() {
        val local=binder ?: return message("Sensor worker is not connected yet.")
        if(authority.source==Source.PHONE)return
        demoJob?.cancel();demoJob=null
        val token=beginSession(Source.PHONE);prepareRoadSession(token);local.dispatch{resetEngine(token)}
        runCatching{context.startForegroundService(Intent(context,SensorService::class.java).setAction(SensorService.ACTION_START))}
            .onFailure{if(authority==token)endSession();message("Cannot start recording: ${it.message}")}
    }
    fun stopSensors(){endSession();context.startService(Intent(context,SensorService::class.java).setAction(SensorService.ACTION_STOP))}
    fun confirmForward(){
        val token=authority
        binder?.dispatch {synchronized(authorityLock){
            if(token!=authority || token.source==Source.IDLE || token!=engineOwner)return@synchronized
            engine.confirmForwardMotion();mutable.update{it.copy(nav=engine.snapshot(),message="Forward intent recorded. Keep the phone mounted; drive forward safely to make alignment observable.")}
        }}
    }
    fun remount(){
        val token=authority
        binder?.dispatch {synchronized(authorityLock){
            if(token!=authority || token.source==Source.IDLE || token!=engineOwner)return@synchronized
            if(token.source==Source.SYNTHETIC)demoMountRotated=!demoMountRotated
            engine.onDiscontinuity("user_declared_remount",SystemClock.elapsedRealtimeNanos()/1e9)
            graphGeneration.incrementAndGet()
            mutable.update{it.copy(nav=engine.snapshot(),match=null,journey=it.journey?.copy(position=null))}
            work{if(token==authority){matcher?.reset();journey?.resetTracking()?.let{cleared->mutable.update{it.copy(journey=cleared)}}}}
        }}
    }
    fun exportSession(callback:(File?,String?)->Unit){binder?.exportLastSession(callback) ?: callback(null,"Sensor service is not connected")}
    fun startDemo() {
        val local=binder ?: return message("Sensor worker is not connected yet.")
        if(authority.source==Source.PHONE || state.value.sensor.active || local.service.states.value.active){message("Stop real recording before starting simulated replay.");return}
        demoJob?.cancel()
        val token=beginSession(Source.SYNTHETIC)
        val roadsReady=prepareRoadSession(token)
        demoCutStartedAt=null
        mutable.update{it.copy(demoGnss="NORMAL",demoElapsedS=0.0,demoOutageS=0.0,demoFinished=false,demoBlockedRoad=null,
            demoPreviewRoute=null,demoPreviewBlockedSegment=null,demoPreviewAnchor=null,demoPreviewDeltaM=null,
            guidedWaiting=false,guidedDetourChosen=false,
            message="Preparing the simulated journey.")}
        demoJob=scope.launch {
            try {
                val text=withContext(Dispatchers.IO){context.assets.open("demo/maneuver.csv").bufferedReader().use{it.readText()}}
                val replay=SyntheticReplay(text)
                roadsReady.await()
                val s=state.value
                val route=journey?.state?.route
                val plan=if(s.guidedPrepared && route!=null && s.graph!=null && s.destination!=null)
                    DemoRoutePlan.find(s.graph,route,s.destination,journey!!.alternatives) else null
                mutable.update{it.copy(guidedPlan=plan)}
                val ready=CompletableDeferred<Unit>()
                local.dispatch {
                    synchronized(authorityLock) {if(token==authority){resetEngine(token)
                        mutable.update{it.copy(message="The illustrated journey and native sensor replay are running.")}}}
                    ready.complete(Unit)
                }
                ready.await()
                val replayClock=DemoReplayClock(SystemClock.elapsedRealtimeNanos()/1e9)
                var lastUi=Double.NEGATIVE_INFINITY
                while(isActive && token==authority && !replay.finished) {
                    val now=SystemClock.elapsedRealtimeNanos()/1e9
                    val waiting=state.value.guidedWaiting
                    val elapsed=replayClock.advance(now,waiting)
                    if(waiting){delay(20);continue}
                    if(plan!=null) {
                        if(elapsed>=GuidedTimeline.GNSS_CUT_S && state.value.demoGnss=="NORMAL" && !state.value.guidedDetourChosen)
                            demoGnss("CUT")
                        if(elapsed>=GuidedTimeline.ROADBLOCK_S && !state.value.guidedDetourChosen) {
                            replayClock.pauseAt(GuidedTimeline.ROADBLOCK_S)
                            mutable.update{it.copy(demoElapsedS=GuidedTimeline.ROADBLOCK_S,guidedWaiting=true,
                                demoPreviewRoute=plan.alternate,demoPreviewBlockedSegment=plan.blocked.segmentId,
                                demoPreviewAnchor=plan.anchorRoad.name,demoPreviewDeltaM=plan.deltaM,
                                demoBlockedRoad=plan.blocked.name,
                                message="Road ahead closed. Choose the connected offline alternative.")}
                            continue
                        }
                        if(elapsed>=GuidedTimeline.GNSS_RETURN_S && state.value.demoGnss=="CUT")demoGnss("NORMAL")
                    }
                    if(elapsed-lastUi>=0.2){
                        lastUi=elapsed
                        mutable.update{it.copy(demoElapsedS=elapsed.coerceAtMost(replay.rows.last().t))}
                    }
                    val batch=replay.takeUntil(elapsed)
                    if(batch.isNotEmpty()) {
                        val processed=CompletableDeferred<Unit>()
                        local.dispatch {
                            try {for(row in batch) {
                                if(token!=authority || token!=engineOwner)break
                                // Generated/mock source uses a virtual acquisition+receipt clock.
                                // A presenter pause is not a gap or stale fix in the source recording.
                                val t=replayClock.sourceTimestamp(row.t);val receipt=t;val v=row.values
                                if(row.kind=="G") {
                                    if(state.value.demoGnss!="CUT")engine.onGnss(PhoneGnss(t,receipt,v[0],v[1],v[2],
                                        if(state.value.demoGnss=="DEGRADED")100.0 else v[3],v[5],v[6],v[7],3.0,"explicit_synthetic_replay",true,v[4]))
                                } else {
                                    fun rotated(a:DoubleArray)=if(demoMountRotated)doubleArrayOf(-a[1],a[0],a[2]) else a
                                    val began=System.nanoTime();engine.onImu(PhoneImu(t,receipt,rotated(v.take(3).toDoubleArray()),rotated(v.drop(3).toDoubleArray()),0))
                                    publish(t,(System.nanoTime()-began)/1e6,token)
                                }
                            }} finally {processed.complete(Unit)}
                        }
                        processed.await()
                    }
                    delay(20)
                }
                if(token==authority) {
                    demoCutStartedAt?.let{began->
                        val duration=(SystemClock.elapsedRealtimeNanos()/1e9-began).coerceAtLeast(0.0)
                        mutable.update{it.copy(demoOutageS=it.demoOutageS+duration)}
                        demoCutStartedAt=null
                    }
                    mutable.update{it.copy(demoFinished=true,demoElapsedS=replay.rows.last().t,
                        message="Synthetic replay finished. This is software behaviour, not field accuracy evidence.")}
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){if(token==authority){stopDemo();message("Demo failed: ${e.message}")}}
        }
    }
    fun demoGnss(mode:String){
        require(mode in setOf("NORMAL","DEGRADED","CUT"))
        if(authority.source!=Source.SYNTHETIC || state.value.demoFinished)return
        val now=SystemClock.elapsedRealtimeNanos()/1e9
        if(mode=="CUT" && demoCutStartedAt==null)demoCutStartedAt=now
        if(mode!="CUT")demoCutStartedAt?.let{began->
            mutable.update{it.copy(demoOutageS=it.demoOutageS+(now-began).coerceAtLeast(0.0))}
            demoCutStartedAt=null
        }
        mutable.update{it.copy(demoGnss=mode,message=when(mode){
            "CUT"->"Simulated GNSS input withheld. Marker, if shown, remains the estimator output."
            "DEGRADED"->"Simulated GNSS fixes degraded; inspect the estimator state."
            else->"Simulated GNSS input restored. Wait for the estimator's actual recovery state."
        })}
    }
    fun stopDemo(){
        if(authority.source==Source.SYNTHETIC) {
            demoCutStartedAt?.let{began->
                val now=SystemClock.elapsedRealtimeNanos()/1e9
                mutable.update{it.copy(demoOutageS=it.demoOutageS+(now-began).coerceAtLeast(0.0))}
                demoCutStartedAt=null
            }
            endSession()
        }
    }
    companion object {
        fun geoPoint(graph:RoadGraph,lat:Double,lon:Double)=Point((lon-graph.originLonDeg)*Math.PI/180*6378137*cos(Math.toRadians(graph.originLatDeg)),(lat-graph.originLatDeg)*Math.PI/180*6378137)
    }
}

object NativeGraph {
    @Volatile private var controller:NativeController?=null
    @Synchronized fun get(context:Context)=controller ?: NativeController(context.applicationContext).also{controller=it}
}
