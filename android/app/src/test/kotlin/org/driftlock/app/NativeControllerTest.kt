package org.driftlock.app

import android.app.Application
import android.content.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.driftlock.app.sensors.SensorService
import org.driftlock.app.sensors.SensorSessionState
import org.driftlock.app.storage.SessionLogger
import org.driftlock.app.trips.TripRepository
import org.driftlock.core.nav.NavigationEngine
import org.driftlock.core.routing.*
import org.driftlock.core.sensors.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import android.os.SystemClock
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class NativeControllerTest {
    private val xml="""<osm version="0.6"><bounds minlat="30" minlon="76" maxlat="30.01" maxlon="76.01"/>
        <node id="1" lat="30.001" lon="76.001"/><node id="2" lat="30.001" lon="76.002"/>
        <way id="10"><nd ref="1"/><nd ref="2"/><tag k="highway" v="residential"/><tag k="oneway" v="yes"/></way></osm>""".toByteArray()
    private class LocalContext(base:Context,val root:File):ContextWrapper(base) {
        lateinit var connection:ServiceConnection
        override fun getFilesDir()=root
        override fun bindService(intent:Intent,connection:ServiceConnection,flags:Int):Boolean {this.connection=connection;return true}
        override fun startService(intent:Intent)=ComponentName(this,SensorService::class.java)
        override fun startForegroundService(intent:Intent)=ComponentName(this,SensorService::class.java)
    }
    private fun field(controller:NativeController,name:String):Any? = NativeController::class.java.getDeclaredField(name).apply{isAccessible=true}.get(controller)
    private fun set(controller:NativeController,name:String,value:Any?) {NativeController::class.java.getDeclaredField(name).apply{isAccessible=true}.set(controller,value)}
    @Suppress("UNCHECKED_CAST") private fun mutable(controller:NativeController)=field(controller,"mutable") as MutableStateFlow<NativeState>
    private fun mapBarrier(controller:NativeController)=runBlocking{withContext(field(controller,"mapDispatcher") as CoroutineDispatcher){}}
    private fun workerBarrier(binder:SensorService.LocalBinder) {
        val done=CountDownLatch(1);binder.dispatch{done.countDown()};assertTrue(done.await(5,TimeUnit.SECONDS))
    }
    private fun withController(test:(NativeController,LocalContext)->Unit) {
        val context=LocalContext(RuntimeEnvironment.getApplication(),Files.createTempDirectory("native-controller-test").toFile())
        val controller=NativeController(context)
        try {mapBarrier(controller);test(controller,context)} finally {
            (field(controller,"scope") as CoroutineScope).cancel()
            (field(controller,"mapDispatcher") as ExecutorCoroutineDispatcher).close()
            (field(controller,"repository") as TripRepository).close()
            context.root.deleteRecursively()
        }
    }
    private fun loadFixture(controller:NativeController,context:LocalContext) {
        val graph=OsmImporter.parse(xml,"saved","synthetic controller fixture").graph
        TripPackStore(File(context.filesDir,"trip_packs")).create("saved","Fixture",xml,"synthetic controller fixture",OfflineRouter(graph).alternatives(1,2),2)
        controller.loadPack("saved");mapBarrier(controller)
        assertEquals("saved",controller.state.value.packId)
    }
    @Test fun stoppingClearsNavigationAndCannotRepublishLatePhoneCallbacks()=withController{controller,context->
        loadFixture(controller,context)
        val journey=field(controller,"journey") as OfflineJourney
        journey.observe(1.0,RoadPosition("10:0:f",.5))
        mutable(controller).value=controller.state.value.copy(nav=NavigationEngine().snapshot(),journey=journey.state,sensor=SensorSessionState(active=true))
        controller.stopSensors()
        assertNull(controller.state.value.nav)
        assertNull(controller.state.value.journey?.position)
        (field(controller,"sink") as SensorSink).onImu(PhoneImu(2.0,2.0,doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(0.0,0.0,0.0),0))
        assertNull(controller.state.value.nav)
    }
    @Test fun cancellingDemoBeforeWorkerStartsRestoresTheRealJourney()=withController{controller,context->
        loadFixture(controller,context)
        val original=field(controller,"journey")
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val service=serviceController.get();val binder=service.onBind(null)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        binder.dispatch {entered.countDown();release.await(5,TimeUnit.SECONDS)}
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        try {
            controller.startDemo()
            runBlocking {withTimeout(5000){while(field(controller,"preDemoJourney")==null)delay(10)}}
            controller.stopDemo();mapBarrier(controller)
            assertSame(original,field(controller,"journey"))
            assertNull(field(controller,"preDemoJourney"))
            assertFalse(controller.state.value.demo)
            assertNull(controller.state.value.nav)
        } finally {release.countDown();serviceController.destroy()}
    }
    @Test fun loadingPackRetainsOriginalMapForSavingAnotherJourney()=withController{controller,context->
        loadFixture(controller,context)
        controller.savePack();mapBarrier(controller)
        assertNotEquals("saved",controller.state.value.packId)
        val id=controller.state.value.packId!!
        assertArrayEquals(xml,File(context.filesDir,"trip_packs/$id/roads.osm").readBytes())
    }
    @Test fun terminalOneWayNodeCanBeChosenAsDestination()=withController{controller,context->
        loadFixture(controller,context)
        val graph=controller.state.value.graph!!
        controller.selectPoint(graph.nodes.getValue(1).point);mapBarrier(controller)
        controller.selectPoint(graph.nodes.getValue(2).point);mapBarrier(controller)
        assertEquals(2L,controller.state.value.destination)
        assertEquals(listOf("10:0:f"),controller.state.value.routes.single().legs.map{it.edgeId})
    }
    @Test fun farMapTapCannotSilentlySelectAnUnrelatedRoad()=withController{controller,context->
        loadFixture(controller,context)
        controller.selectPoint(Point(100000.0,100000.0));mapBarrier(controller)
        assertNull(controller.state.value.start)
    }
    @Test fun bundledReplayCorridorFindsOnlyActualOfflineRoutes()=withController{controller,_->
        controller.sampleMap();mapBarrier(controller)
        controller.prepareDemoCorridor();mapBarrier(controller)
        val state=controller.state.value
        assertNotNull(state.start)
        assertNotNull(state.destination)
        assertTrue(state.routes.size>=2)
        assertTrue(state.routes.size<=3)
        assertEquals(state.routes.minBy{it.distanceM}.id,state.selectedRoute)
        state.routes.forEach{route->route.legs.forEach{assertTrue(it.edgeId in state.graph!!.edges)}}
        val route=state.routes.first()
        val router=OfflineRouter(state.graph!!)
        assertTrue(route.legs.dropLast(1).indices.any{i->
            val here=state.graph.edges.getValue(route.legs[i].edgeId)
            val next=state.graph.edges.getValue(route.legs[i+1].edgeId)
            router.reroute(RoadPosition(here.id,0.5),state.destination!!,state.routes,setOf(next.segmentId)).route!=null
        })
    }
    @Test fun tappingGuidedShortestRouteSavesAlternativesAndStartsSimulatedRun()=withController{controller,context->
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val binder=serviceController.get().onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        try {
            controller.sampleMap();mapBarrier(controller)
            controller.prepareDemoCorridor();mapBarrier(controller)
            assertTrue(controller.state.value.guidedPrepared)
            val shortest=controller.state.value.routes.minBy{it.distanceM}
            controller.startGuidedRoute(shortest.id)
            runBlocking{withTimeout(8000){while(!controller.state.value.demo || controller.state.value.guidedPlan==null)delay(20)}}
            val state=controller.state.value
            assertEquals("Demo",state.page)
            assertNotNull(state.packId)
            assertEquals(state.routes.size,state.packs.first{it.id==state.packId}.routeCount)
            assertFalse(state.sensor.active)
            assertNull(state.journey?.position)
        } finally {controller.stopDemo();serviceController.destroy()}
    }
    @Test fun choosingStoryDetourPreservesUnverifiedLivePosition()=withController{controller,_->
        controller.sampleMap();mapBarrier(controller)
        controller.prepareDemoCorridor();mapBarrier(controller)
        controller.savePack();mapBarrier(controller)
        val state=controller.state.value
        val plan=DemoRoutePlan.find(state.graph!!,state.journey!!.route!!,state.destination!!,state.routes)!!
        mutable(controller).value=state.copy(demo=true,guidedPlan=plan,guidedWaiting=true)
        controller.acceptGuidedDetour()
        assertTrue(controller.state.value.guidedDetourChosen)
        assertFalse(controller.state.value.guidedWaiting)
        assertNull(controller.state.value.journey?.position)
    }
    @Test fun openingTheSavedRegionEndsAnExistingSimulatedJourney()=withController{controller,context->
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val binder=serviceController.get().onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        try {
            controller.sampleMap();mapBarrier(controller)
            controller.prepareDemoCorridor();mapBarrier(controller)
            controller.startGuidedRoute(controller.state.value.selectedRoute!!)
            runBlocking{withTimeout(8000){while(!controller.state.value.demo)delay(20)}}
            controller.sampleMap();mapBarrier(controller)
            assertFalse(controller.state.value.demo)
            assertEquals("Prepare",controller.state.value.page)
            assertNull(controller.state.value.nav)
            assertFalse(controller.state.value.guidedPrepared)
        } finally {controller.stopDemo();serviceController.destroy()}
    }
    @Test fun unclearRoadMatchAllowsOnlyAnExplicitWhatIfAlternate()=withController{controller,_->
        controller.sampleMap();mapBarrier(controller)
        controller.prepareDemoCorridor();mapBarrier(controller)
        controller.savePack();mapBarrier(controller)
        mutable(controller).value=controller.state.value.copy(demo=true)
        controller.previewDemoBlockage();mapBarrier(controller)
        val state=controller.state.value
        val blocked=state.demoPreviewBlockedSegment
        assertNotNull(state.demoPreviewRoute)
        assertNotNull(blocked)
        assertTrue(state.demoPreviewDeltaM!!.isFinite())
        assertNull(state.journey?.position)
        assertTrue(state.demoPreviewRoute!!.legs.none{state.graph!!.edges.getValue(it.edgeId).segmentId==blocked})
        assertTrue(state.message.contains("WHAT-IF ONLY"))
    }
    @Test fun offRouteRoadMatchAlsoAllowsExplicitWhatIfWithoutClaimingPosition()=withController{controller,_->
        controller.sampleMap();mapBarrier(controller)
        controller.prepareDemoCorridor();mapBarrier(controller)
        controller.savePack();mapBarrier(controller)
        val journey=field(controller,"journey") as OfflineJourney
        val used=journey.state.route!!.legs.map{it.edgeId}.toSet()
        val offRoute=journey.graph.edges.values.first{it.id !in used}
        journey.observe(1.0,RoadPosition(offRoute.id,0.5))
        mutable(controller).value=controller.state.value.copy(demo=true,journey=journey.state)
        controller.previewDemoBlockage();mapBarrier(controller)
        val state=controller.state.value
        assertNotNull(state.journey?.position)
        assertNotNull(state.demoPreviewRoute)
        assertTrue(state.message.contains("WHAT-IF ONLY"))
    }
    @Test fun queuedDemoBlockCannotPersistAfterExit()=withController{controller,context->
        loadFixture(controller,context)
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val binder=serviceController.get().onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            controller.startDemo()
            runBlocking {withTimeout(5000){while(field(controller,"preDemoJourney")==null)delay(10)}}
            (field(controller,"scope") as CoroutineScope).launch(field(controller,"mapDispatcher") as CoroutineDispatcher){entered.countDown();release.await(5,TimeUnit.SECONDS)}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            controller.block("10:1:2")
            controller.stopDemo()
            release.countDown();mapBarrier(controller)
            assertTrue(TripPackStore(File(context.filesDir,"trip_packs")).load("saved").blockedSegments.isEmpty())
            assertTrue(controller.state.value.journey!!.blocked.isEmpty())
        } finally {release.countDown();serviceController.destroy()}
    }
    @Test fun staleSyntheticPublicationCannotBecomePhoneNavigationOrLog()=withController{controller,context->
        loadFixture(controller,context)
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val service=serviceController.get();val binder=service.onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        val logger=SessionLogger(File(context.filesDir,"test_recordings"),"{\"source\":\"test_only\"}")
        SensorService::class.java.getDeclaredField("session").apply{isAccessible=true}.set(service,logger)
        try {
            controller.startDemo()
            val syntheticToken=field(controller,"authority")
            controller.stopDemo();controller.startSensors();workerBarrier(binder);mapBarrier(controller)
            // Reproduce an already-running synthetic task completing after the source switch.
            set(controller,"engineOwner",syntheticToken)
            NativeController::class.java.declaredMethods.single{it.name=="publish"}.apply{isAccessible=true}.invoke(controller,100.0,.1,syntheticToken)
            workerBarrier(binder);logger.flush()
            assertNull(controller.state.value.nav)
            assertEquals(1,logger.directory.resolve("events.csv").readLines().size)
        } finally {logger.close("test complete");serviceController.destroy()}
    }
    @Test fun phonePublicationDoesNotUndershootTenHzOnFourTickQuantization()=withController{controller,context->
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val service=serviceController.get();val binder=service.onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        val logger=SessionLogger(File(context.filesDir,"test_recordings"),"{\"source\":\"test_only\"}")
        SensorService::class.java.getDeclaredField("session").apply{isAccessible=true}.set(service,logger)
        try {
            controller.startSensors();workerBarrier(binder)
            val sink=field(controller,"sink") as SensorSink
            binder.dispatch {
                repeat(50){i->val t=1.0+i/49.37;sink.onImu(PhoneImu(t,t,doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(0.0,0.0,0.0),0))}
            }
            workerBarrier(binder);workerBarrier(binder);logger.flush()
            val rows=logger.directory.resolve("events.csv").readLines().drop(1)
            assertTrue("Expected at least 11 publishes in this one-second fixture, got ${rows.size}",rows.size>=11)
            val json=rows.first().substringAfter(",navigation,").removeSurrounding("\"").replace("\"\"","\"")
            val event=JSONObject(json)
            assertEquals("engine_on_imu_only",event.getString("tick_scope"))
            assertTrue(event.getDouble("tick_ms")>=0)
            assertEquals((event.getDouble("receipt_t")-event.getDouble("target_t"))*1000,event.getDouble("receipt_target_lag_ms"),1e-7)
        } finally {logger.close("test complete");serviceController.destroy()}
    }
    @Test fun stalledPhoneStreamExpiresOnceAndRecoversOnlyFromNewImu()=withController{controller,context->
        loadFixture(controller,context)
        val serviceController=Robolectric.buildService(SensorService::class.java).create()
        val service=serviceController.get();val binder=service.onBind(null)
        context.connection.onServiceConnected(ComponentName(context,SensorService::class.java),binder)
        val logger=SessionLogger(File(context.filesDir,"test_recordings"),"{\"source\":\"test_only\"}")
        SensorService::class.java.getDeclaredField("session").apply{isAccessible=true}.set(service,logger)
        try {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
            controller.startSensors();workerBarrier(binder);mapBarrier(controller)
            @Suppress("UNCHECKED_CAST") val sensorState=SensorService::class.java.getDeclaredField("state").apply{isAccessible=true}.get(service) as MutableStateFlow<SensorSessionState>
            sensorState.value=sensorState.value.copy(active=true)
            runBlocking{withTimeout(5000){while(!controller.state.value.sensor.active)delay(10)}}
            val sink=field(controller,"sink") as SensorSink
            fun imu(){val t=SystemClock.elapsedRealtimeNanos()/1e9;binder.dispatch{sink.onImu(PhoneImu(t,t,doubleArrayOf(0.0,0.0,9.80665),doubleArrayOf(0.0,0.0,0.0),0))};workerBarrier(binder)}
            imu();assertNotNull(controller.state.value.nav)
            val lastMeasurementT=controller.state.value.nav!!.t
            val journey=field(controller,"journey") as OfflineJourney
            journey.observe(SystemClock.elapsedRealtimeNanos()/1e9,RoadPosition("10:0:f",.5))
            mutable(controller).value=controller.state.value.copy(journey=journey.state)
            // Robolectric injects the monotonic clock; no sensor callback advances the estimator.
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2));runBlocking{delay(400)}
            workerBarrier(binder);mapBarrier(controller)
            assertNull(controller.state.value.nav);assertNull(controller.state.value.journey?.position)
            assertEquals(lastMeasurementT,(field(controller,"engine") as NavigationEngine).snapshot().t)
            logger.flush()
            assertEquals(1,logger.directory.resolve("events.csv").readLines().count{it.contains(",discontinuity,phone_imu_stream_stalled")})
            ShadowSystemClock.advanceBy(Duration.ofSeconds(2));runBlocking{delay(400)}
            workerBarrier(binder);logger.flush()
            assertEquals(1,logger.directory.resolve("events.csv").readLines().count{it.contains(",discontinuity,phone_imu_stream_stalled")})
            imu();assertNotNull(controller.state.value.nav)
            assertNull(controller.state.value.journey?.position)
            assertTrue(controller.state.value.message.contains("resumed"))
        } finally {controller.stopSensors();workerBarrier(binder);workerBarrier(binder);logger.close("test complete");serviceController.destroy()}
    }
}
