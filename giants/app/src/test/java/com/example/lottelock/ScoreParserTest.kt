package com.example.lottelock

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** 실제 네이버 스포츠 응답(2026-09 경기)을 줄여서 사용 */
class ScoreParserTest {

    private val scheduleBody = """
        {"code":200,"success":true,"result":{"games":[
          {"gameId":"20260920SSLT02026","categoryId":"kbo","gameDate":"2026-09-20","gameDateTime":"2026-09-20T14:00:00","homeTeamCode":"LT","homeTeamName":"롯데","homeTeamScore":13,"awayTeamCode":"SS","awayTeamName":"삼성","awayTeamScore":6,"winner":"HOME","statusCode":"RESULT","statusInfo":"9회초","cancel":false,"suspended":false},
          {"gameId":"20260922KTSK02026","categoryId":"kbo","gameDate":"2026-09-22","gameDateTime":"2026-09-22T18:30:00","homeTeamCode":"SK","homeTeamName":"SSG","homeTeamScore":2,"awayTeamCode":"KT","awayTeamName":"KT","awayTeamScore":8,"winner":"AWAY","statusCode":"RESULT","statusInfo":"9회말","cancel":false,"suspended":false},
          {"gameId":"20260922LTHH02026","categoryId":"kbo","gameDate":"2026-09-22","gameDateTime":"2026-09-22T18:30:00","homeTeamCode":"HH","homeTeamName":"한화","homeTeamScore":0,"awayTeamCode":"LT","awayTeamName":"롯데","awayTeamScore":7,"winner":"AWAY","statusCode":"RESULT","statusInfo":"9회말","cancel":false,"suspended":false},
          {"gameId":"20260923LTHH02026","categoryId":"kbo","gameDate":"2026-09-23","gameDateTime":"2026-09-23T18:30:00","homeTeamCode":"HH","homeTeamName":"한화","homeTeamScore":3,"awayTeamCode":"LT","awayTeamName":"롯데","awayTeamScore":5,"statusCode":"STARTED","statusInfo":"7회말","cancel":false,"suspended":false},
          {"gameId":"20260924LTLG02026","categoryId":"kbo","gameDate":"2026-09-24","gameDateTime":"2026-09-24T18:30:00","homeTeamCode":"LG","homeTeamName":"LG","homeTeamScore":0,"awayTeamCode":"LT","awayTeamName":"롯데","awayTeamScore":0,"statusCode":"CANCEL","statusInfo":"경기취소","cancel":true,"suspended":false}
        ],"gameTotalCount":5}}
    """.trimIndent()

    private val recordBody = """
        {"code":200,"success":true,"result":{"recordData":{
          "gameInfo":{"aFullName":"롯데 자이언츠","hCode":"HH","hName":"한화","gdate":20260922,"gtime":"18:30","aName":"롯데","stadium":"대전","aCode":"LT","statusCode":"4"},
          "scoreBoard":{"rheb":{"away":{"r":7,"b":5,"e":1,"h":11},"home":{"r":0,"b":1,"e":0,"h":5}},
                        "inn":{"away":[0,0,5,0,0,2,0,0,0],"home":[0,0,0,0,0,0,0,0,0]}},
          "currentInning":"9"}}}
    """.trimIndent()

    private val games = ScoreParser.parseSchedule(scheduleBody).games

    @Test fun picksLotteFinishedGamesNewestFirst() {
        val picked = ScoreParser.pickFinished(games, "LT", 3).map { it.optString("gameId") }
        assertEquals(listOf("20260922LTHH02026", "20260920SSLT02026"), picked)
        // 한국어 팀명으로도 매칭
        assertEquals(2, ScoreParser.pickFinished(games, "롯데", 3).size)
    }

    @Test fun parsesRealLineScore() {
        val raw = games.first { it.optString("gameId") == "20260922LTHH02026" }
        val g = ScoreParser.parseComposite(ScoreParser.compose(raw, ScoreParser.parseRecord(recordBody)), "LT")!!
        assertFalse(g.trackedIsHome)
        assertEquals("롯데", g.tracked.shortName)
        assertEquals("한화", g.opponent.shortName)
        assertEquals(listOf(0, 0, 5, 0, 0, 2, 0, 0, 0), g.away.innings)
        assertEquals(7, g.away.runs); assertEquals(11, g.away.hits); assertEquals(1, g.away.errors)
        assertEquals(0, g.home.runs); assertEquals(5, g.home.hits)
        assertNull(g.away.extra)
        assertEquals("대전", g.stadium)
        assertEquals("롯데 7 : 0 한화", g.scoreLine)    // 롯데 원정 → 앞
        assertEquals(GameState.FINISHED, g.state)
        assertEquals(Outcome.WIN, g.outcome)
    }

