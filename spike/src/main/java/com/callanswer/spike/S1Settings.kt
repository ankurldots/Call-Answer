package com.callanswer.spike

import android.app.Activity
import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView

/** What the spike does with a screened incoming call. */
enum class S1Mode(val label: String) {
    AUTO_ANSWER_AND_END("AUTO_ANSWER_AND_END (answer, end after delay)"),
    AUTO_ANSWER_ONLY("AUTO_ANSWER_ONLY (answer, never end)"),
    OFF("OFF (screen and start service only; answer by hand)"),
}

/** Persisted "S1 auto behaviour" setting (SharedPreferences). */
object S1Settings {
    private const val PREFS = "spike_s1_settings"
    private const val KEY_MODE = "mode"
    private const val KEY_DELAY = "end_delay_s"
    val DELAY_OPTIONS_S = listOf(15, 60, 180)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): S1Mode =
        S1Mode.values().firstOrNull { it.name == prefs(ctx).getString(KEY_MODE, null) } ?: S1Mode.AUTO_ANSWER_AND_END

    fun setMode(ctx: Context, m: S1Mode) { prefs(ctx).edit().putString(KEY_MODE, m.name).apply() }

    fun endDelaySeconds(ctx: Context): Int =
        prefs(ctx).getInt(KEY_DELAY, 15).takeIf { it in DELAY_OPTIONS_S } ?: 15

    fun setEndDelaySeconds(ctx: Context, s: Int) { prefs(ctx).edit().putInt(KEY_DELAY, s).apply() }

    /** One-line description, e.g. for logs and the status text. */
    fun describe(ctx: Context): String {
        val m = mode(ctx)
        return if (m == S1Mode.AUTO_ANSWER_AND_END) "$m (end after ${endDelaySeconds(ctx)} s)" else m.name
    }

    /** Banner text for S2/S3; warns when calls will be ended automatically. */
    fun bannerText(ctx: Context): String {
        val m = mode(ctx)
        return if (m == S1Mode.AUTO_ANSWER_AND_END) {
            "S1 mode: ${describe(ctx)}\nWARNING: calls will be ended after ${endDelaySeconds(ctx)} s. " +
                "Set AUTO_ANSWER_ONLY or OFF on the main menu or S1 screen before testing."
        } else {
            "S1 mode: ${describe(ctx)} (calls are not ended by the app)"
        }
    }

    /** Adds a mode spinner and a delay spinner to [parent]; [onChange] runs after any change. */
    fun addSelector(activity: Activity, parent: LinearLayout, onChange: () -> Unit) {
        parent.addView(TextView(activity).apply { text = "S1 auto behaviour (applies to incoming calls)"; textSize = 15f })
        val modeSpinner = Spinner(activity)
        modeSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, S1Mode.values().map { it.label })
        modeSpinner.setSelection(mode(activity).ordinal)
        val delaySpinner = Spinner(activity)
        delaySpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, DELAY_OPTIONS_S.map { "Auto-end delay: $it s" })
        delaySpinner.setSelection(DELAY_OPTIONS_S.indexOf(endDelaySeconds(activity)))
        modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val m = S1Mode.values()[pos]
                if (m != mode(activity)) { setMode(activity, m); SpikeLog.log("S1", "setting changed: ${describe(activity)}") }
                onChange()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        delaySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val s = DELAY_OPTIONS_S[pos]
                if (s != endDelaySeconds(activity)) { setEndDelaySeconds(activity, s); SpikeLog.log("S1", "setting changed: ${describe(activity)}") }
                onChange()
            }
            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        parent.addView(modeSpinner)
        parent.addView(delaySpinner)
    }
}
