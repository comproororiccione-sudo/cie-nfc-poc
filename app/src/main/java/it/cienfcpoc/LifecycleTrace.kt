package it.cienfcpoc

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

object LifecycleTrace {
    private val processStarted=SystemClock.elapsedRealtime()
    private val nextId=AtomicInteger(0)
    private val events=ArrayDeque<String>()

    fun newActivityId():Int=nextId.incrementAndGet()

    @Synchronized fun add(activityId:Int,event:String,canState:String){
        val elapsed=SystemClock.elapsedRealtime()-processStarted
        events.addLast("#"+activityId+" "+event+" @"+elapsed+"ms CAN="+canState)
        while(events.size>5)events.removeFirst()
    }

    @Synchronized fun summary():String=if(events.isEmpty())"NESSUN_EVENTO" else events.joinToString(" -> ")
}
