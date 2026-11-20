package com.example.lottelock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 실시간 갱신 서비스 시작/예약.
 *
 * 안드로이드 12+는 백그라운드(WorkManager 등)에서 포그라운드 서비스를 바로 시작하지 못한다.
 * 정확한 알람(exact alarm)으로 깨어난 경우는 예외라서, 백그라운드에서는 항상 알람을 거쳐 시작한다.
 */
object LiveController {

    /** 앱 화면에서 직접 시작 (포그라운드라 바로 가능) */
    fun startNow(context: Context, demo: Boolean = false) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LiveScoreService::class.java).putExtra(LiveScoreService.EXTRA_DEMO, demo),
        )
    }

    /** 백그라운드에서 시작: 1초 뒤 알람 → 리시버 → 서비스 */
    fun startFromBackground(context: Context) = scheduleStart(context, System.currentTimeMillis() + 1_000)

    /** [atMillis]에 실시간 갱신 시작 (경기 시작 시각) */
    fun scheduleStart(context: Context, atMillis: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = startIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val at = maxOf(atMillis, System.currentTimeMillis() + 1_000)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)   // 권한 없으면 부정확 알람 (시작이 늦어질 수 있음)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 실시간 서비스를 멈추고, 아직 울리지 않은 시작 알람도 취소한다 (안 그러면 경기 시작 시각에 다시 켜짐) */
    fun stop(context: Context) {
        context.stopService(Intent(context, LiveScoreService::class.java))
        cancelScheduled(context)
    }

    /** 예약해 둔 시작 알람 취소. 다음 결과 갱신에서 오늘 경기가 있으면 다시 예약된다 */
    fun cancelScheduled(context: Context) {
        val pi = startIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pi)
        pi.cancel()
    }

    private fun startIntent(context: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, GameStartReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or flag,
    )
}

class GameStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 알람을 예약한 뒤 '알림에 스코어보드 표시'를 껐으면 시작하지 않는다 (끄면 실시간 갱신도 안 하기로 했으므로)
        if (!Prefs(context).notificationsOn) return
        runCatching { LiveController.startNow(context) }
            .onFailure { Prefs(context).lastStatus = "실시간 갱신 시작 실패: ${it.message}" }
    }
}
