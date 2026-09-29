package org.driftlock.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import `in`.driftlock.ui.design.GlassSurface
import `in`.driftlock.ui.design.GlassTokens
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.driftlock.app.storage.SessionExports
import org.driftlock.core.routing.*
import org.driftlock.core.matching.MatchStatus
import kotlin.math.*

@Composable fun NativeApp() {
    val context=LocalContext.current;val controller=remember{NativeGraph.get(context)}
    val state by controller.state.collectAsStateWithLifecycle()
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result[Manifest.permission.ACCESS_FINE_LOCATION]==true)controller.startSensors()
        else controller.message("Precise location permission is needed for GNSS calibration. No recording started.")
    }
    NativeScreen(state,{action->
        when {
            action.startsWith("page:")->controller.page(action.substringAfter(':'))
            action=="sample"->controller.sampleMap()
            action=="demo-corridor"->controller.prepareDemoCorridor()
            action=="save"->controller.savePack()
            action=="start"->permissions.launch(listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
                .plus(if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray())
            action=="stop"->controller.stopSensors()
            action=="forward"->controller.confirmForward()
            action=="remount"->controller.remount()
            action=="demo-start"->controller.startDemo()
            action=="demo-stop"->controller.stopDemo()
            action=="guided-accept"->controller.acceptGuidedDetour()
            action=="preview-block"->controller.previewDemoBlockage()
            action.startsWith("demo-gnss:")->controller.demoGnss(action.substringAfter(':'))
            action=="change"->controller.changeRoute()
            action.startsWith("route:")->controller.chooseRoute(action.substringAfter(':'))
            action.startsWith("guided-route:")->controller.startGuidedRoute(action.substringAfter(':'))
            action.startsWith("load:")->controller.loadPack(action.substringAfter(':'))
            action.startsWith("delete:")->controller.deletePack(action.substringAfter(':'))
            action.startsWith("block:")->controller.block(action.substringAfter(':'))
            action.startsWith("download:")->runCatching{
                val v=action.split(':');controller.downloadRegion(v[1].toDouble(),v[2].toDouble())
            }.onFailure{controller.message("Enter valid latitude and longitude.")}
            action=="export"->controller.exportSession{file,error->
                if(file!=null)context.startActivity(SessionExports.shareIntent(context,file)) else controller.message(error ?: "Export unavailable")
            }
        }
    },controller::selectPoint,recordedTripPreview=`in`.driftlock.ui.BuildConfig.RECORDED_TRIP_PREVIEW)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun NativeScreen(state:NativeState,action:(String)->Unit,tap:(Point)->Unit={},recordedTripPreview:Boolean=false) {
    var blocked by rememberSaveable{mutableStateOf(false)}
    SharedTransitionLayout {
    CompositionLocalProvider(LocalJourneyShared provides this,LocalRecordedTripPreview provides recordedTripPreview) {
    Scaffold(modifier=Modifier.background(GlassTokens.backdrop),containerColor=Color.Transparent,topBar={Row(Modifier.fillMaxWidth().statusBarsPadding().padding(22.dp,12.dp),verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.SpaceBetween) {
        Column {Text("D R I F T L O C K",style=MaterialTheme.typography.titleLarge,color=Color(0xFF163F32))
            Text("N A V I G A T E   F U R T H E R",style=MaterialTheme.typography.labelMedium)}
        GlassSurface(modifier=Modifier.widthIn(max=130.dp),tint=if(state.demo)Color(0xA6DFE9F5) else Color(0xA6D9E9DA),shape=RoundedCornerShape(50)) {
            AnimatedContent(if(recordedTripPreview)"DESIGN PREVIEW" else if(state.page=="Research")"MODEL LAB" else if(state.demo)"SIMULATED" else if(state.sensor.active)"LIVE PHONE" else if(state.packId!=null)"OFFLINE PACK" else if(state.graph!=null)"MAP LOADED" else "SETUP",transitionSpec={fadeIn(tween(240)) togetherWith fadeOut(tween(160))},label="source-label"){label->Text(label,Modifier.padding(horizontal=10.dp,vertical=7.dp),style=MaterialTheme.typography.labelMedium)}
        }
    }},bottomBar={GlassSurface(Modifier.navigationBarsPadding().padding(horizontal=8.dp,vertical=3.dp),shape=RoundedCornerShape(28.dp)){Row(Modifier.fillMaxWidth().padding(3.dp),horizontalArrangement=Arrangement.spacedBy(2.dp)) {
        listOf("Home" to "Home","Routes" to "Prepare","Run" to "Demo","Research" to "Research","More" to "More").forEach{(label,page)->TextButton(onClick={action("page:$page")},modifier=Modifier.weight(1f),contentPadding=PaddingValues(2.dp),
            colors=ButtonDefaults.textButtonColors(containerColor=animateColorAsState(if(state.page==page)Color(0xADBCD8C3) else Color.Transparent,tween(240),label="nav-selection").value)){
            Text(label,style=MaterialTheme.typography.labelMedium,maxLines=1)}}
    }}}) {padding->
        AnimatedContent(state.page,Modifier.fillMaxSize().padding(padding),
            transitionSpec={ (fadeIn(tween(380))+slideInVertically(tween(440)){it/35}) togetherWith
                (fadeOut(tween(240))+slideOutVertically(tween(360)){-it/50}) },label="screen-transition") { page ->
            CompositionLocalProvider(LocalJourneyMotion provides this) {
                NativePage(state.copy(page=page),action,tap,{blocked=true})
            }
        }
    }
    }
    }
    if(blocked) {
        val route=state.journey?.route;val position=state.journey?.position
        val i=route?.legs?.indexOfFirst{it.edgeId==position?.edgeId} ?: -1
        val next=if(i>=0)route?.legs?.getOrNull(i+1)?.let{state.graph?.edges?.get(it.edgeId)} else null
        AlertDialog(onDismissRequest={blocked=false},title={Text("Route unavailable?")},text={Column{
            Text("Use these controls while parked or ask a passenger. No live closure detection is implied.")
            Text(if(next!=null)"Avoid upcoming road: ${next.name}. Both directions of that segment will be blocked for this trip." else if(state.demo)"No usable upcoming route segment is established from the current road state. Preview a closure from a mapped route point; this does not place the phone there." else "No upcoming matched segment is established. A current road match is needed for safe rerouting.")
        }},confirmButton={Column{
            if(next!=null)TextButton(onClick={blocked=false;action("block:${next.segmentId}")}){Text("Avoid this road")}
            if(next==null && state.demo)TextButton(onClick={blocked=false;action("preview-block")}){Text("Preview mapped closure")}
            if(next!=null)TextButton(onClick={blocked=false;action("change")}){Text("Use alternate route")}
        }},dismissButton={TextButton(onClick={blocked=false}){Text("Cancel")}})
    }
}

@Composable private fun NativePage(state:NativeState,action:(String)->Unit,tap:(Point)->Unit,onBlocked:()->Unit) {
    val preview=LocalRecordedTripPreview.current
    val listState=rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().testTag("native-content"),state=listState,contentPadding=PaddingValues(horizontal=18.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if(state.demo && !preview && state.page!="Demo" && state.page!="Research")item{Banner("SIMULATED • controlled sensor replay")}
            if(state.page!="Demo")item{Text(when(state.page){"Home"->"Choose your journey";"Prepare"->"Choose a route";"Research"->"Research model";else->state.page},style=MaterialTheme.typography.headlineLarge)}
            item{AnimatedVisibility(state.busy,enter=fadeIn(tween(200))+expandVertically(),exit=fadeOut(tween(200))+shrinkVertically()){LinearProgressIndicator(Modifier.fillMaxWidth())}}
            if(state.page!="Research" && state.page!="Demo" && state.page!="Prepare" && state.page!="Home")item{Text(state.message,style=MaterialTheme.typography.bodyMedium)}
            when(state.page) {
                "Home"->{
                    item{HomeHero(state,action)}
                    item{Text(if(preview)"Recorded-trip layout preview · generated sample data." else "Guided scenario · simulated motion on saved OpenStreetMap roads.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    item{GlassOutlinedButton(onClick={action("page:Download")},modifier=Modifier.fillMaxWidth()){Text("Download another small region")}}
                    item{GlassOutlinedButton(onClick={action("page:Calibration")},modifier=Modifier.fillMaxWidth()){Text("Connect phone sensors")}}
                }
                "Download"->{
                    item{DownloadForm(state,action)}
                    item{CardBody("Before downloading","Enter the centre of your journey region. This request sends only the selected map rectangle to OpenStreetMap. Network is needed here; saved routes work locally. Map size is unknown until fetched, with a 20 MB cap.")}
                }
                "Prepare"->{
                    item{Text("CHANDIGARH  ·  SAVED OFFLINE",style=MaterialTheme.typography.labelLarge,color=Color(0xFF527566))}
                    if(!state.guidedPrepared)item{GlassOutlinedButton(onClick={action("demo-corridor")},enabled=state.graph!=null&&!state.busy,modifier=Modifier.fillMaxWidth()){
                        Text("Preview guided journey  →")}}
                    item{RoadMap(state,tap,modifier=Modifier.journeyBounds())}
                    if(!state.guidedPrepared)item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Text("Local roads  ·  ${formatBytes(state.graphBytes)}",style=MaterialTheme.typography.labelMedium)
                        Text("${state.routes.size} route${if(state.routes.size==1)"" else "s"}",style=MaterialTheme.typography.labelMedium)
                    }}
                    if(!state.guidedPrepared)item{Text(if(state.graph==null)"Open the local map from Home." else if(state.start==null)"Tap a road to set your start, or preview the guided journey." else if(state.destination==null)"Tap a road for your destination." else "Choose a route and save all alternatives.",style=MaterialTheme.typography.bodyMedium)}
                    if(state.message.startsWith("Tap a downloaded") || state.message.startsWith("No connected"))item{
                        Text(state.message,style=MaterialTheme.typography.bodySmall,color=Color(0xFF9A552B))}
                    if(state.routes.isNotEmpty() && !state.guidedPrepared)item{Text("CHOOSE YOUR ROUTE",style=MaterialTheme.typography.labelMedium)}
                    items(state.routes.sortedBy{it.distanceM}.size){i->
                        val route=state.routes.sortedBy{it.distanceM}[i]
                        RouteChoice(route,i,state.selectedRoute==route.id,state.guidedPrepared && i==0){
                            action(if(state.guidedPrepared && i==0)"guided-route:${route.id}" else "route:${route.id}")
                        }
                    }
                    if(!state.guidedPrepared)item{GlassButton(onClick={action("save")},enabled=state.routes.isNotEmpty()&&!state.busy,modifier=Modifier.fillMaxWidth().height(58.dp)){Text("SAVE ALL ROUTES OFFLINE")}}
                    if(state.guidedPrepared)item{Text("Two connected local routes · ${formatBytes(state.graphBytes)} saved map",style=MaterialTheme.typography.labelMedium)}
                    item{Text("Map coverage is limited to this saved area. © OpenStreetMap contributors.",style=MaterialTheme.typography.labelSmall)}
                }
                "Calibration"->{
                    item{CardBody("Set up while parked","Secure the phone firmly. Keep Location enabled. Start recording, remain still briefly for gravity and bias estimation, then drive forward normally when safe. Yaw needs observable movement; a stationary phone cannot establish it.")}
                    item{CaptureControls(state,action)}
                    item{GlassButton(onClick={action("forward")},enabled=state.sensor.active,modifier=Modifier.fillMaxWidth()){Text("I will drive forward for calibration")}}
                    item{CardBody("Alignment: ${state.nav?.alignment ?: "UNINITIALIZED"}","Forward confirmation establishes intended direction, not a completed calibration. Sensor and GNSS evidence must still pass the alignment gates.")}
                    item{GlassOutlinedButton(onClick={action("remount")}){Text("Phone moved / recalibrate")}}
                    item{GlassButton(onClick={action("page:Driver")},modifier=Modifier.fillMaxWidth()){Text("Open driver view")}}
                    item{GlassOutlinedButton(onClick={action("page:Demo")},enabled=state.packId!=null,modifier=Modifier.fillMaxWidth()){Text("Run guided simulated demo")}}
                }
                "Driver"->{
                    item{Banner("GNSS ${state.nav?.gnss ?: "UNKNOWN"} • ${state.nav?.horizon ?: "UNRELIABLE"}")}
                    item{RoadMap(state)}
                    item{Text(guidance(state),style=MaterialTheme.typography.headlineSmall)}
                    item{Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                        Metric("Estimated speed",state.nav?.let{"%.1f km/h".format(it.speedMps*3.6)} ?: "Waiting")
                        Metric("Position uncertainty",state.nav?.positionSigmaM?.let{"σ %.1f m".format(it)} ?: "Unknown")
                    }}
                    item{Text("${state.journey?.status ?: "No saved journey"} • road ${state.match?.status ?: "not matched"}")}
                    item{if(state.nav?.position==null)Text("Waiting for usable phone GNSS to initialize position. No simulated location is substituted.")}
                    item{GlassButton(onClick=onBlocked,enabled=state.journey?.position!=null,modifier=Modifier.fillMaxWidth()){Text("Route blocked / change route")}}
                    item{GlassOutlinedButton(onClick={action("page:Confidence")}){Text("Understand reliability")}}
                    item{Text("ML ${state.sensor.model.status}. Physics remains active where initialized; blackout accuracy is not yet field validated.")}
                }
                "Confidence"->{
                    item{CardBody("Confidence horizon: ${state.nav?.horizon ?: "UNRELIABLE"}","This category combines the estimator's available evidence. It is not a percentage or a guaranteed remaining distance.")}
                    item{Text("HIGH: strong current evidence. MODERATE: some uncertainty. LOW: limited evidence. UNRELIABLE: do not depend on the displayed position.")}
                    item{Text("Position σ is the filter's estimated spread in metres. ML speed σ would be a separate spread in m/s. Road probability ranks map hypotheses; it is not positioning accuracy.")}
                    item{Text("If uncertain, slow down or stop where safe and use road signs. Do not operate these controls while driving.")}
                }
                "Judge"->{
                    item{JudgeDetails(state)}
                }
                "Research"->{item{ResearchPanel(state)}}
                "Packs"->{
                    if(state.packs.isEmpty())item{Text("No offline trip packs saved yet. Prepare a route from Home.")}
                    items(state.packs.size){i->val pack=state.packs[i];GlassSurface(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){
                        Text(pack.name,style=MaterialTheme.typography.titleMedium);Text("${pack.routeCount} routes • ${formatBytes(pack.bytes)}")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){GlassButton(onClick={action("load:${pack.id}")}){Text("Load")};GlassOutlinedButton(onClick={action("delete:${pack.id}")}){Text("Delete")}}
                    }}}
                }
                "Logs"->{
                    item{CaptureControls(state,action)}
                    item{CardBody("Local session export","Stop recording first. The archive includes hardware timestamps, raw IMU, GNSS, model and navigation events. It stays on your phone until you choose where to share it. GNSS coordinates can reveal your travel history.")}
                    item{GlassButton(onClick={action("export")},enabled=!state.sensor.active,modifier=Modifier.fillMaxWidth()){Text("Export last completed session")}}
                }
                "Settings"->{
                    item{CardBody("Runtime contract","Phone accelerometer + gyroscope. Gravity included. Hardware clocks since boot. Six-channel model windows at 10 Hz. No CAN, wheel-speed, cloud inference or hidden reference input.")}
                    item{CardBody("Engineering settings","CPU ONNX runtime; no production bundle approved. Road feedback to the estimator is disabled pending ablation evidence. Sensor request 50 Hz, model target 10 Hz; actual rates must be measured on this phone.")}
                    item{Text("App data is local. Android backup is disabled. Remove trip packs from Packs. Stop sensor recording explicitly when finished.")}
                }
                "Demo"->{item{GuidedDemoPanel(state,action,onBlocked)}}
                else->{
                    items(listOf("Prepare","Calibration","Driver","Judge","Packs","Confidence","Demo","Research","Logs","Settings").size){i->val page=listOf("Prepare","Calibration","Driver","Judge","Packs","Confidence","Demo","Research","Logs","Settings")[i]
                        GlassOutlinedButton(onClick={action("page:$page")},modifier=Modifier.fillMaxWidth()){Text(page)}}
                }
            }
            item{Spacer(Modifier.height(12.dp))}
        }
}

