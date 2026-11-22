package com.callanswer.core.gate

import com.callanswer.core.model.Gate
import com.callanswer.core.model.GateConfig
import com.callanswer.core.model.LocalTimeHm
import com.callanswer.core.model.NightWindow

sealed interface GateSelection {
    /** [window] is carried so the renderer can state Night_Window times when asked. */
    data class Single(val gate: Gate, val night: Boolean, val window: NightWindow? = null) : GateSelection

    /** Time unreadable: both gates are stated (Requirement 11.17). */
    data class Both(val gate1: Gate, val gate3: Gate, val window: NightWindow) : GateSelection
}

/** Start inclusive, end exclusive; handles windows that wrap past midnight. */
fun isInNightWindow(now: LocalTimeHm, w: NightWindow): Boolean =
    if (w.start < w.end) now >= w.start && now < w.end
    else now >= w.start || now < w.end

fun selectGate(now: LocalTimeHm?, cfg: GateConfig): GateSelection =
    if (now == null) GateSelection.Both(cfg.gate1, cfg.gate3, cfg.nightWindow)
    else if (isInNightWindow(now, cfg.nightWindow)) GateSelection.Single(cfg.gate3, night = true, window = cfg.nightWindow)
    else GateSelection.Single(cfg.gate1, night = false, window = cfg.nightWindow)

object GateSelector {
    fun isInNightWindow(now: LocalTimeHm, w: NightWindow): Boolean =
        com.callanswer.core.gate.isInNightWindow(now, w)

    fun selectGate(now: LocalTimeHm?, cfg: GateConfig): GateSelection =
        com.callanswer.core.gate.selectGate(now, cfg)
}
