package com.futurethinking.timestampgenius.util
object FuzzyMatcher {
 fun normalize(v:String):List<String>=v.lowercase().replace("[^\\p{L}\\p{Nd}']+".toRegex()," ").trim().split(Regex("\\s+")).filter{it.isNotBlank()}
 fun progress(expected:String,spoken:String):Float{val e=normalize(expected);val s=normalize(spoken);if(e.isEmpty()||s.isEmpty())return 0f;var cursor=0;var matched=0;for(word in s.takeLast(32)){while(cursor<e.size&&similarity(e[cursor],word)<0.68f)cursor++;if(cursor<e.size&&similarity(e[cursor],word)>=0.68f){matched++;cursor++}};return(matched.toFloat()/e.size).coerceIn(0f,1f)}
 fun similarity(a:String,b:String):Float{if(a==b)return 1f;if(a.length<2||b.length<2)return 0f;return 1f-levenshtein(a,b).toFloat()/maxOf(a.length,b.length)}
 private fun levenshtein(a:String,b:String):Int{val prev=IntArray(b.length+1){it};val cur=IntArray(b.length+1);for(i in a.indices){cur[0]=i+1;for(j in b.indices)cur[j+1]=minOf(cur[j]+1,prev[j+1]+1,prev[j]+if(a[i]==b[j])0 else 1);for(j in cur.indices)prev[j]=cur[j]};return prev[b.length]}
}
