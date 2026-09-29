package org.driftlock.app.trips

import android.content.Context
import androidx.room.Room
import org.driftlock.core.routing.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** All methods run on a background owner. Graph files are authoritative; Room is a rebuildable index. */
class TripRepository(context:Context):AutoCloseable {
    private val store=TripPackStore(File(context.filesDir,"trip_packs"))
    private val database=Room.databaseBuilder(context.applicationContext,TripDatabase::class.java,"trips.db").build()
    private fun index(p:TripPack) { database.trips().put(TripRecord(p.id,p.name,p.byteSize,p.routes.size,System.currentTimeMillis())) }
    fun list():List<TripRecord> {
        val ids=store.listIds().toSet()
        database.trips().all().filter{it.id !in ids}.forEach{database.trips().remove(it.id)}
        ids.forEach{id->runCatching{load(id)}}
        return database.trips().all()
    }
    fun create(id:String,name:String,bytes:ByteArray,source:String,routes:List<Route>,destination:Long):TripPack =
        store.create(id,name,bytes,source,routes,destination).also(::index)
    fun load(id:String):TripPack=store.load(id).also(::index)
    fun loadForPlanning(id:String):StoredTripPack=store.loadWithSource(id).also{index(it.pack)}
    fun block(id:String,segments:Set<String>) { store.saveBlocked(id,segments);index(store.load(id)) }
    fun delete(id:String):Boolean=store.delete(id).also{if(it)database.trips().remove(id)}
    override fun close()=database.close()
}

object MapDownload {
    /** A small explicit region request, never a private trip upload or hidden location request. */
    fun fetch(bounds:GeoBounds):Pair<ByteArray,String> {
        require(bounds.east-bounds.west<=.025 && bounds.north-bounds.south<=.025) { "Choose a region no wider than 0.025 degrees" }
        val url="https://api.openstreetmap.org/api/0.6/map?bbox=${bounds.west},${bounds.south},${bounds.east},${bounds.north}"
        val connection=URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout=15_000;connection.readTimeout=30_000;connection.instanceFollowRedirects=false
        connection.setRequestProperty("User-Agent","DRIFTLOCK-SIH26168/0.2 (explicit offline region download)")
        try {
            check(connection.responseCode==200){"OSM download HTTP ${connection.responseCode}"}
            check(connection.contentLengthLong<=20_000_000){"Map exceeds 20 MB limit"}
            val bytes=connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(16_384)
                while(true) { val n=input.read(buffer);if(n<0)break;check(output.size()+n<=20_000_000){"Map exceeds 20 MB limit"};output.write(buffer,0,n) }
                output.toByteArray()
            }
            OsmImporter.parse(bytes,"download-review",url,bounds)
            return bytes to url
        } finally { connection.disconnect() }
    }
}
