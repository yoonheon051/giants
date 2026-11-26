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
    private lateinit var islandSwitch: Switch
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

    /** '다른 앱 위에 표시' 설정 화면에서 돌아옴 → 허용했으면 아일랜드 켜기 (결과 코드는 믿을 수 없어 권한을 직접 확인) */
    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (IslandController.canDraw(this)) {
                turnIslandOn()
            } else {
                setIslandSwitchSilently(false)
                toast("'다른 앱 위에 표시'를 허용해야 아일랜드를 띄울 수 있습니다.")
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
        islandSwitch = findViewById(R.id.islandSwitch)
        bgValue = findViewById(R.id.bgValue)

        // 응원 팀 선택
        teamChip.setOnClickListener { showTeamMenu() }

        // 숨은 기능: 제목을 길게 누르면 데모 경기 (테스트용)
        findViewById<TextView>(R.id.appTitle).setOnLongClickListener {
            if (hasNotificationPermission() || prefs.islandReady()) {
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

        // 아일랜드 표시 (알약을 길게 눌러 끄면 스위치도 같이 꺼지도록)
        setIslandSwitchSilently(prefs.islandReady())
        IslandController.onIslandChanged = { setIslandSwitchSilently(prefs.islandReady()); bindBoard() }

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
        // '다른 앱 위에 표시' 권한을 거둔 경우
        if (islandSwitch.isChecked && !IslandController.canDraw(this)) setIslandSwitchSilently(false)
        // 앱이 종료됐다가 다시 열렸으면 아일랜드를 다시 띄움
        IslandController.sync(this)
    }

    override fun onDestroy() {
        IslandController.onIslandChanged = null   // 화면이 사라진 뒤에는 스위치를 건드리지 않도록 (메모리 누수 방지)
        super.onDestroy()
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
        IslandController.sync(this)
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
            IslandController.sync(this@MainActivity)   // 떠 있는 아일랜드도 새 결과로
            bindBoard()
        }
    }

    private fun bindBoard() {
        val game = runCatching { ScoreUpdater.currentGame(this, includeUpcoming = true) }.getOrNull()
        board.bind(game, prefs.team, prefs.liveEnabled(), prefs.lastRefreshAt)
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
        // 아일랜드가 실시간 점수를 보여주는 동안에는 알림을 건드리지 않음 (경기가 끝나면 최종 스코어가 알림에 남음)
        if (!LiveScoreService.islandActive && !ScoreNotifier.update(this, force = true)) toast("먼저 '결과 불러오기'를 누르세요.")
        bindBoard()
    }

    private fun turnNotificationsOff() {
        prefs.notificationsOn = false
        if (prefs.liveEnabled()) {
            // 아일랜드는 켜져 있음 → 실시간 갱신은 계속 (아일랜드가 점수를 보여줌)
            if (!LiveScoreService.islandActive) ScoreNotifier.cancel(this)
            LiveController.refreshUi(this)
        } else {
            LiveController.stop(this)   // 돌고 있는 서비스와 예약된 시작 알람 모두
            ScoreNotifier.cancel(this)
        }
        bindBoard()
    }

    // ── 아일랜드 표시 스위치

    private fun onIslandSwitch(on: Boolean) {
        when {
            !on -> IslandController.disable(this)   // 스위치·화면 갱신은 onIslandChanged가
            IslandController.canDraw(this) -> turnIslandOn()
            else -> askOverlayPermission()
        }
    }

    private fun askOverlayPermission() {
        toast("목록에서 이 앱을 찾아 '다른 앱 위에 표시'를 허용해 주세요.")
        requestOverlayPermission.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun turnIslandOn() {
        val wasLive = prefs.liveEnabled()
        prefs.islandOn = true
        setIslandSwitchSilently(true)
        if (LiveScoreService.running) {
            LiveController.refreshUi(this)
        } else {
            IslandController.showIdle(this)
            if (!wasLive) refresh()   // 그동안 실시간 갱신이 꺼져 있었으므로 오늘 경기를 다시 확인해 예약
        }
        bindBoard()
        // 실제로 떴는지 확인해서, 안 떴으면 이유를 보여줌
        lifecycleScope.launch {
            delay(1_000)
            if (IslandController.isShowing) {
                toast("아일랜드를 띄웠습니다. 누르면 펼쳐지고, 길게 누르면 꺼집니다.")
            } else {
                toast("아일랜드를 띄우지 못했습니다: ${IslandController.lastError ?: "알 수 없는 이유"}")
            }
        }
    }

    private fun setIslandSwitchSilently(on: Boolean) {
        if (::islandSwitch.isInitialized) islandSwitch.setCheckedSilently(on) { onIslandSwitch(it) }
    }

    /** 리스너를 거치지 않고 스위치 상태만 바꾼 뒤 [listener]를 다시 붙임 */
    private fun Switch.setCheckedSilently(on: Boolean, listener: (Boolean) -> Unit) {
        setOnCheckedChangeListener(null)
        isChecked = on
        setOnCheckedChangeListener { _, checked -> listener(checked) }
    }

    private fun setSwitchSilently(on: Boolean) {
        if (::notifSwitch.isInitialized) notifSwitch.setCheckedSilently(on) { onNotifSwitch(it) }
    }

    private fun onNotifSwitch(on: Boolean) = if (on) ensurePermissionThenTurnOn() else turnNotificationsOff()

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
