package com.example.lottelock

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 배경 사진 자르기 화면. 2:1 틀이 고정돼 있고, 사진을 움직이거나 확대해서 맞춘 뒤 '완료'.
 * 입력: [EXTRA_PATH] (MainActivity가 줄여서 저장해 둔 임시 파일)
 * 결과: Prefs.appBackgroundFile 에 저장하고 RESULT_OK
 */
class CropActivity : ComponentActivity() {

    private lateinit var cropView: CropImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)
        cropView = findViewById(R.id.cropView)
        val source = File(intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return })

        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(source.path) }.getOrNull() }
            if (bmp == null) {
                Toast.makeText(this@CropActivity, "사진을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                cropView.bitmap = bmp
            }
        }

        findViewById<Button>(R.id.btnCropCancel).setOnClickListener {
            source.delete()
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<Button>(R.id.btnCropDone).setOnClickListener { save(source) }
    }

    private fun save(source: File) {
        val result = cropView.cropped() ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    Prefs(this@CropActivity).appBackgroundFile.outputStream().use {
                        result.compress(Bitmap.CompressFormat.JPEG, 90, it)
                    }
                    source.delete()
                }.isSuccess
            }
            setResult(if (ok) RESULT_OK else RESULT_CANCELED)
            finish()
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}
