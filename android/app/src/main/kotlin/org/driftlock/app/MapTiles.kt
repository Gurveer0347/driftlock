package org.driftlock.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.driftlock.core.routing.*
import kotlin.math.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Geography is painted once per cached tile, never rebuilt by the animation clock. */
internal data class MapTileKey(val level:Int,val x:Int,val y:Int) {
    val scale get()=2.0.pow(level/2.0)
    fun pixel(p:Point):Pair<Double,Double> {
        val uv=plane(p)
        return uv.first*scale-x*SIZE to uv.second*scale-y*SIZE
    }
    fun screenPoint(p:Point,camera:MapCamera,w:Double,h:Double):Pair<Double,Double> {
        val local=pixel(p);val focus=plane(camera.focus)
        return (local.first+x*SIZE)/scale*camera.pixelsPerMeter+w/2-focus.first*camera.pixelsPerMeter+camera.panX to
            (local.second+y*SIZE)/scale*camera.pixelsPerMeter+h/2-focus.second*camera.pixelsPerMeter+camera.panY
    }
    companion object {
        const val SIZE=256
        fun plane(p:Point)=p.eastM-.14*p.northM to -.78*p.northM
        fun forPoint(p:Point,scale:Double):MapTileKey {
            val level=floor(log2(scale.coerceAtLeast(.0001))*2).toInt()
            val s=2.0.pow(level/2.0);val uv=plane(p)
            return MapTileKey(level,floor(uv.first*s/SIZE).toInt(),floor(uv.second*s/SIZE).toInt())
        }
        fun visible(camera:MapCamera,w:Double,h:Double):List<MapTileKey> {
            if(w<=0 || h<=0)return emptyList()
            val uv=plane(camera.focus);val level=floor(log2(camera.pixelsPerMeter.coerceAtLeast(.0001))*2).toInt()
            val scale=2.0.pow(level/2.0)
            fun ix(screen:Double,centre:Double,pan:Double,focus:Double)=floor((focus+(screen-centre-pan)/camera.pixelsPerMeter)*scale/SIZE).toInt()
            val minX=ix(0.0,w/2,camera.panX,uv.first)-1;val maxX=ix(w,w/2,camera.panX,uv.first)+1
            val minY=ix(0.0,h/2,camera.panY,uv.second)-1;val maxY=ix(h,h/2,camera.panY,uv.second)+1
            return (minY..maxY).flatMap{y->(minX..maxX).map{x->MapTileKey(level,x,y)}}
        }
    }
}