@Composable private fun HomeHero(state:NativeState,action:(String)->Unit) {
    val preview=LocalRecordedTripPreview.current
    GlassSurface(modifier=Modifier.journeyBounds(),shape=RoundedCornerShape(28.dp),tint=Color(0xDA153D31),contentColor=Color.White,elevation=2.dp) {
        Box(Modifier.fillMaxWidth().height(305.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val route=Path().apply {
                    moveTo(-size.width*.12f,size.height*.76f)
                    cubicTo(size.width*.18f,size.height*.58f,size.width*.15f,size.height*.42f,size.width*.48f,size.height*.48f)
                    cubicTo(size.width*.8f,size.height*.54f,size.width*.68f,size.height*.13f,size.width*1.1f,size.height*.13f)
                }
                drawPath(route,Color(0xFF9BD6AB).copy(alpha=.16f),style=Stroke(width=32f,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                drawPath(route,Color(0xFFB2E5BA).copy(alpha=.42f),style=Stroke(width=3f,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                drawCircle(Color(0xFFB2E5BA).copy(alpha=.5f),14f,Offset(size.width*.48f,size.height*.48f))
            }
            Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("OFFLINE REGION",style=MaterialTheme.typography.labelLarge,color=Color(0xFFB5D8BD))
                Text("Chandigarh",style=MaterialTheme.typography.headlineLarge,color=Color.White)
                Text(if(preview)"Choose a route, then replay your recorded journey." else "Choose a route, then explore a guided drive.",style=MaterialTheme.typography.bodyLarge,color=Color(0xFFE2EEE6))
                Spacer(Modifier.weight(1f))
                GlassButton(onClick={action("sample")},enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(56.dp),
                    colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color(0xFF153D31))) {
                    Text("Open saved Chandigarh region")
                }
            }
        }
    }
}

@Composable private fun RouteChoice(route:Route,index:Int,selected:Boolean,guidedStart:Boolean=false,onClick:()->Unit) {
    val ink=when(index){0->Color(0xFF148650);1->Color(0xFF3574BE);else->Color(0xFFBD7335)}
    val tint by animateColorAsState(if(selected)Color(0xB3DCECDD) else Color(0xBFFDFDF8),tween(280),label="route-glass")
    val edgeColor by animateColorAsState(if(selected)Color(0xFF17633F) else GlassTokens.edge,tween(280),label="route-edge")
    GlassSurface(Modifier.fillMaxWidth().clickable(onClick=onClick).animateContentSize(tween(320)),tint=tint,borderColor=edgeColor) {
        Row(Modifier.fillMaxWidth().padding(17.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Canvas(Modifier.size(52.dp,42.dp)) {
                val p=Path().apply{moveTo(2f,size.height-5);cubicTo(size.width*.25f,size.height*.8f,size.width*.2f,size.height*.45f,size.width*.5f,size.height*.55f)
                    cubicTo(size.width*.8f,size.height*.6f,size.width*.65f,7f,size.width-3,5f)}
                drawPath(p,ink,style=Stroke(width=6f,cap=androidx.compose.ui.graphics.StrokeCap.Round))
                drawCircle(ink,4f,Offset(2f,size.height-5));drawCircle(ink,4f,Offset(size.width-3,5f))
            }
            Column(Modifier.weight(1f)) {
                Text(if(index==0)"SHORTEST · ${"%.2f".format(route.distanceM/1000)} km" else "ROUTE ${index+1} · ${"%.2f".format(route.distanceM/1000)} km",style=MaterialTheme.typography.titleMedium)
                Text(if(guidedStart)"START DRIVE  →" else route.estimatedSeconds?.let{"Free-flow ~${"%.0f".format(it/60)} min"} ?: "Connected local route",
                    style=MaterialTheme.typography.bodyMedium,color=if(guidedStart)Color(0xFF087A53) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("→",style=MaterialTheme.typography.headlineSmall,color=ink)
        }
    }
}
@Composable fun CardBody(title:String,body:String){GlassSurface(Modifier.fillMaxWidth()){Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Text(body)}}}
@Composable private fun Banner(text:String){GlassSurface(tint=Color(0xAADBE9DC),shape=RoundedCornerShape(12.dp)){Text(text,Modifier.fillMaxWidth().padding(14.dp),style=MaterialTheme.typography.labelLarge)}}
@Composable private fun Metric(label:String,value:String){Column{Text(label,style=MaterialTheme.typography.labelSmall);Text(value,style=MaterialTheme.typography.titleLarge)}}
@Composable private fun CaptureControls(state:NativeState,action:(String)->Unit){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
    Text(state.sensor.status);Text("IMU ${state.sensor.sensorRows} • GNSS ${state.sensor.gnssRows} • dropped ${state.sensor.droppedEvents}")
    GlassButton(onClick={action(if(state.sensor.active)"stop" else "start")},modifier=Modifier.fillMaxWidth()){Text(if(state.sensor.active)"Stop recording" else "Start phone sensors")}
}}
@Composable private fun DownloadForm(state:NativeState,action:(String)->Unit){
    var lat by rememberSaveable{mutableStateOf(state.sensor.lastGnss?.latitudeDeg?.toString() ?: "")}
    var lon by rememberSaveable{mutableStateOf(state.sensor.lastGnss?.longitudeDeg?.toString() ?: "")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedTextField(lat,{lat=it},label={Text("Centre latitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(lon,{lon=it},label={Text("Centre longitude")},singleLine=true,modifier=Modifier.fillMaxWidth())
        GlassButton(onClick={action("download:$lat:$lon")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("Download selected region")}
    }
}
@Composable private fun JudgeDetails(state:NativeState){
    val nav=state.nav;val model=state.sensor.model
    val pairs=listOf("Source" to if(state.demo)"SIMULATED" else if(state.sensor.active)"Real phone callbacks" else "Phone sensors stopped",
        "GNSS" to (nav?.gnss?.name ?: "No active session"),"Alignment" to (nav?.alignment?.name ?: "UNINITIALIZED"),"Confidence horizon" to (nav?.horizon?.name ?: "UNRELIABLE"),
        "ShadowDR" to "${nav?.calibration ?: "UNCALIBRATED"} • completed ${nav?.shadowCompleted ?: 0}",
        "Trusted fix age" to (nav?.timeSinceFixS?.let{"%.2f s".format(it)} ?: "unknown"),
        "Heading / sigma" to (nav?.courseDeg?.let{"%.1f° / %.1f°".format(it,nav.headingSigmaDeg)} ?: "unavailable"),
        "Magnetometer" to (nav?.magStatus?.name ?: "unavailable"),"ML status" to "${model.status}: ${model.reason ?: ""}",
        "Model invocations" to model.invocations.toString(),"ML eligible / rejected" to "${model.eligible} / ${model.rejected}",
        "Filter accepted / rejected ML" to "${nav?.acceptedSpeeds ?: 0} / ${nav?.rejectedSpeeds ?: 0}",
        "Model SHA256" to (model.modelSha256 ?: "No approved model"),"Last inference" to (model.lastLatencyMs?.let{"%.3f ms".format(it)} ?: "unmeasured"),
        "Observed publish rate" to (state.navigationHz?.let{"%.1f Hz".format(it)} ?: "unmeasured"),
        "Latest engine IMU call" to (state.navTickMs?.let{"%.3f ms".format(it)} ?: "unmeasured"),
        "Road matching" to (state.match?.status?.name ?: "unavailable"),"Current route" to (state.journey?.route?.id ?: "none"),
        "Blocked physical segments" to (state.journey?.blocked?.size ?: 0).toString(),
        "Offline graph" to (state.graph?.let{"${it.nodes.size} nodes / ${it.edges.size} directed edges"} ?: "not loaded"))
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
        pairs.forEach{(k,v)->Text(k,style=MaterialTheme.typography.labelSmall);Text(v)}
        state.match?.hypotheses?.take(4)?.forEach{Text("Road ${it.position.edgeId} • posterior ${"%.3f".format(it.posterior)}")}
        Text("Hypothesis posterior is not positioning accuracy. Tick time is a single observed tick, not a phone performance benchmark.")
        nav?.audit?.takeLast(5)?.forEach{Text("${it.kind}: ${it.reason}")}
    }
}

@Composable internal fun RoadMap(state:NativeState,tap:((Point)->Unit)?=null,guided:Boolean=false,modifier:Modifier=Modifier){
    val graph=state.graph
    if(graph==null){CardBody("Offline map not loaded","Choose a region or load a saved trip pack.");return}
    val coverage=graph.coverage
    val primary=state.routes.firstOrNull{it.id==state.selectedRoute} ?: state.journey?.route
    val plan=if(guided)state.guidedPlan else null
    val pose=if(plan!=null && primary!=null)runCatching {
        val route=if(state.guidedDetourChosen)plan.alternate else primary
        val distance=if(state.guidedDetourChosen)plan.alternate.distanceM*GuidedTimeline.detourProgress(state.demoElapsedS)
            else plan.anchorDistanceM*GuidedTimeline.primaryProgress(state.demoElapsedS)
        DemoRoutePlan.pose(graph,route,distance)
    }.getOrNull() else null
    val carEast = animateFloatAsState(pose?.point?.eastM?.toFloat() ?: 0f,tween(190,easing=LinearEasing),label="scene-east")
    val carNorth = animateFloatAsState(pose?.point?.northM?.toFloat() ?: 0f,tween(190,easing=LinearEasing),label="scene-north")
    fun scenePoint()=pose?.let{Point(carEast.value.toDouble(),carNorth.value.toDouble())}
    val sceneHeading=remember{Animatable(pose?.headingRad?.toFloat() ?: 0f)}
    LaunchedEffect(pose?.headingRad){pose?.let{sceneHeading.animateTo(nearestSceneHeading(sceneHeading.value.toDouble(),it.headingRad).toFloat(),tween(220))}}
    val routePoints=remember(graph,state.routes){state.routes.flatMap{route->route.legs.flatMap{leg->graph.edges[leg.edgeId]?.let{routeLegGeometry(it,leg)}.orEmpty()}}}
    val bounds=remember(graph,routePoints) {
        val points=if(routePoints.isNotEmpty())routePoints else graph.nodes.values.map{it.point}
        val minE=points.minOf{it.eastM};val maxE=points.maxOf{it.eastM}
        val minN=points.minOf{it.northM};val maxN=points.maxOf{it.northM}
        Triple(Point((minE+maxE)/2,(minN+maxN)/2),(maxE-minE)+(maxN-minN)*.14+120.0,(maxN-minN)*.78+120.0)
    }
    val centre=bounds.first;val spanW=bounds.second;val spanH=bounds.third
    var mapZoom by remember(graph,guided,state.routes,state.selectedRoute){mutableFloatStateOf(if(guided)1.8f else if(routePoints.isEmpty())1.35f else 1f)}
    var pan by remember(graph,guided,state.routes,state.selectedRoute){mutableStateOf(Offset.Zero)}
    var follow by remember(graph,guided,state.routes,state.selectedRoute){mutableStateOf(guided)}
    var manualFocus by remember(graph,guided,state.routes,state.selectedRoute){mutableStateOf<Point?>(null)}
    var viewport by remember{mutableStateOf(IntSize.Zero)}
    val animatedZoom=animateFloatAsState(mapZoom,tween(320,easing=FastOutSlowInEasing),label="map-zoom")
    fun fitScale(w:Float,h:Float)=min((w-30)/spanW,(h-30)/spanH).coerceAtLeast(.001)
    val logicalFocus=if(follow)pose?.point ?: centre else manualFocus ?: centre
    val targetFocus=if(viewport.width>0 && viewport.height>0)MapCamera(logicalFocus,
        fitScale(viewport.width.toFloat(),viewport.height.toFloat())*mapZoom,pan.x.toDouble(),pan.y.toDouble())
        .unproject(viewport.width/2.0,viewport.height/2.0,viewport.width.toDouble(),viewport.height.toDouble()) else logicalFocus
    val focusEast=animateFloatAsState(targetFocus.eastM.toFloat(),tween(190,easing=LinearEasing),label="camera-east")
    val focusNorth=animateFloatAsState(targetFocus.northM.toFloat(),tween(190,easing=LinearEasing),label="camera-north")
    fun camera(w:Float,h:Float)=MapCamera(Point(focusEast.value.toDouble(),focusNorth.value.toDouble()),fitScale(w,h)*animatedZoom.value)
    val currentCamera by rememberUpdatedState<(Float,Float)->MapCamera>({w,h->camera(w,h)})
    val currentTap by rememberUpdatedState(tap)
    val currentTransform by rememberUpdatedState<(Offset,Float)->Unit>({drag,pinch->
        if(follow){manualFocus=Point(focusEast.value.toDouble(),focusNorth.value.toDouble());pan=Offset.Zero;follow=false}
        mapZoom=(mapZoom*pinch).coerceIn(.8f,6f)
        pan+=drag
    })
    val requestedTiles by remember{derivedStateOf{MapTileKey.visible(currentCamera(viewport.width.toFloat(),viewport.height.toFloat()),viewport.width.toDouble(),viewport.height.toDouble())}}
    val raster=remember(graph,state.mapScene){MapRasterWorker(graph,state.mapScene)}
    var geography by remember(graph,state.mapScene){mutableStateOf<List<Pair<MapTileKey,androidx.compose.ui.graphics.ImageBitmap>>>(emptyList())}
    DisposableEffect(raster){onDispose{raster.close()}}
    DisposableEffect(raster,requestedTiles) {
        raster.request(requestedTiles){geography=it}
        onDispose{}
    }
    val routeGeometry=remember(graph,state.routes,state.journey?.route,state.demoPreviewRoute) {
        (state.routes+listOfNotNull(state.journey?.route,state.demoPreviewRoute)).associateWith{r->
            r.legs.flatMap{leg->graph.edges[leg.edgeId]?.let{routeLegGeometry(it,leg).zipWithNext()}.orEmpty()}
        }
    }
    val blockedEdges=remember(graph,state.journey?.blocked,state.demoPreviewBlockedSegment) {
        val segments=state.journey?.blocked.orEmpty()+listOfNotNull(state.demoPreviewBlockedSegment)
        graph.edges.values.filter{it.segmentId in segments}.distinctBy{it.segmentId}
    }
    Box(modifier.fillMaxWidth().animateContentSize(tween(450)).height(if(guided)530.dp else if(state.guidedPrepared)260.dp else if(tap!=null)450.dp else 370.dp)
        .clip(RoundedCornerShape(28.dp)).background(Color(0xFFE5ECE5)).onSizeChanged{viewport=it}) {
        Canvas(Modifier.fillMaxSize().testTag("offline-road-map")
            .semantics{contentDescription="Offline OpenStreetMap roads and mapped footprints. Perspective styling is illustrative. Tap to select roads within the downloaded area."}
            .pointerInput(graph,guided,tap!=null){if(tap!=null)detectTapGestures{p->
                currentTap?.invoke(currentCamera(size.width.toFloat(),size.height.toFloat()).unproject(p.x.toDouble(),p.y.toDouble(),size.width.toDouble(),size.height.toDouble()))
            }}
            .pointerInput(graph,guided){detectTransformGestures{_,drag,pinch,_->
                currentTransform(drag,pinch)
            }}) {
            val view=camera(size.width,size.height)
            val s=view.pixelsPerMeter
            fun project(p:Point):Offset {
                val xy=view.project(p,size.width.toDouble(),size.height.toDouble())
                return Offset(xy.first.toFloat(),xy.second.toFloat())
            }
            fun visible(points:List<Point>):Boolean {
                return view.intersects(points,size.width.toDouble(),size.height.toDouble())
            }
            fun line(edge:RoadEdge,color:Color,width:Float){if(visible(edge.geometry))edge.geometry.zipWithNext().forEach{
                drawLine(color,project(it.first),project(it.second),width,cap=androidx.compose.ui.graphics.StrokeCap.Round)
            }}
            fun routeLine(edge:RoadEdge,leg:RouteLeg,color:Color,width:Float){val points=routeLegGeometry(edge,leg);if(visible(points))points.zipWithNext().forEach{
                drawLine(color,project(it.first),project(it.second),width,cap=androidx.compose.ui.graphics.StrokeCap.Round)
            }}
            geography.forEach{(key,image)->
                val uv=MapTileKey.plane(view.focus)
                val ratio=(view.pixelsPerMeter/key.scale).toFloat()
                val x=(size.width/2-uv.first*view.pixelsPerMeter+view.panX+key.x*MapTileKey.SIZE*ratio).toFloat()
                val y=(size.height/2-uv.second*view.pixelsPerMeter+view.panY+key.y*MapTileKey.SIZE*ratio).toFloat()
                withTransform({translate(x,y);scale(ratio,ratio,pivot=Offset.Zero)}) {drawImage(image,filterQuality=androidx.compose.ui.graphics.FilterQuality.Low)}
            }
            fun paintRoute(route:Route,color:Color,width:Float) {
                routeGeometry[route]?.forEach{(a,b)->drawLine(color,project(a),project(b),width,cap=androidx.compose.ui.graphics.StrokeCap.Round)}
            }
            val ordered=state.routes.sortedBy{it.distanceM}
            ordered.forEachIndexed{index,r->if(r.id!=state.selectedRoute)paintRoute(r,if(index==1)Color(0xFF4B82C0) else Color(0xFFCA8245),7f)}
            val route=state.demoPreviewRoute ?: state.journey?.route ?: state.routes.firstOrNull{it.id==state.selectedRoute}
            route?.let{paintRoute(it,Color.White,13f);paintRoute(it,if(state.demoPreviewRoute!=null)Color(0xFFDB813B) else Color(0xFF00A66A),8f)}
            blockedEdges.forEach{line(it,Color(0xFFD25443),8f)}
            state.start?.let{graph.nodes[it]?.let{n->val p=project(n.point);drawCircle(Color.White,10f,p);drawCircle(Color(0xFF164D38),7f,p)}}
            state.destination?.let{graph.nodes[it]?.let{n->val p=project(n.point);drawCircle(Color.White,11f,p);drawCircle(Color(0xFFD25443),8f,p)}}
            state.nav?.position?.let{position->
                val p=NativeController.geoPoint(graph,position.latitudeDeg,position.longitudeDeg)
                if(coverage.contains(p)){
                    val centre=project(p);val sigma=state.nav.positionSigmaM
                    if(sigma!=null)drawCircle(Color(0x993B65AC),(sigma*s).toFloat().coerceIn(2f,size.maxDimension*2),centre,style=Stroke(2f))
                    val heading=state.nav.headingRad
                    if(heading==null){drawCircle(Color.White,13f,centre);drawCircle(Color(0xFF183A80),9f,centre)} else {
                        val a=-heading;fun point(angle:Double,r:Float)=centre+Offset((cos(angle)*r).toFloat(),(sin(angle)*r).toFloat())
                        val arrow=Path().apply{val tip=point(a,17f);moveTo(tip.x,tip.y);val left=point(a+2.5,12f);lineTo(left.x,left.y);val right=point(a-2.5,12f);lineTo(right.x,right.y);close()}
                        drawPath(arrow,Color.White,style=Stroke(5f));drawPath(arrow,Color(0xFF183A80))
                    }
                }
            }
            val carPoint=scenePoint()
            if(carPoint!=null && pose!=null) {
                val p=project(carPoint)
                drawCircle(Color(0x4413B377),31f,p)
                drawCircle(Color.White,20f,p)
                drawCircle(Color(0xFF126B4A),15f,p)
                val angle=-sceneHeading.value.toDouble()
                val tip=p+Offset((cos(angle)*12).toFloat(),(sin(angle)*12).toFloat())
                val left=p+Offset((cos(angle+2.45)*9).toFloat(),(sin(angle+2.45)*9).toFloat())
                val right=p+Offset((cos(angle-2.45)*9).toFloat(),(sin(angle-2.45)*9).toFloat())
                drawPath(Path().apply{moveTo(tip.x,tip.y);lineTo(left.x,left.y);lineTo(right.x,right.y);close()},Color.White)
            }
        }
        GlassSurface(Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max=265.dp),tint=Color(0xBFFFFFF9),shape=RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(if(guided && LocalRecordedTripPreview.current)"RECORDED TRIP  ·  Chandigarh" else if(guided)"SIMULATED DRIVE  ·  mapped roads" else if(state.demoPreviewRoute!=null)"WHAT-IF ALTERNATE  ·  mapped roads" else "LOCAL MAP  ·  ${graph.nodes.size} road nodes",style=MaterialTheme.typography.labelMedium)
                if(guided && LocalRecordedTripPreview.current)Text("Layout preview · generated sample data.",style=MaterialTheme.typography.labelSmall,color=Color(0xFF65756B))
            }
        }
        GlassSurface(Modifier.align(Alignment.CenterEnd).padding(12.dp).width(46.dp),tint=Color(0xD9FFFEFB),shape=RoundedCornerShape(18.dp),elevation=3.dp) {
            Column {
                IconButton(onClick={mapZoom=(mapZoom*1.35f).coerceAtMost(6f)},modifier=Modifier.size(46.dp).semantics{contentDescription="Zoom in"}){Text("＋")}
                IconButton(onClick={mapZoom=(mapZoom/1.35f).coerceAtLeast(.8f)},modifier=Modifier.size(46.dp).semantics{contentDescription="Zoom out"}){Text("－")}
                if(guided)IconButton(onClick={follow=true;manualFocus=null;pan=Offset.Zero},modifier=Modifier.size(46.dp).semantics{contentDescription="Follow scene vehicle"}){Text("◎")}
            }
        }
        GlassSurface(Modifier.align(if(guided)Alignment.TopStart else Alignment.BottomStart)
            .padding(start=12.dp,top=if(guided && LocalRecordedTripPreview.current)80.dp else if(guided)58.dp else 0.dp,bottom=if(guided)0.dp else 12.dp).widthIn(max=275.dp),
            tint=Color(0xBFFFFFF9),shape=RoundedCornerShape(16.dp)) {
            Text("© OpenStreetMap contributors  ·  offline",Modifier.padding(10.dp),style=MaterialTheme.typography.labelMedium)
        }
    }
    if(!guided && !state.guidedPrepared)Text("Mapped OSM roads and footprints · illustrative perspective",style=MaterialTheme.typography.labelSmall)
}
private fun formatBytes(bytes:Long)="%.2f MB".format(bytes/1_000_000.0)
private fun guidance(state:NativeState):String {
    val current=state.journey?.position ?: return "Waiting for a clear road match"
    val route=state.journey.route ?: return "No downloaded route available"
    val graph=state.graph ?: return "Load an offline map"
    val index=route.legs.indexOfFirst{it.edgeId==current.edgeId}
    if(index<0)return "Checking route deviation…"
    val edge=graph.edges.getValue(current.edgeId);val next=route.legs.getOrNull(index+1)?.let{graph.edges[it.edgeId]}
    val metres=((1-current.fraction)*edge.lengthM).roundToInt()
    if(next==null)return "Destination ahead • $metres m on this segment"
    val a=edge.geometry.last()-edge.geometry.first();val b=next.geometry.last()-next.geometry.first()
    val angle=atan2(a.first*b.second-a.second*b.first,a.first*b.first+a.second*b.second)
    val instruction=if(abs(angle)<.35)"Continue" else if(angle>0)"Turn left" else "Turn right"
    return "$instruction in $metres m • ${next.name}"
}
private operator fun Point.minus(other:Point)=Pair(eastM-other.eastM,northM-other.northM)
