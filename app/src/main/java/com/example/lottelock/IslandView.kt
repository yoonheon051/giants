// Lucian UX/UI Prompt #459 · MIT License (c) 2026 Seokwoooo · https://lucianlabs.dev/uxui-prompt/#459
package com.example.lottelock

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 화면 위에 떠 있는 검은 알약 스코어보드 (평소엔 최근 경기, 경기 중엔 실시간 점수).
 * Lucian UX/UI #459 '다이내믹 아일랜드'의 모양과 움직임을 Android 뷰로 옮겼다.
 *  - 크기·모서리·곡선은 원본 값 그대로: 접힘 206×36(모서리 18) → 펼침 316×132,
 *    크기는 cubic-bezier(.3,1.32,.5,1) 580ms 스프링, 내용은 나가는 쪽 160ms 페이드 → 들어오는 쪽 130ms 뒤 300ms 페이드 + 0.9배에서 커짐
 *  - 알약 색은 테마와 상관없이 늘 검정 (다크 모드에서만 옅은 흰 테두리)
 *  - 원본의 '옆으로 밀어 활동 바꾸기'는 경기 하나만 보여주므로 뺐다
 *
 * 한 번 누르면 펼쳐지고(이닝별 점수), 펼친 상태에서 한 번 더 누르면 [onOpenApp].
 * 바깥을 누르거나 8초 동안 그대로 두면 다시 접힌다. 길게 누르면 [onDismiss] (아일랜드 끄기).
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : View(context) {

    var onOpenApp: () -> Unit = {}
    var onDismiss: () -> Unit = {}
    private var longPressed = false
    private val longPress = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pressScale(1f, 450L, releaseCurve)
        onDismiss()
    }

    var game: GameResult? = null
        set(v) {
            field = v
            updateDescription()
            invalidate()
        }

    var expanded = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    // 원본 #459의 모양별 크기: 접힘 [206, 36, 18], 펼침은 라인스코어가 들어가도록 높이만 키움
    private val cw = dp(206f); private val ch = dp(36f); private val cr = dp(18f)
    private val ew = dp(316f); private val eh = dp(132f); private val er = dp(44f)

    private var progress = 0f     // 0 접힘 → 1 펼침 (스프링이라 1을 살짝 넘었다 돌아옴)
    private var contentT = 1f     // 내용 전환 진행 (시간 기준 0→1)
    private var animator: ValueAnimator? = null

    private val sizeCurve = PathInterpolator(0.3f, 1.32f, 0.5f, 1f)
    private val releaseCurve = PathInterpolator(0.34f, 1.6f, 0.5f, 1f)

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BLACK }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = 0x21FFFFFF   // rgba(255,255,255,.13)
    }
    private val semibold = Typeface.create(Typeface.DEFAULT, 600, false)
    private val bold = Typeface.create(Typeface.DEFAULT, 700, false)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }

    private val pill = RectF()
    private val clip = Path()

    private val collapseLater = Runnable { setExpanded(false) }

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    // ── 크기: 진행률에 따라 접힘 ↔ 펼침 사이 (창이 WRAP_CONTENT라 창도 같이 커짐)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(lerp(cw, ew, progress).roundToInt(), lerp(ch, eh, progress).roundToInt())
    }

    fun setExpanded(v: Boolean) {
        removeCallbacks(collapseLater)
        if (v) postDelayed(collapseLater, AUTO_COLLAPSE_MS)
        if (v == expanded) return
        expanded = v
        updateDescription()
        animator?.cancel()
        val from = progress
        val to = if (v) 1f else 0f
        if (!ValueAnimator.areAnimatorsEnabled()) {   // 애니메이션 끔(동작 줄이기) → 바로 바뀜
            progress = to; contentT = 1f
            requestLayout(); invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SIZE_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                progress = from + (to - from) * sizeCurve.getInterpolation(t)
                contentT = t
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    // ── 누르기: 접혀 있으면 펼치고, 펼쳐져 있으면 앱 열기

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { if (expanded) setExpanded(false); return false }
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                pressScale(0.965f, 120L, null)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longPressed) return true
                pressScale(1f, 450L, releaseCurve)
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); pressScale(1f, 450L, releaseCurve) }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (expanded) {
            setExpanded(false)
            onOpenApp()
        } else {
            setExpanded(true)
        }
        return true
    }

    private fun pressScale(s: Float, ms: Long, curve: PathInterpolator?) {
        animate().scaleX(s).scaleY(s).setDuration(ms).setInterpolator(curve ?: LinearInterpolator()).start()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(collapseLater)
        removeCallbacks(longPress)
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // ── 그리기

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val r = lerp(cr, er, progress.coerceIn(0f, 1.05f))
        pill.set(0f, 0f, w, h)
        canvas.drawRoundRect(pill, r, r, pillPaint)
        if (isNight()) {
            val half = strokePaint.strokeWidth / 2
            pill.inset(half, half)
            canvas.drawRoundRect(pill, r, r, strokePaint)
            pill.set(0f, 0f, w, h)
        }
        val g = game

        clip.reset()
        clip.addRoundRect(pill, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)

        // 나가는 내용은 빨리 사라지고(160ms), 들어오는 내용은 130ms 뒤 300ms 동안 나타남 (원본 .u459-v 전환)
        val outA = (1f - contentT / 0.28f).coerceIn(0f, 1f)
        val inA = ((contentT - 0.22f) / 0.52f).coerceIn(0f, 1f)
        val collapsedA = if (expanded) outA else inA
        val expandedA = if (expanded) inA else outA
        if (collapsedA > 0f) layer(canvas, cw, collapsedA) {
            if (g != null) drawCollapsed(it, g) else drawCentered(it, "불러온 경기가 없습니다", cw / 2, ch / 2, 12.5f, semibold, DIM)
        }
        if (expandedA > 0f) layer(canvas, ew, expandedA) {
            if (g != null) drawExpanded(it, g) else {
                drawCentered(it, "아직 불러온 경기가 없습니다", ew / 2, dp(52f), 14f, semibold, WHITE)
                drawCentered(it, "앱에서 '결과 불러오기'를 눌러 주세요", ew / 2, dp(76f), 12f, semibold, DIM)
                drawCentered(it, "한 번 더 누르면 앱 열기", ew / 2, dp(118f), 11f, semibold, FAINT)
            }
        }
        canvas.restore()
    }

    /** [contentW] 폭의 내용을 가운데 위쪽 기준으로, 투명도·크기(0.9→1)를 적용해 그림 */
    private inline fun layer(canvas: Canvas, contentW: Float, alpha: Float, draw: (Canvas) -> Unit) {
        val s = 0.9f + 0.1f * alpha
        val saved = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (alpha * 255).roundToInt())
        canvas.translate((width - contentW) / 2f, 0f)
        canvas.scale(s, s, contentW / 2f, 0f)
        draw(canvas)
        canvas.restoreToCount(saved)
    }

    /** 접힘: 원정 이름 점수 · (자리가 있으면 상태) · 점수 이름 홈 */
    private fun drawCollapsed(c: Canvas, g: GameResult) {
        val pad = dp(14f)
        val cy = ch / 2f

        fun teamWidth(line: TeamLine): Float =
            measure(line.shortName, 12.5f, semibold) + dp(5f) + measure(line.runs.toString(), 15f, bold)

        val awayW = teamWidth(g.away)
        val homeW = teamWidth(g.home)

        // 원정 (왼쪽)
        var x = pad
        x += drawText(c, g.away.shortName, x, cy, 12.5f, semibold, nameColor(g, home = false))
        x += dp(5f)
        drawText(c, g.away.runs.toString(), x, cy, 15f, bold, WHITE)

        // 홈 (오른쪽)
        x = cw - pad - homeW
        x += drawText(c, g.home.runs.toString(), x, cy, 15f, bold, WHITE)
        x += dp(5f)
        drawText(c, g.home.shortName, x, cy, 12.5f, semibold, nameColor(g, home = true))

        // 가운데 상태 (예: ● 7회말) — 들어갈 자리가 있을 때만
        val status = statusLabel(g)
        val statusW = measure(status, 11.5f, semibold)
        val free = cw - pad * 2 - awayW - homeW - dp(12f)
        if (statusW <= free) {
            val left = pad + awayW + (free + dp(12f) - statusW) / 2
            drawText(c, status, left, cy, 11.5f, semibold, statusColor(g))
        }
    }

    /** 펼침: 상태·날짜·구장 / 라인스코어 / '누르면 앱 열기' */
    private fun drawExpanded(c: Canvas, g: GameResult) {
        val pad = dp(18f)
        val contentW = ew - pad * 2

        // 윗줄
        val headY = dp(26f)
        val statusW = drawText(c, statusLabel(g), pad, headY, 13f, semibold, statusColor(g))
        val meta = listOfNotNull(
            Instant.ofEpochSecond(g.timestamp).atZone(ScoreParser.SEOUL).format(DATE_FMT),
            g.stadium,
        ).joinToString(" · ")
        val metaRoom = contentW - statusW - dp(12f)
        if (metaRoom > 0) {
            val shown = ellipsize(meta, 12f, semibold, metaRoom)
            drawText(c, shown, ew - pad - measure(shown, 12f, semibold), headY, 12f, semibold, DIM)
        }

        // 라인스코어
        val labels = (1..9).map { it.toString() } + (if (g.hasExtra) listOf("연") else emptyList()) + listOf("R", "H", "E")
        val teamCol = dp(46f)
        val cell = (contentW - teamCol) / labels.size
        val rowH = dp(20f)
        var rowY = dp(52f)
        labels.forEachIndexed { i, s -> drawCentered(c, s, pad + teamCol + cell * (i + 0.5f), rowY, 11f, semibold, FAINT) }

        val homeWon = g.isFinished && g.home.runs > g.away.runs
        listOf(g.away to false, g.home to true).forEach { (line, isHome) ->
            rowY += rowH
            val name = ellipsize(line.shortName, 13f, semibold, teamCol - dp(4f))
            drawText(c, name, pad, rowY, 13f, semibold, nameColor(g, isHome))
            val cells = line.innings.mapIndexed { i, v ->
                v?.toString() ?: when {
                    !g.isFinished -> ""                                                  // 아직 안 한 이닝
                    isHome && i == 8 && homeWon && g.away.innings[8] != null -> "X"      // 9회말 공격 없이 홈팀 승
                    else -> "-"
                }
            } + (if (g.hasExtra) listOf(line.extra?.toString() ?: if (g.isFinished) "-" else "") else emptyList())
            cells.forEachIndexed { i, s -> drawCentered(c, s, pad + teamCol + cell * (i + 0.5f), rowY, 13.5f, semibold, WHITE) }
            val base = cells.size
            val tracked = isHome == g.trackedIsHome
            drawCentered(c, line.runs.toString(), pad + teamCol + cell * (base + 0.5f), rowY, 14.5f, bold, if (tracked) ORANGE else WHITE)
            drawCentered(c, line.hits?.toString() ?: "-", pad + teamCol + cell * (base + 1.5f), rowY, 13.5f, semibold, DIM)
            drawCentered(c, line.errors?.toString() ?: "-", pad + teamCol + cell * (base + 2.5f), rowY, 13.5f, semibold, DIM)
        }

        drawCentered(c, "한 번 더 누르면 앱 열기", ew / 2, dp(118f), 11f, semibold, FAINT)
    }

    // ── 글자 도우미 (y는 글자 세로 가운데)

    private fun setText(sizeSp: Float, face: Typeface, color: Int) {
        text.textSize = sp(sizeSp); text.typeface = face; text.color = color
    }

    private fun measure(s: String, sizeSp: Float, face: Typeface): Float {
        setText(sizeSp, face, WHITE)
        return text.measureText(s)
    }

    /** @return 그린 폭 */
    private fun drawText(c: Canvas, s: String, x: Float, cy: Float, sizeSp: Float, face: Typeface, color: Int): Float {
        setText(sizeSp, face, color)
        c.drawText(s, x, cy - (text.ascent() + text.descent()) / 2, text)
        return text.measureText(s)
    }

    private fun drawCentered(c: Canvas, s: String, cx: Float, cy: Float, sizeSp: Float, face: Typeface, color: Int) {
        if (s.isEmpty()) return
        setText(sizeSp, face, color)
        c.drawText(s, cx - text.measureText(s) / 2, cy - (text.ascent() + text.descent()) / 2, text)
    }

    private fun ellipsize(s: String, sizeSp: Float, face: Typeface, room: Float): String {
        setText(sizeSp, face, WHITE)
        return TextUtils.ellipsize(s, text, room, TextUtils.TruncateAt.END).toString()
    }

    // ── 상태·색

    private fun statusLabel(g: GameResult) = when (g.state) {
        GameState.LIVE -> "● " + g.liveLabel
        GameState.FINISHED -> "경기 종료"
        GameState.SCHEDULED -> "경기 전"
        GameState.CANCELLED -> g.liveLabel
    }

    private fun statusColor(g: GameResult) = when (g.state) {
        GameState.LIVE -> GREEN
        GameState.SCHEDULED -> ORANGE
        else -> DIM
    }

    private fun nameColor(g: GameResult, home: Boolean) = if (home == g.trackedIsHome) RED else WHITE

    private fun updateDescription() {
        val g = game
        contentDescription = if (g == null) "스코어보드" else {
            "${g.scoreLine}, ${statusLabel(g).removePrefix("● ")}" +
                if (expanded) ". 펼침, 한 번 더 누르면 앱 열기" else ". 누르면 펼침"
        }
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    companion object {
        private const val SIZE_MS = 580L
        private const val AUTO_COLLAPSE_MS = 8_000L
        private val DATE_FMT = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

        // 원본 #459 색: 검정 알약, 초록 #30D158, 주황 #FF9F0A, 흐린 흰색 55%
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val DIM = 0x8CFFFFFF.toInt()
        private const val FAINT = 0x73FFFFFF   // 이닝 번호·안내 문구 (흰색 45%)
        private const val GREEN = 0xFF30D158.toInt()
        private const val ORANGE = 0xFFFF9F0A.toInt()
        private const val RED = 0xFFFF5C73.toInt()   // 앱 전광판의 응원 팀 빨강
    }
}
