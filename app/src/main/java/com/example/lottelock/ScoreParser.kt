package com.example.lottelock

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** 한 팀의 라인스코어 */
data class TeamLine(
    val code: String,          // 네이버 팀 코드 (예: "LT")
    val name: String,          // 한국어 팀명 (예: "롯데")
    val innings: List<Int?>,   // 1~9회, 진행되지 않은 이닝은 null
    val extra: Int?,           // 연장전(10회~) 득점 합계, 연장 없으면 null
    val runs: Int,
    val hits: Int?,
    val errors: Int?,
) {
    val shortName: String get() = name
}

enum class Outcome { WIN, LOSS, DRAW }
enum class GameState { SCHEDULED, LIVE, FINISHED, CANCELLED }

data class GameResult(
    val id: String,            // 네이버 gameId (예: "20260922LTHH02026")
    val timestamp: Long,       // 경기 시작 epoch seconds
    val state: GameState,
    val statusInfo: String,    // 네이버가 주는 진행 상황 (예: "7회말")
    val stadium: String?,      // 구장 (기록을 받았을 때만)
    val away: TeamLine,
    val home: TeamLine,
    val trackedIsHome: Boolean,
) {
    val isLive: Boolean get() = state == GameState.LIVE
    val isFinished: Boolean get() = state == GameState.FINISHED
    val hasExtra: Boolean get() = away.extra != null || home.extra != null
    val tracked: TeamLine get() = if (trackedIsHome) home else away
    val opponent: TeamLine get() = if (trackedIsHome) away else home
    val liveLabel: String get() = statusInfo.ifBlank { "진행 중" }

    /** 야구 표기 관례대로 원정 : 홈 순서 (예: "LG 2 : 0 롯데") */
    val scoreLine: String get() = "${away.shortName} ${away.runs} : ${home.runs} ${home.shortName}"

    /** 추적 팀 기준 승/패/무 */
    val outcome: Outcome
        get() = when {
            tracked.runs > opponent.runs -> Outcome.WIN
            tracked.runs < opponent.runs -> Outcome.LOSS
            else -> Outcome.DRAW
        }
}

class ApiException(message: String) : Exception(message)

data class Schedule(val games: List<JSONObject>, val totalCount: Int)

/**
 * 네이버 스포츠 응답 파싱.
 *
 * 경기 목록: GET /schedule/games?... → result.games[] (gameId, gameDateTime, home/awayTeamCode·Name·Score, statusCode, statusInfo, cancel)
 * 경기 기록: GET /schedule/games/{gameId}/record → result.recordData.scoreBoard { inn{away[],home[]}, rheb{away{r,h,e,b},home{..}} }, gameInfo.stadium
 *
 * 캐시에는 둘을 묶은 "composite" {"game": 목록 항목, "record": {"scoreBoard": .., "stadium": ..}} 를 저장한다.
 */
object ScoreParser {
    val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

    fun parseSchedule(body: String): Schedule {
        val root = JSONObject(body)
        if (!root.optBoolean("success", true)) throw ApiException(root.optString("message", "네이버 응답 오류 (code ${root.optInt("code")})"))
        val result = root.optJSONObject("result") ?: return Schedule(emptyList(), 0)
        val arr = result.optJSONArray("games") ?: JSONArray()
        val games = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        return Schedule(games, result.optInt("gameTotalCount", games.size))
    }

    /** 경기 기록 응답 → {"scoreBoard": .., "stadium": ..}. 기록이 없으면 null */
    fun parseRecord(body: String): JSONObject? {
        val data = JSONObject(body).optJSONObject("result")?.optJSONObject("recordData") ?: return null
        val scoreBoard = data.optJSONObject("scoreBoard") ?: return null
        val stadium = data.optJSONObject("gameInfo")?.optString("stadium").orEmpty()
        return JSONObject().put("scoreBoard", scoreBoard).put("stadium", stadium)
    }

    fun compose(game: JSONObject, record: JSONObject?): JSONObject =
        JSONObject().put("game", game).put("record", record ?: JSONObject.NULL)

