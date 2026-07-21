package me.aptprice.service

import me.aptprice.model.Listing
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

class AbuseBlockedException(message: String) : RuntimeException(message)
class RegionFetchFailedException(message: String) : RuntimeException(message)

@Service
class NaverService(
    private val objectMapper: ObjectMapper,
    private val browserArticleFetcher: BrowserArticleFetcher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = Random(System.currentTimeMillis())
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .version(HttpClient.Version.HTTP_1_1)
        .build()
    @Value("\${naver.safe.max-attempts:2}")
    private var maxAttempts: Int = 2

    @Value("\${naver.safe.base-backoff-ms:2000}")
    private var baseBackoffMs: Long = 2_000L

    @Value("\${naver.safe.backoff-jitter-ms:1500}")
    private var backoffJitterMs: Long = 1_500L

    @Value("\${naver.safe.max-complex-pages:1}")
    private var maxComplexPages: Int = 1

    @Value("\${naver.safe.max-complex-pages-on-overflow:3}")
    private var maxComplexPagesOnOverflow: Int = 3

    @Value("\${naver.safe.max-overflow-complexes-per-region:8}")
    private var maxOverflowComplexesPerRegion: Int = 8

    @Value("\${naver.safe.overflow-batch-size:2}")
    private var overflowBatchSize: Int = 2

    @Value("\${naver.safe.overflow-batch-cooldown-min-ms:20000}")
    private var overflowBatchCooldownMinMs: Long = 20_000L

    @Value("\${naver.safe.overflow-batch-cooldown-max-ms:35000}")
    private var overflowBatchCooldownMaxMs: Long = 35_000L

    @Value("\${naver.safe.article-order:prc,date}")
    private var articleOrder: String = "prc,date"

    @Value("\${naver.safe.max-complexes-per-region:35}")
    private var maxComplexesPerRegion: Int = 35

    @Value("\${naver.safe.rotate-complexes-by-day:true}")
    private var rotateComplexesByDay: Boolean = true

    @Value("\${naver.safe.complex-delay-min-ms:1200}")
    private var complexDelayMinMs: Long = 1_200L

    @Value("\${naver.safe.complex-delay-max-ms:2600}")
    private var complexDelayMaxMs: Long = 2_600L

    @Value("\${naver.safe.complex-batch-size:6}")
    private var complexBatchSize: Int = 6

    @Value("\${naver.safe.batch-cooldown-min-ms:8000}")
    private var batchCooldownMinMs: Long = 8_000L

    @Value("\${naver.safe.batch-cooldown-max-ms:15000}")
    private var batchCooldownMaxMs: Long = 15_000L

    @Value("\${naver.safe.overflow-complex-delay-min-ms:2600}")
    private var overflowComplexDelayMinMs: Long = 2_600L

    @Value("\${naver.safe.overflow-complex-delay-max-ms:5200}")
    private var overflowComplexDelayMaxMs: Long = 5_200L

    @Value("\${naver.safe.page-delay-min-ms:600}")
    private var pageDelayMinMs: Long = 600L

    @Value("\${naver.safe.page-delay-max-ms:1500}")
    private var pageDelayMaxMs: Long = 1_500L

    @Value("\${naver.safe.request-timeout-ms:20000}")
    private var requestTimeoutMs: Long = 20_000L

    @Value("\${naver.safe.abuse-cooldown-minutes:30}")
    private var abuseCooldownMinutes: Long = 30L

    @Volatile
    private var blockedUntilEpochMillis: Long = 0

    fun abuseCooldownRemainingMillis(nowMillis: Long = System.currentTimeMillis()): Long =
        (blockedUntilEpochMillis - nowMillis).coerceAtLeast(0L)

    fun fetchListings(regionName: String, cortarNo: String): List<Listing> {
        val now = System.currentTimeMillis()
        if (now < blockedUntilEpochMillis) {
            val remainSec = ((blockedUntilEpochMillis - now) / 1000).coerceAtLeast(1)
            throw AbuseBlockedException("abuse 차단 쿨다운 중 (${remainSec}초 남음)")
        }

        log.info("{} 수집 시작 (fin.land 브라우저)", regionName)

        val complexes = fetchComplexes(regionName, cortarNo)
        if (complexes.isEmpty()) {
            log.warn("{} 단지 목록 없음", regionName)
            return emptyList()
        }

        val householdCounts = fetchHouseholdCounts(regionName, cortarNo)
        val enrichedComplexes = complexes.map { it.copy(totHsehCnt = householdCounts[it.hscpNo] ?: 0) }

        val runComplexes = selectRunComplexes(regionName, enrichedComplexes)
        val listings = mutableListOf<Listing>()
        var overflowExpandedComplexes = 0
        var overflowCapLogged = false
        for ((index, complex) in runComplexes.withIndex()) {
            val allowOverflowExpansion = maxOverflowComplexesPerRegion <= 0 ||
                overflowExpandedComplexes < maxOverflowComplexesPerRegion
            if (!allowOverflowExpansion && !overflowCapLogged) {
                overflowCapLogged = true
                log.info(
                    "{} 지역은 과도한 요청 방지를 위해 페이지 확장 단지 상한({})을 적용합니다.",
                    regionName,
                    maxOverflowComplexesPerRegion
                )
            }

            val articleResult = fetchComplexArticles(regionName, complex, allowOverflowExpansion)
            listings.addAll(articleResult.listings)
            if (articleResult.overflowExpanded) {
                overflowExpandedComplexes += 1
            }

            if (articleResult.blockedByAbuse) {
                log.warn("{} 지역 수집 중 abuse 차단이 감지되어 이번 지역 결과를 폐기하고 수집을 중단합니다.", regionName)
                throw AbuseBlockedException("${regionName} 지역 수집 중 abuse 차단 감지")
            }

            if (index < runComplexes.lastIndex) {
                val delayMillis = if (articleResult.overflowExpanded) {
                    randomDelayMs(overflowComplexDelayMinMs, overflowComplexDelayMaxMs, 2_600L, 5_200L)
                } else {
                    randomDelayMs(complexDelayMinMs, complexDelayMaxMs, 4_000L, 9_000L)
                }
                Thread.sleep(delayMillis)

                if (complexBatchSize > 0 && (index + 1) % complexBatchSize == 0) {
                    val batchDelay = randomDelayMs(batchCooldownMinMs, batchCooldownMaxMs, 8_000L, 15_000L)
                    log.info(
                        "{} 지역 배치 쿨다운 {}ms ({}개 단지 처리)",
                        regionName,
                        batchDelay,
                        index + 1
                    )
                    Thread.sleep(batchDelay)
                }

                if (articleResult.overflowExpanded &&
                    overflowBatchSize > 0 &&
                    overflowExpandedComplexes > 0 &&
                    overflowExpandedComplexes % overflowBatchSize == 0
                ) {
                    val overflowBatchDelay = randomDelayMs(
                        overflowBatchCooldownMinMs,
                        overflowBatchCooldownMaxMs,
                        20_000L,
                        35_000L
                    )
                    log.info(
                        "{} 지역 overflow 쿨다운 {}ms (확장 단지 {}개 처리)",
                        regionName,
                        overflowBatchDelay,
                        overflowExpandedComplexes
                    )
                    Thread.sleep(overflowBatchDelay)
                }
            }
        }

        val deduped = listings
            .groupBy { dedupKey(it) }
            .mapNotNull { (_, items) ->
                items.minWithOrNull(
                    compareBy<Listing> { it.price }
                        .thenByDescending { it.updatedAt }
                        .thenBy { it.articleNo }
                )
            }

        log.info(
            "{} 수집 성공 - 단지 전체: {}개, 수집 대상 단지: {}개, 매물: {}건",
            regionName,
            complexes.size,
            runComplexes.size,
            deduped.size
        )
        return deduped
    }

    private fun fetchComplexes(regionName: String, cortarNo: String): List<ComplexInfo> {
        val url = "https://m.land.naver.com/complex/ajax/complexListByCortarNo?cortarNo=$cortarNo"
        val response = requestBodyWithRetry(url, "https://m.land.naver.com/")
        if (response.blockedByAbuse) {
            throw AbuseBlockedException("${regionName} 단지 목록 조회가 abuse 차단으로 중단됨")
        }
        if (response.timedOut) {
            throw RegionFetchFailedException("${regionName} 단지 목록 조회 타임아웃")
        }
        val body = response.body ?: throw RegionFetchFailedException("${regionName} 단지 목록 응답 없음")

        val root = runCatching { objectMapper.readTree(body) }.getOrElse {
            throw RegionFetchFailedException("${regionName} 단지 목록 파싱 실패: ${it.message}")
        }

        val result = root.get("result") ?: throw RegionFetchFailedException("${regionName} 단지 목록 result 필드 없음")
        return result.mapNotNull { node ->
            val hscpNo = node.get("hscpNo").textOrEmpty().trim()
            val hscpNm = node.get("hscpNm").textOrEmpty().trim()
            val hscpTypeCd = node.get("hscpTypeCd").textOrEmpty().trim()
            if (hscpNo.isBlank() || hscpNm.isBlank()) return@mapNotNull null
            if (hscpTypeCd != "A01") return@mapNotNull null // 아파트만 수집
            ComplexInfo(hscpNo = hscpNo, hscpNm = hscpNm)
        }
    }

    // 단지별 총세대수는 목록 API에 없어서 cluster 목록 API(페이지당 20개)로 별도 조회한다.
    // 세대수는 부가 정보라 조회 실패 시 그때까지 수집한 값만 사용하고 수집 자체는 계속 진행한다.
    private fun fetchHouseholdCounts(regionName: String, cortarNo: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        var page = 1
        while (page <= MAX_HOUSEHOLD_PAGES) {
            val url = "https://m.land.naver.com/cluster/ajax/complexList" +
                "?cortarNo=$cortarNo&rletTpCd=APT&tradTpCd=A1&page=$page"
            val response = requestBodyWithRetry(url, "https://m.land.naver.com/")
            if (response.blockedByAbuse) {
                throw AbuseBlockedException("${regionName} 세대수 조회가 abuse 차단으로 중단됨")
            }
            val body = response.body ?: break
            val root = runCatching { objectMapper.readTree(body) }.getOrNull() ?: break
            val result = root.get("result")
            if (result == null || !result.isArray) break
            result.forEach { node ->
                val hscpNo = node.get("hscpNo").textOrEmpty().trim()
                val totHsehCnt = node.get("totHsehCnt")?.asInt(0) ?: 0
                if (hscpNo.isNotBlank() && totHsehCnt > 0) counts[hscpNo] = totHsehCnt
            }
            val hasMore = root.get("more")?.asBoolean(false) ?: false
            if (!hasMore) break
            page += 1
            Thread.sleep(randomDelayMs(pageDelayMinMs, pageDelayMaxMs, 600L, 1_500L))
        }
        log.info("{} 세대수 조회 완료 - {}개 단지", regionName, counts.size)
        return counts
    }

    private fun selectRunComplexes(regionName: String, complexes: List<ComplexInfo>): List<ComplexInfo> {
        if (complexes.isEmpty()) return emptyList()

        val limit = maxComplexesPerRegion
        if (limit <= 0 || limit >= complexes.size) {
            return complexes
        }

        if (!rotateComplexesByDay) {
            return complexes.take(limit)
        }

        val dayOffset = LocalDate.now().dayOfYear
        val regionOffset = regionName.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        val start = (dayOffset + regionOffset) % complexes.size
        val rotated = complexes.drop(start) + complexes.take(start)
        return rotated.take(limit)
    }

    // 구 m.land API가 폐기되고 신규 fin.land API는 브라우저 TLS 지문 + 헤드리스 탐지 WAF로
    // 막혀 있어, 매물 목록은 헤드풀 Chrome 헬퍼(BrowserArticleFetcher)로 조회한다.
    private fun fetchComplexArticles(
        regionName: String,
        complex: ComplexInfo,
        allowOverflowExpansion: Boolean,
    ): ArticleFetchResult {
        if (complex.hscpNo.toLongOrNull() == null) return ArticleFetchResult(listings = emptyList())

        val orders = parsedArticleOrders().map { articleSortType(it) }.distinct()
        val configuredMaxPages = maxComplexPages.coerceAtLeast(1)
        val maxPages = if (allowOverflowExpansion) {
            maxOf(configuredMaxPages, maxComplexPagesOnOverflow.coerceAtLeast(1))
        } else {
            configuredMaxPages
        }

        val rawPages = try {
            browserArticleFetcher.fetchComplexPages(
                complexNo = complex.hscpNo,
                orders = orders,
                maxPages = maxPages,
                pageSize = ARTICLE_PAGE_SIZE,
                tradeType = "A1",
            )
        } catch (e: AbuseBlockedException) {
            registerAbuseCooldown("브라우저 조회 429")
            return ArticleFetchResult(listings = emptyList(), blockedByAbuse = true)
        }

        val listings = mutableListOf<Listing>()
        rawPages.forEach { body ->
            val pageResult = parseArticleListBody(regionName, complex, body)
            if (pageResult.blockedByAbuse) {
                registerAbuseCooldown("front-api TOO_MANY_REQUESTS")
                return ArticleFetchResult(listings = listings, blockedByAbuse = true)
            }
            pageResult.nodes.forEach { node ->
                mapArticleNode(regionName, complex, node)?.let { listings.add(it) }
            }
        }

        return ArticleFetchResult(
            listings = listings,
            overflowExpanded = allowOverflowExpansion && rawPages.size > configuredMaxPages
        )
    }

    internal fun parseArticleListBody(regionName: String, complex: ComplexInfo, body: String): ArticlePageResult {
        val root = runCatching { objectMapper.readTree(body) }.getOrElse {
            log.warn("{} {} 매물 응답 파싱 실패: {}", regionName, complex.hscpNm, it.message)
            return ArticlePageResult(hasData = false)
        }
        if (root.isNull || !root.isObject) {
            log.warn("{} {} 매물 응답이 비정상입니다. body={}", regionName, complex.hscpNm, body.oneLineSnippet())
            return ArticlePageResult(hasData = false)
        }

        if (root.get("detailCode").textOrEmpty() == "TOO_MANY_REQUESTS") {
            registerAbuseCooldown("front-api TOO_MANY_REQUESTS")
            return ArticlePageResult(blockedByAbuse = true)
        }

        val result = root.get("result")
        if (result == null || result.isNull) {
            log.warn("{} {} 매물 응답에 result 없음. body={}", regionName, complex.hscpNm, body.oneLineSnippet())
            return ArticlePageResult(hasData = false)
        }
        val rawList = sequenceOf(result.get("list"), result.get("articleList"), result)
            .firstOrNull { it != null && it.isArray }
            ?: run {
                log.warn("{} {} 매물 응답에 목록 필드 없음. body={}", regionName, complex.hscpNm, body.oneLineSnippet())
                return ArticlePageResult(hasData = false)
            }

        // 응답은 대표 매물 + 동일 주소 중복 매물 그룹 구조. 그룹 키를 노드에 심어 dedup에 활용한다.
        val nodes = mutableListOf<JsonNode>()
        rawList.forEach { item ->
            val rep = item.get("representativeArticleInfo")
            if (rep != null && rep.isObject) {
                val groupKey = rep.get("articleNumber").textOrEmpty().trim()
                (rep as ObjectNode).put(SAME_ADDR_GROUP_FIELD, groupKey)
                nodes.add(rep)
                val dupList = item.get("duplicatedArticleInfo")?.get("articleInfoList")
                if (dupList != null && dupList.isArray) {
                    dupList.forEach { dup ->
                        if (dup.isObject) {
                            (dup as ObjectNode).put(SAME_ADDR_GROUP_FIELD, groupKey)
                            nodes.add(dup)
                        }
                    }
                }
            } else if (item.isObject) {
                nodes.add(item)
            }
        }
        if (nodes.isEmpty()) {
            return ArticlePageResult(hasData = false)
        }

        return ArticlePageResult(
            nodes = nodes,
            hasNextPage = result.get("hasNextPage")?.asBoolean(false) ?: false,
            lastInfo = result.get("lastInfo"),
            seed = result.get("seed").textOrEmpty(),
            hasData = true
        )
    }

    private fun articleSortType(order: String): String = when (order.lowercase()) {
        "prc", "price_asc" -> "PRICE_ASC"
        "date", "date_desc" -> "DATE_DESC"
        "ranking", "ranking_desc" -> "RANKING_DESC"
        else -> "PRICE_ASC"
    }

    private fun registerAbuseCooldown(reason: String) {
        val cooldownMs = abuseCooldownMinutes.coerceAtLeast(1L) * 60_000L
        blockedUntilEpochMillis = maxOf(blockedUntilEpochMillis, System.currentTimeMillis() + cooldownMs)
        log.warn("요청 차단 감지({}) -> 쿨다운 {}분", reason, abuseCooldownMinutes)
    }

    private fun parsedArticleOrders(): List<String> {
        val parsed = articleOrder
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
        return parsed.ifEmpty { listOf("prc") }
    }

    internal fun mapArticleNode(regionName: String, complex: ComplexInfo, node: JsonNode): Listing? {
        val articleNo = node.get("articleNumber").textOrEmpty().trim()
        if (articleNo.isBlank()) return null

        val detail = node.get("articleDetail")
        val priceInfo = node.get("priceInfo")
        val space = node.get("spaceInfo") ?: node.get("sizeInfo")

        val parsedPrice = parseFrontPrice(priceInfo?.get("dealPrice"))
        if (parsedPrice <= 0L) return null

        val areaSupplySqm = parseAreaSqm(space?.get("supplySpace"))
        val areaExclusiveSqm = parseAreaSqm(space?.get("exclusiveSpace"))
        val areaSqm = firstPositiveArea(areaExclusiveSqm, areaSupplySqm)
        val pyeongBaseSqm = firstPositiveArea(areaSupplySqm, areaExclusiveSqm)

        val title = node.get("articleName").textOrEmpty().trim().ifBlank { complex.hscpNm }
        val featureDesc = firstNonBlankText(
            detail?.get("articleFeatureDescription"),
            detail?.get("featureDescription"),
            node.get("articleFeatureDescription")
        )
        val buildingName = firstNonBlankText(
            detail?.get("buildingName"),
            node.get("buildingName"),
            detail?.get("dongName"),
            node.get("dongName")
        )
        val floor = firstNonBlankText(detail?.get("floorInfo"), node.get("floorInfo"))
        val tagList = parseTagList(node.get("tagList") ?: detail?.get("tagList"))
        val sameAddrHash = node.get(SAME_ADDR_GROUP_FIELD).textOrEmpty().trim()

        return Listing(
            articleNo = articleNo,
            hscpNo = complex.hscpNo,
            sameAddrHash = sameAddrHash,
            buildingName = buildingName,
            title = title,
            featureDesc = featureDesc,
            tagList = tagList,
            regionName = regionName,
            price = parsedPrice,
            floor = floor,
            areaSqm = areaSqm,
            areaSupplySqm = areaSupplySqm,
            areaExclusiveSqm = areaExclusiveSqm,
            householdCount = complex.totHsehCnt,
            pyeong = if (pyeongBaseSqm > 0.0) (pyeongBaseSqm / 3.3058).roundToInt() else 0,
            url = "https://fin.land.naver.com/articles/$articleNo"
        )
    }

    private fun requestBodyWithRetry(url: String, referer: String): RequestResult {
        var lastBodySnippet = ""
        var sawTimeout = false

        for (attempt in 1..maxAttempts.coerceAtLeast(1)) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(requestTimeoutMs.coerceAtLeast(5_000L)))
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Referer", referer)
                    .header("User-Agent", MOBILE_USER_AGENT)
                    .GET()
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                val status = response.statusCode()
                val body = response.body().orEmpty()
                lastBodySnippet = body.oneLineSnippet()
                val location = response.headers().firstValue("location").orElse("")

                if (status == 200) {
                    return RequestResult(body = body)
                }

                if (status == 302 && location.contains("/error/abuse")) {
                    val cooldownMs = abuseCooldownMinutes.coerceAtLeast(1L) * 60_000L
                    blockedUntilEpochMillis = maxOf(
                        blockedUntilEpochMillis,
                        System.currentTimeMillis() + cooldownMs
                    )
                    log.warn("요청 차단(302 abuse). url={} location={} -> 쿨다운 {}분", url, location, abuseCooldownMinutes)
                    return RequestResult(body = null, blockedByAbuse = true)
                }

                if (status in RETRYABLE_STATUS_CODES) {
                    val backoff = backoffMillis(attempt)
                    log.warn(
                        "요청 재시도 상태코드({}) - {}ms 대기. url={} location={} body={}",
                        status,
                        backoff,
                        url,
                        location,
                        lastBodySnippet
                    )
                    Thread.sleep(backoff)
                    continue
                }

                log.error("요청 실패 상태코드({}). url={} location={} body={}", status, url, location, lastBodySnippet)
                return RequestResult(body = null)
            } catch (e: HttpTimeoutException) {
                sawTimeout = true
                val backoff = backoffMillis(attempt)
                log.warn("요청 타임아웃(시도 {}/{}): {} - {}ms 대기. url={}", attempt, maxAttempts, e.message, backoff, url)
                Thread.sleep(backoff)
            } catch (e: Exception) {
                val backoff = backoffMillis(attempt)
                log.warn("요청 오류(시도 {}/{}): {} - {}ms 대기. url={}", attempt, maxAttempts, e.message, backoff, url)
                Thread.sleep(backoff)
            }
        }

        if (sawTimeout) {
            log.warn("최대 재시도 소진(타임아웃). url={} lastBody={}", url, lastBodySnippet)
            return RequestResult(body = null, timedOut = true)
        }

        log.warn("최대 재시도 소진(응답 없음/오류). url={} lastBody={}", url, lastBodySnippet)
        return RequestResult(body = null)
    }

    private fun backoffMillis(attempt: Int): Long {
        val base = baseBackoffMs.coerceAtLeast(500L) * (1L shl (attempt - 1))
        val jitter = backoffJitterMs.coerceAtLeast(0L)
        return base + random.nextLong(jitter + 1)
    }

    private fun randomDelayMs(minMs: Long, maxMs: Long, defaultMinMs: Long, defaultMaxMs: Long): Long {
        if (minMs <= 0L && maxMs <= 0L) return 0L
        val min = if (minMs > 0) minMs else defaultMinMs
        val max = if (maxMs >= min) maxMs else defaultMaxMs.coerceAtLeast(min)
        return random.nextLong(min, max + 1)
    }

    private fun parsePrice(raw: String): Long {
        val normalized = raw
            .substringBefore("~")
            .replace("만원", "")
            .trim()

        val clean = normalized.replace(",", "").replace(" ", "")
        return if (clean.contains("억")) {
            val s = clean.split("억")
            val uk = s[0].toLongOrNull() ?: 0L
            val man = if (s.size > 1 && s[1].isNotEmpty()) s[1].toLongOrNull() ?: 0L else 0L
            uk * 10000 + man
        } else clean.toLongOrNull() ?: 0L
    }

    private fun JsonNode?.textOrEmpty(): String {
        if (this == null || this.isNull) return ""
        return this.asString("")
    }

    private fun parseFrontPrice(node: JsonNode?): Long {
        if (node == null || node.isNull) return 0L
        // front-api dealPrice는 원 단위. 기존 저장 포맷은 만원 단위이므로 변환한다.
        if (node.isNumber) return node.asLong(0L) / 10_000L
        return parsePrice(node.textOrEmpty())
    }

    private fun firstNonBlankText(vararg nodes: JsonNode?): String =
        nodes.firstNotNullOfOrNull { n -> n.textOrEmpty().trim().ifBlank { null } } ?: ""

    private fun parseAreaSqm(node: JsonNode?): Double {
        if (node == null || node.isNull) return 0.0
        return when {
            node.isNumber -> node.asDouble(0.0)
            else -> node.textOrEmpty()
                .replace(",", "")
                .replace("㎡", "")
                .trim()
                .toDoubleOrNull() ?: 0.0
        }.coerceAtLeast(0.0)
    }

    private fun parseTagList(node: JsonNode?): List<String> {
        if (node == null || node.isNull || !node.isArray) return emptyList()
        return node.mapNotNull { tagNode ->
            val text = tagNode.textOrEmpty().trim()
            if (text.isBlank()) null else text
        }.distinct()
    }

    private fun firstPositiveArea(vararg values: Double): Double =
        values.firstOrNull { it > 0.0 } ?: 0.0

    private fun dedupKey(listing: Listing): String {
        if (listing.sameAddrHash.isNotBlank()) {
            return "HASH:${listing.hscpNo}:${listing.sameAddrHash}"
        }
        val buildingKey = normalizeBuildingKey(listing.buildingName)
        val floorKey = normalizeFloorKey(listing.floor)
        val areaKey = normalizedAreaKey(firstPositiveArea(listing.areaExclusiveSqm, listing.areaSupplySqm, listing.areaSqm))
        val titleKey = normalizeTitleKey(listing.title)
        if (buildingKey.isNotBlank() && floorKey.isNotBlank() && areaKey > 0.0 && titleKey.isNotBlank()) {
            return "UNIT:${listing.hscpNo}:$titleKey:$buildingKey:$floorKey:${"%.2f".format(Locale.US, areaKey)}"
        }
        return "ATCL:${listing.articleNo}"
    }

    private fun normalizeFloorKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(" ", "")
            .replace("저", "L")
            .replace("중", "M")
            .replace("고", "H")
            .uppercase()
    }

    private fun normalizeTitleKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^0-9a-z가-힣동]"), "")
    }

    private fun normalizeBuildingKey(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .replace(" ", "")
            .replace(Regex("[^0-9a-zA-Z가-힣동]"), "")
            .uppercase()
    }

    private fun normalizedAreaKey(area: Double): Double =
        if (area > 0.0) kotlin.math.round(area * 100.0) / 100.0 else 0.0

    private fun String.oneLineSnippet(maxLength: Int = 180): String {
        return replace("\n", " ").replace("\r", " ").trim().take(maxLength)
    }

    internal data class ComplexInfo(
        val hscpNo: String,
        val hscpNm: String,
        val totHsehCnt: Int = 0,
    )

    private data class ArticleFetchResult(
        val listings: List<Listing>,
        val blockedByAbuse: Boolean = false,
        val overflowExpanded: Boolean = false,
    )

    internal data class ArticlePageResult(
        val nodes: List<JsonNode> = emptyList(),
        val hasNextPage: Boolean = false,
        val lastInfo: JsonNode? = null,
        val seed: String = "",
        val hasData: Boolean = false,
        val blockedByAbuse: Boolean = false,
        val timedOut: Boolean = false,
    )

    private data class RequestResult(
        val body: String?,
        val blockedByAbuse: Boolean = false,
        val timedOut: Boolean = false,
    )

    companion object {
        private val RETRYABLE_STATUS_CODES = setOf(401, 403, 429, 500, 502, 503, 504)
        private const val ARTICLE_PAGE_SIZE = 30
        private const val MAX_HOUSEHOLD_PAGES = 30
        internal const val SAME_ADDR_GROUP_FIELD = "__sameAddrGroup"
        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
    }
}