internal class MapTiles(graph:RoadGraph,scene:MapScene) {
    private class Shape(val points:List<Point>,val kind:MapFeatureKind?=null) {
        val uv=points.map{MapTileKey.plane(it)}
        val left=uv.minOf{it.first};val right=uv.maxOf{it.first}
        val top=uv.minOf{it.second};val bottom=uv.maxOf{it.second}
    }
    private val areas=scene.areas.map{Shape(it.points,it.kind)}
    private val roads=graph.edges.values.distinctBy{it.segmentId}.map{Shape(it.geometry)}
    private val labels=graph.edges.values.filter{it.name!="Unnamed road"}.groupBy{it.name}.map{(name,edges)->
        name to edges.maxBy{it.lengthM}.geometry.let{it[it.size/2]}
    }
    private val cache=LinkedHashMap<MapTileKey,ImageBitmap>(MAX_CACHED_TILES,.75f,true)
    private val levelLabels=HashMap<Int,List<Pair<String,Point>>>()
    var renderCount=0;private set
    val cacheSize get()=cache.size
    /** Called on the raster worker. Published ImageBitmaps are immutable and never recycled in use. */
    @Synchronized fun tile(key:MapTileKey):ImageBitmap {
        cache[key]?.let{return it}
        val bmp=Bitmap.createBitmap(MapTileKey.SIZE,MapTileKey.SIZE,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bmp);canvas.drawColor(0xFFE5ECE5.toInt())
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        val margin=100.0/key.scale
        val left=key.x*MapTileKey.SIZE/key.scale-margin;val right=(key.x+1)*MapTileKey.SIZE/key.scale+margin
        val top=key.y*MapTileKey.SIZE/key.scale-margin;val bottom=(key.y+1)*MapTileKey.SIZE/key.scale+margin
        fun intersects(s:Shape)=s.left<=right && s.right>=left && s.top<=bottom && s.bottom>=top
        fun path(s:Shape,closed:Boolean):Path=Path().apply {
            s.points.forEachIndexed{i,p->val a=key.pixel(p);if(i==0)moveTo(a.first.toFloat(),a.second.toFloat()) else lineTo(a.first.toFloat(),a.second.toFloat())}
            if(closed)close()
        }
        areas.forEach{area->if(intersects(area)) {
            val p=path(area,true);paint.style=Paint.Style.FILL
            if(area.kind==MapFeatureKind.BUILDING){
                canvas.save();canvas.translate(4f,6f);paint.color=0xFFB7BCAD.toInt();canvas.drawPath(p,paint);canvas.restore()
            }
            paint.color=when(area.kind){MapFeatureKind.BUILDING->0xFFE2DED1.toInt();MapFeatureKind.PARK->0xFFBBD9B4.toInt();else->0xFFABD3DD.toInt()}
            canvas.drawPath(p,paint)
            if(area.kind==MapFeatureKind.BUILDING){paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=0xFFD0CABB.toInt();canvas.drawPath(p,paint)}
        }}
        paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        roads.forEach{road->if(intersects(road)) {
            val p=path(road,false)
            paint.color=0xFFB7C6BB.toInt();paint.strokeWidth=10f;canvas.drawPath(p,paint)
            paint.color=0xFFFFFEFA.toInt();paint.strokeWidth=7.3f;canvas.drawPath(p,paint)
        }}
        paint.style=Paint.Style.FILL;paint.textSize=26f;paint.color=0xFF2D473C.toInt()
        val selected=levelLabels.getOrPut(key.level) {
            val placed=ArrayList<Pair<Double,Double>>()
            labels.filter{(_,point)->
                val uv=MapTileKey.plane(point);val p=uv.first*key.scale to uv.second*key.scale
                if(placed.any{hypot(it.first-p.first,it.second-p.second)<130})false else {placed.add(p);true}
            }
        }
        selected.forEach{(name,point)->
            val p=key.pixel(point)
            if(p.first in -paint.measureText(name).toDouble()..MapTileKey.SIZE.toDouble() && p.second in -40.0..(MapTileKey.SIZE+40.0)) {
                // A narrow outline stays sharp and avoids an expensive soft shadow.
                paint.style=Paint.Style.STROKE;paint.strokeWidth=3f;paint.color=0xFFFDFCF6.toInt()
                canvas.drawText(name,p.first.toFloat()+7,p.second.toFloat()-8,paint)
                paint.style=Paint.Style.FILL;paint.color=0xFF2D473C.toInt()
                canvas.drawText(name,p.first.toFloat()+7,p.second.toFloat()-8,paint)
            }
        }
        renderCount++
        val image=bmp.asImageBitmap();cache[key]=image
        while(cache.size>MAX_CACHED_TILES)cache.remove(cache.keys.first())
        return image
    }
    companion object {const val MAX_CACHED_TILES=96}
}

/** Worker results enter Compose only on the main Looper; obsolete region requests are revoked. */
internal class MapRasterWorker(private val graph:RoadGraph,private val scene:MapScene) {
    private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"DriftlockMapRaster")}
    private val main=Handler(Looper.getMainLooper())
    private val generation=AtomicLong()
    private var tiles:MapTiles?=null
    @Volatile private var closed=false
    fun request(keys:List<MapTileKey>,publish:(List<Pair<MapTileKey,ImageBitmap>>)->Unit) {
        if(closed || keys.isEmpty())return
        val token=generation.incrementAndGet()
        executor.execute {
            val source=tiles ?: MapTiles(graph,scene).also{tiles=it}
            val batch=ArrayList<Pair<MapTileKey,ImageBitmap>>()
            for(key in keys){if(closed || generation.get()!=token)return@execute;batch.add(key to source.tile(key))}
            main.post{if(!closed && generation.get()==token)publish(batch)}
        }
    }
    fun close(){closed=true;generation.incrementAndGet();executor.shutdownNow()}
}
