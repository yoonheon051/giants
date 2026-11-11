package com.example.lottelock

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.StyleSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 스코어보드 알림.
 *  - 접힌 상태: "롯데 7 : 0 한화" / "승 · 9월 22일 (화) · 대전"  (잠금화면에서 주로 보이는 부분)
 *  - 펼친 상태: 이닝별 라인스코어 (1~9회, 연장, R H E)
 * 실시간 서비스가 돌 때는 서비스의 포그라운드 알림이 곧 이 알림이다 (같은 ID).
 */
object ScoreNotifier {
    const val CHANNEL_ID = "scoreboard_v2"
    private const val OLD_CHANNEL_ID = "scoreboard"
    const val NOTIFICATION_ID = 1

    private const val TRACKED_COLOR = 0xFFE8344E.toInt()            // 밝은/어두운 알림 테마 모두에서 보이는 롯데 레드
    private const val TRACKED_COLOR_ON_IMAGE = 0xFFFF5C73.toInt()   // 앱 배경 사진 위에서 더 잘 보이는 밝은 레드
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val WHITE_DIM = 0xB3FFFFFF.toInt()
    private val DATE_FMT = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

    // [행][열]  행: 0=이닝 번호, 1=원정, 2=홈 / 열: 0=팀, 1~9=이닝, 10=연장, 11=R, 12=H, 13=E
    private val CELLS: Array<IntArray> = arrayOf(
        intArrayOf(R.id.c0_team, R.id.c0_1, R.id.c0_2, R.id.c0_3, R.id.c0_4, R.id.c0_5, R.id.c0_6, R.id.c0_7, R.id.c0_8, R.id.c0_9, R.id.c0_x, R.id.c0_r, R.id.c0_h, R.id.c0_e),
        intArrayOf(R.id.c1_team, R.id.c1_1, R.id.c1_2, R.id.c1_3, R.id.c1_4, R.id.c1_5, R.id.c1_6, R.id.c1_7, R.id.c1_8, R.id.c1_9, R.id.c1_x, R.id.c1_r, R.id.c1_h, R.id.c1_e),
        intArrayOf(R.id.c2_team, R.id.c2_1, R.id.c2_2, R.id.c2_3, R.id.c2_4, R.id.c2_5, R.id.c2_6, R.id.c2_7, R.id.c2_8, R.id.c2_9, R.id.c2_x, R.id.c2_r, R.id.c2_h, R.id.c2_e),
    )
    private const val COL_EXTRA = 10
    private const val COL_R = 11
    private const val COL_H = 12
    private const val COL_E = 13

