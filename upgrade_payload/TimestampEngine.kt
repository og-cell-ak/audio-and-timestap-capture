package com.futurethinking.audiotimestampcapture

data class SpokenSegment(val startMs: Long, val text: String, val confidence: Float)
data class TimedScript(val timestampMs: Long, val panelNumber: Int, val script: String, val confidence: Float)

object TimestampEngine {
    fun bestLine(screenLines: List<ScreenLine>, spoken: String, alreadyMatched: Set<Int>): ScreenLine? {
        val a=normalize(spoken)
        if(a.isEmpty())return null
        var best:ScreenLine?=null
        var score=0f
        for(line in screenLines){
            if(line.index in alreadyMatched)continue
            val x=similarity(a,normalize(line.text))
            if(x>score){score=x;best=line}
        }
        return if(score>=0.22f)best else null
    }
    fun match(screenLines:List<ScreenLine>,spoken:SpokenSegment,alreadyMatched:Set<Int>):TimedScript?{
        val speech=normalize(spoken.text)
        if(speech.isEmpty())return null
        var best:ScreenLine?=null
        var score=0f
        for(line in screenLines){
            if(line.index in alreadyMatched)continue
            val x=similarity(speech,normalize(line.text))
            if(x>score){score=x;best=line}
        }
        if(best==null||score<0.38f)return null
        return TimedScript(spoken.startMs,best.index,clean(best.text),(score*spoken.confidence).coerceIn(0f,1f))
    }
    fun ordered(rows:Collection<TimedScript>):List<TimedScript>=rows.sortedBy{it.timestampMs}
    private fun clean(s:String)=s.replace(Regex("""^\s*\d{1,4}[).:\-]?\s*"""),"").replace(Regex("""\s+""")," ").trim()
    private fun normalize(s:String)=s.lowercase().replace(Regex("""[^\p{L}\p{N}]+""")," ").split(Regex("""\s+""")).filter{it.length>=2}
    private fun similarity(a:List<String>,b:List<String>):Float{
        if(a.isEmpty()||b.isEmpty())return 0f
        val sa=a.toSet();val sb=b.toSet()
        val i=sa.intersect(sb).size.toFloat();val u=sa.union(sb).size.toFloat().coerceAtLeast(1f)
        return i/u*.55f+i/minOf(sa.size,sb.size).toFloat().coerceAtLeast(1f)*.45f
    }
}
