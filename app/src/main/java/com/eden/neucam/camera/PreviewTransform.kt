package com.eden.neucam.camera

import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min

/**
 * 프리뷰 좌표 계산.
 *
 * 프리뷰는 SurfaceView 라 회전을 합성기가 처리하지만, 탭 좌표 → 센서 좌표 역변환에는
 * "화면에 최종적으로 보이는 모습"을 기술하는 아래 행렬이 그대로 유효하다 (TextureView 기준 모델).
 *
 * TextureView 는 SurfaceTexture 의 변환(센서 → 디바이스 '자연 방향' 보정, 전면은 미러 포함)을 적용한 뒤
 * 결과를 뷰 크기로 그대로 늘려서 그린다. 따라서 앱은
 *  1) 늘어난 비율을 원래 비율로 되돌리고
 *  2) 현재 디스플레이 회전만큼 반대로 돌리고
 *  3) 채우기(center-crop) 또는 맞추기(fit) 스케일을 적용해야 한다.
 * 폴더블 펼침/접힘, 멀티윈도우, 180° 회전(구성 변경 없음) 모두 이 한 함수로 처리한다.
 */
object PreviewTransform {

    fun matrix(
        viewW: Float, viewH: Float,
        bufW: Int, bufH: Int,
        sensorOrientation: Int,
        displayRotation: Int,
        fill: Boolean,
    ): Matrix {
        val m = Matrix()
        if (viewW <= 0f || viewH <= 0f || bufW <= 0 || bufH <= 0) return m
        val swapped = sensorOrientation % 180 != 0
        val nw = (if (swapped) bufH else bufW).toFloat()
        val nh = (if (swapped) bufW else bufH).toFloat()
        val cx = viewW / 2f
        val cy = viewH / 2f
        m.postScale(nw / viewW, nh / viewH, cx, cy)
        m.postRotate(-displayRotation.toFloat(), cx, cy)
        val rotSwapped = displayRotation % 180 != 0
        val rw = if (rotSwapped) nh else nw
        val rh = if (rotSwapped) nw else nh
        val s = if (fill) max(viewW / rw, viewH / rh) else min(viewW / rw, viewH / rh)
        m.postScale(s, s, cx, cy)
        return m
    }

    /** 화면에 보이는 콘텐츠의 비율 (가로/세로) */
    fun displayedAspect(bufW: Int, bufH: Int, sensorOrientation: Int, displayRotation: Int): Float {
        val swapped = (sensorOrientation + displayRotation) % 180 != 0
        return if (swapped) bufH.toFloat() / bufW else bufW.toFloat() / bufH
    }

    /**
     * 뷰 좌표 → 센서 정규화 좌표(0..1). 탭 포커스/측광 영역 계산에 사용.
     */
    fun viewToSensor(
        x: Float, y: Float,
        viewW: Float, viewH: Float,
        bufW: Int, bufH: Int,
        sensorOrientation: Int,
        displayRotation: Int,
        fill: Boolean,
        front: Boolean,
    ): Pair<Float, Float> {
        val inv = Matrix()
        matrix(viewW, viewH, bufW, bufH, sensorOrientation, displayRotation, fill).invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        var u = (p[0] / viewW).coerceIn(0f, 1f)
        val v = (p[1] / viewH).coerceIn(0f, 1f)
        if (front) u = 1f - u
        return when (sensorOrientation) {
            90 -> v to 1f - u
            180 -> 1f - u to 1f - v
            270 -> 1f - v to u
            else -> u to v
        }
    }

    /** OrientationEventListener 각도(시계방향) → JPEG_ORIENTATION / MediaRecorder orientationHint */
    fun jpegOrientation(sensorOrientation: Int, deviceOrientation: Int, front: Boolean): Int {
        if (deviceOrientation < 0) return sensorOrientation
        var d = ((deviceOrientation + 45) / 90 * 90) % 360
        if (front) d = -d
        return (sensorOrientation + d + 360) % 360
    }
}