    /**
     * 중요도 '기본' + 소리·진동 끔.
     * '낮음' 채널은 안드로이드가 알림창 아래쪽 '무음 알림' 구역으로 내려 보내기 때문에,
     * 소리만 없앤 '기본' 채널로 일반 알림들과 같은 위쪽 구역에 표시되게 한다. (팝업은 뜨지 않음)
     * 채널 중요도는 만든 뒤 앱에서 바꿀 수 없어서 새 ID로 만들고 예전 채널은 지운다.
     */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(CHANNEL_ID, "경기 스코어보드", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "최근 경기 최종 스코어와 진행 중인 경기 점수"
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 접힌 상태 제목/내용 (앱 화면 미리보기에서도 사용) */
    fun title(game: GameResult): String =
        game.scoreLine

    fun summary(game: GameResult, note: String? = null): String =
        listOfNotNull(statusText(game), date(game), game.stadium, note).joinToString(" · ")

    fun build(context: Context, game: GameResult?, note: String? = null): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // 잠금화면에서도 점수까지 표시
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)      // Android 7 이하용 (8+는 채널 중요도를 따름)
            .setSound(null)
            .setVibrate(null)
        if (game == null) {
            return b.setContentTitle("스코어보드").setContentText(note ?: "경기 결과가 없습니다").build()
        }
        return b.setContentTitle(title(game))
            .setContentText(summary(game, note))
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomBigContentView(lineScoreView(context, game, note))
            .build()
    }

    /**
     * 펼친 상태의 라인스코어. 알림에서는 기본 색상 그대로,
     * 앱 화면에서 배경 사진 위에 그릴 때는 [onImage]=true로 글자를 흰색으로 바꾼다.
     */
    fun lineScoreView(
        context: Context, game: GameResult, note: String? = null,
        onImage: Boolean = false, showHeader: Boolean = true,
    ): RemoteViews {
        val rv = RemoteViews(context.packageName, R.layout.notification_scoreboard)
        rv.setTextViewText(R.id.sb_header, summary(game, note))
        if (!showHeader) rv.setViewVisibility(R.id.sb_header, View.GONE)   // 앱 화면은 위에 요약이 이미 있음
        if (onImage) {
            rv.setTextColor(R.id.sb_header, WHITE)
            CELLS[0].forEach { rv.setTextColor(it, WHITE_DIM) }
            (CELLS[1] + CELLS[2]).forEach { rv.setTextColor(it, WHITE) }
        }

        val extraVisibility = if (game.hasExtra) View.VISIBLE else View.GONE
        for (row in CELLS) rv.setViewVisibility(row[COL_EXTRA], extraVisibility)

        val homeWon = game.isFinished && game.home.runs > game.away.runs
        listOf(game.away to false, game.home to true).forEachIndexed { i, (line, isHome) ->
            val ids = CELLS[i + 1]
            val isTracked = isHome == game.trackedIsHome
            // 진행 중에는 양 팀 점수 모두 굵게, 종료 후에는 이긴 팀만
            val runsBold = !game.isFinished || line.runs > (if (isHome) game.away.runs else game.home.runs)

            rv.setTextViewText(ids[0], bold(line.shortName))
            if (isTracked) rv.setTextColor(ids[0], if (onImage) TRACKED_COLOR_ON_IMAGE else TRACKED_COLOR)

            line.innings.forEachIndexed { inning, v ->
                val text = v?.toString() ?: when {
                    !game.isFinished -> ""                                                 // 아직 안 한 이닝
                    isHome && inning == 8 && homeWon && game.away.innings[8] != null -> "X" // 9회말 공격 없이 홈팀 승
                    else -> "-"
                }
                rv.setTextViewText(ids[inning + 1], text)
            }
            rv.setTextViewText(ids[COL_EXTRA], line.extra?.toString() ?: if (game.isFinished) "-" else "")
            rv.setTextViewText(ids[COL_R], if (runsBold) bold(line.runs.toString()) else line.runs.toString())
            rv.setTextViewText(ids[COL_H], line.hits?.toString() ?: "-")
            rv.setTextViewText(ids[COL_E], line.errors?.toString() ?: "-")
        }
        return rv
    }

    /**
     * 현재 경기로 알림을 올린다. [force]가 false면 내용이 바뀌었을 때만.
     * @return 알림을 올렸으면 true
     */
    @SuppressLint("MissingPermission")   // canNotify()로 확인
    fun update(context: Context, force: Boolean, note: String? = null): Boolean {
        val prefs = Prefs(context)
        val game = ScoreUpdater.currentGame(context) ?: return false
        if (!canNotify(context)) return false
        val sig = listOf(
            game.id, game.state, game.statusInfo, game.away.innings, game.home.innings,
            game.away.runs, game.home.runs, game.away.hits, game.home.hits, note, prefs.teamCode,
        ).joinToString("|")
        if (!force && sig == prefs.lastNotifiedSignature) return false
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(context, game, note))
            prefs.lastNotifiedSignature = sig
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        Prefs(context).lastNotifiedSignature = ""
    }

    private fun statusText(game: GameResult): String = when {
        !game.isFinished -> "● ${game.liveLabel}"
        game.outcome == Outcome.WIN -> "승"
        game.outcome == Outcome.LOSS -> "패"
        else -> "무"
    }

    private fun date(game: GameResult): String =
        Instant.ofEpochSecond(game.timestamp).atZone(ScoreParser.SEOUL).format(DATE_FMT)

    private fun bold(s: String) = SpannableString(s).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, 0) }
}
