package com.callanswer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Gate(val number: String, val location: String, val landmark: String)

/** Start inclusive, end exclusive; start != end. */
@Serializable
data class NightWindow(val start: LocalTimeHm, val end: LocalTimeHm)

@Serializable
data class GateConfig(val gate1: Gate, val gate3: Gate, val nightWindow: NightWindow)

val DefaultGates: GateConfig = GateConfig(
    gate1 = Gate(number = "1", location = "Press Enclave Road", landmark = "near Punjab National Bank"),
    gate3 = Gate(number = "3", location = "colony door no 3", landmark = "opposite Aurobindo College"),
    nightWindow = NightWindow(start = LocalTimeHm(22, 30), end = LocalTimeHm(5, 30)),
)
