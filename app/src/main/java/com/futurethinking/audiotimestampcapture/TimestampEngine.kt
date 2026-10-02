package com.futurethinking.audiotimestampcapture

object TimestampEngine{
    fun similarity(a:String,b:String):Float{
        val aa=normalize(a);val bb=normalize(b)
        if(aa.isEmpty()||bb.isEmpty())return 0f
        val hits=aa.count{aw->bb.any{bw->FuzzyMatcher.close(aw,bw)}}
        return hits.toFloat()/maxOf(aa.size,bb.size)
    }
    fun clean(text:String):String=text
        .replace(Regex("^\\s*\\d{1,4}\\s*[).:\\-]?\\s*"),"")
        .replace(Regex("\\s+")," ")
        .trim()
    private fun normalize(s:String)=s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+")," ")
        .trim().split(Regex("\\s+")).filter{it.isNotBlank()}
}