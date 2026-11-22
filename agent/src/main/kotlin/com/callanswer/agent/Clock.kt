package com.callanswer.agent

/** Time source for the Agent_Server. Tests replace it with a fixed or manual clock. */
interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
