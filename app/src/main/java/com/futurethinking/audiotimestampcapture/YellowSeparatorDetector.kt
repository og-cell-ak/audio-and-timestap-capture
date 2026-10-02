package com.futurethinking.audiotimestampcapture

import android.graphics.Bitmap
import android.graphics.Rect

object YellowSeparatorDetector {
    fun findBands(bitmap: Bitmap): List<IntRange> {
        val raw = mutableListOf<Int>()
        val sampleX = if (bitmap.width > 600) 6 else 3

        for (y in 0 until bitmap.height) {
            var yellow = 0
            var tested = 0
            var x = 0

            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 255
                val g = (pixel shr 8) and 255
                val b = pixel and 255

                if (r > 185 && g > 155 && b < 125 && r - g < 110) {
                    yellow++
                }

                tested++
                x += sampleX
            }

            if (tested > 0 && yellow.toFloat() / tested.toFloat() > 0.55f) {
                raw += y
            }
        }

        if (raw.isEmpty()) return emptyList()

        val bands = mutableListOf<IntRange>()
        var start = raw.first()
        var previous = start

        for (y in raw.drop(1)) {
            if (y - previous <= 3) {
                previous = y
            } else {
                if (previous - start >= 1) bands += start..previous
                start = y
                previous = y
            }
        }

        if (previous - start >= 1) bands += start..previous
        return bands
    }

    fun extractSegments(bitmap: Bitmap, bands: List<IntRange>): List<Rect> {
        if (bands.isEmpty()) {
            return emptyList()
        }

        val boundaries = mutableListOf(0)

        for (band in bands) {
            val center = (band.first + band.last) / 2
            if (center > bitmap.height * 0.02f &&
                center < bitmap.height * 0.98f
            ) {
                boundaries += center
            }
        }

        boundaries += bitmap.height
        boundaries.sort()

        val result = mutableListOf<Rect>()

        for (index in 0 until boundaries.lastIndex) {
            val top = boundaries[index] + 3
            val bottom = boundaries[index + 1] - 3

            if (bottom - top >= 8) {
                result += Rect(
                    0,
                    top.coerceAtLeast(0),
                    bitmap.width,
                    bottom.coerceAtMost(bitmap.height)
                )
            }
        }

        return result
    }
}
