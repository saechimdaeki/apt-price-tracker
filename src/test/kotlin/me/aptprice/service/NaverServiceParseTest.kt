package me.aptprice.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class NaverServiceParseTest {

    private val mapper = JsonMapper.builder().build()
    private val service = NaverService(mapper, BrowserArticleFetcher(mapper))
    private val complex = NaverService.ComplexInfo(hscpNo = "8762", hscpNm = "극동")

    @Test
    fun `front-api response is parsed into representative and duplicated articles`() {
        val body = """
            {
              "isSuccess": true,
              "result": {
                "list": [
                  {
                    "representativeArticleInfo": {
                      "articleNumber": "2501234567",
                      "articleName": "극동",
                      "tradeType": "A1",
                      "dongName": "101",
                      "priceInfo": { "dealPrice": 450000000 },
                      "spaceInfo": { "supplySpace": 84.9, "exclusiveSpace": 59.8 },
                      "articleDetail": { "floorInfo": "10/15", "articleFeatureDescription": "역세권" }
                    },
                    "duplicatedArticleInfo": {
                      "articleInfoList": [
                        {
                          "articleNumber": "2501234568",
                          "articleName": "극동",
                          "dongName": "102",
                          "priceInfo": { "dealPrice": 445000000 },
                          "spaceInfo": { "supplySpace": 84.9, "exclusiveSpace": 59.8 },
                          "articleDetail": { "floorInfo": "10/15" }
                        }
                      ]
                    }
                  }
                ],
                "hasNextPage": true,
                "lastInfo": [ { "articleNumber": "2501234568" } ],
                "seed": "seed-123"
              }
            }
        """.trimIndent()

        val page = service.parseArticleListBody("수원_매탄동", complex, body)

        assertTrue(page.hasData)
        assertFalse(page.blockedByAbuse)
        assertTrue(page.hasNextPage)
        assertEquals("seed-123", page.seed)
        assertNotNull(page.lastInfo)
        assertEquals(2, page.nodes.size)

        val rep = service.mapArticleNode("수원_매탄동", complex, page.nodes[0])
        assertNotNull(rep)
        rep!!
        assertEquals("2501234567", rep.articleNo)
        assertEquals(45000L, rep.price) // 450,000,000원 -> 45000만원
        assertEquals("10/15", rep.floor)
        assertEquals("101", rep.buildingName) // dongName
        assertEquals("역세권", rep.featureDesc)
        assertEquals(26, rep.pyeong) // 84.9㎡ / 3.3058
        assertEquals("2501234567", rep.sameAddrHash)

        val dup = service.mapArticleNode("수원_매탄동", complex, page.nodes[1])
        assertNotNull(dup)
        assertEquals("2501234567", dup!!.sameAddrHash) // 대표 매물과 같은 그룹
        assertEquals(44500L, dup.price)
    }

    @Test
    fun `too many requests response is treated as abuse block`() {
        val page = service.parseArticleListBody(
            "수원_매탄동",
            complex,
            """{"detailCode":"TOO_MANY_REQUESTS","message":""}"""
        )
        assertTrue(page.blockedByAbuse)
        assertFalse(page.hasData)
        assertTrue(service.abuseCooldownRemainingMillis() > 0)
    }

    @Test
    fun `null body has no data`() {
        val page = service.parseArticleListBody("수원_매탄동", complex, "null")
        assertFalse(page.hasData)
        assertFalse(page.blockedByAbuse)
    }
}
