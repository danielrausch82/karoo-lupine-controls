package com.karoo.lupinecontrols.extension

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback

class ToggleLightAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.startService(
            Intent(context, LupineControlService::class.java)
                .setAction(LupineControlService.ACTION_TOGGLE_100),
        )
    }
}
