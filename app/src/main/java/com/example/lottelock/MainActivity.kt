package com.example.lottelock

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
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

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var previewTitle: TextView
    private lateinit var previewSummary: TextView
    private lateinit var previewLineScore: FrameLayout
    private lateinit var previewCard: FrameLayout
    private lateinit var previewBg: ImageView
    private lateinit var previewScrim: View
    private lateinit var status: TextView

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) showNotification()
            else setStatus("알림 권한이 없어 스코어보드를 표시할 수 없습니다. '알림 설정 열기'에서 허용해 주세요.")
        }

    /** 자르기 화면 → 완료하면 2:1로 잘린 사진이 저장돼 있음 */
    private val cropBackground =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setStatus("배경 이미지를 바꿨습니다.")
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
                    setStatus("이미지를 불러오지 못했습니다.")
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

        previewTitle = findViewById(R.id.previewTitle)
        previewSummary = findViewById(R.id.previewSummary)
        previewLineScore = findViewById(R.id.previewLineScore)
        previewCard = findViewById<FrameLayout>(R.id.previewCard).apply { clipToOutline = true }   // 둥근 모서리로 사진 자르기
        previewBg = findViewById(R.id.previewBg)
        previewScrim = findViewById(R.id.previewScrim)

        findViewById<Button>(R.id.btnPickBg).setOnClickListener {
            pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<Button>(R.id.btnClearBg).setOnClickListener {
            prefs.appBackgroundFile.delete()
            applyBackground()
            setStatus("기본 배경으로 되돌렸습니다.")
        }
        status = findViewById(R.id.status)
        findViewById<RadioGroup>(R.id.teamToggle).apply {
            check(if (prefs.team == TeamChoice.KOREA) R.id.teamKorea else R.id.teamLotte)
            setOnCheckedChangeListener { _, checkedId ->
                selectTeam(if (checkedId == R.id.teamKorea) TeamChoice.KOREA else TeamChoice.LOTTE)
            }
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnShow).setOnClickListener { ensurePermissionThenShow() }
        findViewById<Button>(R.id.btnNotifSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        findViewById<Button>(R.id.btnDemo).setOnClickListener {
            if (!hasNotificationPermission()) { ensurePermissionThenShow(); return@setOnClickListener }
            LiveController.startNow(this, demo = true)
            setStatus("데모 경기 시작: 20초마다 반 이닝씩 진행됩니다. 알림창과 잠금화면에서 확인해 보세요.")
        }
        findViewById<Button>(R.id.btnStopLive).setOnClickListener {
            LiveController.stop(this)
            setStatus("실시간 갱신을 멈췄습니다.")
        }

        setStatus(prefs.lastStatus.ifBlank { "'결과 불러오기'를 누르세요." })
        UpdateWorker.schedule(this)
        applyBackground()

        // 화면이 보이는 동안 5초마다 상태와 미리보기 갱신 (실시간 갱신·데모 진행 확인용)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(5_000)
                    if (prefs.lastStatus.isNotBlank()) setStatus(prefs.lastStatus)
                    updatePreview()
                }
            }
        }
    }

    /** 팀을 바꾸면 예전 팀 결과·실시간 갱신을 정리하고 새 팀 결과를 바로 불러온다 */
    private fun selectTeam(choice: TeamChoice) {
        if (choice == prefs.team) return
        if (LiveScoreService.running) LiveController.stop(this)
        prefs.team = choice
        prefs.cachedGames = "[]"
        prefs.liveGame = ""
        prefs.lastNotifiedSignature = ""
        updatePreview()
        refresh()
    }

    private fun refresh() {
        setStatus("불러오는 중…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ScoreUpdater.refresh(this@MainActivity) } }
            result.onSuccess { msg ->
                setStatus("$msg (오늘 요청 ${prefs.requestsToday}회)")
                UpdateWorker.schedule(this@MainActivity)
                if (hasNotificationPermission() && !LiveScoreService.running) ScoreNotifier.update(this@MainActivity, force = true)
            }.onFailure { setStatus("실패: ${it.message}") }
            updatePreview()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionThenShow() {
        if (hasNotificationPermission()) showNotification()
        else requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun showNotification() {
        if (!ScoreNotifier.canNotify(this)) {
            setStatus("이 앱의 알림이 꺼져 있습니다. '알림 설정 열기'에서 켜 주세요."); return
        }
        val shown = ScoreNotifier.update(this, force = true)
        setStatus(
            if (shown) "알림에 스코어보드를 띄웠습니다. 잠금화면에 점수가 안 보이면 휴대폰 설정 → 잠금화면 → 알림에서 '내용 표시'로 바꿔 주세요."
            else "먼저 '결과 불러오기'를 누르세요."
        )
        if (shown) UpdateWorker.schedule(this)
    }

    /** 알림과 같은 레이아웃(RemoteViews)을 그대로 앱 화면에 그려서 미리보기 */
    private fun updatePreview() {
        val game = runCatching { ScoreUpdater.currentGame(this) }.getOrNull()
        previewLineScore.removeAllViews()
        if (game == null) {
            previewTitle.text = "스코어보드"
            previewSummary.text = "아직 불러온 경기가 없습니다"
            return
        }
        val note = if (LiveScoreService.demoRunning) "데모" else null
        previewTitle.text = ScoreNotifier.title(game)
        previewSummary.text = ScoreNotifier.summary(game, note)
        val rv = ScoreNotifier.lineScoreView(this, game, note, onImage = hasBackground(), showHeader = false)
        previewLineScore.addView(rv.apply(this, previewLineScore))
    }

    private fun hasBackground() = prefs.appBackgroundFile.exists()

    /** 배경 사진이 있으면 카드 뒤에 깔고 글자를 흰색으로, 없으면 기본 회색 카드 */
    private fun applyBackground() {
        val bmp = if (hasBackground()) runCatching { BitmapFactory.decodeFile(prefs.appBackgroundFile.path) }.getOrNull() else null
        previewBg.setImageBitmap(bmp)
        val visibility = if (bmp != null) View.VISIBLE else View.GONE
        previewBg.visibility = visibility
        previewScrim.visibility = visibility
        if (bmp != null) {
            previewTitle.setTextColor(Color.WHITE)
            previewSummary.setTextColor(0xD9FFFFFF.toInt())
        } else {
            previewTitle.setTextColor(0xFF212121.toInt())
            previewSummary.setTextColor(0xFF616161.toInt())
        }
        updatePreview()
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

    private fun setStatus(msg: String) {
        status.text = msg
    }
}