    fun isTracked(game: JSONObject, team: String): Boolean {
        val t = team.trim()
        if (t.isEmpty()) return false
        return listOf("homeTeamCode", "awayTeamCode", "homeTeamName", "awayTeamName")
            .any { game.optString(it).equals(t, ignoreCase = true) }
    }

    fun stateOf(game: JSONObject): GameState {
        val code = game.optString("statusCode")
        return when {
            game.optBoolean("cancel") || code == "CANCEL" -> GameState.CANCELLED
            game.optBoolean("suspended") -> GameState.CANCELLED   // 서스펜디드: 나중에 재개되므로 실시간 갱신은 멈춤
            code == "RESULT" -> GameState.FINISHED
            code == "BEFORE" || code.isEmpty() -> GameState.SCHEDULED
            else -> GameState.LIVE                                 // STARTED 등
        }
    }

    fun timestampOf(game: JSONObject): Long =
        runCatching { LocalDateTime.parse(game.optString("gameDateTime")).atZone(SEOUL).toEpochSecond() }
            .getOrElse { runCatching { LocalDate.parse(game.optString("gameDate")).atStartOfDay(SEOUL).toEpochSecond() }.getOrDefault(0L) }

    /** 추적 팀의 종료 경기, 최신순 최대 [max]개 */
    fun pickFinished(games: List<JSONObject>, team: String, max: Int): List<JSONObject> =
        games.filter { isTracked(it, team) && stateOf(it) == GameState.FINISHED }
            .distinctBy { it.optString("gameId") }
            .sortedByDescending { timestampOf(it) }
            .take(max)

    /** [today]의 추적 팀 경기 (취소 제외). 여러 개면 진행 중 > 예정 > 종료 (더블헤더 대비) */
    fun findToday(games: List<JSONObject>, team: String, today: LocalDate): JSONObject? =
        games.filter { isTracked(it, team) && stateOf(it) != GameState.CANCELLED }
            .filter { java.time.Instant.ofEpochSecond(timestampOf(it)).atZone(SEOUL).toLocalDate() == today }
            .sortedWith(compareBy<JSONObject> {
                when (stateOf(it)) {
                    GameState.LIVE -> 0
                    GameState.SCHEDULED -> 1
                    else -> 2
                }
            }.thenByDescending { timestampOf(it) })
            .firstOrNull()

    fun parseComposites(arr: JSONArray, team: String): List<GameResult> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { c -> parseComposite(c, team) } }

    fun parseComposite(composite: JSONObject, team: String): GameResult? {
        val g = composite.optJSONObject("game") ?: return null
        val record = composite.optJSONObject("record")
        val sb = record?.optJSONObject("scoreBoard")
        val home = line(g, "home", sb)
        val away = line(g, "away", sb)
        return GameResult(
            id = g.optString("gameId"),
            timestamp = timestampOf(g),
            state = stateOf(g),
            statusInfo = g.optString("statusInfo"),
            stadium = record?.optString("stadium")?.takeIf { it.isNotBlank() },
            away = away,
            home = home,
            trackedIsHome = home.code.equals(team.trim(), true) || home.name.equals(team.trim(), true),
        )
    }

    private fun line(g: JSONObject, side: String, sb: JSONObject?): TeamLine {
        val inn = sb?.optJSONObject("inn")?.optJSONArray(side)
        val rheb = sb?.optJSONObject("rheb")?.optJSONObject(side)
        val innings = (0 until 9).map { inn?.intAt(it) }
        val extras = if (inn != null && inn.length() > 9) (9 until inn.length()).mapNotNull { inn.intAt(it) } else emptyList()
        return TeamLine(
            code = g.optString("${side}TeamCode"),
            name = g.optString("${side}TeamName"),
            innings = innings,
            extra = if (extras.isEmpty()) null else extras.sum(),
            runs = rheb?.intOrNull("r") ?: g.optInt("${side}TeamScore"),
            hits = rheb?.intOrNull("h"),
            errors = rheb?.intOrNull("e"),
        )
    }

    /** 숫자 또는 숫자 문자열만 인정. "-", "X", "" 등은 null (진행 안 한 이닝) */
    private fun JSONArray.intAt(i: Int): Int? = when (val v = opt(i)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun JSONObject.intOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val v = opt(key)) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }
    }
}
