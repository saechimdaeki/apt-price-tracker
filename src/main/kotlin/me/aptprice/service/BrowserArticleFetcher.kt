package me.aptprice.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.Writer
import java.util.concurrent.TimeUnit

/**
 * fin.land 매물 목록을 헤드풀 Chrome(Playwright)으로 조회한다.
 *
 * 네이버가 신규 API를 브라우저 TLS 지문 + 헤드리스 탐지 WAF로 막아 Java HttpClient로는
 * 무조건 429가 나므로, node 헬퍼(scripts/naver-browser-fetch.js)를 한 번 띄워두고
 * 단지별 요청을 주고받는다. 러너는 GUI 세션(헤드풀 Chrome 가능)이어야 한다.
 *
 * ponytail: 단일 브라우저/단일 페이지를 프로세스 수명 동안 재사용. 동시성이 필요해지면
 * 페이지 풀로 확장. 지금은 봇이 순차 수집이라 단일로 충분하다.
 */
@Component
class BrowserArticleFetcher(private val objectMapper: ObjectMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Value("\${naver.browser.node-path:node}")
    private var nodePath: String = "node"

    @Value("\${naver.browser.script-path:scripts/naver-browser-fetch.js}")
    private var scriptPath: String = "scripts/naver-browser-fetch.js"

    @Value("\${naver.browser.startup-timeout-ms:60000}")
    private var startupTimeoutMs: Long = 60_000L

    @Value("\${naver.browser.request-timeout-ms:60000}")
    private var requestTimeoutMs: Long = 60_000L

    private var process: Process? = null
    private var writer: Writer? = null
    private var reader: BufferedReader? = null
    private var nextId = 1
    private var failed = false

    @Synchronized
    private fun ensureStarted() {
        if (process?.isAlive == true) return
        if (failed) throw RegionFetchFailedException("브라우저 헬퍼 시작 실패 상태")

        val script = File(scriptPath)
        if (!script.exists()) {
            failed = true
            throw RegionFetchFailedException("브라우저 헬퍼 스크립트 없음: ${script.absolutePath}")
        }

        log.info("브라우저 매물 헬퍼 시작: {} {}", nodePath, scriptPath)
        val proc = ProcessBuilder(nodePath, scriptPath)
            .redirectErrorStream(false)
            .start()
        // stderr는 로그로 흘려보냄(디버깅용)
        Thread {
            proc.errorStream.bufferedReader().forEachLine { log.debug("[browser-helper] {}", it) }
        }.apply { isDaemon = true }.start()

        val r = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
        val w = proc.outputStream.bufferedWriter()

        val ready = readLineWithin(r, startupTimeoutMs)
            ?: run {
                proc.destroyForcibly()
                failed = true
                throw RegionFetchFailedException("브라우저 헬퍼 준비 신호 타임아웃")
            }
        val readyNode = objectMapper.readTree(ready)
        if (readyNode.get("ready")?.asBoolean(false) != true) {
            proc.destroyForcibly()
            failed = true
            throw RegionFetchFailedException("브라우저 헬퍼 준비 실패: ${readyNode.get("error").asString("unknown")}")
        }

        process = proc
        reader = r
        writer = w
        Runtime.getRuntime().addShutdownHook(Thread { stop() })
    }

    /**
     * 단지 하나의 매물 원본 응답들(front-api JSON 문자열 목록)을 반환.
     * @throws AbuseBlockedException WAF 차단(429) 시
     * @throws RegionFetchFailedException 헬퍼 오류/타임아웃 시
     */
    @Synchronized
    fun fetchComplexPages(
        complexNo: String,
        orders: List<String>,
        maxPages: Int,
        pageSize: Int,
        tradeType: String,
    ): List<String> {
        ensureStarted()
        val id = nextId++
        val req = objectMapper.createObjectNode().apply {
            put("id", id)
            put("complexNo", complexNo)
            putArray("orders").also { arr -> orders.forEach { arr.add(it) } }
            put("maxPages", maxPages)
            put("pageSize", pageSize)
            put("tradeType", tradeType)
        }
        val w = writer ?: throw RegionFetchFailedException("브라우저 헬퍼 미기동")
        w.write(objectMapper.writeValueAsString(req))
        w.write("\n")
        w.flush()

        val line = readLineWithin(reader!!, requestTimeoutMs)
            ?: run { stop(); throw RegionFetchFailedException("브라우저 헬퍼 응답 타임아웃(complex=$complexNo)") }
        val res = objectMapper.readTree(line)
        if (res.get("blocked")?.asBoolean(false) == true) {
            throw AbuseBlockedException("브라우저 조회 중 차단(429) 감지 complex=$complexNo")
        }
        val error = res.get("error")
        if (error != null && !error.isNull) {
            throw RegionFetchFailedException("브라우저 헬퍼 오류(complex=$complexNo): ${error.asString("")}")
        }
        val pages = res.get("pages") ?: return emptyList()
        return pages.mapNotNull { if (it.isNull) null else it.asString("") }.filter { it.isNotBlank() }
    }

    private fun readLineWithin(r: BufferedReader, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (r.ready()) return r.readLine()
            if (process?.isAlive == false) return null
            Thread.sleep(50)
        }
        return null
    }

    @Synchronized
    fun stop() {
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        val proc = process
        process = null
        reader = null
        writer = null
        if (proc != null && proc.isAlive) {
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
    }
}
