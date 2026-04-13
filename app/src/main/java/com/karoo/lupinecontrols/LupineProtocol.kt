package com.karoo.lupinecontrols

data class LupineStatusSnapshot(
    val rawHex: String,
    val outputTarget: LupineLampOutputTarget,
    val isEco: Boolean,
)

enum class LupineLampOutputTarget {
    LOW,
    HIGH,
    OFF,
    UNKNOWN,
}

object LupineProtocol {
    fun buildInitializationFrames(): List<String> = listOf(
        LupineBleProfile.INIT_ENABLE_UPLINK,
        LupineBleProfile.INIT_SESSION,
    )

    fun buildStatusRequest(): String = LupineBleProfile.INIT_SESSION

    fun buildBeamCommand(mode: LupineBeamMode): String? = when (mode) {
        LupineBeamMode.LOW_BEAM,
        LupineBeamMode.HIGH_BEAM,
        LupineBeamMode.OFF -> null
    }

    fun parseStatusSnapshot(frameHex: String): LupineStatusSnapshot? {
        val clean = frameHex.uppercase()
        if (!clean.startsWith(LupineBleProfile.STATUS_SNAPSHOT_PREFIX)) return null
        val payload = clean.removePrefix(LupineBleProfile.STATUS_SNAPSHOT_PREFIX)
        if (payload.isEmpty()) {
            return LupineStatusSnapshot(
                rawHex = clean,
                outputTarget = LupineLampOutputTarget.UNKNOWN,
                isEco = false,
            )
        }

        if (payload.all { it == '0' }) {
            return LupineStatusSnapshot(
                rawHex = clean,
                outputTarget = LupineLampOutputTarget.OFF,
                isEco = false,
            )
        }

        return LupineStatusSnapshot(
            rawHex = clean,
            outputTarget = LupineLampOutputTarget.UNKNOWN,
            isEco = false,
        )
    }
}
