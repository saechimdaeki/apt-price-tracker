package me.aptprice.util

import me.aptprice.model.Listing
import me.aptprice.model.MarketStatus
import me.aptprice.repository.FileDataRepository
import me.aptprice.service.AbuseBlockedException
import me.aptprice.service.NaverService
import me.aptprice.service.RegionFetchFailedException
import me.aptprice.service.TeamsNotifierService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

@Component
@ConditionalOnProperty(name = ["bot.enabled"], havingValue = "true", matchIfMissing = true)
class BotRunner(
    private val naverService: NaverService, // 서비스 교체
    private val repository: FileDataRepository,
    private val notifier: TeamsNotifierService,
    @Value("\${bot.safe.max-regions-per-run:0}") private val maxRegionsPerRun: Int,
    @Value("\${bot.safe.rotate-start-by-day:true}") private val rotateStartByDay: Boolean,
    @Value("\${bot.safe.start-region-offset:0}") private val startRegionOffset: Int,
    @Value("\${bot.safe.retry-region-after-abuse:true}") private val retryRegionAfterAbuse: Boolean,
    @Value("\${bot.safe.max-abuse-retry-per-region:1}") private val maxAbuseRetryPerRegion: Int,
    @Value("\${bot.safe.max-abuse-wait-ms:480000}") private val maxAbuseWaitMs: Long,
    @Value("\${bot.safe.stop-run-on-abuse:true}") private val stopRunOnAbuse: Boolean,
    @Value("\${bot.safe.replay-abuse-skipped-regions:true}") private val replayAbuseSkippedRegionsEnabled: Boolean,
    @Value("\${bot.safe.max-abuse-replay-rounds:2}") private val maxAbuseReplayRounds: Int,
    @Value("\${bot.safe.region-shard:0}") private val regionShard: Int,
    @Value("\${bot.safe.save-run-progress:true}") private val saveRunProgressEnabled: Boolean,
    @Value("\${bot.safe.region-delay-min-ms:5000}") private val regionDelayMinMs: Long,
    @Value("\${bot.safe.region-delay-max-ms:9000}") private val regionDelayMaxMs: Long,
    @Value("\${bot.safe.max-run-minutes:0}") private val maxRunMinutes: Int,
    @Value("\${bot.market.off-market-confirm-miss-count:3}") private val offMarketConfirmMissCount: Int,
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random(System.currentTimeMillis())

    override fun run(vararg args: String) {
        val targetRegions = listOf(
            // 1) 수원시 (영통구, 광교, 화서)
            mapOf("name" to "수원_매탄동", "code" to "4111710100"),
            mapOf("name" to "수원_영통동", "code" to "4111710500"),
            mapOf("name" to "수원_망포동", "code" to "4111710700"),
            mapOf("name" to "수원_이의동", "code" to "4111710300"),
            mapOf("name" to "수원_하동", "code" to "4111710400"),
            mapOf("name" to "수원_화서동", "code" to "4111513800"),
            mapOf("name" to "수원_천천동", "code" to "4111113300"),

            // 2) 용인시 수지구
            mapOf("name" to "용인수지_풍덕천동", "code" to "4146510100"),
            mapOf("name" to "용인수지_죽전동", "code" to "4146510200"),
            mapOf("name" to "용인수지_동천동", "code" to "4146510300"),
            mapOf("name" to "용인수지_신봉동", "code" to "4146510500"),
            mapOf("name" to "용인수지_성복동", "code" to "4146510600"),
            mapOf("name" to "용인수지_상현동", "code" to "4146510700"),

            // 3) 서울 마용성
            mapOf("name" to "서울마포_아현동", "code" to "1144010100"),
            mapOf("name" to "서울마포_공덕동", "code" to "1144010200"),
            mapOf("name" to "서울마포_상암동", "code" to "1144012700"),
            mapOf("name" to "서울마포_염리동", "code" to "1144010900"),
            mapOf("name" to "서울성동_옥수동", "code" to "1120011300"),
            mapOf("name" to "서울성동_금호동1가", "code" to "1120010900"),
            mapOf("name" to "서울성동_행당동", "code" to "1120010700"),
            mapOf("name" to "서울성동_성수동1가", "code" to "1120011400"),
            mapOf("name" to "서울용산_이촌동", "code" to "1117012900"),

            // 4) 서울 서남권
            mapOf("name" to "서울양천_목동", "code" to "1147010200"),
            mapOf("name" to "서울양천_신정동", "code" to "1147010100"),
            mapOf("name" to "서울강서_마곡동", "code" to "1150010500"),
            mapOf("name" to "서울강서_내발산동", "code" to "1150010600"),
            mapOf("name" to "서울강서_화곡동", "code" to "1150010300"),
            mapOf("name" to "서울영등포_신길동", "code" to "1156013200"),
            mapOf("name" to "서울영등포_문래동3가", "code" to "1156012100"),
            mapOf("name" to "서울영등포_여의도동", "code" to "1156011000"),

            // 5) 서울 남부
            mapOf("name" to "서울동작_흑석동", "code" to "1159010500"),
            mapOf("name" to "서울동작_상도동", "code" to "1159010200"),
            mapOf("name" to "서울동작_사당동", "code" to "1159010700"),
            mapOf("name" to "서울관악_봉천동", "code" to "1162010100"),
            mapOf("name" to "서울구로_신도림동", "code" to "1153010100"),
            mapOf("name" to "서울구로_구로동", "code" to "1153010200"),
            mapOf("name" to "서울구로_개봉동", "code" to "1153010700"),
            mapOf("name" to "서울금천_독산동", "code" to "1154510200"),

            // 6) 서울 동부/서북권
            mapOf("name" to "서울강동_고덕동", "code" to "1174010200"),
            mapOf("name" to "서울강동_명일동", "code" to "1174010100"),
            mapOf("name" to "서울강동_상일동", "code" to "1174010300"),
            mapOf("name" to "서울광진_광장동", "code" to "1121510400"),
            mapOf("name" to "서울광진_자양동", "code" to "1121510500"),
            mapOf("name" to "서울동대문_용두동", "code" to "1123010200"),
            mapOf("name" to "서울동대문_제기동", "code" to "1123010300"),
            mapOf("name" to "서울동대문_전농동", "code" to "1123010400"),
            mapOf("name" to "서울동대문_답십리동", "code" to "1123010500"),
            mapOf("name" to "서울동대문_장안동", "code" to "1123010600"),
            mapOf("name" to "서울동대문_청량리동", "code" to "1123010700"),
            mapOf("name" to "서울동대문_회기동", "code" to "1123010800"),
            mapOf("name" to "서울동대문_휘경동", "code" to "1123010900"),
            mapOf("name" to "서울동대문_이문동", "code" to "1123011000"),
            mapOf("name" to "서울성북_길음동", "code" to "1129013400"),
            mapOf("name" to "서울성북_정릉동", "code" to "1129013300"),
            mapOf("name" to "서울성북_종암동", "code" to "1129013500"),
            mapOf("name" to "서울성북_하월곡동", "code" to "1129013600"),
            mapOf("name" to "서울성북_상월곡동", "code" to "1129013700"),
            mapOf("name" to "서울성북_장위동", "code" to "1129013800"),
            mapOf("name" to "서울서대문_북아현동", "code" to "1141011000"),
            mapOf("name" to "서울서대문_홍제동", "code" to "1141011100"),
            mapOf("name" to "서울서대문_연희동", "code" to "1141011700"),
            mapOf("name" to "서울서대문_홍은동", "code" to "1141011800"),
            mapOf("name" to "서울서대문_남가좌동", "code" to "1141012000"),
            mapOf("name" to "서울서대문_북가좌동", "code" to "1141011900"),
            mapOf("name" to "서울종로_무악동", "code" to "1111018700"),
            mapOf("name" to "서울종로_평동", "code" to "1111017700"),
            mapOf("name" to "서울중구_신당동", "code" to "1114016200"),
            mapOf("name" to "서울중구_중림동", "code" to "1114017100"),
            mapOf("name" to "서울은평_진관동", "code" to "1138011400"),
            mapOf("name" to "서울은평_응암동", "code" to "1138010700"),

            // 7) 성남 전역 (수정/중원/분당)
            mapOf("name" to "성남수정_신흥동", "code" to "4113110100"),
            mapOf("name" to "성남수정_태평동", "code" to "4113110200"),
            mapOf("name" to "성남수정_수진동", "code" to "4113110300"),
            mapOf("name" to "성남수정_단대동", "code" to "4113110400"),
            mapOf("name" to "성남수정_산성동", "code" to "4113110500"),
            mapOf("name" to "성남수정_양지동", "code" to "4113110600"),
            mapOf("name" to "성남수정_복정동", "code" to "4113110700"),
            mapOf("name" to "성남수정_창곡동", "code" to "4113110800"),
            mapOf("name" to "성남수정_신촌동", "code" to "4113110900"),
            mapOf("name" to "성남수정_오야동", "code" to "4113111000"),
            mapOf("name" to "성남수정_심곡동", "code" to "4113111100"),
            mapOf("name" to "성남수정_고등동", "code" to "4113111200"),
            mapOf("name" to "성남수정_상적동", "code" to "4113111300"),
            mapOf("name" to "성남수정_둔전동", "code" to "4113111400"),
            mapOf("name" to "성남수정_시흥동", "code" to "4113111500"),
            mapOf("name" to "성남수정_금토동", "code" to "4113111600"),
            mapOf("name" to "성남수정_사송동", "code" to "4113111700"),

            mapOf("name" to "성남중원_성남동", "code" to "4113310100"),
            mapOf("name" to "성남중원_금광동", "code" to "4113310300"),
            mapOf("name" to "성남중원_은행동", "code" to "4113310400"),
            mapOf("name" to "성남중원_상대원동", "code" to "4113310500"),
            mapOf("name" to "성남중원_여수동", "code" to "4113310600"),
            mapOf("name" to "성남중원_도촌동", "code" to "4113310700"),
            mapOf("name" to "성남중원_갈현동", "code" to "4113310800"),
            mapOf("name" to "성남중원_하대원동", "code" to "4113310900"),
            mapOf("name" to "성남중원_중앙동", "code" to "4113313200"),

            mapOf("name" to "성남분당_분당동", "code" to "4113510100"),
            mapOf("name" to "성남분당_수내동", "code" to "4113510200"),
            mapOf("name" to "성남분당_정자동", "code" to "4113510300"),
            mapOf("name" to "성남분당_율동", "code" to "4113510400"),
            mapOf("name" to "성남분당_서현동", "code" to "4113510500"),
            mapOf("name" to "성남분당_이매동", "code" to "4113510600"),
            mapOf("name" to "성남분당_야탑동", "code" to "4113510700"),
            mapOf("name" to "성남판교_판교동", "code" to "4113510800"),
            mapOf("name" to "성남판교_삼평동", "code" to "4113510900"),
            mapOf("name" to "성남판교_백현동", "code" to "4113511000"),
            mapOf("name" to "성남분당_금곡동", "code" to "4113511100"),
            mapOf("name" to "성남분당_궁내동", "code" to "4113511200"),
            mapOf("name" to "성남분당_동원동", "code" to "4113511300"),
            mapOf("name" to "성남분당_구미동", "code" to "4113511400"),
            mapOf("name" to "성남판교_운중동", "code" to "4113511500"),
            mapOf("name" to "성남판교_대장동", "code" to "4113511600"),
            mapOf("name" to "성남판교_석운동", "code" to "4113511700"),
            mapOf("name" to "성남판교_하산운동", "code" to "4113511800"),

            // 8) 안양권
            mapOf("name" to "안양_안양동", "code" to "4117110100"),
            mapOf("name" to "안양_비산동", "code" to "4117310100"),
            mapOf("name" to "안양_관양동", "code" to "4117310200"),
            mapOf("name" to "안양_평촌동", "code" to "4117310300"),
            mapOf("name" to "안양_호계동", "code" to "4117310400"),

            // 9) 서울 강남3구 (강남/서초/송파)
            mapOf("name" to "서울강남_역삼동", "code" to "1168010100"),
            mapOf("name" to "서울강남_개포동", "code" to "1168010300"),
            mapOf("name" to "서울강남_청담동", "code" to "1168010400"),
            mapOf("name" to "서울강남_삼성동", "code" to "1168010500"),
            mapOf("name" to "서울강남_대치동", "code" to "1168010600"),
            mapOf("name" to "서울강남_신사동", "code" to "1168010700"),
            mapOf("name" to "서울강남_논현동", "code" to "1168010800"),
            mapOf("name" to "서울강남_압구정동", "code" to "1168011000"),
            mapOf("name" to "서울강남_세곡동", "code" to "1168011100"),
            mapOf("name" to "서울강남_자곡동", "code" to "1168011200"),
            mapOf("name" to "서울강남_율현동", "code" to "1168011300"),
            mapOf("name" to "서울강남_일원동", "code" to "1168011400"),
            mapOf("name" to "서울강남_수서동", "code" to "1168011500"),
            mapOf("name" to "서울강남_도곡동", "code" to "1168011800"),

            mapOf("name" to "서울서초_방배동", "code" to "1165010100"),
            mapOf("name" to "서울서초_양재동", "code" to "1165010200"),
            mapOf("name" to "서울서초_우면동", "code" to "1165010300"),
            mapOf("name" to "서울서초_원지동", "code" to "1165010400"),
            mapOf("name" to "서울서초_잠원동", "code" to "1165010600"),
            mapOf("name" to "서울서초_반포동", "code" to "1165010700"),
            mapOf("name" to "서울서초_서초동", "code" to "1165010800"),
            mapOf("name" to "서울서초_내곡동", "code" to "1165010900"),
            mapOf("name" to "서울서초_염곡동", "code" to "1165011000"),
            mapOf("name" to "서울서초_신원동", "code" to "1165011100"),

            mapOf("name" to "서울송파_잠실동", "code" to "1171010100"),
            mapOf("name" to "서울송파_신천동", "code" to "1171010200"),
            mapOf("name" to "서울송파_풍납동", "code" to "1171010300"),
            mapOf("name" to "서울송파_송파동", "code" to "1171010400"),
            mapOf("name" to "서울송파_석촌동", "code" to "1171010500"),
            mapOf("name" to "서울송파_삼전동", "code" to "1171010600"),
            mapOf("name" to "서울송파_가락동", "code" to "1171010700"),
            mapOf("name" to "서울송파_문정동", "code" to "1171010800"),
            mapOf("name" to "서울송파_장지동", "code" to "1171010900"),
            mapOf("name" to "서울송파_방이동", "code" to "1171011100"),
            mapOf("name" to "서울송파_오금동", "code" to "1171011200"),
            mapOf("name" to "서울송파_거여동", "code" to "1171011300"),
            mapOf("name" to "서울송파_마천동", "code" to "1171011400")
        )

        val normalizedRegionShard = if (regionShard in 1..9) regionShard else 0
        val shardTargetRegions = targetRegions.filterIndexed { index, _ ->
            normalizedRegionShard == 0 || regionGroupByIndex(index) == normalizedRegionShard
        }
        if (shardTargetRegions.isEmpty()) {
            log.warn("실행 대상 지역이 없습니다. region-shard={}, totalRegions={}", normalizedRegionShard, targetRegions.size)
            return
        }

        val totalRegions = shardTargetRegions.size
        val baseOffset = if (rotateStartByDay) LocalDate.now().dayOfYear % totalRegions else 0
        val effectiveOffset = ((baseOffset + startRegionOffset) % totalRegions + totalRegions) % totalRegions
        val orderedRegions = if (effectiveOffset == 0) {
            shardTargetRegions
        } else {
            shardTargetRegions.drop(effectiveOffset) + shardTargetRegions.take(effectiveOffset)
        }

        val runToTailOnly = maxRegionsPerRun <= 0 && !rotateStartByDay && effectiveOffset > 0
        val runLimit = when {
            runToTailOnly -> totalRegions - effectiveOffset
            maxRegionsPerRun <= 0 -> totalRegions
            maxRegionsPerRun >= totalRegions -> totalRegions
            else -> maxRegionsPerRun
        }
        val runRegions = orderedRegions.take(runLimit)
        log.info(
            "=== [네이버 모바일] 아파트 매매용 매물 감시 가동 (shard: {}, 전체: {}개 동, 이번 실행: {}개 동, 시작 오프셋: {}) ===",
            if (normalizedRegionShard == 0) "ALL" else normalizedRegionShard,
            totalRegions,
            runRegions.size,
            effectiveOffset
        )

        val oldData = repository.loadAll()
        val allNewListings = mutableListOf<Listing>()
        val successfulRegions = mutableSetOf<String>()
        var blockedByAbuse = false
        var abortedByAbuse = false
        var stoppedByTimeBudget = false
        var stoppedByDataIssue = false
        var blockedRegionName: String? = null
        var nextStartOffset = effectiveOffset
        var lastRegionName = ""
        val abuseSkippedRegionQueue = linkedMapOf<String, Map<String, String>>()
        val runStartedAt = System.currentTimeMillis()
        val maxRunMillis = maxRunMinutes.coerceAtLeast(0).toLong() * 60_000L

        fun elapsedMillis(): Long = System.currentTimeMillis() - runStartedAt
        fun isTimeBudgetExceeded(): Boolean = maxRunMillis > 0 && elapsedMillis() >= maxRunMillis
        fun timeBudgetLogText(): String {
            if (maxRunMillis <= 0) return "무제한"
            val elapsedSec = elapsedMillis() / 1000
            val limitSec = maxRunMillis / 1000
            return "${elapsedSec}s/${limitSec}s"
        }

        fun persistRunProgress(reason: String, blockedRegion: String? = null) {
            if (!saveRunProgressEnabled || totalRegions <= 0) return
            repository.saveRunProgress(
                FileDataRepository.RunProgress(
                    nextStartRegionOffset = ((nextStartOffset % totalRegions) + totalRegions) % totalRegions,
                    totalRegions = totalRegions,
                    lastRunAt = LocalDateTime.now().toString(),
                    lastRegionName = lastRegionName,
                    reason = reason,
                    blockedRegionName = blockedRegion
                )
            )
        }

        fun saveCheckpoint(listings: List<Listing>, regionName: String): CheckpointSaveResult {
            val filtered = listings.filter { it.pyeong in 20..39 }
            if (filtered.isEmpty()) {
                val acceptEmptyResult = RegionResultGuard.shouldAccept(regionName, oldData, listings)
                if (!acceptEmptyResult) {
                    log.error(
                        "{} 수집 결과가 비정상적으로 비어 있어 성공 처리하지 않습니다. rawListings={}, targetPyeongListings={}, 기존추적매물존재=true",
                        regionName,
                        listings.size,
                        filtered.size
                    )
                    return CheckpointSaveResult.SUSPICIOUS_EMPTY
                }
                log.warn(
                    "{} 수집 결과가 비어 있어 기존 데이터를 유지합니다. rawListings={}, targetPyeongListings={}",
                    regionName,
                    listings.size,
                    filtered.size
                )
                return CheckpointSaveResult.EMPTY_ACCEPTED
            }

            allNewListings.addAll(filtered)
            successfulRegions.add(regionName)

            val checkpointNow = LocalDateTime.now().toString()
            val checkpoint = buildMergeResult(oldData, allNewListings, successfulRegions, checkpointNow)
            repository.saveAll(checkpoint.mergedListings)
            log.info(
                "체크포인트 저장 - 완료 지역: {}개, 보존 지역: {}개, 이번 노출: {}건, 중간 저장: {}건",
                successfulRegions.size,
                (runRegions.size - successfulRegions.size).coerceAtLeast(0),
                checkpoint.newVisibleCount,
                checkpoint.mergedListings.size
            )
            return CheckpointSaveResult.SAVED
        }

        fun waitBeforeNextRegion(shouldWait: Boolean): Boolean {
            if (!shouldWait) return true
            if (isTimeBudgetExceeded()) return false
            var delayMillis = nextRegionDelayMillis()
            if (maxRunMillis > 0) {
                val remaining = (maxRunMillis - elapsedMillis()).coerceAtLeast(0L)
                delayMillis = delayMillis.coerceAtMost(remaining)
            }
            log.info("다음 지역 수집 전 {}ms 대기", delayMillis)
            if (delayMillis > 0) {
                Thread.sleep(delayMillis)
            }
            return !isTimeBudgetExceeded()
        }

        mainLoop@ for ((index, region) in runRegions.withIndex()) {
            if (isTimeBudgetExceeded()) {
                stoppedByTimeBudget = true
                log.warn(
                    "실행 시간 예산 도달로 조기 종료합니다. 다음 오프셋: {}, 경과/제한: {}",
                    nextStartOffset,
                    timeBudgetLogText()
                )
                break@mainLoop
            }
            val regionName = region["name"]!!
            val regionCode = region["code"]!!
            val regionAbsoluteIndex = (effectiveOffset + index) % totalRegions
            lastRegionName = regionName
            when (val outcome = fetchRegionWithCooldownRetry(regionName, regionCode)) {
                is RegionFetchOutcome.Success -> {
                    when (saveCheckpoint(outcome.listings, regionName)) {
                        CheckpointSaveResult.SAVED,
                        CheckpointSaveResult.EMPTY_ACCEPTED -> {
                            nextStartOffset = (regionAbsoluteIndex + 1) % totalRegions
                            persistRunProgress("RUNNING")
                        }
                        CheckpointSaveResult.SUSPICIOUS_EMPTY -> {
                            // 한 지역의 이상 데이터가 전체 런을 막지 않도록 해당 지역만 건너뛰고 계속 진행
                            stoppedByDataIssue = true
                            blockedRegionName = regionName
                            nextStartOffset = (regionAbsoluteIndex + 1) % totalRegions
                            persistRunProgress("SUSPICIOUS_EMPTY_SKIPPED", regionName)
                            log.error("{} 수집 결과가 의심스러워 이 지역만 건너뛰고(기존 데이터 유지) 다음 지역을 계속 수집합니다.", regionName)
                        }
                    }
                }
                is RegionFetchOutcome.AbuseSkipped -> {
                    blockedByAbuse = true
                    abuseSkippedRegionQueue[regionName] = region
                    log.warn("{} 수집 스킵(차단): {}", regionName, outcome.reason)
                    if (stopRunOnAbuse) {
                        abortedByAbuse = true
                        blockedRegionName = regionName
                        // 다음 실행은 차단된 동일 지역부터 재개
                        nextStartOffset = regionAbsoluteIndex
                        persistRunProgress("ABUSE_ABORTED", blockedRegionName)
                        break@mainLoop
                    } else {
                        nextStartOffset = (regionAbsoluteIndex + 1) % totalRegions
                        persistRunProgress("RUNNING")
                    }
                }
                is RegionFetchOutcome.Failed -> {
                    log.warn("{} 수집 실패(기존 데이터 유지): {}", regionName, outcome.reason)
                    stoppedByDataIssue = true
                    blockedRegionName = regionName
                    nextStartOffset = (regionAbsoluteIndex + 1) % totalRegions
                    persistRunProgress("FETCH_FAILED_SKIPPED", regionName)
                    log.error("{} 수집 실패로 이 지역만 건너뛰고 다음 지역을 계속 수집합니다.", regionName)
                }
            }
            if (!waitBeforeNextRegion(index < runRegions.lastIndex)) {
                stoppedByTimeBudget = true
                log.warn(
                    "실행 시간 예산 도달로 조기 종료합니다. 다음 오프셋: {}, 경과/제한: {}",
                    nextStartOffset,
                    timeBudgetLogText()
                )
                break@mainLoop
            }
        }

        if (!stopRunOnAbuse && replayAbuseSkippedRegionsEnabled && abuseSkippedRegionQueue.isNotEmpty()) {
            val replayRounds = maxAbuseReplayRounds.coerceAtLeast(0)
            for (round in 1..replayRounds) {
                if (abuseSkippedRegionQueue.isEmpty()) break
                if (isTimeBudgetExceeded()) {
                    stoppedByTimeBudget = true
                    log.warn(
                        "재수집 단계에서 실행 시간 예산 도달로 조기 종료합니다. 다음 오프셋: {}, 경과/제한: {}",
                        nextStartOffset,
                        timeBudgetLogText()
                    )
                    break
                }

                val replayTargets = abuseSkippedRegionQueue.values.toList()
                abuseSkippedRegionQueue.clear()
                log.warn("abuse 차단 스킵 지역 재수집 {}차 시작: {}개", round, replayTargets.size)

                for ((index, region) in replayTargets.withIndex()) {
                    if (isTimeBudgetExceeded()) {
                        stoppedByTimeBudget = true
                        log.warn(
                            "재수집 단계에서 실행 시간 예산 도달로 조기 종료합니다. 다음 오프셋: {}, 경과/제한: {}",
                            nextStartOffset,
                            timeBudgetLogText()
                        )
                        break
                    }
                    val regionName = region["name"]!!
                    val regionCode = region["code"]!!
                    when (val outcome = fetchRegionWithCooldownRetry(regionName, regionCode)) {
                        is RegionFetchOutcome.Success -> {
                            when (saveCheckpoint(outcome.listings, regionName)) {
                                CheckpointSaveResult.SAVED,
                                CheckpointSaveResult.EMPTY_ACCEPTED -> {
                                    log.info("{} 재수집 성공({}/{})", regionName, round, replayRounds)
                                }
                                CheckpointSaveResult.SUSPICIOUS_EMPTY -> {
                                    log.error("{} 재수집도 비정상 빈 결과라 재수집 큐에 다시 넣습니다.", regionName)
                                    abuseSkippedRegionQueue[regionName] = region
                                    stoppedByDataIssue = true
                                    blockedRegionName = regionName
                                }
                            }
                        }
                        is RegionFetchOutcome.AbuseSkipped -> {
                            blockedByAbuse = true
                            abuseSkippedRegionQueue[regionName] = region
                            log.warn("{} 재수집 스킵(차단, {}/{}): {}", regionName, round, replayRounds, outcome.reason)
                        }
                        is RegionFetchOutcome.Failed -> {
                            log.warn("{} 재수집 실패(기존 데이터 유지): {}", regionName, outcome.reason)
                            abuseSkippedRegionQueue[regionName] = region
                            stoppedByDataIssue = true
                            blockedRegionName = regionName
                        }
                    }
                    if (!waitBeforeNextRegion(index < replayTargets.lastIndex)) {
                        stoppedByTimeBudget = true
                        log.warn(
                            "재수집 단계에서 실행 시간 예산 도달로 조기 종료합니다. 다음 오프셋: {}, 경과/제한: {}",
                            nextStartOffset,
                            timeBudgetLogText()
                        )
                        break
                    }
                }
                if (stoppedByTimeBudget) break
            }
        }

        if (successfulRegions.isEmpty()) {
            if (blockedByAbuse) {
                log.warn("abuse 차단으로 성공한 지역이 없어 기존 JSON 데이터를 유지합니다.")
                if (abortedByAbuse) {
                    persistRunProgress("ABUSE_ABORTED", blockedRegionName)
                } else {
                    persistRunProgress("ABUSE_NO_SUCCESS", blockedRegionName)
                }
            } else if (stoppedByDataIssue) {
                log.warn("데이터 이상/수집 실패로 성공한 지역 없이 종료합니다. 다음 시작 오프셋: {}", nextStartOffset)
                persistRunProgress("DATA_ISSUE_NO_SUCCESS", blockedRegionName)
            } else if (stoppedByTimeBudget) {
                log.warn("실행 시간 예산 도달로 성공 지역 없이 종료합니다. 다음 시작 오프셋: {}", nextStartOffset)
                persistRunProgress("TIME_BUDGET_NO_SUCCESS")
            } else {
                log.warn("성공한 지역이 없어 기존 JSON 데이터를 유지합니다.")
                persistRunProgress("NO_SUCCESS")
            }
            return
        }

        val now = LocalDateTime.now().toString()
        val mergeResult = buildMergeResult(oldData, allNewListings, successfulRegions, now)
        if (mergeResult.notifyList.isNotEmpty()) {
            notifier.sendGroupedNotification(mergeResult.notifyList)
        }

        repository.saveAll(mergeResult.mergedListings)
        val activeCount = mergeResult.mergedListings.count { it.status == MarketStatus.ACTIVE || it.status == MarketStatus.RELISTED }
        val candidateCount = mergeResult.mergedListings.count { it.status == MarketStatus.OFF_MARKET_CANDIDATE }
        val offMarketCount = mergeResult.mergedListings.count { it.status == MarketStatus.OFF_MARKET }
        log.info(
            "저장 완료 - 성공 지역: {}개, 보존 지역: {}개, 이번 노출: {}건, 최종 저장: {}건",
            successfulRegions.size,
            (runRegions.size - successfulRegions.size).coerceAtLeast(0),
            mergeResult.newVisibleCount,
            mergeResult.mergedListings.size
        )
        log.info(
            "상태 요약 - ACTIVE/RELISTED: {}건, OFF_MARKET_CANDIDATE: {}건, OFF_MARKET: {}건",
            activeCount,
            candidateCount,
            offMarketCount
        )
        log.info(
            "상태 전환 - 거래종결 후보 전환: {}건, 거래종결 추정 전환: {}건, 다시 등록된 매물 전환: {}건",
            mergeResult.offMarketCandidateChanged,
            mergeResult.offMarketChanged,
            mergeResult.relistedChanged
        )
        val abuseSkippedRegions = abuseSkippedRegionQueue.size
        if (abuseSkippedRegions > 0) {
            log.warn("이번 실행에서 abuse 차단으로 스킵된 지역: {}개", abuseSkippedRegions)
        }
        if (abortedByAbuse) {
            log.warn("abuse 감지로 실행을 조기 종료했습니다. 다음 시작 오프셋: {}", nextStartOffset)
            persistRunProgress("ABUSE_ABORTED", blockedRegionName)
        } else if (stoppedByDataIssue) {
            log.warn("일부 지역을 데이터 이상/수집 실패로 건너뛰었습니다. 다음 시작 오프셋: {}", nextStartOffset)
            persistRunProgress("DATA_ISSUE", blockedRegionName)
        } else if (stoppedByTimeBudget) {
            log.warn("실행 시간 예산 도달로 조기 종료했습니다. 다음 시작 오프셋: {}", nextStartOffset)
            persistRunProgress("TIME_BUDGET")
        } else if (abuseSkippedRegions > 0) {
            persistRunProgress("PARTIAL")
        } else {
            persistRunProgress("SUCCESS")
        }
    }

    private fun fetchRegionWithCooldownRetry(regionName: String, regionCode: String): RegionFetchOutcome {
        var abuseAttempt = 0
        while (true) {
            try {
                return RegionFetchOutcome.Success(naverService.fetchListings(regionName, regionCode))
            } catch (e: AbuseBlockedException) {
                val remainMs = naverService.abuseCooldownRemainingMillis()
                val allowRetry = retryRegionAfterAbuse &&
                    abuseAttempt < maxAbuseRetryPerRegion.coerceAtLeast(0) &&
                    remainMs > 0

                if (allowRetry) {
                    val waitMs = remainMs.coerceAtMost(maxAbuseWaitMs.coerceAtLeast(0L))
                    log.warn(
                        "{} 차단 감지: {}ms 대기 후 재시도 ({}/{})",
                        regionName,
                        waitMs,
                        abuseAttempt + 1,
                        maxAbuseRetryPerRegion + 1
                    )
                    if (waitMs > 0) {
                        Thread.sleep(waitMs)
                    }
                    abuseAttempt += 1
                    continue
                }

                return RegionFetchOutcome.AbuseSkipped(e.message ?: "abuse 차단")
            } catch (e: RegionFetchFailedException) {
                return RegionFetchOutcome.Failed(e.message ?: "지역 수집 실패")
            }
        }
    }

    private fun nextRegionDelayMillis(): Long {
        val delayRange = normalizedDelayRange(regionDelayMinMs, regionDelayMaxMs, 3_000L, 7_000L)
        return random.nextLong(delayRange.first, delayRange.second + 1)
    }

    private fun buildMergeResult(
        oldData: Map<String, Listing>,
        allNewListings: List<Listing>,
        successfulRegions: Set<String>,
        now: String,
    ): MergeResult =
        ListingStatusMerger.merge(
            oldData = oldData,
            allNewListings = allNewListings,
            successfulRegions = successfulRegions,
            now = now,
            offMarketConfirmMissCount = offMarketConfirmMissCount
        )

    private fun normalizedDelayRange(minMs: Long, maxMs: Long, defaultMinMs: Long, defaultMaxMs: Long): Pair<Long, Long> {
        if (minMs <= 0L && maxMs <= 0L) {
            return 0L to 0L
        }
        val min = if (minMs > 0) minMs else defaultMinMs
        val max = if (maxMs >= min) maxMs else defaultMaxMs.coerceAtLeast(min)
        return min to max
    }

    private fun regionGroupByIndex(index: Int): Int = when (index) {
        in 0..6 -> 1
        in 7..12 -> 2
        in 13..21 -> 3
        in 22..29 -> 4
        in 30..37 -> 5
        in 38..69 -> 6
        in 70..113 -> 7
        in 114..118 -> 8
        else -> 9
    }

    private sealed class RegionFetchOutcome {
        data class Success(val listings: List<Listing>) : RegionFetchOutcome()
        data class AbuseSkipped(val reason: String) : RegionFetchOutcome()
        data class Failed(val reason: String) : RegionFetchOutcome()
    }

    private enum class CheckpointSaveResult {
        SAVED,
        EMPTY_ACCEPTED,
        SUSPICIOUS_EMPTY
    }
}
