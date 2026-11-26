package com.example.lottelock

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 경기 중에만 도는 실시간 갱신 서비스. 이 서비스의 포그라운드 알림이 곧 스코어보드 알림이다.
 *  - 화면이 켜져 있으면 5분마다, 꺼져 있으면 15분마다 (종료 감지용) 점수 확인
 *  - 화면을 켜는 순간 바로 확인 (단, 직전 확인 후 3분 이내면 생략 → API 한도 보호)
 *  - 점수가 바뀌었을 때만 알림 갱신
 *  - 경기가 끝나면 최종 스코어 알림을 남기고 스스로 종료
 *  - 아일랜드를 켰으면([Prefs.islandReady]) 점수는 화면 위 알약으로 보여주고,
 *    알림은 시스템이 요구하는 조용한 한 줄 알림만 남긴다
 */
class LiveScoreService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var demo = false
    private var demoGame: DemoGame? = null
    private var lastPollAt = 0L
    private var startedAt = 0L
    private val pollNow = Channel<Unit>(Channel.CONFLATED)
    private var shownAsIsland: Boolean? = null   // 마지막으로 쓴 표시 방식 (바뀌면 알림을 새로 올림)
    private lateinit var prefs: Prefs

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pollNow.trySend(Unit)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = Prefs(this)
        startedAt = System.currentTimeMillis()
        // 첫 알림은 지금 표시 중인 경기 그대로 (점수 알림이 잠깐 사라지지 않도록)
        val island = prefs.islandReady()
        islandActive = island
        shownAsIsland = island
        ServiceCompat.startForeground(
            this, ScoreNotifier.NOTIFICATION_ID,
            if (island) ScoreNotifier.buildIslandService(this, null, "경기 확인 중")
            else ScoreNotifier.build(this, ScoreUpdater.currentGame(this), "경기 확인 중"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        prefs.lastNotifiedSignature = ""
        ContextCompat.registerReceiver(this, screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 앱 화면에서 아일랜드·알림 설정을 바꿈 → 지금 상태를 새 방식으로 다시 표시
        if (intent?.getBooleanExtra(EXTRA_REFRESH_UI, false) == true && loop?.isActive == true) {
            scope.launch { refreshUi() }
            return START_NOT_STICKY
        }
        val wantDemo = intent?.getBooleanExtra(EXTRA_DEMO, false) ?: false
        if (loop?.isActive != true || wantDemo != demo) {
            loop?.cancel()
            demo = wantDemo
            demoRunning = wantDemo
            demoGame = if (wantDemo) DemoGame(prefs.teamCode) else null
            lastPollAt = 0L
            loop = scope.launch { runLoop() }
        }
        return START_NOT_STICKY   // 죽으면 3시간 주기 작업이 오늘 경기를 다시 확인해 재시작함
    }

    private suspend fun runLoop() {
        while (scope.isActive) {
            if (System.currentTimeMillis() - startedAt > MAX_RUNTIME_MS) {
                setStatus("최대 실행 시간 초과로 실시간 갱신 종료"); break
            }
            val throttle = if (demo) 3_000L else 3 * 60_000L
            if (System.currentTimeMillis() - lastPollAt >= throttle) {
                lastPollAt = System.currentTimeMillis()
                if (pollOnce()) break
            }
            val interval = when {
                demo -> 20_000L
                isScreenOn() -> 5 * 60_000L
                else -> 15 * 60_000L
            }
            withTimeoutOrNull(interval) { pollNow.receive() }
        }
        stopSelf()   // 정리는 onDestroy에서
    }

    /** @return 실시간 갱신을 끝내야 하면 true */
    private suspend fun pollOnce(): Boolean {
        val team = prefs.teamCode
        val composite = try {
            if (demo) {
                demoGame!!.next()
            } else {
                if (prefs.requestsToday >= Prefs.LIVE_REQUEST_LIMIT) {
                    setStatus("오늘 요청 ${prefs.requestsToday}회 → 과도한 요청 방지를 위해 실시간 갱신 중단"); return true
                }
                val client = prefs.client()
                val today = ScoreUpdater.seoulToday()
                val raw = ScoreParser.findToday(client.schedule(prefs.categoryId, team, today, today), team, today)
                    ?: run { setStatus("오늘 경기가 없습니다."); return true }
                // 진행 중·종료 경기만 이닝별 기록을 받음 (예정 경기는 목록만으로 충분)
                val needRecord = ScoreParser.stateOf(raw).let { it == GameState.LIVE || it == GameState.FINISHED }
                ScoreParser.compose(raw, if (needRecord) client.record(raw.optString("gameId")) else null)
            }
        } catch (e: IOException) {
            setStatus("네트워크 오류, 다음 주기에 재시도: ${e.message}"); return false
        } catch (e: Exception) {
            setStatus("실시간 갱신 오류: ${e.message}"); return true
        }

        val game = ScoreParser.parseComposite(composite, team) ?: return true
        val note = if (demo) "데모" else null
        val score = game.scoreLine

        return when (game.state) {
            GameState.LIVE -> {
                prefs.liveGame = composite.toString()
                val changed = present(game, note)
                setStatus("${game.liveLabel} $score" + if (changed) " (갱신됨)" else "")
                false
            }
            GameState.FINISHED -> {
                prefs.liveGame = composite.toString()
                if (!demo) prefs.upcomingGame = ""   // 오늘 예정 경기가 끝났으므로
                if (demo) {
                    present(game, "데모 · 1분 뒤 원래대로")
                    delay(60_000L)   // 데모: 최종 스코어를 잠깐 보여준 뒤 실제 최근 경기로
                } else {
                    prefs.addFinishedGame(composite)
                }
                setStatus("경기 종료: $score")
                true
            }
            GameState.CANCELLED -> { setStatus("경기 취소/중단"); true }
            GameState.SCHEDULED -> {
                // 우천 지연 등으로 시작이 늦어질 수 있어 시작 예정 3시간 뒤까지 기다림.
                // 그동안 알림은 직전 경기 결과 + "오늘 경기 대기 중"
                prefs.liveGame = ""
                present(null, WAITING)
                System.currentTimeMillis() > game.timestamp * 1000 + 3 * 3600_000L
            }
        }
    }

    /**
     * 지금 경기 상황을 표시. 아일랜드 모드면 알약 + 조용한 서비스 알림, 아니면 기존 스코어보드 알림.
     * [game]이 null이면 아직 시작 전 (아일랜드는 내리고, 알림은 직전 경기 + [note]).
     * @return 표시 내용이 바뀌었으면 true
     */
    private fun present(game: GameResult?, note: String?): Boolean {
        val island = prefs.islandReady()
        val switched = island != shownAsIsland
        shownAsIsland = island
        islandActive = island
        return if (island) {
            if (game != null) IslandController.show(this, game) else IslandController.showIdle(this)
            ScoreNotifier.notifyIslandService(this, game, note, force = switched)
            true
        } else {
            IslandController.showIdle(this)   // 직접 띄운 아일랜드가 있으면 그대로 (진행 중 경기로 갱신)
            ScoreNotifier.update(this, force = switched, note = note)
        }
    }

    private fun refreshUi() {
        val live = prefs.liveGame.takeIf { it.isNotBlank() }
            ?.let { runCatching { ScoreParser.parseComposite(org.json.JSONObject(it), prefs.teamCode) }.getOrNull() }
        present(live, if (live == null) WAITING else if (demo) "데모" else null)
    }

    override fun onDestroy() {
        // 경기 종료·중지 버튼·시간 제한 등 어떤 이유로 끝나든:
        // 아일랜드를 내리고, 알림을 서비스에서 떼어 남겨두고(DETACH), 최종/최근 경기 내용으로 다시 올린다.
        islandActive = false
        prefs.liveGame = ""
        IslandController.showIdle(this)   // 직접 띄운 아일랜드면 최근 경기로 남기고, 아니면 내림
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        if (!ScoreNotifier.update(this, force = true)) ScoreNotifier.cancel(this)
        running = false
        demoRunning = false
        runCatching { unregisterReceiver(screenOnReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    /** Android 15: dataSync 서비스는 하루 6시간 제한. 초과 시 호출됨 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        setStatus("시스템 시간 제한으로 실시간 갱신 종료")
        stopSelf()
    }

    private fun isScreenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun setStatus(msg: String) {
        val time = SimpleDateFormat("M/d HH:mm", Locale.KOREAN).format(Date())
        prefs.lastStatus = "$time $msg"
    }

    companion object {
        const val EXTRA_DEMO = "demo"
        const val EXTRA_REFRESH_UI = "refresh_ui"
        private const val WAITING = "오늘 경기 시작 대기 중"
        private const val MAX_RUNTIME_MS = 5 * 3600_000L

        @Volatile var running = false
            private set
        @Volatile var demoRunning = false
            private set
        /** 서비스가 점수를 아일랜드로 보여주는 중 (이때는 스코어보드 알림에 진행 중 경기를 올리지 않음) */
        @Volatile var islandActive = false
            private set
    }
}
