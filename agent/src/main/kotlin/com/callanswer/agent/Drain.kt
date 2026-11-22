package com.callanswer.agent

import java.util.concurrent.atomic.AtomicBoolean

/**
 * SIGTERM drain: after [beginDrain] the service must refuse new calls (route answers non-200,
 * stream upgrades are rejected) and [awaitDrained] waits until active calls end or [timeoutMillis] passes.
 *
 * Wiring (Main): `val drain = DrainCoordinator { activeCalls() }`, check `drain.draining` in the route
 * and stream handlers, and call `drain.installShutdownHook { server.stop(1000, 5000) }`.
 */
class DrainCoordinator(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val pollMillis: Long = 500,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val activeCalls: () -> Int,
) {
    private val drainingFlag = AtomicBoolean(false)

    val draining: Boolean get() = drainingFlag.get()

    fun beginDrain() {
        drainingFlag.set(true)
    }

    /** Returns true when no call is active, false when the timeout elapsed first. */
    fun awaitDrained(): Boolean {
        val deadline = nowMillis() + timeoutMillis
        while (activeCalls() > 0) {
            if (nowMillis() >= deadline) return false
            sleep(pollMillis)
        }
        return true
    }

    /** Registers a JVM shutdown hook (runs on SIGTERM) that drains, then runs [stopServer]. */
    fun installShutdownHook(stopServer: () -> Unit) {
        Runtime.getRuntime().addShutdownHook(
            Thread({
                beginDrain()
                awaitDrained()
                stopServer()
            }, "agent-drain"),
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}
