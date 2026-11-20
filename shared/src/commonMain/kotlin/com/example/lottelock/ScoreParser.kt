package com.example.lottelock

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
    val category: String,      // 네이버 리그 분류 (예: "kbo", "apbc")
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

data class Schedule(val games: List<JsonObject>, val totalCount: Int)

/**
 * 네이버 스포츠 응답 파싱.
 *
 * 경기 목록: GET /schedule/games?... → result.games[] (gameId, gameDateTime, home/awayTeamCode·Name·Score, statusCode, statusInfo, cancel)
 * 경기 기록: GET /schedule/games/{gameId}/record → result.recordData.scoreBoard { inn{away[],home[]}, rheb{away{r,h,e,b},home{..}} }, gameInfo.stadium
 *
 * 캐시에는 둘을 묶은 "composite" {"game": 목록 항목, "record": {"scoreBoard": .., "stadium": ..}} 를 저장한다.
 */
object ScoreParser {
    /** 경기 시각 기준 시간대. 안드로이드의 java.time 쪽은 이 ID로 ZoneId를 만든다 */
    const val SEOUL_ID = "Asia/Seoul"
    val SEOUL: TimeZone = TimeZone.of(SEOUL_ID)

    fun parseSchedule(body: String): Schedule {
        val root = parseJsonObject(body)
        if (!root.bool("success", true)) throw ApiException(root.str("message", "네이버 응답 오류 (code ${root.int("code")})"))
        val result = root.obj("result") ?: return Schedule(emptyList(), 0)
        val games = result.arr("games").orEmpty().mapNotNull { it as? JsonObject }
        return Schedule(games, result.int("gameTotalCount", games.size))
    }

    /**
     * 경기 기록 응답 → {"scoreBoard": .. | null, "stadium": ..}. 기록 자체가 없으면 null.
     * 경기 전에는 점수판 없이 구장만 있을 수 있다.
     */
    fun parseRecord(body: String): JsonObject? {
        val data = parseJsonObject(body).obj("result")?.obj("recordData") ?: return null
        val scoreBoard = data.obj("scoreBoard")
        val stadium = data.obj("gameInfo")?.str("stadium").orEmpty()
        if (scoreBoard == null && stadium.isBlank()) return null
        return buildJsonObject {
            put("scoreBoard", scoreBoard ?: JsonNull)
            put("stadium", stadium)
        }
    }

    fun compose(game: JsonObject, record: JsonObject?): JsonObject = buildJsonObject {
        put("game", game)
        put("record", record ?: JsonNull)
    }

    fun isTracked(game: JsonObject, team: String): Boolean {
        val t = team.trim()
        if (t.isEmpty()) return false
        return listOf("homeTeamCode", "awayTeamCode", "homeTeamName", "awayTeamName")
            .any { game.str(it).equals(t, ignoreCase = true) }
    }

    fun stateOf(game: JsonObject): GameState {
        val code = game.str("statusCode")
        return when {
            game.bool("cancel") || code == "CANCEL" -> GameState.CANCELLED
            game.bool("suspended") -> GameState.CANCELLED   // 서스펜디드: 나중에 재개되므로 실시간 갱신은 멈춤
            code == "RESULT" -> GameState.FINISHED
            code == "BEFORE" || code.isEmpty() -> GameState.SCHEDULED
            else -> GameState.LIVE                                 // STARTED 등
        }
    }

    fun timestampOf(game: JsonObject): Long =
        runCatching { LocalDateTime.parse(game.str("gameDateTime")).toInstant(SEOUL).epochSeconds }
            .getOrElse { runCatching { LocalDate.parse(game.str("gameDate")).atStartOfDayIn(SEOUL).epochSeconds }.getOrDefault(0L) }

    /** epoch seconds → 서울 기준 날짜 */
    fun seoulDateOf(epochSeconds: Long): LocalDate =
        Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(SEOUL).date

    /** 추적 팀의 종료 경기, 최신순 최대 [max]개 */
    fun pickFinished(games: List<JsonObject>, team: String, max: Int): List<JsonObject> =
        games.filter { isTracked(it, team) && stateOf(it) == GameState.FINISHED }
            .distinctBy { it.str("gameId") }
            .sortedByDescending { timestampOf(it) }
            .take(max)

    /** [today]의 추적 팀 경기 (취소 제외). 여러 개면 진행 중 > 예정 > 종료 (더블헤더 대비) */
    fun findToday(games: List<JsonObject>, team: String, today: LocalDate): JsonObject? =
        games.filter { isTracked(it, team) && stateOf(it) != GameState.CANCELLED }
            .filter { seoulDateOf(timestampOf(it)) == today }
            .sortedWith(compareBy<JsonObject> {
                when (stateOf(it)) {
                    GameState.LIVE -> 0
                    GameState.SCHEDULED -> 1
                    else -> 2
                }
            }.thenByDescending { timestampOf(it) })
            .firstOrNull()

    fun parseComposites(arr: JsonArray, team: String): List<GameResult> =
        arr.mapNotNull { (it as? JsonObject)?.let { c -> parseComposite(c, team) } }

    fun parseComposite(composite: JsonObject, team: String): GameResult? {
        val g = composite.obj("game") ?: return null
        val record = composite.obj("record")
        val sb = record?.obj("scoreBoard")
        val home = line(g, "home", sb)
        val away = line(g, "away", sb)
        return GameResult(
            id = g.str("gameId"),
            timestamp = timestampOf(g),
            state = stateOf(g),
            statusInfo = g.str("statusInfo"),
            stadium = record?.str("stadium")?.takeIf { it.isNotBlank() },
            category = g.str("categoryId"),
            away = away,
            home = home,
            trackedIsHome = home.code.equals(team.trim(), true) || home.name.equals(team.trim(), true),
        )
    }

    private fun line(g: JsonObject, side: String, sb: JsonObject?): TeamLine {
        val inn = sb?.obj("inn")?.arr(side)
        val rheb = sb?.obj("rheb")?.obj(side)
        val innings = (0 until 9).map { inn?.intAt(it) }
        val extras = if (inn != null && inn.size > 9) (9 until inn.size).mapNotNull { inn.intAt(it) } else emptyList()
        return TeamLine(
            code = g.str("${side}TeamCode"),
            name = g.str("${side}TeamName"),
            innings = innings,
            extra = if (extras.isEmpty()) null else extras.sum(),
            runs = rheb?.intOrNull("r") ?: g.int("${side}TeamScore"),
            hits = rheb?.intOrNull("h"),
            errors = rheb?.intOrNull("e"),
        )
    }
}
