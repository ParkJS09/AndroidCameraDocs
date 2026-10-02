package com.eden.neucam.camera

import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SurfaceView 기반 프리뷰 출력.
 *
 * - SurfaceView 는 카메라 버퍼의 센서 방향 변환 + 디스플레이 회전을 합성기(SurfaceFlinger)가 처리하므로
 *   TextureView 처럼 행렬로 회전시킬 필요가 없다.
 * - 대신 버퍼 크기를 holder.setFixedSize() 로 카메라 출력 크기에 맞춰야 하고,
 *   surfaceChanged 로 반영된 뒤에 세션을 만들어야 한다 → [surfaceFor]
 * - surfaceDestroyed 가 리턴되면 Surface 는 무효가 되므로 그 전에 세션을 닫아야 한다 → [onDestroyed] 가 돌려준 Job 을 대기
 */
class PreviewTarget(
    val view: SurfaceView,
    private val onCreated: (PreviewTarget) -> Unit,
    private val onDestroyed: (PreviewTarget) -> Job,
) : SurfaceHolder.Callback {

    private val main = Handler(Looper.getMainLooper())
    private val size = MutableStateFlow<Size?>(null)
    @Volatile var valid = false
        private set

    init {
        view.holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        valid = true
        onCreated(this)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        size.value = Size(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        valid = false
        size.value = null
        val job = onDestroyed(this)
        // 카메라 스레드가 세션을 닫을 때까지 (최대 1.5초) 대기 — Surface 가 사라진 뒤 카메라가 쓰지 않도록
        runBlocking { withTimeoutOrNull(1500) { job.join() } }
    }

    /**
     * 카메라 스레드에서 호출. 버퍼 크기를 지정하고 surfaceChanged 로 반영될 때까지 기다린 뒤 Surface 를 돌려준다.
     * setFixedSize 는 UI 스레드에서만 호출 가능하므로 post 하고, 메인 스레드를 막지 않도록 결과는 Flow 로 기다린다.
     */
    suspend fun surfaceFor(target: Size): Surface {
        main.post { if (valid) view.holder.setFixedSize(target.width, target.height) }
        withTimeoutOrNull(1500) { size.first { it == target || !valid } }
        val s = view.holder.surface
        check(valid && s.isValid) { "프리뷰 Surface 가 유효하지 않습니다" }
        return s
    }
}
