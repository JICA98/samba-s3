package net.rpcsx

import androidx.annotation.Keep

@Keep
object GameRepository {
    @Keep
    @JvmStatic
    fun add(gameInfos: Array<GameInfo>, progressId: Long) {
        val convertedInfos = gameInfos.map { gi ->
            com.zenithblue.sambas3.GameInfo(gi.path, gi.name, gi.iconPath, gi.gameFlags)
        }.toTypedArray()
        com.zenithblue.sambas3.GameRepository.add(convertedInfos, progressId)
    }
}
