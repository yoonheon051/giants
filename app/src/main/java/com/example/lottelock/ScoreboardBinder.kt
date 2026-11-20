package com.example.lottelock

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 앱 화면의 전광판(디자인 시안 C)을 그린다.
 *  - 제목: 롯데 = "GIANTS"(남색 + 하늘색 테두리), 국가대표 = 태극 마크 + "TEAM KOREA"(위 빨강·아래 파랑)
 *  - 상태: 경기 전 / 경기 중 / 경기 종료
 *  - 라인스코어: 검은 칸에 주황 전광판 숫자, 응원 팀은 빨간색
 *  - 아래 띠: 승패·점수·갱신 시각 (경기 전: 실시간 갱신 예약·시작 시각)
 */
class ScoreboardBinder(root: View) {
    private val context = root.context
    private val density = context.resources.displayMetrics.density

    private val taegeuk: ImageView = root.findViewById(R.id.boardTaegeuk)
    private val titleStroke: TextView = root.findViewById(R.id.boardTitleStroke)
    private val title: TextView = root.findViewById(R.id.boardTitle)
    private val status: TextView = root.findViewById(R.id.boardStatus)
    private val meta: TextView = root.findViewById(R.id.boardMeta)
    private val lineScore: LinearLayout = root.findViewById(R.id.boardLineScore)
    private val empty: TextView = root.findViewById(R.id.boardEmpty)
    private val strip: View = root.findViewById(R.id.boardStrip)
    private val stripLeft: TextView = root.findViewById(R.id.stripLeft)
    private val stripCenter: TextView = root.findViewById(R.id.stripCenter)
    private val stripRight: TextView = root.findViewById(R.id.stripRight)

    private val led = ResourcesCompat.getFont(context, R.font.vt323)
    private var currentTeam: TeamChoice? = null

    fun bind(game: GameResult?, team: TeamChoice, notificationsOn: Boolean, refreshedAt: Long) {
        bindTitle(team)
        if (game == null) {
            status.visibility = View.GONE
            meta.visibility = View.GONE
            lineScore.visibility = View.GONE
            strip.visibility = View.GONE
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        listOf(status, meta, lineScore, strip).forEach { it.visibility = View.VISIBLE }

        // 상태 표시
        val (statusText, statusColor) = when (game.state) {
            GameState.SCHEDULED -> "경기 전" to AMBER
            GameState.LIVE -> "경기 중" to GREEN
            GameState.FINISHED -> "경기 종료" to LIGHT
            GameState.CANCELLED -> "경기 취소" to DIM
        }
        status.text = statusText
        status.setTextColor(statusColor)
        status.background = GradientDrawable().apply {
            setStroke(dp(1.5f).toInt(), statusColor)
            cornerRadius = dp(6f)
        }

        // 날짜 · (경기 전이면 시작 시각) · 구장 · (KBO가 아니면 대회 이름)
        val start = Instant.ofEpochSecond(game.timestamp).atZone(SEOUL_ZONE)
        meta.text = listOfNotNull(
            start.format(DATE_FMT) + if (game.state == GameState.SCHEDULED) " " + start.format(TIME_FMT) else "",
            game.stadium,
            game.category.takeIf { it.isNotBlank() && !it.equals("kbo", true) }?.uppercase(Locale.ROOT),
        ).joinToString(" · ")

        bindLineScore(game)

        // 아래 띠
        val score = "${game.away.runs} - ${game.home.runs}"
        val refreshed = if (refreshedAt > 0) Instant.ofEpochMilli(refreshedAt).atZone(SEOUL_ZONE).format(TIME_FMT) + " 갱신" else ""
        when (game.state) {
            GameState.SCHEDULED -> {
                stripLeft.text = if (notificationsOn) "실시간 갱신 예약됨" else ""
                stripLeft.setTextColor(GREEN)
                stripCenter.text = "PLAY BALL " + start.format(TIME_FMT)
                stripRight.text = ""
            }
            GameState.LIVE -> {
                stripLeft.text = "● " + game.liveLabel
                stripLeft.setTextColor(GREEN)
                stripCenter.text = score
                stripRight.text = refreshed
            }
            GameState.FINISHED -> {
                val (word, color) = when (game.outcome) {
                    Outcome.WIN -> "승" to RED
                    Outcome.LOSS -> "패" to LIGHT
                    Outcome.DRAW -> "무" to LIGHT
                }
                stripLeft.text = "${game.tracked.shortName} $word"
                stripLeft.setTextColor(color)
                stripCenter.text = score
                stripRight.text = refreshed
            }
            GameState.CANCELLED -> {
                stripLeft.text = game.liveLabel
                stripLeft.setTextColor(DIM)
                stripCenter.text = ""
                stripRight.text = refreshed
            }
        }
    }

    /** 제목: 아래 TextView는 테두리(stroke)만, 위 TextView는 채우기 → 외곽선 글자 */
    private fun bindTitle(team: TeamChoice) {
        if (team == currentTeam) return
        currentTeam = team
        val korea = team == TeamChoice.KOREA
        val text = if (korea) "TEAM KOREA" else "GIANTS"
        val size = if (korea) 28f else 36f
        taegeuk.visibility = if (korea) View.VISIBLE else View.GONE

        listOf(titleStroke, title).forEach {
            it.text = text
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }
        titleStroke.paint.apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(if (korea) 2f else 4f)   // 가운데 기준으로 그려서 바깥으로 절반만 보임
        }
        titleStroke.setTextColor(if (korea) WHITE else GIANTS_OUTLINE)

        if (korea) {
            // 태극: 위 빨강 · 아래 파랑 (경계를 살짝 기울임)
            title.setTextColor(WHITE)
            title.post {
                if (currentTeam != TeamChoice.KOREA) return@post   // 그새 팀이 바뀌었으면 무시
                val h = title.height.toFloat()
                title.paint.shader = LinearGradient(
                    0f, 0f, h * 0.14f, h,
                    intArrayOf(TAEGEUK_RED, TAEGEUK_RED, TAEGEUK_BLUE, TAEGEUK_BLUE),
                    floatArrayOf(0f, 0.48f, 0.52f, 1f),
                    Shader.TileMode.CLAMP,
                )
                title.invalidate()
            }
        } else {
            title.paint.shader = null
            title.setTextColor(GIANTS_NAVY)
        }
        title.contentDescription = text
    }

