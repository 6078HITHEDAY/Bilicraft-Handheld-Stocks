package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhPluginHost

class WatchlistStore(private val host: BhPluginHost) {
    private val file get() = host.pluginDataDir.resolve("watchlist.txt")

    fun load(): Set<Int> = runCatching {
        if (!file.isFile) return emptySet()
        file.readLines()
            .mapNotNull(String::toIntOrNull)
            .toSet()
    }.getOrDefault(emptySet())

    fun save(companyIds: Set<Int>) {
        file.parentFile?.mkdirs()
        val temporary = file.resolveSibling("${file.name}.tmp")
        temporary.writeText(companyIds.sorted().joinToString("\n"))
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }
}
