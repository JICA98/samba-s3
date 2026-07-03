package net.rpcsx

import androidx.annotation.Keep

@Keep
data class GameInfo @Keep constructor(
    val path: String,
    var name: String? = null,
    var iconPath: String? = null,
    var gameFlags: Int = 0
)
