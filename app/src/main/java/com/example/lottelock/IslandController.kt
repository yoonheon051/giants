package com.example.lottelock

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager

/**
 * 아일랜드 알약([IslandView])을 다른 앱 위에 띄우고 내린다.
 *  - '다른 앱 위에 표시' 권한이 필요하고, 화면 맨 위 상태표시줄 바로 아래 가운데에 뜬다.
 *  - 잠금화면에서는 시스템이 오버레이를 가리므로 보이지 않는다.
 *  - 어느 스레드에서 불러도 되도록 창 작업은 메인 스레드로 넘긴다.
 *
 * 누가 그리나:
 *  - 경기 중 실시간 서비스가 아일랜드 모드로 돌면([LiveScoreService.islandActive]) 서비스가 [show]로 실시간 점수를 그린다.
 *  - 그 밖에는 [sync]/[showIdle]이 저장된 경기(진행 중 > 오늘 예정 > 최근 종료)를 그린다.
 */
object IslandController {
    private const val NO_PERMISSION = "'다른 앱 위에 표시' 권한이 없습니다"

    private val main = Handler(Looper.getMainLooper())
    private var view: IslandView? = null
    private var winCtx: Context? = null

    /** 마지막으로 아일랜드를 띄우지 못한 이유 (앱 화면에서 보여줌). 띄우면 null */
    @Volatile var lastError: String? = null
        private set

    /** 알약을 길게 눌러 껐을 때 앱 화면의 스위치를 갱신하려고 (앱 화면이 떠 있을 때만 등록) */
    var onIslandChanged: (() -> Unit)? = null

    val isShowing: Boolean get() = view != null

    fun canDraw(context: Context) = Settings.canDrawOverlays(context)

    // ── 표시

    /** [game]으로 알약을 띄우거나 내용을 바꾼다. null이면 '불러온 경기가 없습니다' */
    fun show(context: Context, game: GameResult?) {
        val app = context.applicationContext
        main.post { showNow(windowContext(app), game) }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { hideNow(windowContext(app)) }
    }

    /** 아일랜드를 켰으면 저장된 경기로 띄우고, 껐거나 권한이 없으면 내린다 */
    fun showIdle(context: Context) {
        val prefs = Prefs(context)
        when {
            !prefs.islandOn -> hide(context)
            !canDraw(context) -> { lastError = NO_PERMISSION; hide(context) }
            else -> show(context, runCatching { ScoreUpdater.currentGame(context, includeUpcoming = true) }.getOrNull())
        }
    }

    /** 앱 화면·주기 갱신에서 부름: 실시간 서비스가 아일랜드를 그리는 중이면 그대로 두고, 아니면 [showIdle] */
    fun sync(context: Context) {
        if (!LiveScoreService.islandActive) showIdle(context)
    }

    /** 아일랜드 끄기 (앱의 스위치 또는 알약 길게 누르기). 알림도 꺼져 있으면 실시간 갱신도 멈춘다 */
    fun disable(context: Context) {
        val prefs = Prefs(context)
        prefs.islandOn = false
        hide(context)
        if (prefs.liveEnabled()) LiveController.refreshUi(context)   // 경기 중이면 알림으로 다시 표시
        else LiveController.stop(context)
        onIslandChanged?.invoke()
    }

    // ── 창 다루기 (메인 스레드)

    private fun showNow(ctx: Context, game: GameResult?) {
        view?.let { it.game = game; return }
        if (!canDraw(ctx)) { lastError = NO_PERMISSION; return }
        val v = IslandView(ctx).apply {
            this.game = game
            onOpenApp = { openApp(ctx) }
            onDismiss = { disable(ctx) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 키보드·뒤로 가기와 알약 바깥 터치는 아래 앱으로 (바깥 터치는 접기 신호로만 받음)
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight(ctx) + (6 * ctx.resources.displayMetrics.density).toInt()
            title = "LotteScoreboardIsland"
        }
        runCatching { ctx.getSystemService(WindowManager::class.java).addView(v, lp) }
            .onSuccess { view = v; lastError = null }
            .onFailure {
                lastError = "${it.javaClass.simpleName}: ${it.message}"
                Prefs(ctx).lastStatus = "아일랜드 표시 실패: $lastError"
            }
    }

    private fun hideNow(ctx: Context) {
        val v = view ?: return
        view = null
        runCatching { ctx.getSystemService(WindowManager::class.java).removeView(v) }
    }

    /** 펼친 알약을 한 번 더 누르면 앱 화면으로 ('다른 앱 위에 표시' 권한이 있으면 백그라운드에서도 화면을 열 수 있음) */
    private fun openApp(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /** Android 11+는 창을 띄울 때 창 전용 Context를 쓰도록 권장 (앱 Context로 WindowManager를 쓰면 경고) */
    private fun windowContext(app: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app
        return winCtx ?: run {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).also { winCtx = it }
        }
    }

    private fun statusBarHeight(ctx: Context): Int {
        @Suppress("DiscouragedApi")
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }
}
