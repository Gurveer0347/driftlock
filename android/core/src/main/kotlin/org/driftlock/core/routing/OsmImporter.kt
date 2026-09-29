package org.driftlock.core.routing

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.*

data class ImportedGraph(val graph: RoadGraph, val warnings: List<String>)
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(listOf(south,west,north,east).all{it.isFinite()})
        require(south >= -85 && north <= 85 && west >= -180 && east <= 180)
        require(north>south && east>west && north-south<=0.5 && east-west<=0.5) { "Use a bounded offline region, at most 0.5 degrees per side" }
    }
}

object OsmImporter {
    private val drivable=setOf("motorway","motorway_link","trunk","trunk_link","primary","primary_link",
        "secondary","secondary_link","tertiary","tertiary_link","unclassified","residential","living_street","service")
    private val allowedAccess=setOf("yes","permissive","designated")
    private fun tags(e: Element): Map<String,String> {
        val out=mutableMapOf<String,String>(); val children=e.getElementsByTagName("tag")
        for(i in 0 until children.length) { val t=children.item(i) as Element; out[t.getAttribute("k")]=t.getAttribute("v") }
        return out
    }
    private fun children(e: Element,name: String): List<Element> {
        val nodes=e.getElementsByTagName(name); return (0 until nodes.length).map{nodes.item(it) as Element}
    }
    private fun speed(text: String?): Double? {
        if(text==null) return null
        val v=text.trim().lowercase(); val mph=v.endsWith("mph")
        val n=v.removeSuffix("mph").removeSuffix("km/h").trim().toDoubleOrNull() ?: return null
        if(!n.isFinite() || n<=0 || n>200) return null
        return if(mph) n*0.44704 else n/3.6
    }