    @Test fun worksWithoutRecord() {
        val raw = games.first { it.optString("gameId") == "20260920SSLT02026" }
        val g = ScoreParser.parseComposite(ScoreParser.compose(raw, null), "LT")!!
        assertTrue(g.trackedIsHome)
        assertEquals(13, g.home.runs)
        assertEquals("삼성 6 : 13 롯데", g.scoreLine)   // 롯데 홈 → 뒤 (원정 : 홈)
        assertEquals(List(9) { null }, g.home.innings)
        assertNull(g.stadium)
    }

    @Test fun extraInningsAndUnplayedBottomNinth() {
        val raw = JSONObject(games.first { it.optString("gameId") == "20260920SSLT02026" }.toString())
        val sb = JSONObject("""{"inn":{"away":[0,1,0,0,2,0,0,0,0,0,1],"home":[1,0,0,2,0,0,0,0,0,0,"-"]},"rheb":{"away":{"r":4},"home":{"r":3}}}""")
        val g = ScoreParser.parseComposite(ScoreParser.compose(raw, JSONObject().put("scoreBoard", sb)), "LT")!!
        assertEquals(1, g.away.extra)
        assertEquals(0, g.home.extra)
        assertTrue(g.hasExtra)
        assertEquals(4, g.away.runs)   // rheb 우선

        val sb2 = JSONObject("""{"inn":{"away":[0,0,0,0,0,0,0,0,0],"home":[2,0,1,0,0,2,0,0,"X"]}}""")
        val g2 = ScoreParser.parseComposite(ScoreParser.compose(raw, JSONObject().put("scoreBoard", sb2)), "LT")!!
        assertNull(g2.home.innings[8])
    }

    @Test fun statesAndToday() {
        fun state(id: String) = ScoreParser.stateOf(games.first { it.optString("gameId") == id })
        assertEquals(GameState.FINISHED, state("20260922LTHH02026"))
        assertEquals(GameState.LIVE, state("20260923LTHH02026"))
        assertEquals(GameState.CANCELLED, state("20260924LTLG02026"))

        val today = ScoreParser.findToday(games, "LT", LocalDate.of(2026, 9, 23))!!
        val g = ScoreParser.parseComposite(ScoreParser.compose(today, null), "LT")!!
        assertTrue(g.isLive)
        assertEquals("7회말", g.liveLabel)
        assertNull(ScoreParser.findToday(games, "LT", LocalDate.of(2026, 9, 24)))   // 취소 경기 제외
    }

    @Test fun failedResponseThrows() {
        try {
            ScoreParser.parseSchedule("""{"code":500,"success":false,"message":"error"}""")
            fail()
        } catch (e: ApiException) {
            assertEquals("error", e.message)
        }
    }

