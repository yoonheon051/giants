package com.example.lottelock

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 메인 화면 (디자인 시안 "B + C").
 * 위: 제목 + 응원 팀 선택 / 가운데: 전광판 / 아래: 결과 불러오기, 설정 목록, 실시간 갱신 중지.
 * 데모 경기는 화면에서 뺐고, 제목 "스코어보드"를 길게 누르면 실행된다(테스트용).
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var board: ScoreboardBinder
    private lateinit var boardBg: ImageView
    private lateinit var boardScrim: View
    private lateinit var teamChip: TextView
    private lateinit var notifSwitch: Switch
    private lateinit var bgValue: TextView

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                turnNotificationsOn()
            } else {
                setSwitchSilently(false)
                prefs.notificationsOn = false
                toast("알림 권한이 없어 스코어보드를 표시할 수 없습니다. '휴대폰 알림 설정'에서 허용해 주세요.")
            }
        }

    /** 자르기 화면 → 완료하면 잘린 사진이 저장돼 있음 */
    private val cropBackground =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                toast("배경 이미지를 바꿨습니다.")
                applyBackground()
            }
        }

    /** 시스템 사진 선택기 (저장소 권한 필요 없음) → 자르기 화면으로 */
    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) { runCatching { prepareCropSource(uri) }.getOrNull() }
                if (file == null) {
                    toast("이미지를 불러오지 못했습니다.")
                } else {
                    cropBackground.launch(
                        Intent(this@MainActivity, CropActivity::class.java).putExtra(CropActivity.EXTRA_PATH, file.path)
                    )
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        ScoreNotifier.ensureChannel(this)

        board = ScoreboardBinder(findViewById(R.id.boardCard))
        findViewById<FrameLayout>(R.id.boardCard).clipToOutline = true   // 둥근 모서리로 배경 사진 자르기
        boardBg = findViewById(R.id.boardBg)
        boardScrim = findViewById(R.id.boardScrim)
        teamChip = findViewById(R.id.teamChip)
        notifSwitch = findViewById(R.id.notifSwitch)
        bgValue = findViewById(R.id.bgValue)

        // 응원 팀 선택
        teamChip.setOnClickListener { showTeamMenu() }

        // 숨은 기능: 제목을 길게 누르면 데모 경기 (테스트용)
        findViewById<TextView>(R.id.appTitle).setOnLongClickListener {
            if (hasNotificationPermission()) {
                LiveController.startNow(this, demo = true)
                toast("데모 경기 시작: 20초마다 반 이닝씩 진행됩니다.")
            } else {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            true
        }

        findViewById<View>(R.id.btnRefresh).setOnClickListener { refresh() }

        // 알림에 스코어보드 표시
        setSwitchSilently(prefs.notificationsOn && hasNotificationPermission())
        notifSwitch.setOnCheckedChangeListener { _, on -> if (on) ensurePermissionThenTurnOn() else turnNotificationsOff() }

        findViewById<View>(R.id.rowBackground).setOnClickListener { showBackgroundMenu() }
        findViewById<View>(R.id.rowNotifSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<View>(R.id.btnStopLive).setOnClickListener {
            LiveController.stop(this)
            toast("실시간 갱신을 멈췄습니다.")
        }

        UpdateWorker.schedule(this)
        bindTeamChip()
        applyBackground()

        // 화면이 보이는 동안 5초마다 전광판 갱신 (실시간 갱신·데모 진행 반영)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    bindBoard()
                    delay(5_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 휴대폰 설정에서 알림 권한을 바꾸고 돌아온 경우 반영
        if (notifSwitch.isChecked && !hasNotificationPermission()) setSwitchSilently(false)
    }

    // ── 응원 팀

    private fun showTeamMenu() {
        PopupMenu(this, teamChip).apply {
            menu.add(0, 0, 0, teamName(TeamChoice.LOTTE))
            menu.add(0, 1, 1, teamName(TeamChoice.KOREA))
            setOnMenuItemClickListener { item ->
                selectTeam(if (item.itemId == 1) TeamChoice.KOREA else TeamChoice.LOTTE)
                true
            }
        }.show()
    }

    private fun teamName(team: TeamChoice) = when (team) {
        TeamChoice.LOTTE -> "롯데 자이언츠"
        TeamChoice.KOREA -> "국가대표"
    }

    private fun bindTeamChip() {
        teamChip.text = teamName(prefs.team)
    }

    /** 팀을 바꾸면 예전 팀 결과·실시간 갱신을 정리하고 새 팀 결과를 바로 불러온다 */
    private fun selectTeam(choice: TeamChoice) {
        if (choice == prefs.team) return
        LiveController.stop(this)   // 예전 팀 경기로 예약된 알람까지 취소 (새 팀 경기는 refresh가 다시 예약)
        prefs.team = choice
        prefs.cachedGames = "[]"
        prefs.liveGame = ""
        prefs.upcomingGame = ""
        prefs.lastNotifiedSignature = ""
        bindTeamChip()
        bindBoard()
        refresh()
    }

    // ── 결과 불러오기

    private fun refresh() {
        toast("불러오는 중…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ScoreUpdater.refresh(this@MainActivity) } }
            result.onSuccess { msg ->
                toast(msg)
                UpdateWorker.schedule(this@MainActivity)
                if (prefs.notificationsOn && hasNotificationPermission() && !LiveScoreService.running) {
                    ScoreNotifier.update(this@MainActivity, force = true)
                }
            }.onFailure { toast("불러오지 못했습니다: ${it.message}") }
            bindBoard()
        }
    }

    private fun bindBoard() {
        val game = runCatching { ScoreUpdater.currentGame(this, includeUpcoming = true) }.getOrNull()
        board.bind(game, prefs.team, prefs.notificationsOn, prefs.lastRefreshAt)
    }

    // ── 알림 표시 스위치

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenTurnOn() {
        if (hasNotificationPermission()) turnNotificationsOn()
        else requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun turnNotificationsOn() {
        prefs.notificationsOn = true
        setSwitchSilently(true)
        if (!ScoreNotifier.canNotify(this)) {
            toast("이 앱의 알림이 꺼져 있습니다. '휴대폰 알림 설정'에서 켜 주세요.")
            return
        }
        prefs.lastNotifiedSignature = ""
        if (!ScoreNotifier.update(this, force = true)) toast("먼저 '결과 불러오기'를 누르세요.")
        bindBoard()
    }

    private fun turnNotificationsOff() {
        prefs.notificationsOn = false
        LiveController.stop(this)   // 돌고 있는 서비스와 예약된 시작 알람 모두
        ScoreNotifier.cancel(this)
        bindBoard()
    }

    /** 리스너를 거치지 않고 스위치 상태만 바꿈 */
    private fun setSwitchSilently(on: Boolean) {
        if (!::notifSwitch.isInitialized) return
        notifSwitch.setOnCheckedChangeListener(null)
        notifSwitch.isChecked = on
        notifSwitch.setOnCheckedChangeListener { _, checked -> if (checked) ensurePermissionThenTurnOn() else turnNotificationsOff() }
    }

    // ── 전광판 배경

    private fun showBackgroundMenu() {
        val items = if (hasBackground()) arrayOf("사진 선택", "기본 배경으로") else arrayOf("사진 선택")
        AlertDialog.Builder(this)
            .setTitle("스코어보드 배경")
            .setItems(items) { _, i ->
                if (i == 0) {
                    pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    prefs.appBackgroundFile.delete()
                    applyBackground()
                    toast("기본 배경으로 되돌렸습니다.")
                }
            }
            .show()
    }

    private fun hasBackground() = prefs.appBackgroundFile.exists()

    /** 배경 사진이 있으면 전광판 뒤에 깔고 어둡게 덮음 */
    private fun applyBackground() {
        val bmp = if (hasBackground()) runCatching { BitmapFactory.decodeFile(prefs.appBackgroundFile.path) }.getOrNull() else null
        boardBg.setImageBitmap(bmp)
        val visibility = if (bmp != null) View.VISIBLE else View.GONE
        boardBg.visibility = visibility
        boardScrim.visibility = visibility
        bgValue.text = getString(if (bmp != null) R.string.bg_photo else R.string.bg_default)
        bindBoard()
    }

    /**
     * 고른 사진을 자르기 화면에서 다루기 좋은 크기(긴 변 2048px 이하)로 줄여 임시 파일로 저장.
     * 사진 방향(세로로 찍은 사진 등)은 ImageDecoder가 알아서 바로잡는다.
     */
    private fun prepareCropSource(uri: Uri): java.io.File {
        val maxSide = 2048
        val source = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        val file = java.io.File(cacheDir, "crop_source.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return file
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
