package com.example.lottelock

import kotlin.math.max
import kotlin.math.roundToInt

/** 앱 화면 스코어보드 카드 배경 규격 */
object BackgroundSpec {
    /** 가로 : 세로 = 3 : 2 (전광판 카드와 자르기 틀이 같은 비율) */
    const val RATIO = 1.5f
    /** 저장할 배경 이미지 최대 폭(px) */
    const val OUTPUT_WIDTH = 1440
}

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

/** 사진의 현재 위치: 화면 좌표 = 사진 좌표 × scale + (tx, ty) */
data class CropState(val scale: Float, val tx: Float, val ty: Float)

/**
 * 자르기 화면 계산 (안드로이드와 무관한 순수 계산이라 유닛 테스트 가능).
 * 규칙: 사진은 항상 자르기 틀을 빈틈없이 덮어야 한다.
 */
object CropMath {
    const val MAX_ZOOM = 8f

    /** 화면 가운데에 [ratio] 비율의 가장 큰 틀 (여백 [margin]) */
    fun cropBox(viewW: Float, viewH: Float, margin: Float, ratio: Float = BackgroundSpec.RATIO): Box {
        var w = viewW - margin * 2
        var h = w / ratio
        if (h > viewH - margin * 2) {
            h = viewH - margin * 2
            w = h * ratio
        }
        val l = (viewW - w) / 2
        val t = (viewH - h) / 2
        return Box(l, t, l + w, t + h)
    }

    /** 틀을 덮는 최소 배율 */
    fun minScale(bw: Int, bh: Int, crop: Box): Float = max(crop.width / bw, crop.height / bh)

    /** 처음 위치: 틀을 덮는 최소 배율로 가운데 정렬 */
    fun initial(bw: Int, bh: Int, crop: Box): CropState {
        val s = minScale(bw, bh, crop)
        return CropState(s, crop.centerX - bw * s / 2, crop.centerY - bh * s / 2)
    }

    /** 사진이 틀 밖으로 비지 않게 위치 보정 */
    fun clamp(st: CropState, bw: Int, bh: Int, crop: Box): CropState {
        val min = minScale(bw, bh, crop)
        val s = st.scale.coerceIn(min, min * MAX_ZOOM)
        val tx = st.tx.coerceIn(crop.right - bw * s, crop.left)
        val ty = st.ty.coerceIn(crop.bottom - bh * s, crop.top)
        return CropState(s, tx, ty)
    }

    /** 끌어서 이동 */
    fun pan(st: CropState, dx: Float, dy: Float, bw: Int, bh: Int, crop: Box): CropState =
        clamp(st.copy(tx = st.tx + dx, ty = st.ty + dy), bw, bh, crop)

    /** 두 손가락 확대·축소: (fx, fy) 지점을 중심으로 */
    fun zoom(st: CropState, factor: Float, fx: Float, fy: Float, bw: Int, bh: Int, crop: Box): CropState {
        val min = minScale(bw, bh, crop)
        val target = (st.scale * factor).coerceIn(min, min * MAX_ZOOM)
        val f = target / st.scale
        return clamp(CropState(target, fx - (fx - st.tx) * f, fy - (fy - st.ty) * f), bw, bh, crop)
    }

    /** 틀 안에 보이는 부분 = 원본 사진에서 잘라낼 영역 (픽셀, [l, t, w, h]) */
    fun sourceRect(st: CropState, crop: Box, bw: Int, bh: Int): IntArray {
        val l = ((crop.left - st.tx) / st.scale).roundToInt().coerceIn(0, bw - 1)
        val t = ((crop.top - st.ty) / st.scale).roundToInt().coerceIn(0, bh - 1)
        val w = (crop.width / st.scale).roundToInt().coerceIn(1, bw - l)
        val h = (crop.height / st.scale).roundToInt().coerceIn(1, bh - t)
        return intArrayOf(l, t, w, h)
    }

    /** 저장 크기: 잘라낸 폭과 최대 폭 중 작은 쪽, 높이는 비율대로 */
    fun outputSize(srcW: Int, ratio: Float = BackgroundSpec.RATIO): Pair<Int, Int> {
        val w = minOf(srcW, BackgroundSpec.OUTPUT_WIDTH)
        return w to (w / ratio).roundToInt().coerceAtLeast(1)
    }
}
