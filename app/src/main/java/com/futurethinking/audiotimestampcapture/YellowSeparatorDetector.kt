package com.futurethinking.audiotimestampcapture

import android.graphics.Bitmap
import android.graphics.Rect

object YellowSeparatorDetector {
    fun findBands(bitmap: Bitmap): List<IntRange> {
        val raw=mutableListOf<Int>()
        val sampleX=if(bitmap.width>600) 6 else 3
        for(y in 0 until bitmap.height) {
            var yellow=0
            var tested=0
            var x=0
            while(x<bitmap.width) {
                val c=bitmap.getPixel(x,y)
                val r=(c shr 16) and 255
                val g=(c shr 8) and 255
                val b=c and 255
                if(r>185 && g>155 && b<125 && r-g<110) yellow++
                tested++
                x+=sampleX
            }
            if(tested>0 && yellow.toFloat()/tested.toFloat()>.55f) raw+=y
        }
        if(raw.isEmpty()) return emptyList()
        val bands=mutableListOf<IntRange>()
        var start=raw.first(); var prev=start
        for(y in raw.drop(1)) {
            if(y-prev<=3) prev=y
            else { if(prev-start>=1) bands+=start..prev; start=y; prev=y }
        }
        if(prev-start>=1) bands+=start..prev
        return bands
    }

    fun extractSegments(bitmap: Bitmap,bands: List<IntRange>): List<Rect> {
        if(bands.size<2) return emptyList()
        val result=mutableListOf<Rect>()
        for(i in 0 until bands.lastIndex) {
            val top=(bands[i].last+2).coerceAtLeast(0)
            val bottom=(bands[i+1].first-2).coerceAtMost(bitmap.height)
            if(bottom-top>=8) result+=Rect(0,top,bitmap.width,bottom)
        }
        return result
    }
}
