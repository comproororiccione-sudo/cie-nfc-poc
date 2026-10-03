package it.cienfcpoc

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger

object LifecycleTrace {
    private val processStarted=SystemClock.elapsedRealtime()
    private val nextId=AtomicInteger(0)
    private const val MAX_EVENTS=24
    private val events=ArrayDeque<String>()

    fun newActivityId():Int=nextId.incrementAndGet()

    @Synchronized fun add(activityId:Int,event:String,state:String){
        val elapsed=SystemClock.elapsedRealtime()-processStarted
        events.addLast("#"+activityId+" "+event+" @"+elapsed+"ms "+state)
        while(events.size>MAX_EVENTS)events.removeFirst()
    }

    @Synchronized fun summary():String=if(events.isEmpty())"NESSUN_EVENTO" else events.joinToString(" -> ")
}
