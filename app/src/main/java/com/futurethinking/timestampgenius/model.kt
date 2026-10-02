package com.futurethinking.timestampgenius

data class LineShape(var unlocked:Boolean=false,var widthFraction:Float=1f,var heightFraction:Float=1f,var cornerFraction:Float=0f)
data class LayoutConfig(var boxLeft:Float=0.08f,var boxTop:Float=0.18f,var boxWidth:Float=0.84f,var boxHeight:Float=0.45f,var lineCount:Int=5,var scrollSpeed:Int=0,var lines:MutableList<LineShape> = MutableList(5){LineShape()})
data class TimestampLine(val index:Int,val text:String,val millis:Long?)