    fun parse(bytes: ByteArray, id: String, source: String, requestedBounds: GeoBounds? = null): ImportedGraph {
        require(bytes.size in 1..20_000_000) { "OSM response is empty or exceeds 20 MB limit" }
        val text=bytes.toString(Charsets.UTF_8)
        require(!text.contains("<!DOCTYPE",true) && !text.contains("<!ENTITY",true)) { "External entities are forbidden" }
        val factory=DocumentBuilderFactory.newInstance()
        factory.isExpandEntityReferences=false
        // DOCTYPE rejection above is portable to Android's smaller XML implementation.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        val document=factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val root=document.documentElement
        require(root.tagName=="osm") { "Expected OSM XML, not an error or HTML page" }
        require(children(root,"remark").isEmpty()) { "Overpass returned an incomplete/error response" }
        val bound=children(root,"bounds").firstOrNull()
        val bounds=requestedBounds ?: bound?.let {
            GeoBounds(it.getAttribute("minlat").toDouble(),it.getAttribute("minlon").toDouble(),
                it.getAttribute("maxlat").toDouble(),it.getAttribute("maxlon").toDouble())
        } ?: throw IllegalArgumentException("Explicit downloaded coverage bounds required")
        val lat0=(bounds.south+bounds.north)/2; val lon0=(bounds.west+bounds.east)/2
        val latScale=Math.PI/180*6378137.0; val lonScale=latScale*cos(Math.toRadians(lat0))
        fun point(lat: Double,lon: Double) = Point((lon-lon0)*lonScale,(lat-lat0)*latScale)
        val corners=listOf(point(bounds.south,bounds.west),point(bounds.north,bounds.east))
        val coverage=Coverage(corners[0].eastM,corners[0].northM,corners[1].eastM,corners[1].northM)
        val nodes=linkedMapOf<Long,RoadNode>(); val barriers=mutableSetOf<Long>(); val warnings=mutableListOf<String>()
        for(n in children(root,"node")) {
            val nid=n.getAttribute("id").toLong(); val lat=n.getAttribute("lat").toDouble(); val lon=n.getAttribute("lon").toDouble()
            require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0)
            require(nid !in nodes) { "Duplicate OSM node" }
            nodes[nid]=RoadNode(nid,point(lat,lon))
            val t=tags(n); val access=t["motorcar"] ?: t["motor_vehicle"] ?: t["vehicle"] ?: t["access"]
            if((access != null && access !in allowedAccess) || (t["barrier"] != null && t["barrier"] !in setOf("no","entrance") && access !in allowedAccess)) barriers.add(nid)
        }
        require(nodes.size <= 150_000) { "Offline region is too large" }
        data class Restriction(val id: String,val from: Long,val to: Long,val via: Long,val only: Boolean,val uturn: Boolean)
        val restrictions=mutableListOf<Restriction>(); val excludedWays=mutableSetOf<Long>()
        for(rel in children(root,"relation")) {
            val t=tags(rel); if(t["type"]!="restriction") continue
            if(t["except"].orEmpty().split(';').any{it in setOf("motorcar","motor_vehicle","vehicle")}) continue
            val value=t["restriction:motorcar"] ?: t["restriction:motor_vehicle"] ?: t["restriction"]
            if(value==null && t.keys.none{it.startsWith("restriction") && it.endsWith(":conditional")}) continue
            val members=children(rel,"member"); val from=members.filter{it.getAttribute("role")=="from" && it.getAttribute("type")=="way"}
            val to=members.filter{it.getAttribute("role")=="to" && it.getAttribute("type")=="way"}
            val via=members.filter{it.getAttribute("role")=="via"}
            val supported=from.size==1 && to.size==1 && via.size==1 && via[0].getAttribute("type")=="node" &&
                value != null && (value.startsWith("no_") || value.startsWith("only_")) && t.keys.none{it.endsWith(":conditional")}
            if(!supported) {
                members.filter{it.getAttribute("type")=="way"}.forEach{excludedWays.add(it.getAttribute("ref").toLong())}
                warnings.add("Restriction ${rel.getAttribute("id")} unsupported; affected ways excluded")
                continue
            }
            restrictions.add(Restriction(rel.getAttribute("id"),from[0].getAttribute("ref").toLong(),to[0].getAttribute("ref").toLong(),
                via[0].getAttribute("ref").toLong(),value!!.startsWith("only_"),value.endsWith("u_turn")))
        }
        val edges=mutableListOf<RoadEdge>()
        for(way in children(root,"way")) {
            val wayId=way.getAttribute("id").toLong(); val t=tags(way)
            if(wayId in excludedWays || t["highway"] !in drivable) continue
            val access=t["motorcar"] ?: t["motor_vehicle"] ?: t["vehicle"] ?: t["access"]
            if(access != null && access !in allowedAccess) continue
            if(t.keys.any{it.endsWith(":conditional")} || t["ford"]=="yes" || t["construction"]!=null) {
                warnings.add("Way $wayId excluded: conditional access, ford or construction"); continue
            }
            val direction=t["oneway:motorcar"] ?: t["oneway:motor_vehicle"] ?: t["oneway"] ?:
                if(t["junction"] in setOf("roundabout","circular") || t["highway"]=="motorway") "yes" else "no"
            if(direction !in setOf("yes","1","true","-1","reverse","no","0","false")) {
                warnings.add("Way $wayId excluded: unsupported one-way value"); continue
            }
            val refs=children(way,"nd").map{it.getAttribute("ref").toLong()}
            if(refs.any{it !in nodes}) { warnings.add("Way $wayId incomplete; omitted"); continue }
            for(i in 0 until refs.size-1) {
                val a=nodes.getValue(refs[i]);val b=nodes.getValue(refs[i+1])
                if(a.id in barriers || b.id in barriers || a.id==b.id || a.point.distance(b.point)<0.01) continue
                if(!coverage.contains(a.point) || !coverage.contains(b.point)) continue
                val segment="$wayId:${a.id}:${b.id}"
                fun add(from: RoadNode,to: RoadNode,suffix: String) {
                    edges.add(RoadEdge("$wayId:$i:$suffix",from.id,to.id,segment,wayId,t["name"] ?: t["ref"] ?: "Unnamed road",
                        from.point.distance(to.point),listOf(from.point,to.point),speed(t["maxspeed"])))
                }
                if(direction !in setOf("-1","reverse")) add(a,b,"f")
                if(direction in setOf("-1","reverse","no","0","false")) add(b,a,"r")
            }
        }
        val rules=mutableSetOf<TurnRestriction>()
        for(r in restrictions) {
            val incoming=edges.filter{it.wayId==r.from && it.to==r.via}
            val outgoing=edges.filter{it.wayId==r.to && it.from==r.via}
            if(incoming.isEmpty() || outgoing.isEmpty()) {
                // Restriction target missing at coverage edge: do not let its incoming way route through.
                excludedWays.add(r.from);warnings.add("Restriction ${r.id} incomplete; incoming way excluded")
            } else for(a in incoming) for(b in outgoing) {
                if(!r.uturn || b.to==a.from) rules.add(TurnRestriction(a.id,b.id,r.only))
            }
        }
        // An excluded only-turn target must not turn its incoming way into an
        // unrestricted approach. Exclusions can cascade through earlier relations.
        var changed: Boolean
        do {
            changed=false
            for(r in restrictions) {
                if(r.only && r.to in excludedWays && excludedWays.add(r.from)) {
                    warnings.add("Restriction ${r.id} has no usable only-turn target; incoming way excluded")
                    changed=true
                }
            }
        } while(changed)
        val kept=edges.filter{it.wayId !in excludedWays};val ids=kept.map{it.id}.toSet()
        val used=kept.flatMap{listOf(it.from,it.to)}.toSet()
        require(kept.isNotEmpty()) { "No usable driving roads inside downloaded coverage" }
        return ImportedGraph(RoadGraph(id,nodes.values.filter{it.id in used},kept,
            rules.filter{it.incomingEdge in ids && it.outgoingEdge in ids}.toSet(),coverage,lat0,lon0,source,
            "© OpenStreetMap contributors · ODbL · https://www.openstreetmap.org/copyright"),warnings.distinct())
    }
}