    @Test fun clientSplitsByDayWhenPartialPage() {
        val requested = mutableListOf<String>()
        val client = NaverSportsClient(fetch = { url ->
            requested += url
            val partial = !url.contains("fromDate=2026-09-20&toDate=2026-09-20") && !url.contains("fromDate=2026-09-21&toDate=2026-09-21")
            if (partial) """{"success":true,"result":{"games":[],"gameTotalCount":35}}"""
            else """{"success":true,"result":{"games":[{"gameId":"x"}],"gameTotalCount":1}}"""
        })
        val result = client.schedule("kbo", "LT", LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 21))
        assertEquals(3, requested.size)          // 전체 1회 + 하루씩 2회
        assertEquals(2, result.size)
        assertTrue(requested[0].contains("teamCode=LT") && requested[0].contains("size=100"))
    }

    @Test fun demoGamePlaysToCompletionThroughParser() {
        repeat(50) { seed ->
            val demo = DemoGame("LT", Random(seed))
            var last: GameResult? = null
            var steps = 0
            while (!demo.isOver) {
                last = ScoreParser.parseComposite(demo.next(), "LT")!!
                steps++
                if (!demo.isOver) assertTrue(last.isLive && last.liveLabel.matches(Regex("[1-9]회[초말]")))
            }
            val g = last!!
            assertTrue(g.isFinished)
            assertTrue(steps in 17..18)
            assertEquals("롯데", g.tracked.shortName)
            assertEquals(g.home.runs, g.home.innings.sumOf { it ?: 0 })
            if (g.home.innings[8] == null) assertTrue(g.home.runs > g.away.runs)
        }
        assertEquals(1, ScoreParser.parseComposites(JSONArray().put(DemoGame("LT").next()), "LT").size)
    }

    /** 실제 네이버 APBC 2026 일정 (일부) */
    @Test fun apbcKoreaGames() {
        val body = """
            {"code":200,"success":true,"result":{"games":[
              {"gameId":"88881112AUKR02026","categoryId":"apbc","gameDate":"2026-11-12","gameDateTime":"2026-11-12T12:00:00","homeTeamCode":"KR","homeTeamName":"대한민국","homeTeamScore":0,"awayTeamCode":"AU","awayTeamName":"호주","awayTeamScore":0,"winner":"DRAW","statusCode":"BEFORE","statusInfo":"경기전","cancel":false,"suspended":false},
              {"gameId":"20261109KBO1","categoryId":"kbaseballetc","gameDate":"2026-11-12","gameDateTime":"2026-11-12T17:00:00","homeTeamCode":"","homeTeamName":"","homeTeamScore":0,"awayTeamCode":"","awayTeamName":"","awayTeamScore":0,"winner":"DRAW","statusCode":"BEFORE","statusInfo":"","cancel":false,"suspended":false},
              {"gameId":"88881112JPTW02026","categoryId":"apbc","gameDate":"2026-11-12","gameDateTime":"2026-11-12T19:00:00","homeTeamCode":"TW","homeTeamName":"차이니스 타이베이","homeTeamScore":0,"awayTeamCode":"JP","awayTeamName":"일본","awayTeamScore":0,"winner":"DRAW","statusCode":"BEFORE","statusInfo":"경기전","cancel":false,"suspended":false},
              {"gameId":"88881113KRJP02026","categoryId":"apbc","gameDate":"2026-11-13","gameDateTime":"2026-11-13T19:00:00","homeTeamCode":"JP","homeTeamName":"일본","homeTeamScore":0,"awayTeamCode":"KR","awayTeamName":"대한민국","awayTeamScore":0,"winner":"DRAW","statusCode":"BEFORE","statusInfo":"경기전","cancel":false,"suspended":false}
            ],"gameTotalCount":4}}
        """.trimIndent()
        val apbc = ScoreParser.parseSchedule(body).games
        val nov12 = ScoreParser.parseComposite(ScoreParser.compose(ScoreParser.findToday(apbc, "KR", LocalDate.of(2026, 11, 12))!!, null), "KR")!!
        assertEquals("88881112AUKR02026", nov12.id)
        assertEquals(GameState.SCHEDULED, nov12.state)
        assertTrue(nov12.trackedIsHome)
        assertEquals("호주", nov12.opponent.shortName)
        // 12:00 KST 시작 → 실시간 갱신 예약 시각
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 12, 12, 0, 0, 0, ScoreParser.SEOUL).toEpochSecond(), nov12.timestamp)

        val nov13 = ScoreParser.parseComposite(ScoreParser.compose(ScoreParser.findToday(apbc, "대한민국", LocalDate.of(2026, 11, 13))!!, null), "대한민국")!!
        assertFalse(nov13.trackedIsHome)
        assertEquals("일본", nov13.opponent.shortName)
        // 팀 코드가 빈 기타 경기는 매칭되지 않음
        assertTrue(ScoreParser.pickFinished(apbc, "KR", 3).isEmpty())
        assertNull(ScoreParser.findToday(apbc, "KR", LocalDate.of(2026, 11, 14)))
    }

    @Test fun nationalTeamQueriesAllBaseballCategories() {
        val urls = mutableListOf<String>()
        val client = NaverSportsClient(fetch = { url -> urls += url; """{"success":true,"result":{"games":[],"gameTotalCount":0}}""" })
        client.schedule(TeamChoice.KOREA.category, TeamChoice.KOREA.code, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 15))
        client.schedule(TeamChoice.LOTTE.category, TeamChoice.LOTTE.code, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14))
        assertFalse(urls[0].contains("categoryId"))           // 국가대표: 대회 분류 무관
        assertTrue(urls[0].contains("teamCode=KR"))
        assertTrue(urls[1].contains("categoryId=kbo") && urls[1].contains("teamCode=LT"))
    }

    @Test fun koreaDemoGame() {
        val g = ScoreParser.parseComposite(DemoGame(TeamChoice.KOREA.code, Random(1)).next(), "KR")!!
        assertEquals("대한민국", g.tracked.shortName)
        assertEquals("일본", g.opponent.shortName)
        assertTrue(g.scoreLine.startsWith("일본 "))      // 데모는 추적 팀이 홈 → 상대가 앞
    }

    @Test fun pregameRecordKeepsStadiumAndCategory() {
        // 경기 전: 점수판 없이 구장만 있는 기록
        val rec = ScoreParser.parseRecord("""{"code":200,"success":true,"result":{"recordData":{"gameInfo":{"stadium":"도쿄돔"}}}}""")!!
        val raw = JSONObject("""{"gameId":"88881112AUKR02026","categoryId":"apbc","gameDateTime":"2026-11-12T12:00:00","homeTeamCode":"KR","homeTeamName":"대한민국","homeTeamScore":0,"awayTeamCode":"AU","awayTeamName":"호주","awayTeamScore":0,"statusCode":"BEFORE","statusInfo":"경기전","cancel":false,"suspended":false}""")
        val g = ScoreParser.parseComposite(ScoreParser.compose(raw, rec), "KR")!!
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("도쿄돔", g.stadium)
        assertEquals("apbc", g.category)
        assertEquals(List(9) { null }, g.home.innings)
        assertEquals("호주 0 : 0 대한민국", g.scoreLine)
        // 기록이 아예 없으면 null
        assertNull(ScoreParser.parseRecord("""{"code":200,"success":true,"result":{}}"""))
    }
}
