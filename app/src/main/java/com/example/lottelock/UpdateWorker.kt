package com.example.lottelock

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 3시간마다 결과를 받아와 새 경기가 있으면 알림을 갱신하고,
 * 오늘 경기가 있으면 시작 시각에 실시간 갱신을 예약한다.
 * 평소에는 한 번에 요청 1~2회 (비시즌에는 경기를 찾느라 몇 번 더).
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        val now = SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(Date())
        try {
            ScoreUpdater.refresh(applicationContext, deep = false)
            // 실시간 서비스가 돌고 있으면 알림은 서비스가 관리
            val changed = !LiveScoreService.running && ScoreNotifier.update(applicationContext, force = false)
            IslandController.sync(applicationContext)   // 떠 있는 아일랜드도 새 결과로
            prefs.lastStatus = "$now 자동 갱신 " + if (changed) "(새 결과)" else "(변경 없음)"
            Result.success()
        } catch (e: IOException) {
            prefs.lastStatus = "$now 네트워크 오류: ${e.message}"
            Result.retry()
        } catch (e: Exception) {
            prefs.lastStatus = "$now 오류: ${e.message}"
            Result.success()
        }
    }

    companion object {
        private const val NAME = "lotte-scoreboard"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
