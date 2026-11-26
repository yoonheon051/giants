package com.example.lottelock

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
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
    private val startedAt = LocalDateTime.now(ScoreParser.SEOUL).withNano(0)

    val isOver: Boolean get() = halves >= 18

    fun next(): JSONObject {
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
        val game = JSONObject()
            .put("gameId", "DEMO")
            .put("gameDateTime", startedAt.toString())
            .put("homeTeamCode", trackedCode).put("homeTeamName", trackedName).put("homeTeamScore", sum(home))
            .put("awayTeamCode", opponentCode).put("awayTeamName", opponentName).put("awayTeamScore", sum(away))
            .put("statusCode", if (isOver) "RESULT" else "STARTED")
            .put("statusInfo", statusInfo)
            .put("cancel", false)
        val scoreBoard = JSONObject()
            .put("inn", JSONObject().put("away", innings(away)).put("home", innings(home)))
            .put("rheb", JSONObject()
                .put("away", JSONObject().put("r", sum(away)).put("h", awayHits).put("e", 0))
                .put("home", JSONObject().put("r", sum(home)).put("h", homeHits).put("e", 0)))
        return ScoreParser.compose(game, JSONObject().put("scoreBoard", scoreBoard).put("stadium", "데모구장"))
    }

    private fun innings(a: Array<Int?>): JSONArray {
        val arr = JSONArray()
        a.forEach { arr.put(it ?: "-") }
        return arr
    }

    private fun sum(a: Array<Int?>) = a.sumOf { it ?: 0 }

    companion object {
        val NAMES = mapOf(
            "LT" to "롯데", "LG" to "LG", "OB" to "두산", "HT" to "KIA", "SS" to "삼성",
            "SK" to "SSG", "WO" to "키움", "NC" to "NC", "KT" to "KT", "HH" to "한화",
            "KR" to "대한민국", "JP" to "일본",
        )
    }
}
