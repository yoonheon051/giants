package com.example.lottelock

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.ZoneId

// 응원 팀 목록(TeamChoice)은 아이폰과 함께 쓰도록 shared 모듈에 있다.

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lotte_lock", Context.MODE_PRIVATE)

    /** 앱 화면 스코어보드 카드 뒤에 깔 배경 사진 (사용자가 고른 이미지를 줄여서 복사해 둠). 없으면 기본 배경 */
    val appBackgroundFile = java.io.File(context.filesDir, "app_background.jpg")

    /** 응원 팀 (앱 화면의 토글로 선택) */
    var team: TeamChoice
        get() = runCatching { TeamChoice.valueOf(sp.getString("team_choice", null) ?: "") }.getOrDefault(TeamChoice.LOTTE)
        set(v) = sp.edit { putString("team_choice", v.name) }

    /** 네이버 팀 코드 (롯데 "LT", 국가대표 "KR") */
    val teamCode: String get() = team.code

    /** 네이버 리그 분류 ("kbo"). 빈 값이면 야구 전체 (국가대표는 APBC·WBC 등 대회마다 분류가 달라서) */
    val categoryId: String get() = team.category

    /** 종료된 경기 composite JSON 배열 (최신순) */
    var cachedGames: String
        get() = sp.getString("cached_games_v2", "[]").orEmpty()
        set(v) = sp.edit { putString("cached_games_v2", v) }

    /** 진행 중인 경기 composite JSON ("" = 없음). 있으면 이 경기를 알림에 표시 */
    var liveGame: String
        get() = sp.getString("live_game_v2", "").orEmpty()
        set(v) = sp.edit { putString("live_game_v2", v) }

    /** 오늘 열릴 예정인 경기 composite JSON ("" = 없음). 앱 전광판에 '경기 전'으로 표시 */
    var upcomingGame: String
        get() = sp.getString("upcoming_game", "").orEmpty()
        set(v) = sp.edit { putString("upcoming_game", v) }

    /** 알림에 스코어보드 표시 (앱 화면의 스위치). 끄면 알림과 실시간 갱신을 하지 않음 */
    var notificationsOn: Boolean
        get() = sp.getBoolean("notifications_on", true)
        set(v) = sp.edit { putBoolean("notifications_on", v) }

    /** 마지막으로 결과를 불러온 시각 (epoch ms, 0 = 없음) */
    var lastRefreshAt: Long
        get() = sp.getLong("last_refresh_at", 0L)
        set(v) = sp.edit { putLong("last_refresh_at", v) }

    /** 마지막으로 띄운 알림 내용의 서명. 같으면 알림을 다시 올리지 않음 */
    var lastNotifiedSignature: String
        get() = sp.getString("last_notified_sig", "").orEmpty()
        set(v) = sp.edit { putString("last_notified_sig", v) }

    var lastStatus: String
        get() = sp.getString("last_status", "").orEmpty()
        set(v) = sp.edit { putString("last_status", v) }

    /** 캐시에 있는 경기 composite (기록을 다시 받지 않으려고) */
    fun cachedComposite(gameId: String): JsonObject? =
        cachedList().firstOrNull { it.obj("game")?.str("gameId") == gameId && it.obj("record") != null }

    /** 방금 끝난 경기를 종료 경기 캐시 맨 앞에 추가 */
    fun addFinishedGame(composite: JsonObject) {
        val id = composite.obj("game")?.str("gameId")
        val rest = cachedList().filter { it.obj("game")?.str("gameId") != id }
        cachedGames = JsonArray((listOf(composite) + rest).take(MAX_GAMES)).toString()
    }

    private fun cachedList(): List<JsonObject> = parseJsonArray(cachedGames).mapNotNull { it as? JsonObject }

    // ── 하루 요청 수 (비공식 API라 과하게 부르지 않도록)
    private fun today() = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()

    fun countRequest() {
        val count = if (sp.getString("calls_date", "") == today()) sp.getInt("calls_count", 0) else 0
        sp.edit { putString("calls_date", today()); putInt("calls_count", count + 1) }
    }

    val requestsToday: Int
        get() = if (sp.getString("calls_date", "") == today()) sp.getInt("calls_count", 0) else 0

    fun client() = NaverSportsClient(onRequest = ::countRequest)

    companion object {
        const val MAX_GAMES = 3
        /** 실시간 갱신은 하루 이 요청 수를 넘으면 멈춤 (보통 경기 하나에 100회 안팎) */
        const val LIVE_REQUEST_LIMIT = 400
    }
}
