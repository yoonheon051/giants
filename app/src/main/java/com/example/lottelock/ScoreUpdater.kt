package com.example.lottelock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

object ScoreUpdater {

    fun seoulToday(): LocalDate = LocalDate.now(ScoreParser.SEOUL)

    /**
     * 최근 경기 목록을 받아
     *  - 가장 최근 종료 경기(이닝별 기록 포함)를 캐시에 저장하고
     *  - 오늘 경기가 있으면 실시간 갱신을 시작하거나 경기 시작 시각에 예약한다.
     * [deep]이면(사용자가 직접 누름) 최근 2주에 경기가 없을 때 2주씩 더 거슬러 올라가며 찾고,
     * 아니면(3시간 주기 자동 갱신) 최근 2주만 본다 — 새 경기는 항상 그 안에 있으므로.
     * @return 사용자에게 보여줄 요약
     */
    fun refresh(context: Context, deep: Boolean = true): String {
        val prefs = Prefs(context)
        val client = prefs.client()
        val team = prefs.teamCode
        val category = prefs.categoryId
        val today = seoulToday()
        val hasCache = JSONArray(prefs.cachedGames).length() > 0
        val maxWindows = when {
            !deep -> 1
            hasCache -> 6       // 약 3개월
            else -> 13          // 약 6개월
        }

        var todayGame: JSONObject? = null
        var finals: List<JSONObject> = emptyList()
        for (k in 0 until maxWindows) {
            val to = today.minusDays(14L * k)
            val games = client.schedule(category, team, to.minusDays(13), to)
            if (k == 0) todayGame = ScoreParser.findToday(games, team, today)
            finals = ScoreParser.pickFinished(games, team, Prefs.MAX_GAMES)
            if (finals.isNotEmpty()) break
        }

        if (finals.isNotEmpty()) {
            // 가장 최근 경기만 기록(이닝별 점수)을 받고, 이미 받아둔 경기는 다시 받지 않음
            val composites = finals.mapIndexed { i, g ->
                val id = g.optString("gameId")
                prefs.cachedComposite(id) ?: ScoreParser.compose(g, if (i == 0) client.record(id) else null)
            }
            prefs.cachedGames = JSONArray(composites).toString()
        }

        val today0 = todayGame?.let { ScoreParser.parseComposite(ScoreParser.compose(it, null), team) }
        // 서비스가 비정상 종료돼 남은 '진행 중' 경기 정리 (실시간 서비스가 돌고 있으면 건드리지 않음)
        if (today0?.isLive != true && !LiveScoreService.running) prefs.liveGame = ""
        // 오늘 예정 경기는 앱 전광판에 '경기 전'으로 보여줌 (구장 정보가 있으면 함께)
        prefs.upcomingGame = if (todayGame != null && today0?.state == GameState.SCHEDULED) {
            ScoreParser.compose(todayGame!!, client.record(todayGame!!.optString("gameId"))).toString()
        } else ""
        prefs.lastRefreshAt = System.currentTimeMillis()

        val todayMsg = when {
            today0 == null -> ""
            !prefs.notificationsOn -> ""   // 알림을 끄면 실시간 갱신도 하지 않음
            today0.isLive -> {
                LiveController.startFromBackground(context)
                " · 오늘 경기 진행 중 → 실시간 갱신 시작"
            }
            today0.state == GameState.SCHEDULED -> {
                LiveController.scheduleStart(context, today0.timestamp * 1000)
                " · 오늘 경기 시작 시각에 실시간 갱신 예약"
            }
            else -> ""
        }

        val latest = currentGame(context)
        val latestMsg = latest?.let { "최근 경기: ${ScoreNotifier.title(it)} (${ScoreNotifier.summary(it)})" }
            ?: "최근 경기를 찾지 못했습니다"
        return latestMsg + todayMsg
    }

    /**
     * 보여줄 경기: 진행 중 경기 > (앱 화면이면) 오늘 예정 경기 > 가장 최근 종료 경기.
     * 알림은 예정 경기를 보여주지 않는다([includeUpcoming] = false).
     */
    fun currentGame(context: Context, includeUpcoming: Boolean = false): GameResult? {
        val prefs = Prefs(context)
        val team = prefs.teamCode
        fun parse(json: String) = json.takeIf { it.isNotBlank() }
            ?.let { runCatching { ScoreParser.parseComposite(JSONObject(it), team) }.getOrNull() }

        val live = parse(prefs.liveGame)
        if (live != null) return live
        val latestFinal = ScoreParser.parseComposites(JSONArray(prefs.cachedGames), team).firstOrNull()
        if (includeUpcoming) {
            val upcoming = parse(prefs.upcomingGame)
            val isToday = upcoming != null &&
                java.time.Instant.ofEpochSecond(upcoming.timestamp).atZone(ScoreParser.SEOUL).toLocalDate() == seoulToday()
            if (upcoming != null && isToday && upcoming.state == GameState.SCHEDULED && upcoming.id != latestFinal?.id) {
                return upcoming
            }
        }
        return latestFinal
    }
}
