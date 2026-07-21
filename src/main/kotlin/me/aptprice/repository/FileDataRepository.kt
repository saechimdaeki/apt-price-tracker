package me.aptprice.repository

import me.aptprice.model.Listing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Paths

@Repository
class FileDataRepository(
    private val objectMapper: ObjectMapper,
    @Value("\${bot.safe.region-shard:0}") private val regionShard: Int,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private fun shardSuffix(): String = if (regionShard in 1..10) "-s$regionShard" else ""
    private fun shardLabel(): String = if (regionShard in 1..10) "S$regionShard" else "ALL"
    private fun listingPath() = Paths.get("data/apt-listings${shardSuffix()}.json")
    private fun runProgressPath() = Paths.get("data/run-progress${shardSuffix()}.json")

    fun loadAll(): Map<String, Listing> {
        val filePath = listingPath()
        if (!Files.exists(filePath)) return emptyMap()
        val list: List<Listing> = objectMapper.readValue(filePath.toFile())
        return list.associateBy { it.normalizedEntityId() }
    }

    fun saveAll(listings: List<Listing>) {
        val filePath = listingPath()
        if (!Files.exists(filePath.parent)) Files.createDirectories(filePath.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), listings)
        log.info("JSON 저장 완료 ({}건, shard={}, path={})", listings.size, shardLabel(), filePath)
    }

    fun loadRunProgress(): RunProgress? {
        val runProgressPath = runProgressPath()
        if (!Files.exists(runProgressPath)) return null
        return runCatching { objectMapper.readValue<RunProgress>(runProgressPath.toFile()) }
            .onFailure { log.warn("run-progress 로드 실패: {}", it.message) }
            .getOrNull()
    }

    fun saveRunProgress(progress: RunProgress) {
        val runProgressPath = runProgressPath()
        if (!Files.exists(runProgressPath.parent)) Files.createDirectories(runProgressPath.parent)
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(runProgressPath.toFile(), progress)
        log.info(
            "run-progress 저장 완료 (nextOffset={}, reason={}, blockedRegion={}, shard={}, path={})",
            progress.nextStartRegionOffset,
            progress.reason,
            progress.blockedRegionName ?: "-",
            shardLabel(),
            runProgressPath
        )
    }

    data class RunProgress(
        val nextStartRegionOffset: Int = 0,
        val totalRegions: Int = 0,
        val lastRunAt: String = "",
        val lastRegionName: String = "",
        val reason: String = "UNKNOWN",
        val blockedRegionName: String? = null,
    )
}
