package com.example.lottelock

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject

/**
 * 네이버 스포츠 (비공식 API — 네이버가 형식을 바꾸면 수정이 필요할 수 있음).
 * [fetch]는 테스트에서 가짜 응답을 넣을 수 있게 분리했다.
 * 실제 요청은 Ktor가 플랫폼에 맞는 방식으로 보낸다 (안드로이드: HttpURLConnection, 아이폰: NSURLSession).
 */
class NaverSportsClient(
    private val onRequest: () -> Unit = {},
    private val fetch: suspend (String) -> String = ::httpGet,
) {
    /**
     * [from]~[to] 기간의 경기 목록. teamCode/size 옵션이 무시돼서 일부만 오면(gameTotalCount보다 적으면)
     * 하루씩 나눠서 다시 받는다 (하루 경기 수는 페이지 크기보다 적음).
     */
    suspend fun schedule(category: String, team: String, from: LocalDate, to: LocalDate): List<JsonObject> {
        val first = ScoreParser.parseSchedule(get(scheduleUrl(category, team, from, to)))
        if (first.games.size >= first.totalCount || from == to) return first.games
        val all = mutableListOf<JsonObject>()
        var day = from
        while (day <= to) {
            all += ScoreParser.parseSchedule(get(scheduleUrl(category, team, day, day))).games
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return all
    }

    /** 경기 기록(이닝별 점수, R H E, 구장). 실패하면 null — 최종 점수는 목록에도 있으므로 */
    suspend fun record(gameId: String): JsonObject? =
        try {
            ScoreParser.parseRecord(get("$BASE/schedule/games/${enc(gameId)}/record"))
        } catch (e: CancellationException) {
            throw e   // 코루틴 취소는 삼키지 않는다
        } catch (e: Exception) {
            null
        }

    private fun scheduleUrl(category: String, team: String, from: LocalDate, to: LocalDate) =
        "$BASE/schedule/games?fields=basic&upperCategoryId=kbaseball" +
            (if (category.isBlank()) "" else "&categoryId=${enc(category)}") +   // 빈 값이면 야구 전체 (국가대표전)
            "&fromDate=$from&toDate=$to&teamCode=${enc(team)}&size=100"

    private suspend fun get(url: String): String {
        onRequest()
        return fetch(url)
    }

    companion object {
        private const val BASE = "https://api-gw.sports.naver.com"

        private fun enc(s: String) = s.trim().encodeURLParameter()

        private val http by lazy {
            HttpClient {
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 20_000
                }
            }
        }

        /** 네트워크 실패·HTTP 오류는 IOException (안드로이드에서는 java.io.IOException) → 호출 쪽에서 재시도 */
        suspend fun httpGet(url: String): String {
            val res = http.get(url) {
                header(HttpHeaders.UserAgent, "Mozilla/5.0 (Linux; Android) LotteScoreboard")
                header(HttpHeaders.Referrer, "https://m.sports.naver.com/")
                header(HttpHeaders.Accept, "application/json")
            }
            if (res.status != HttpStatusCode.OK) throw IOException("HTTP ${res.status.value}")
            return res.bodyAsText()
        }
    }
}
