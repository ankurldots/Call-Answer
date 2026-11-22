package com.callanswer.spikecloud

import java.io.File

/**
 * Records raw frames (Exotel or Sonic) as fixtures, one file per frame:
 * <dir>/<source>/<seq>-<direction>.json (text) or .bin (binary).
 */
class Frames(private val source: String, private val dir: File = File("spike-cloud/results/frames")) {
    private var seq = 0

    @Synchronized
    fun record(direction: String, payload: String): File =
        target(direction, "json").also { it.writeText(payload) }

    @Synchronized
    fun record(direction: String, payload: ByteArray): File =
        target(direction, "bin").also { it.writeBytes(payload) }

    private fun target(direction: String, ext: String): File {
        val d = dir.resolve(source).apply { mkdirs() }
        return d.resolve("%05d-%s.%s".format(seq++, direction, ext))
    }
}
