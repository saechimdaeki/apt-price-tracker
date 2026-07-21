package me.aptprice.model

import java.time.LocalDateTime

enum class MarketStatus {
    ACTIVE,
    OFF_MARKET_CANDIDATE,
    OFF_MARKET,
    RELISTED
}

data class Listing(
    val entityId: String = "",
    val articleNo: String,
    val hscpNo: String = "",
    val sameAddrHash: String = "",
    val buildingName: String = "",
    val title: String,
    val featureDesc: String = "",
    val tagList: List<String> = emptyList(),
    val regionName: String,
    val price: Long,
    val floor: String,
    val areaSqm: Double, // 하위 호환용(기본 전용면적)
    val areaSupplySqm: Double = 0.0, // 공급면적
    val areaExclusiveSqm: Double = areaSqm, // 전용면적
    val householdCount: Int = 0, // 단지 총세대수 (0=미확인)
    val pyeong: Int,
    val url: String,
    val updatedAt: String = LocalDateTime.now().toString(),
    val firstSeenAt: String = updatedAt,
    val lastSeenAt: String = updatedAt,
    val status: MarketStatus = MarketStatus.ACTIVE,
    val statusChangedAt: String = updatedAt,
    val offMarketAt: String? = null,
    val missCount: Int = 0,
) {
    fun normalizedEntityId(): String = entityId.ifBlank { articleNo }
}

data class TeamsMessage(
    val `@type`: String = "MessageCard",
    val themeColor: String = "0076D7",
    val summary: String,
    val sections: List<Section>,
) {
    data class Section(
        val activityTitle: String,
        val text: String,
        val markdown: Boolean = true,
    )
}
