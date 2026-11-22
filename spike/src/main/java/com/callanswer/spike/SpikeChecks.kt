package com.callanswer.spike

/** One entry per feasibility check, in menu order. */
enum class SpikeCheck(val id: String, val title: String, val task: String) {
    S0("S0", "Device readout", "1.1"),
    S1("S1", "Screen, answer, foreground service, end call", "1.2"),
    S2("S2", "Microphone capture (foreground service)", "1.3"),
    S3("S3", "Agent speech audible to Caller, speaker route", "1.4"),
    S4("S4", "Echo handling prototype", "1.5"),
    S5("S5", "Samsung recording folder probe", "1.6"),
    S6("S6", "Transfer Path B (alert)", "1.7"),
    S7("S7", "On-device AI benchmark", "1.8"),
    S8("S8", "1909 hand-off and send", "1.9"),
}
