package com.futurethinking.timestampgenius.util
object TimestampFormatter { fun format(ms:Long):String{val h=ms/3600000;val m=(ms/60000)%60;val s=(ms/1000)%60;val x=ms%1000;return "%02d:%02d:%02d.%03d".format(h,m,s,x)} }
