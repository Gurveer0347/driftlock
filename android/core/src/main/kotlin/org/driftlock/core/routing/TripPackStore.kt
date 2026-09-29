package org.driftlock.core.routing

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.abs

data class TripPack(val id: String,val name: String,val graph: RoadGraph,val routes: List<Route>,val destination: Long,
    val blockedSegments: Set<String>,val byteSize: Long,val warnings: List<String>)
data class StoredTripPack(val pack: TripPack,val osm: ByteArray)
class TripPackStore(private val root: File) {
    private fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid trip pack ID" }
        val dir=File(root,id).canonicalFile
        require(dir.parentFile==root.canonicalFile) { "Trip pack escapes storage directory" }
        return dir
    }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun safeFile(dir: File,name: String): File {
        val f=File(dir,name).canonicalFile;require(f.parentFile==dir.canonicalFile);return f
    }
    private fun readProperties(dir: File): Properties {
        val file=safeFile(dir,"trip.properties");require(file.isFile && file.length()<=2_000_000)
        return Properties().apply{file.reader(Charsets.UTF_8).use{load(it)}}
    }
    private fun writeProperties(dir: File,p: Properties) {
        val temporary=safeFile(dir,"trip.properties.new")
        temporary.writer(Charsets.UTF_8).use{p.store(it,"DRIFTLOCK offline trip pack")}
        Files.move(temporary.toPath(),safeFile(dir,"trip.properties").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    }
    private fun validateRoute(graph: RoadGraph,route: Route,destination: Long): Route {
        require(route.id.isNotBlank() && route.legs.size<=100_000)
        var previous: String?=null;var distance=0.0;var time=0.0;var allSpeeds=true
        for((index,leg) in route.legs.withIndex()) {
            val edge=graph.edges[leg.edgeId] ?: throw IllegalArgumentException("Route edge missing from graph")
            require(leg.startFraction.isFinite() && leg.startFraction in 0.0..1.0 && leg.endFraction==1.0)
            require(index==0 || leg.startFraction==0.0)
            require(graph.allows(previous,edge)) { "Route contains disconnected or forbidden turn" }
            val metres=edge.lengthM*(1-leg.startFraction);distance+=metres
            if(edge.speedLimitMps==null) allSpeeds=false else time+=metres/edge.speedLimitMps
            previous=edge.id
        }
        require(previous==null || graph.edges.getValue(previous).to==destination) { "Route does not reach destination" }
        return route.copy(distanceM=distance,estimatedSeconds=if(allSpeeds)time else null)
    }
    fun create(id: String,name: String,osm: ByteArray,source: String,routes: List<Route>,destination: Long): TripPack {
        val target=directory(id);require(!target.exists()) { "Trip pack already exists" }
        require(name.isNotBlank() && name.length<=200 && source.isNotBlank())
        val graph=OsmImporter.parse(osm,id,source).graph
        require(destination in graph.nodes && routes.size in 1..3)
        require(routes.map{it.id}.toSet().size==routes.size)
        val checked=routes.map{validateRoute(graph,it,destination)}
        root.mkdirs();val temporary=Files.createTempDirectory(root.toPath(),".create-$id-").toFile()
        try {
            safeFile(temporary,"roads.osm").writeBytes(osm)
            val p=Properties();p.setProperty("schema","driftlock-trip-1");p.setProperty("id",id);p.setProperty("name",name)
            p.setProperty("source",source);p.setProperty("graph_sha256",hash(osm));p.setProperty("graph_bytes",osm.size.toString())
            p.setProperty("destination",destination.toString());p.setProperty("routes",checked.size.toString());p.setProperty("blocked", "0")
            for((i,r) in checked.withIndex()) {
                p.setProperty("route.$i.id",r.id);p.setProperty("route.$i.legs",r.legs.size.toString())
                for((j,leg) in r.legs.withIndex()) {
                    p.setProperty("route.$i.$j.edge",leg.edgeId)
                    p.setProperty("route.$i.$j.start",leg.startFraction.toString())
                }
            }
            writeProperties(temporary,p)
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE)
        } catch(error: Exception) { temporary.deleteRecursively();throw error }
        return load(id)
    }
    fun load(id: String): TripPack = loadWithSource(id).pack
    fun loadWithSource(id: String): StoredTripPack {
        val dir=directory(id);val p=readProperties(dir)
        require(p.getProperty("schema")=="driftlock-trip-1" && p.getProperty("id")==id)
        val file=safeFile(dir,"roads.osm");require(file.length() in 1..20_000_000 && file.length()==p.getProperty("graph_bytes").toLong())
        val bytes=file.readBytes();require(hash(bytes)==p.getProperty("graph_sha256")) { "Road graph hash mismatch" }
        val imported=OsmImporter.parse(bytes,id,p.getProperty("source"))
        val destination=p.getProperty("destination").toLong();require(destination in imported.graph.nodes)
        val count=p.getProperty("routes").toInt();require(count in 1..3)
        val routes=(0 until count).map { i ->
            val legCount=p.getProperty("route.$i.legs").toInt();require(legCount in 0..100_000)
            val legs=(0 until legCount).map{j->RouteLeg(p.getProperty("route.$i.$j.edge"),p.getProperty("route.$i.$j.start").toDouble())}
            validateRoute(imported.graph,Route(p.getProperty("route.$i.id"),legs,0.0,null),destination)
        }
        val blockedCount=p.getProperty("blocked","0").toInt();require(blockedCount in 0..imported.graph.edges.size)
        val blocked=(0 until blockedCount).map{p.getProperty("blocked.$it")}.toSet()
        require(blocked.all{segment->imported.graph.edges.values.any{it.segmentId==segment}})
        return StoredTripPack(TripPack(id,p.getProperty("name"),imported.graph,routes,destination,blocked,
            dir.walkTopDown().filter{it.isFile}.sumOf{it.length()},imported.warnings),bytes)
    }
    fun listIds(): List<String> = root.listFiles().orEmpty().filter{it.isDirectory && it.name.matches(Regex("[A-Za-z0-9_-]{1,64}"))}.map{it.name}.sorted()
    fun delete(id: String): Boolean = directory(id).let{it.exists() && it.deleteRecursively()}
    fun saveBlocked(id: String,segments: Set<String>) {
        val pack=load(id)
        require(segments.all{s->pack.graph.edges.values.any{it.segmentId==s}})
        val dir=directory(id);val p=readProperties(dir)
        p.keys.toList().filter{it.toString().startsWith("blocked.")}.forEach{p.remove(it)}
        p.setProperty("blocked",segments.size.toString())
        segments.sorted().forEachIndexed{i,s->p.setProperty("blocked.$i",s)}
        writeProperties(dir,p)
    }
}
