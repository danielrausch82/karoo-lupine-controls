package com.karoo.lupinecontrols

object LupineBleProfile {
    const val PRIMARY_SERVICE_UUID = "8c1338fe-4f63-4196-90aa-8c0d6c69e007"
    const val COMMAND_CHARACTERISTIC_UUID = "8c1338fe-4f63-4196-90aa-8c0d6c69e008"
    const val NOTIFY_CHARACTERISTIC_UUID = "8c1338fe-4f63-4196-90aa-8c0d6c69e009"

    const val COMMAND_HANDLE = 0x0023
    const val NOTIFY_HANDLE = 0x0027

    const val INIT_ENABLE_UPLINK = "000104"
    const val INIT_SESSION = "00010307"
    const val STATUS_SNAPSHOT_PREFIX = "42"
    const val KNOWN_OFF_STATUS_SNAPSHOT = "4200000000000000000000000000000000000000"
}

enum class LupineBeamMode {
    LOW_BEAM,
    HIGH_BEAM,
    OFF,
}