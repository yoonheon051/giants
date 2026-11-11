package com.example.lottelock

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 가로 : 세로 = [BackgroundSpec.RATIO] 로 높이를 고정하는 카드.
 * 배경 사진도 같은 비율로 잘라 두므로 잘린 그대로 보인다.
 * (글자 크기를 아주 크게 키워서 내용이 넘칠 때만 그만큼 늘어남 — 글자가 잘리지 않도록)
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        var height = (width / BackgroundSpec.RATIO).roundToInt()
        // 마지막 자식(스코어보드 내용)이 필요로 하는 높이 확인
        val content: View? = if (childCount > 0) getChildAt(childCount - 1) else null
        if (content != null && content.visibility != GONE) {
            measureChild(content, widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            height = max(height, content.measuredHeight)
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }
}
