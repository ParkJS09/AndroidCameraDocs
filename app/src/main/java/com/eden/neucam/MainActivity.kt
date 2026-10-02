package com.eden.neucam

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.eden.neucam.ui.CameraApp
import com.eden.neucam.ui.kit.CamTheme

/**
 * 단일 Activity.
 * - targetSdk 37: 대화면에서 방향/크기 제한이 무시되므로 어떤 창 크기·방향에서도 동작하도록 설계
 * - configChanges 로 회전/폴딩/멀티윈도우 크기 변경 시 Activity 재생성 없이 카메라 세션을 유지
 * - Edge-to-edge (API 35+ 강제) 대응, 항상 다크 카메라 UI
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 카메라 UI 는 항상 어두우므로 시스템 바 아이콘은 항상 밝게
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { CamTheme { CameraApp() } }
    }
}
