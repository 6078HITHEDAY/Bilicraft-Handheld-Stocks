package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhPluginHost
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

data class ProfitHistoryRecord(
    val day: String,
    val timestamp: Long,
    val totalPnl: Double,
    val totalPnlPct: Double?,
    val investedCapital: Double,
    val totalAssets: Double? = null
)

private data class ProfitLedger(
    val realizedPnl: Double = 0.0,
    val cumulativeBuyCost: Double = 0.0,
    val records: Map<String, ProfitHistoryRecord> = emptyMap()
)

class ProfitHistoryStore(private val host: BhPluginHost) {
    @Synchronized
    fun load(playerUuid: String): List<ProfitHistoryRecord> = loadLedger(playerUuid).records.values.sortedBy { it.timestamp }

    @Synchronized
    fun recordBuy(playerUuid: String, cost: Double) {
        if (cost <= 0.0) return
        val ledger = loadLedger(playerUuid)
        saveLedger(playerUuid, ledger.copy(cumulativeBuyCost = ledger.cumulativeBuyCost + cost))
    }

    @Synchronized
    fun recordRealizedPnl(playerUuid: String, realizedPnl: Double) {
        val ledger = loadLedger(playerUuid)
        saveLedger(playerUuid, ledger.copy(realizedPnl = ledger.realizedPnl + realizedPnl))
    }

    @Synchronized
    fun recordSnapshot(
        playerUuid: String,
        unrealizedPnl: Double,
        currentTrackedCost: Double,
        totalAssets: Double? = null,
        timestamp: Long = System.currentTimeMillis()
    ): List<ProfitHistoryRecord> {
        val ledger = loadLedger(playerUuid)
        val investedCapital = ledger.cumulativeBuyCost.takeIf { it > 0.0 }
            ?: currentTrackedCost.takeIf { it > 0.0 }
            ?: return ledger.records.values.sortedBy { it.timestamp }
        val totalPnl = ledger.realizedPnl + unrealizedPnl
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        val record = ProfitHistoryRecord(
            day = day,
            timestamp = timestamp,
            totalPnl = totalPnl,
            totalPnlPct = (totalPnl / (totalAssets ?: investedCapital) * 100.0).takeIf(Double::isFinite),
            investedCapital = investedCapital,
            totalAssets = totalAssets
        )
        val updated = ledger.copy(
            cumulativeBuyCost = investedCapital,
            records = ledger.records + (day to record)
        )
        saveLedger(playerUuid, updated)
        return updated.records.values.sortedBy { it.timestamp }
    }

    @Synchronized
    fun clear(playerUuid: String) {
        recordFile(playerUuid).delete()
    }

    @Synchronized
    fun clearAll() {
        host.pluginDataDir.resolve("profit-history").deleteRecursively()
    }

    private fun loadLedger(playerUuid: String): ProfitLedger {
        val file = recordFile(playerUuid)
        if (!file.isFile) return ProfitLedger()
        val properties = Properties()
        return runCatching {
            file.inputStream().use(properties::load)
            val records = properties.stringPropertyNames()
                .filter { it.endsWith(".timestamp") }
                .mapNotNull { key ->
                    val day = key.removeSuffix(".timestamp")
                    val timestamp = properties.getProperty("$day.timestamp")?.toLongOrNull() ?: return@mapNotNull null
                    val totalPnl = properties.getProperty("$day.totalPnl")?.toDoubleOrNull() ?: return@mapNotNull null
                    val investedCapital = properties.getProperty("$day.investedCapital")?.toDoubleOrNull() ?: return@mapNotNull null
                    ProfitHistoryRecord(
                        day = day,
                        timestamp = timestamp,
                        totalPnl = totalPnl,
                        totalPnlPct = properties.getProperty("$day.totalPnlPct")?.toDoubleOrNull(),
                        investedCapital = investedCapital,
                        totalAssets = properties.getProperty("$day.totalAssets")?.toDoubleOrNull()
                    )
                }.associateBy(ProfitHistoryRecord::day)
            ProfitLedger(
                realizedPnl = properties.getProperty("meta.realizedPnl")?.toDoubleOrNull() ?: 0.0,
                cumulativeBuyCost = properties.getProperty("meta.cumulativeBuyCost")?.toDoubleOrNull() ?: 0.0,
                records = records
            )
        }.getOrDefault(ProfitLedger())
    }

    private fun saveLedger(playerUuid: String, ledger: ProfitLedger) {
        val file = recordFile(playerUuid)
        file.parentFile?.mkdirs()
        val properties = Properties().apply {
            setProperty("meta.realizedPnl", ledger.realizedPnl.toString())
            setProperty("meta.cumulativeBuyCost", ledger.cumulativeBuyCost.toString())
            ledger.records.values.forEach { record ->
                setProperty("${record.day}.timestamp", record.timestamp.toString())
                setProperty("${record.day}.totalPnl", record.totalPnl.toString())
                record.totalPnlPct?.let { setProperty("${record.day}.totalPnlPct", it.toString()) }
                setProperty("${record.day}.investedCapital", record.investedCapital.toString())
                record.totalAssets?.let { setProperty("${record.day}.totalAssets", it.toString()) }
            }
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "Bilicraft Handheld stock profit history") }
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun recordFile(playerUuid: String): File {
        val safeUuid = playerUuid.lowercase().replace(Regex("[^a-z0-9-]"), "_")
        return host.pluginDataDir.resolve("profit-history/$safeUuid.properties")
    }
}
