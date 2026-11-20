package com.example.lottelock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CropMathTest {
    // 1080x1920 화면, 여백 48 → 3:2 틀
    private val crop = CropMath.cropBox(1080f, 1920f, 48f)

    private fun covers(st: CropState, bw: Int, bh: Int) =
        st.tx <= crop.left + 0.01f && st.ty <= crop.top + 0.01f &&
            st.tx + bw * st.scale >= crop.right - 0.01f && st.ty + bh * st.scale >= crop.bottom - 0.01f

    @Test fun cropBoxHasFixedRatioAndIsCentered() {
        assertEquals(BackgroundSpec.RATIO, crop.width / crop.height, 0.001f)
        assertEquals(540f, crop.centerX, 0.01f)
        assertEquals(960f, crop.centerY, 0.01f)
        // 가로 화면처럼 높이가 부족하면 높이 기준
        val wide = CropMath.cropBox(2000f, 600f, 50f)
        assertEquals(500f, wide.height, 0.01f)
        assertEquals(BackgroundSpec.RATIO, wide.width / wide.height, 0.001f)
    }

    @Test fun initialCoversCropForPortraitAndLandscapePhotos() {
        for ((bw, bh) in listOf(3000 to 4000, 4000 to 1000, 900 to 600, 500 to 500)) {
            val st = CropMath.initial(bw, bh, crop)
            assertTrue(covers(st, bw, bh), "$bw x $bh")
        }
    }

    @Test fun panCannotRevealEmptySpace() {
        val bw = 3000; val bh = 4000
        var st = CropMath.initial(bw, bh, crop)
        st = CropMath.pan(st, 99999f, 99999f, bw, bh, crop)
        assertTrue(covers(st, bw, bh))
        assertEquals(crop.left, st.tx, 0.01f)   // 왼쪽 끝까지만
        assertEquals(crop.top, st.ty, 0.01f)
        st = CropMath.pan(st, -99999f, -99999f, bw, bh, crop)
        assertTrue(covers(st, bw, bh))
    }

    @Test fun zoomIsBoundedAndKeepsCoverage() {
        val bw = 4000; val bh = 3000
        val start = CropMath.initial(bw, bh, crop)
        val min = CropMath.minScale(bw, bh, crop)
        val out = CropMath.zoom(start, 0.1f, 540f, 960f, bw, bh, crop)
        assertEquals(min, out.scale, 1e-6f)                    // 최소보다 작아지지 않음
        val inn = CropMath.zoom(start, 100f, 300f, 900f, bw, bh, crop)
        assertEquals(min * CropMath.MAX_ZOOM, inn.scale, 1e-5f) // 최대 8배
        assertTrue(covers(inn, bw, bh))
    }

    @Test fun sourceRectKeepsRatio() {
        val bw = 3000; val bh = 4000
        var st = CropMath.initial(bw, bh, crop)
        st = CropMath.zoom(st, 2.5f, 400f, 1000f, bw, bh, crop)
        st = CropMath.pan(st, -120f, 80f, bw, bh, crop)
        val (l, t, w, h) = CropMath.sourceRect(st, crop, bw, bh).toList()
        assertTrue(l >= 0 && t >= 0 && l + w <= bw && t + h <= bh)
        assertEquals(BackgroundSpec.RATIO.toDouble(), w.toDouble() / h, 0.02)
        val (ow, oh) = CropMath.outputSize(w)
        assertTrue(ow <= BackgroundSpec.OUTPUT_WIDTH)
        assertEquals(BackgroundSpec.RATIO.toDouble(), ow.toDouble() / oh, 0.01)
    }
}
