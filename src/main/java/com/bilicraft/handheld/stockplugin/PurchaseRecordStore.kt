package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhPluginHost
import java.io.File
import java.util.Properties

data class PurchaseRecord(
    val companyId: Int,
    val companyName: String,
    val shares: Long,
    val totalCost: Double
) {
    val averagePrice: Double get() = if (shares > 0) totalCost / shares else 0.0
}

class PurchaseRecordStore(private val host: BhPluginHost) {
    fun clearAll() {
        host.pluginDataDir.resolve("purchase-records").deleteRecursively()
    }

    fun load(playerUuid: String): Map<Int, PurchaseRecord> {
        val properties = Properties()
        val file = recordFile(playerUuid)
        if (!file.isFile) return emptyMap()
        file.inputStream().use(properties::load)
        return properties.stringPropertyNames().mapNotNull { key ->
            val companyId = key.substringBefore('.').toIntOrNull() ?: return@mapNotNull null
            if (!key.endsWith(".shares")) return@mapNotNull null
            val shares = properties.getProperty("$companyId.shares")?.toLongOrNull() ?: return@mapNotNull null
            val totalCost = properties.getProperty("$companyId.totalCost")?.toDoubleOrNull() ?: return@mapNotNull null
            val name = properties.getProperty("$companyId.name").orEmpty()
            PurchaseRecord(companyId, name, shares, totalCost).takeIf { it.shares > 0 }
        }.associateBy(PurchaseRecord::companyId)
    }

    fun recordBuy(playerUuid: String, companyId: Int, companyName: String, shares: Long, price: Double) {
        if (shares <= 0 || price < 0.0) return
        val records = load(playerUuid).toMutableMap()
        val current = records[companyId]
        records[companyId] = PurchaseRecord(
            companyId = companyId,
            companyName = companyName,
            shares = (current?.shares ?: 0L) + shares,
            totalCost = (current?.totalCost ?: 0.0) + price * shares
        )
        save(playerUuid, records)
    }

    fun recordSell(playerUuid: String, companyId: Int, shares: Long, fullySold: Boolean) {
        val records = load(playerUuid).toMutableMap()
        val current = records[companyId] ?: return
        val remaining = if (fullySold) 0L else (current.shares - shares).coerceAtLeast(0L)
        if (remaining == 0L) {
            records.remove(companyId)
        } else {
            records[companyId] = current.copy(
                shares = remaining,
                totalCost = current.averagePrice * remaining
            )
        }
        save(playerUuid, records)
    }

    private fun save(playerUuid: String, records: Map<Int, PurchaseRecord>) {
        val file = recordFile(playerUuid)
        file.parentFile?.mkdirs()
        val properties = Properties()
        records.values.forEach { record ->
            properties.setProperty("${record.companyId}.name", record.companyName)
            properties.setProperty("${record.companyId}.shares", record.shares.toString())
            properties.setProperty("${record.companyId}.totalCost", record.totalCost.toString())
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "Bilicraft Handheld stock purchase records") }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun recordFile(playerUuid: String): File {
        val safeUuid = playerUuid.lowercase().replace(Regex("[^a-z0-9-]"), "_")
        return host.pluginDataDir.resolve("purchase-records/$safeUuid.properties")
    }
}
