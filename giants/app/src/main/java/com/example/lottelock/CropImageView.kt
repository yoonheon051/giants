package com.example.lottelock

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * 고정 비율(3:2) 틀 아래에서 사진을 끌고(이동) 두 손가락으로 확대·축소해 자를 부분을 고르는 뷰.
 * 계산은 [CropMath]가 하고, 이 뷰는 그리기와 터치 입력만 담당한다.
 */
class CropImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            resetPosition()
            invalidate()
        }

    private var crop = Box(0f, 0f, 0f, 0f)
    private var state = CropState(1f, 0f, 0f)
    private val density = resources.displayMetrics.density

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 2f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeWidth = 1f * density
    }
    private val dimPath = Path()
    private val cropRect = RectF()   // onDraw 마다 새로 만들지 않도록 재사용

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            state = CropMath.zoom(state, detector.scaleFactor, detector.focusX, detector.focusY, b.width, b.height, crop)
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val b = bitmap ?: return false
            state = CropMath.pan(state, -distanceX, -distanceY, b.width, b.height, crop)
            invalidate()
            return true
        }

        /** 두 번 탭하면 처음 위치로 */
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetPosition(); invalidate(); return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        crop = CropMath.cropBox(w.toFloat(), h.toFloat(), 20f * density)
        resetPosition()
    }

    private fun resetPosition() {
        val b = bitmap ?: return
        if (crop.width <= 0f) return
        state = CropMath.initial(b.width, b.height, crop)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.save()
        canvas.translate(state.tx, state.ty)
        canvas.scale(state.scale, state.scale)
        canvas.drawBitmap(b, 0f, 0f, imagePaint)
        canvas.restore()

        // 틀 바깥은 어둡게 (카드처럼 모서리를 둥글게 보여줌)
        val r = 16f * density
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        cropRect.set(crop.left, crop.top, crop.right, crop.bottom)
        dimPath.addRoundRect(cropRect, r, r, Path.Direction.CW)
        canvas.drawPath(dimPath, dimPaint)

        // 3등분 안내선 + 테두리
        for (i in 1..2) {
            val x = crop.left + crop.width * i / 3
            val y = crop.top + crop.height * i / 3
            canvas.drawLine(x, crop.top, x, crop.bottom, gridPaint)
            canvas.drawLine(crop.left, y, crop.right, y, gridPaint)
        }
        canvas.drawRoundRect(cropRect, r, r, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** 틀 안에 보이는 부분을 3:2로 잘라 저장 크기로 줄인 비트맵 */
    fun cropped(): Bitmap? {
        val b = bitmap ?: return null
        val (l, t, w, h) = CropMath.sourceRect(state, crop, b.width, b.height).toList()
        val part = Bitmap.createBitmap(b, l, t, w, h)
        val (ow, oh) = CropMath.outputSize(w)
        if (ow == part.width && oh == part.height) return part
        return Bitmap.createScaledBitmap(part, ow, oh, true).also { if (it !== part) part.recycle() }
    }
}
