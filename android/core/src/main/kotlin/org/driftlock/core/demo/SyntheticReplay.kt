package org.driftlock.core.demo

/** Explicit fixture reader; this API is never used by the live sensor service. */
data class ReplayRow(val kind:String,val t:Double,val values:List<Double>)
class SyntheticReplay(text:String) {
    val rows:List<ReplayRow>
    private var cursor=0
    init {
        require(text.lineSequence().firstOrNull()?.startsWith("# Synthetic")==true){"Only explicitly synthetic fixtures can be replayed"}
        rows=text.lineSequence().filter{it.isNotBlank()&&!it.startsWith('#')}.map{line->
            val parts=line.split(',');require(parts[0] in setOf("I","G"))
            val numbers=parts.drop(1).map{it.toDouble()};require(numbers.all{it.isFinite()})
            require(numbers.size==if(parts[0]=="I")7 else 9)
            require(numbers[0] in 0.0..3600.0)
            ReplayRow(parts[0],numbers[0],numbers.drop(1))
        }.toList()
        require(rows.isNotEmpty() && rows.size<=200_000)
        require(rows.zipWithNext().all{it.first.t<=it.second.t}) {"Replay cannot silently sort timestamps"}
    }
    fun takeUntil(elapsedS:Double):List<ReplayRow> {
        require(elapsedS.isFinite()&&elapsedS>=0)
        val start=cursor
        while(cursor<rows.size && rows[cursor].t<=elapsedS)cursor++
        return rows.subList(start,cursor)
    }
    val finished:Boolean get()=cursor==rows.size
}
