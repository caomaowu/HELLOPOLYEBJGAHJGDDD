package com.wrbug.polymarketbot.util

/**
 * 市场过滤相关工具。
 * 负责统一过滤模式、列表归一化，以及从 slug 推导系列/周期元数据。
 */
object MarketFilterSupport {
    const val FILTER_MODE_DISABLED = "DISABLED"
    const val FILTER_MODE_WHITELIST = "WHITELIST"
    const val FILTER_MODE_BLACKLIST = "BLACKLIST"

    private val SUPPORTED_FILTER_MODES = setOf(
        FILTER_MODE_DISABLED,
        FILTER_MODE_WHITELIST,
        FILTER_MODE_BLACKLIST
    )

    private val timedSeriesPattern = Regex("^([a-z0-9-]+-(?:5m|15m|1h|4h|1d))(?:-\\d+)?$")

    fun normalizeFilterMode(mode: String?): String {
        if (mode.isNullOrBlank()) {
            return FILTER_MODE_DISABLED
        }
        return mode.trim().uppercase()
    }

    fun validateFilterMode(mode: String, fieldName: String): String? {
        return if (mode in SUPPORTED_FILTER_MODES) {
            null
        } else {
            "$fieldName 不支持: $mode，仅支持 ${SUPPORTED_FILTER_MODES.joinToString(", ")}"
        }
    }

    fun normalizeMarketCategories(categories: List<String>?): List<String> {
        return categories.orEmpty()
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isBlank()) {
                    null
                } else {
                    CategoryValidator.normalizeCategory(trimmed) ?: trimmed.lowercase()
                }
            }
            .distinct()
            .sorted()
    }

    fun normalizeMarketIntervals(intervals: List<Int>?): List<Int> {
        return intervals.orEmpty()
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    fun normalizeMarketSeries(series: List<String>?): List<String> {
        return series.orEmpty()
            .mapNotNull { raw ->
                val trimmed = raw.trim().lowercase()
                trimmed.takeIf { it.isNotBlank() }
            }
            .distinct()
            .sorted()
    }

    fun validateFilterValues(
        mode: String,
        values: Collection<*>,
        fieldLabel: String
    ): String? {
        if (mode == FILTER_MODE_DISABLED) {
            return null
        }
        return if (values.isEmpty()) {
            "$fieldLabel 已启用，但配置列表为空"
        } else {
            null
        }
    }

    fun deriveMarketSeriesMetadata(
        slug: String?,
        eventSlug: String? = null
    ): MarketSeriesMetadata {
        val candidate = sequenceOf(eventSlug, slug)
            .filterNotNull()
            .map { it.trim().lowercase() }
            .firstOrNull { it.isNotBlank() }
            ?: return MarketSeriesMetadata()

        val match = timedSeriesPattern.matchEntire(candidate)
        val seriesSlugPrefix = match?.groupValues?.getOrNull(1)
            ?: candidate.takeIf { extractIntervalSeconds(candidate) != null }
        val intervalSeconds = extractIntervalSeconds(seriesSlugPrefix ?: candidate)

        val marketSourceType = when {
            seriesSlugPrefix?.contains("-updown-") == true -> "CRYPTO_UPDOWN"
            intervalSeconds != null -> "TIMED_SERIES"
            else -> "GENERIC"
        }

        return MarketSeriesMetadata(
            seriesSlugPrefix = seriesSlugPrefix,
            intervalSeconds = intervalSeconds,
            marketSourceType = marketSourceType
        )
    }

    private fun extractIntervalSeconds(value: String?): Int? {
        if (value.isNullOrBlank()) {
            return null
        }
        val normalized = value.lowercase()
        return when {
            normalized.contains("-5m") -> 300
            normalized.contains("-15m") -> 900
            normalized.contains("-1h") -> 3600
            normalized.contains("-4h") -> 14400
            normalized.contains("-1d") -> 86400
            else -> null
        }
    }
}

data class MarketSeriesMetadata(
    val seriesSlugPrefix: String? = null,
    val intervalSeconds: Int? = null,
    val marketSourceType: String = "GENERIC"
)
