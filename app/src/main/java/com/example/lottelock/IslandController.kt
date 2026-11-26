package com.example.lottelock

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.Display
import android.view.WindowManager

/**
 * 경기 중 점수 알약([IslandView])을 다른 앱 위에 띄우고 내린다.
 * '다른 앱 위에 표시' 권한이 필요하고, 화면 맨 위 상태표시줄 바로 아래 가운데에 뜬다.
 * 잠금화면에서는 시스템이 오버레이를 가리므로 보이지 않는다.
 * 어느 스레드에서 불러도 되도록 창 작업은 메인 스레드로 넘긴다.
 */
object IslandController {
    private val main = Handler(Looper.getMainLooper())
    private var view: IslandView? = null

    fun canDraw(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, game: GameResult) {
        val app = context.applicationContext
        main.post { showNow(windowContext(app), game) }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        main.post { hideNow(windowContext(app)) }
    }

    private fun showNow(ctx: Context, game: GameResult) {
        view?.let { it.game = game; return }
        if (!canDraw(ctx)) return
        val v = IslandView(ctx).apply {
            this.game = game
            onOpenApp = { openApp(ctx) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 키보드·뒤로 가기는 아래 앱으로, 알약 바깥 터치도 아래 앱으로 (바깥 터치는 접기 신호로만 받음)
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
            .onSuccess { view = v }
            .onFailure { Prefs(ctx).lastStatus = "아일랜드 표시 실패: ${it.message}" }
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
    private var winCtx: Context? = null
    private fun windowContext(app: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return app
        return winCtx ?: run {
            val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            app.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null).also { winCtx = it }
        }
    }

    private fun statusBarHeight(ctx: Context): Int {
        @Suppress("DiscouragedApi", "InternalInsetsDefinition")
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else 0
    }
}
