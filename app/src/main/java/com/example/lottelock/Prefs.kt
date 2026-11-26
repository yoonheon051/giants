package com.example.lottelock

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** 선택할 수 있는 팀 */
enum class TeamChoice(val code: String, val category: String) {
    LOTTE("LT", "kbo"),
    KOREA("KR", ""),
}

class Prefs(context: Context) {
    private val appContext: Context = context.applicationContext
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

    /** 알림에 스코어보드 표시 (앱 화면의 스위치). 끄면 알림을 띄우지 않고, 아일랜드도 꺼져 있으면 실시간 갱신도 하지 않음 */
    var notificationsOn: Boolean
        get() = sp.getBoolean("notifications_on", true)
        set(v) = sp.edit { putBoolean("notifications_on", v) }

    /**
     * 아일랜드 표시 (앱 화면의 스위치). 켜 두면 화면 위에 알약이 늘 떠 있고,
     * 경기가 없으면 최근/오늘 경기를, 경기 중에는 실시간 점수를 보여준다 (경기 중 점수는 알림 대신 아일랜드로).
     * 알약을 길게 누르거나 스위치를 끄면 꺼진다.
     */
    var islandOn: Boolean
        get() = sp.getBoolean("island_on", false) || sp.getBoolean("island_pinned", false)
        set(v) = sp.edit { putBoolean("island_on", v); remove("island_pinned") }

    /** 아일랜드를 켰고 '다른 앱 위에 표시' 권한도 있음 */
    fun islandReady(): Boolean = islandOn && IslandController.canDraw(appContext)

    /** 실시간 갱신을 할지: 알림이나 아일랜드 중 하나라도 켜져 있으면 */
    fun liveEnabled(): Boolean = notificationsOn || islandReady()

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
    fun cachedComposite(gameId: String): JSONObject? {
        val arr = JSONArray(cachedGames)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .firstOrNull { it.optJSONObject("game")?.optString("gameId") == gameId && it.optJSONObject("record") != null }
    }

    /** 방금 끝난 경기를 종료 경기 캐시 맨 앞에 추가 */
    fun addFinishedGame(composite: JSONObject) {
        val id = composite.optJSONObject("game")?.optString("gameId")
        val old = JSONArray(cachedGames)
        val merged = JSONArray().put(composite)
        for (i in 0 until old.length()) {
            val g = old.optJSONObject(i) ?: continue
            if (g.optJSONObject("game")?.optString("gameId") != id && merged.length() < MAX_GAMES) merged.put(g)
        }
        cachedGames = merged.toString()
    }

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
