package com.goingmerry.gmsingular

import com.singular.sdk.Singular

fun interface SingularUserSetting {
    fun setCustomUserId(id: String)
}

class LiveSingularUserSetter : SingularUserSetting {
    override fun setCustomUserId(id: String) {
        Singular.setCustomUserId(id)
    }
}
