package com.karoo.lupinecontrols.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class LightActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = when (intent.action) {
            ACTION_TOGGLE_100 ->
                Intent(context, LupineControlService::class.java)
                    .setAction(LupineControlService.ACTION_TOGGLE_100)
            else -> null
        } ?: return

        context.startService(serviceIntent)
    }

    companion object {
        private const val PREFS_NAME = "lupine_prefs"
        private const val PREF_EXTENSION_TOGGLE_100 = "extension_toggle_100"

        const val ACTION_TOGGLE_100 = "com.karoo.lupinecontrols.action.LUPINE_TOGGLE_PRIMARY"

        fun isToggleEnabled(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_EXTENSION_TOGGLE_100, false)

        fun setToggleEnabled(context: Context, enabled: Boolean) {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_EXTENSION_TOGGLE_100, enabled)
                .apply()
        }
    }
}
