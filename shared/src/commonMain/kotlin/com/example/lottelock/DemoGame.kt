package com.example.lottelock

import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

/**
 * 테스트용 가상 경기. [next]를 부를 때마다 반 이닝씩 진행되고,
 * 네이버 응답과 같은 형식(composite)을 돌려주므로 실제 파싱·알림 경로를 그대로 탄다.
 */
class DemoGame(teamCode: String, private val random: Random = Random.Default) {
    private val trackedCode = teamCode.trim().ifBlank { "LT" }.uppercase()
    private val trackedName = NAMES[trackedCode] ?: trackedCode
    private val opponentCode = when (trackedCode) {
        "KR" -> "JP"
        "LG" -> "OB"
        else -> "LG"
    }
    private val opponentName = NAMES[opponentCode]!!

    private val away = arrayOfNulls<Int>(9)   // 상대팀 = 원정
    private val home = arrayOfNulls<Int>(9)   // 추적 팀 = 홈
    private var awayHits = 0
    private var homeHits = 0
    private var halves = 0                      // 진행된 반 이닝 수 (0~18)
    private val startedAt = Clock.System.now().toLocalDateTime(ScoreParser.SEOUL).let {
        LocalDateTime(it.year, it.month, it.dayOfMonth, it.hour, it.minute, it.second)   // 초 단위로 자름
    }

    val isOver: Boolean get() = halves >= 18

    fun next(): JsonObject {
        if (!isOver) {
            val inning = halves / 2
            val runs = listOf(0, 0, 0, 0, 1, 1, 2, 3)[random.nextInt(8)]
            if (halves % 2 == 0) {
                away[inning] = runs; awayHits += runs + random.nextInt(3)
            } else {
                home[inning] = runs; homeHits += runs + random.nextInt(3)
            }
            halves++
            // 9회초가 끝났는데 홈팀이 이기고 있으면 9회말 없이 종료
            if (halves == 17 && sum(home) > sum(away)) halves = 18
        }
        val lastInning = (halves + 1) / 2
        val statusInfo = if (halves % 2 == 1) "${lastInning}회초" else "${lastInning}회말"
        val game = buildJsonObject {
            put("gameId", "DEMO")
            put("gameDateTime", startedAt.toString())
            put("homeTeamCode", trackedCode); put("homeTeamName", trackedName); put("homeTeamScore", sum(home))
            put("awayTeamCode", opponentCode); put("awayTeamName", opponentName); put("awayTeamScore", sum(away))
            put("statusCode", if (isOver) "RESULT" else "STARTED")
            put("statusInfo", statusInfo)
            put("cancel", false)
        }
        val record = buildJsonObject {
            putJsonObject("scoreBoard") {
                putJsonObject("inn") {
                    put("away", innings(away))
                    put("home", innings(home))
                }
                putJsonObject("rheb") {
                    putJsonObject("away") { put("r", sum(away)); put("h", awayHits); put("e", 0) }
                    putJsonObject("home") { put("r", sum(home)); put("h", homeHits); put("e", 0) }
                }
            }
            put("stadium", "데모구장")
        }
        return ScoreParser.compose(game, record)
    }

    private fun innings(a: Array<Int?>) = JsonArray(a.map { if (it == null) JsonPrimitive("-") else JsonPrimitive(it) })

    private fun sum(a: Array<Int?>) = a.sumOf { it ?: 0 }

    companion object {
        val NAMES = mapOf(
            "LT" to "롯데", "LG" to "LG", "OB" to "두산", "HT" to "KIA", "SS" to "삼성",
            "SK" to "SSG", "WO" to "키움", "NC" to "NC", "KT" to "KT", "HH" to "한화",
            "KR" to "대한민국", "JP" to "일본",
        )
    }
}
