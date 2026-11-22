package com.callanswer.app.asr

import android.content.Context
import java.io.File

/**
 * Makes bundled ASR model folders available as real files (Vosk and ONNX Runtime memory-map files, not assets).
 * Models are stored uncompressed in `assets/models/asr/<name>` (`noCompress "onnx", "bin"`) and copied once into
 * the app's private storage; later calls reuse the copy. Only the models of the chosen engine are bundled.
 */
class AsrModelStore(private val context: Context) {
    private val root = File(context.filesDir, "asr-models")

    /** Returns the folder for the bundled model [name], or null when it is not bundled. */
    fun dir(name: String): File? {
        val assetPath = "$ASSET_ROOT/$name"
        val target = File(root, name)
        val marker = File(target, DONE_MARKER)
        if (marker.exists()) return target
        val assets = context.assets
        if (assets.list(assetPath).isNullOrEmpty()) return null
        target.deleteRecursively()
        copyAssetDir(assetPath, target)
        marker.writeText("ok")
        return target
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        target.mkdirs()
        for (child in context.assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$child"
            if (context.assets.list(childPath).isNullOrEmpty()) {
                context.assets.open(childPath).use { input ->
                    File(target, child).outputStream().use { input.copyTo(it) }
                }
            } else {
                copyAssetDir(childPath, File(target, child))
            }
        }
    }

    companion object {
        const val ASSET_ROOT = "models/asr"
        const val WHISPER = "whisper-base-int8"
        const val VOSK_HI = "vosk-small-hi"
        const val VOSK_EN_IN = "vosk-small-en-in"
        private const val DONE_MARKER = ".complete"
    }
}
