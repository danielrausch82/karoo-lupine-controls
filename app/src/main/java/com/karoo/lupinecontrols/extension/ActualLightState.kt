package com.karoo.lupinecontrols.extension

import android.content.Context

object ActualLightState {
    private const val PREFS_NAME = "lupine_prefs"
    private const val PREF_ACTUAL_OUTPUT_TARGET = "actual_output_target"
    private const val PREF_ACTUAL_IS_ECO = "actual_is_eco"
    private const val PREF_ACTUAL_RAW_HEX = "actual_raw_hex"

    enum class OutputTarget {
        LOW,
        HIGH,
        OFF,
        UNKNOWN,
    }

    data class Snapshot(
        val outputTarget: OutputTarget,
        val isEco: Boolean,
        val rawHex: String?,
    )

    fun get(context: Context): Snapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val outputTarget = runCatching {
            OutputTarget.valueOf(
                prefs.getString(PREF_ACTUAL_OUTPUT_TARGET, OutputTarget.UNKNOWN.name) ?: OutputTarget.UNKNOWN.name,
            )
        }.getOrDefault(OutputTarget.UNKNOWN)
        return Snapshot(
            outputTarget = outputTarget,
            isEco = prefs.getBoolean(PREF_ACTUAL_IS_ECO, false),
            rawHex = prefs.getString(PREF_ACTUAL_RAW_HEX, null),
        )
    }

    fun set(context: Context, outputTarget: OutputTarget, isEco: Boolean, rawHex: String?) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ACTUAL_OUTPUT_TARGET, outputTarget.name)
            .putBoolean(PREF_ACTUAL_IS_ECO, isEco)
            .putString(PREF_ACTUAL_RAW_HEX, rawHex)
            .apply()
    }

    fun clear(context: Context) {
        set(context, OutputTarget.UNKNOWN, false, null)
    }
}