    private fun bindLineScore(g: GameResult) {
        lineScore.removeAllViews()
        val innings = (1..9).map { it.toString() } + (if (g.hasExtra) listOf("연") else emptyList())
        val labels = innings + listOf("R", "H", "E")

        // 이닝 번호 줄
        lineScore.addView(row(header = true).apply {
            addView(nameCell("", WHITE))
            labels.forEach { addView(ledCell(it, if (it == "R") AMBER else LABEL, header = true)) }
        })

        val homeWon = g.isFinished && g.home.runs > g.away.runs
        listOf(g.away to false, g.home to true).forEach { (line, isHome) ->
            val tracked = isHome == g.trackedIsHome
            lineScore.addView(row(header = false).apply {
                addView(nameCell(line.shortName, if (tracked) RED else WHITE))
                if (g.state == GameState.SCHEDULED) {
                    labels.forEach { addView(ledCell("-", EMPTY_CELL)) }
                    return@apply
                }
                line.innings.forEachIndexed { i, v ->
                    val text = v?.toString() ?: when {
                        !g.isFinished -> ""                                                  // 아직 안 한 이닝
                        isHome && i == 8 && homeWon && g.away.innings[8] != null -> "X"      // 9회말 공격 없이 홈팀 승
                        else -> "-"
                    }
                    addView(ledCell(text, AMBER))
                }
                if (g.hasExtra) addView(ledCell(line.extra?.toString() ?: if (g.isFinished) "-" else "", AMBER))
                addView(ledCell(line.runs.toString(), if (tracked) RED else LIGHT, trackedRuns = tracked))
                addView(ledCell(line.hits?.toString() ?: "-", LIGHT))
                addView(ledCell(line.errors?.toString() ?: "-", LIGHT))
            })
        }
    }

    private fun row(header: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { if (!header) topMargin = dp(3f).toInt() }
    }

    private fun nameCell(name: String, color: Int) = TextView(context).apply {
        text = name
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        paint.isFakeBoldText = true
        maxLines = 1
        layoutParams = LinearLayout.LayoutParams(dp(54f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun ledCell(text: String, color: Int, header: Boolean = false, trackedRuns: Boolean = false) = TextView(context).apply {
        this.text = text
        typeface = led
        gravity = Gravity.CENTER
        includeFontPadding = false
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 19f else 25f)
        if (!header) setBackgroundResource(if (trackedRuns) R.drawable.led_cell_tracked else R.drawable.led_cell)
        layoutParams = LinearLayout.LayoutParams(0, if (header) LinearLayout.LayoutParams.WRAP_CONTENT else dp(32f).toInt(), 1f)
            .apply { marginStart = dp(1.5f).toInt(); marginEnd = dp(1.5f).toInt() }
    }

    private fun dp(v: Float) = v * density

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

        private const val AMBER = 0xFFFFC24A.toInt()
        private const val GREEN = 0xFF6CFF9E.toInt()
        private const val LIGHT = 0xFFC8D4CD.toInt()
        private const val DIM = 0xFF9DB0A6.toInt()
        private const val LABEL = 0xFF7F978A.toInt()
        private const val EMPTY_CELL = 0xFF3A4A42.toInt()
        private const val RED = 0xFFFF5C73.toInt()
        private const val WHITE = 0xFFE8EDEA.toInt()
        private const val GIANTS_NAVY = 0xFF123A80.toInt()
        private const val GIANTS_OUTLINE = 0xFF9CCBF0.toInt()
        private const val TAEGEUK_RED = 0xFFE8394A.toInt()
        private const val TAEGEUK_BLUE = 0xFF3A78E0.toInt()
    }
}
