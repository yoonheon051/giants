package com.example.lottelock

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * 네이버 스포츠 (비공식 API — 네이버가 형식을 바꾸면 수정이 필요할 수 있음).
 * [fetch]는 테스트에서 가짜 응답을 넣을 수 있게 분리했다.
 */
class NaverSportsClient(
    private val onRequest: () -> Unit = {},
    private val fetch: (String) -> String = ::httpGet,
) {
    /**
     * [from]~[to] 기간의 경기 목록. teamCode/size 옵션이 무시돼서 일부만 오면(gameTotalCount보다 적으면)
     * 하루씩 나눠서 다시 받는다 (하루 경기 수는 페이지 크기보다 적음).
     */
    fun schedule(category: String, team: String, from: LocalDate, to: LocalDate): List<JSONObject> {
        val first = ScoreParser.parseSchedule(get(scheduleUrl(category, team, from, to)))
        if (first.games.size >= first.totalCount || from == to) return first.games
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .flatMap { day -> ScoreParser.parseSchedule(get(scheduleUrl(category, team, day, day))).games }
            .toList()
    }

    /** 경기 기록(이닝별 점수, R H E, 구장). 실패하면 null — 최종 점수는 목록에도 있으므로 */
    fun record(gameId: String): JSONObject? =
        runCatching { ScoreParser.parseRecord(get("$BASE/schedule/games/${enc(gameId)}/record")) }.getOrNull()

    private fun scheduleUrl(category: String, team: String, from: LocalDate, to: LocalDate) =
        "$BASE/schedule/games?fields=basic&upperCategoryId=kbaseball" +
            (if (category.isBlank()) "" else "&categoryId=${enc(category)}") +   // 빈 값이면 야구 전체 (국가대표전)
            "&fromDate=$from&toDate=$to&teamCode=${enc(team)}&size=100"

    private fun get(url: String): String {
        onRequest()
        return fetch(url)
    }

    companion object {
        private const val BASE = "https://api-gw.sports.naver.com"

        private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")

        fun httpGet(url: String): String {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LotteScoreboard")
                conn.setRequestProperty("Referer", "https://m.sports.naver.com/")
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != 200) throw IOException("HTTP $code")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
