package com.karoo.lupinecontrols.extension

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.karoo.lupinecontrols.MainActivity
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class LightControlsDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {

    private data class ButtonUi(
        val label: String,
        val background: Color,
        val allowTwoLines: Boolean = false,
    )

    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        context.startService(
            Intent(context, LupineControlService::class.java)
                .setAction(LupineControlService.ACTION_FIELD_VISIBLE),
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val viewWidth = (config.viewSize.first / density).dp
        val viewHeight = (config.viewSize.second / density).dp
        val baseTextSize = config.textSize.toFloat().coerceAtLeast(22f).sp
        var lastSignature: String? = null
        val job: Job = scope.launch {
            while (true) {
                val enabled = LightActionReceiver.isToggleEnabled(context)
                val status = LightFieldState.get(context)
                val snapshot = SharedLightState.get(context)
                val actualSnapshot = ActualLightState.get(context)
                val signature = "$RENDER_VERSION|$enabled|$status|${snapshot.outputTarget}|${snapshot.levelPercent}|${snapshot.lastOnTarget}|${snapshot.lastOnLevelPercent}|${actualSnapshot.outputTarget}|${actualSnapshot.isEco}|${actualSnapshot.rawHex}"
                if (lastSignature != signature) {
                    val remoteViews = glance.compose(context, DpSize(viewWidth, viewHeight)) {
                        SplitLightField(
                            toggleUi = buildToggleUi(enabled, status, snapshot, actualSnapshot),
                            totalWidth = viewWidth,
                            totalHeight = viewHeight,
                            baseTextSize = baseTextSize,
                        )
                    }
                    emitter.updateView(remoteViews.remoteViews)
                    lastSignature = signature
                }
                delay(1000)
            }
        }
        emitter.setCancellable {
            job.cancel()
            context.startService(
                Intent(context, LupineControlService::class.java)
                    .setAction(LupineControlService.ACTION_FIELD_HIDDEN),
            )
        }
    }

    private fun buildToggleUi(
        enabled: Boolean,
        status: String,
        snapshot: SharedLightState.Snapshot,
        actualSnapshot: ActualLightState.Snapshot,
    ): ButtonUi {
        val actualStateLabel = buildActualStateLabel(snapshot, actualSnapshot)
        val actualStateIsOff = effectiveOutputTarget(snapshot, actualSnapshot) == SharedLightState.OutputTarget.OFF || !enabled
        return when (status) {
            LightFieldState.STATUS_SEARCHING ->
                ButtonUi("SEARCH", CARD_COLOR)
            LightFieldState.STATUS_FOUND ->
                ButtonUi(actualStateLabel, if (actualStateIsOff) CARD_COLOR else statusColor(snapshot, actualSnapshot))
            LightFieldState.STATUS_CONNECTING ->
                ButtonUi("CONNECT", CARD_COLOR, allowTwoLines = true)
            LightFieldState.STATUS_CONNECTED -> if (actualStateIsOff) {
                ButtonUi("OFF", CARD_COLOR)
            } else {
                ButtonUi(actualStateLabel, statusColor(snapshot, actualSnapshot))
            }
            LightFieldState.STATUS_NO_DEVICE ->
                ButtonUi("NO\nLIGHT", ORANGE_COLOR, allowTwoLines = true)
            LightFieldState.STATUS_ERROR ->
                ButtonUi("ERROR", ORANGE_COLOR)
            LightFieldState.STATUS_DISCONNECTED,
            LightFieldState.STATUS_IDLE ->
                ButtonUi(actualStateLabel, if (actualStateIsOff) CARD_COLOR else statusColor(snapshot, actualSnapshot))
            else ->
                ButtonUi(actualStateLabel, if (actualStateIsOff) CARD_COLOR else statusColor(snapshot, actualSnapshot))
        }
    }

    private fun buildActualStateLabel(
        snapshot: SharedLightState.Snapshot,
        actualSnapshot: ActualLightState.Snapshot,
    ): String {
        val outputTarget = effectiveOutputTarget(snapshot, actualSnapshot)
        if (outputTarget == SharedLightState.OutputTarget.OFF) return "OFF"
        val isEco = if (actualSnapshot.outputTarget != ActualLightState.OutputTarget.UNKNOWN) {
            actualSnapshot.isEco
        } else {
            (snapshot.levelPercent ?: snapshot.lastOnLevelPercent ?: 25) in setOf(50, 100)
        }
        return when (outputTarget) {
            SharedLightState.OutputTarget.HIGH -> if (isEco) "HIGH ECO" else "HIGH"
            SharedLightState.OutputTarget.LOW -> if (isEco) "LOW ECO" else "LOW"
            SharedLightState.OutputTarget.OFF -> "OFF"
        }
    }

    private fun statusColor(snapshot: SharedLightState.Snapshot, actualSnapshot: ActualLightState.Snapshot): Color {
        val outputTarget = effectiveOutputTarget(snapshot, actualSnapshot)
        val isEco = if (actualSnapshot.outputTarget != ActualLightState.OutputTarget.UNKNOWN) {
            actualSnapshot.isEco
        } else {
            (snapshot.levelPercent ?: snapshot.lastOnLevelPercent ?: 25) in setOf(50, 100)
        }
        return when (outputTarget) {
            SharedLightState.OutputTarget.HIGH -> if (isEco) CYAN_COLOR else BLUE_COLOR
            SharedLightState.OutputTarget.LOW -> if (isEco) LIME_COLOR else GREEN_COLOR
            SharedLightState.OutputTarget.OFF -> CARD_COLOR
        }
    }

    private fun effectiveOutputTarget(
        snapshot: SharedLightState.Snapshot,
        actualSnapshot: ActualLightState.Snapshot,
    ): SharedLightState.OutputTarget = when (actualSnapshot.outputTarget) {
        ActualLightState.OutputTarget.HIGH -> SharedLightState.OutputTarget.HIGH
        ActualLightState.OutputTarget.LOW -> SharedLightState.OutputTarget.LOW
        ActualLightState.OutputTarget.OFF -> SharedLightState.OutputTarget.OFF
        ActualLightState.OutputTarget.UNKNOWN -> snapshot.outputTarget
    }

    @Composable
    private fun SplitLightField(
        toggleUi: ButtonUi,
        totalWidth: Dp,
        totalHeight: Dp,
        baseTextSize: TextUnit,
    ) {
        val outerPadding = 2.dp
        val gap = 2.dp
        val halfWidth = ((totalWidth.value - (outerPadding.value * 2f) - gap.value) / 2f).coerceAtLeast(40f).dp
        val toggleTextSize = fieldTextSize(toggleUi.label, halfWidth, totalHeight, baseTextSize, toggleUi.allowTwoLines)
        val appTextSize = fieldTextSize("APP", halfWidth, totalHeight, baseTextSize, false)
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = outerPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FieldHalf(
                label = toggleUi.label,
                background = toggleUi.background,
                modifier = GlanceModifier
                    .width(halfWidth)
                    .fillMaxHeight()
                    .clickable(actionRunCallback<ToggleLightAction>()),
                textSize = toggleTextSize,
                maxLines = if (toggleUi.allowTwoLines) 2 else 1,
            )
            Spacer(modifier = GlanceModifier.width(gap))
            FieldHalf(
                label = "APP",
                background = CARD_DARK_COLOR,
                modifier = GlanceModifier
                    .width(halfWidth)
                    .fillMaxHeight()
                    .clickable(actionStartActivity<MainActivity>()),
                textSize = appTextSize,
                maxLines = 1,
            )
        }
    }

    private fun fieldTextSize(
        label: String,
        halfWidth: Dp,
        totalHeight: Dp,
        baseTextSize: TextUnit,
        allowTwoLines: Boolean,
    ): TextUnit {
        val longestLine = label.split('\n').maxOf { it.length.coerceAtLeast(1) }
        val heightDriven = if (allowTwoLines) {
            (totalHeight.value * 0.22f).coerceIn(16f, 24f)
        } else {
            (totalHeight.value * 0.30f).coerceIn(22f, 34f)
        }
        val widthDriven = when {
            longestLine <= 3 -> 34f
            longestLine <= 4 -> 32f
            longestLine <= 5 -> 28f
            longestLine <= 6 -> if (allowTwoLines) 22f else 20f
            else -> if (allowTwoLines) 20f else 18f
        }.coerceAtMost((halfWidth.value * 0.22f).coerceAtLeast(18f))
        return minOf(baseTextSize.value.coerceAtLeast(heightDriven), widthDriven).sp
    }

    @Composable
    private fun FieldHalf(
        label: String,
        background: Color,
        modifier: GlanceModifier,
        textSize: TextUnit,
        maxLines: Int,
    ) {
        Box(
            modifier = modifier
                .background(ColorProvider(background, background))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                maxLines = maxLines,
                style = TextStyle(
                    color = ColorProvider(Color.White, Color.White),
                    fontSize = textSize,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    companion object {
        const val TYPE_ID = "DATATYPE_LIGHT_CONTROLS"
        private const val RENDER_VERSION = 10

        private val GREEN_COLOR = Color(0xFF42C83C)
        private val LIME_COLOR = Color(0xFFC6EF2D)
        private val BLUE_COLOR = Color(0xFF0A6BFF)
        private val CYAN_COLOR = Color(0xFF23C7E8)
        private val CARD_COLOR = Color(0xFF6B6B6B)
        private val CARD_DARK_COLOR = Color(0xFF575757)
        private val ORANGE_COLOR = Color(0xFFFF6B00)
    }
}
