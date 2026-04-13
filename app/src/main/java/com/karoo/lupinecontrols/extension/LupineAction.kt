package com.karoo.lupinecontrols.extension

enum class LupineAction(val actionId: String) {
    OFF("lamp-off"),
    LOW_BEAM("low-beam"),
    HIGH_BEAM("high-beam");

    companion object {
        fun fromActionId(actionId: String): LupineAction? = entries.firstOrNull { it.actionId == actionId }
    }
}
