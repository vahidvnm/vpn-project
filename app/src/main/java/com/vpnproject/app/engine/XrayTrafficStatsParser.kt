package com.vpnproject.app.engine

data class EngineTrafficStats(
    val rxBytes: Long? = null,
    val txBytes: Long? = null
)

/** Parses AndroidLibXrayLite traffic stats such as:
 * proxy,downlink,205859;proxy,uplink,126336;
 */
object XrayTrafficStatsParser {
    fun parse(rawStats: String?): EngineTrafficStats {
        if (rawStats.isNullOrBlank()) return EngineTrafficStats()

        var rxTotal = 0L
        var txTotal = 0L
        var hasRx = false
        var hasTx = false

        rawStats
            .split(';', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { entry ->
                val value = entry
                    .split(',', ' ', '\t')
                    .asReversed()
                    .firstNotNullOfOrNull { it.trim().toLongOrNull() }
                    ?: return@forEach
                val label = entry.lowercase()
                when {
                    "downlink" in label -> {
                        rxTotal += value
                        hasRx = true
                    }
                    "uplink" in label -> {
                        txTotal += value
                        hasTx = true
                    }
                }
            }

        return EngineTrafficStats(
            rxBytes = if (hasRx) rxTotal else null,
            txBytes = if (hasTx) txTotal else null
        )
    }
}
