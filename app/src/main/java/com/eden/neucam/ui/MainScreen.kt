package com.eden.neucam.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eden.neucam.AppTab
import com.eden.neucam.AppViewModel
import com.eden.neucam.camera.PreviewMode
import com.eden.neucam.camera.PreviewTransform
import com.eden.neucam.ui.camera.CameraControls
import com.eden.neucam.ui.camera.CameraPreviewHost
import com.eden.neucam.ui.info.InfoScreen
import com.eden.neucam.ui.multi.MultiScreen
import com.eden.neucam.ui.kit.Cam
import com.eden.neucam.ui.kit.CamButton
import com.eden.neucam.ui.kit.CamSurface
import com.eden.neucam.ui.options.OptionsScreen

private fun hasCamera(c: Context) = c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
fun CameraApp(vm: AppViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCamera(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasCamera(context)
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(granted) { vm.onPermission(granted) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> { granted = hasCamera(context); vm.setForeground(true) }
                Lifecycle.Event.ON_STOP -> vm.setForeground(false)
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    if (granted) MainScaffold(vm)
    else PermissionScreen(asked) {
        if (asked) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
}

@Composable
private fun PermissionScreen(asked: Boolean, onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Cam.colors.bg), contentAlignment = Alignment.Center) {
        CamSurface(Modifier.padding(32.dp).widthIn(max = 360.dp), shape = RoundedCornerShape(24.dp), surface = Cam.colors.card) {
            Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CamSurface(Modifier.size(84.dp), shape = RoundedCornerShape(42.dp), surface = Cam.colors.field) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.PhotoCamera, null, tint = Cam.colors.accent, modifier = Modifier.size(38.dp)) }
                }
                Text("NeuCam2", color = Cam.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                Text(
                    "Camera2 로 카메라를 직접 제어하려면 카메라 권한이 필요합니다.\n(동영상 소리는 마이크 권한)",
                    color = Cam.colors.textDim, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp),
                )
                CamButton(onRequest, Modifier.padding(top = 22.dp), shape = RoundedCornerShape(24.dp), surface = Cam.colors.accent) {
                    Text(if (asked) "설정에서 허용" else "권한 허용", color = Cam.colors.onAccent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel) {
    val tab by vm.tab.collectAsState()
    val state by vm.controller.state.collectAsState()
    val live by vm.controller.live.collectAsState()
    val cameras by vm.cameras.collectAsState()
    val posture = rememberPosture()
    val rotation = rememberDisplayRotation()
    var grid by rememberSaveable { mutableStateOf(false) }
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    BoxWithConstraints(Modifier.fillMaxSize().background(Cam.colors.bg)) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        LaunchedEffect(w, h) { if (w > 0 && h > 0) vm.setWindowAspect(maxOf(w, h) / minOf(w, h)) }
        val ins = Insets(
            safe.getLeft(density, dir).toFloat(), safe.getTop(density).toFloat(),
            safe.getRight(density, dir).toFloat(), safe.getBottom(density).toFloat(),
        )
        val cfg = state.config
        val desc = cameras.firstOrNull { it.id == cfg?.cameraId }
        val contentAspect = state.previewSize?.let { PreviewTransform.displayedAspect(it.width, it.height, state.sensorOrientation, rotation) }
        val layout = LayoutCalc.compute(w, h, ins, density, tab, posture, cfg?.previewMode ?: PreviewMode.FULL, contentAspect)

        // 프리뷰는 항상 같은 composition 위치에 둔다 → 카메라 ↔ 옵션 탭 전환 시 SurfaceView/세션 유지
        val previewRect = layout.preview
        if (previewRect != null && cfg != null) {
            val zr = desc?.caps?.zoomRange
            CameraPreviewHost(
                vm = vm,
                state = state,
                live = live,
                zoomRange = (zr?.lower ?: 1f)..(zr?.upper ?: 1f),
                displayRotation = rotation,
                fill = layout.previewFill,
                rounded = layout.previewRounded,
                interactive = tab == AppTab.CAMERA,
                grid = grid && tab == AppTab.CAMERA,
                modifier = Modifier.place(previewRect),
            )
        }

        when (tab) {
            AppTab.CAMERA -> CameraControls(vm, state, live, desc, cameras, layout.controlsVertical, grid, { grid = it }, Modifier.place(layout.content))
            AppTab.OPTIONS -> OptionsScreen(vm, state, cameras, Modifier.place(layout.content))
            AppTab.MULTI -> MultiScreen(vm, rotation, Modifier.place(layout.content))
            AppTab.INFO -> InfoScreen(vm.repo, Modifier.place(layout.content))
        }

        TabBar(tab, layout.tabVertical, vm::selectTab, Modifier.place(layout.tabBar))
    }
}

@Composable
private fun TabBar(selected: AppTab, vertical: Boolean, onSelect: (AppTab) -> Unit, modifier: Modifier) {
    val items: @Composable () -> Unit = {
        AppTab.entries.forEach { t ->
            val sel = t == selected
            // 탭은 테두리 없는 아이콘+라벨. 선택 = 앰버
            Box(
                Modifier.size(64.dp, 52.dp).clip(RoundedCornerShape(14.dp)).clickable(role = Role.Tab) { onSelect(t) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        when (t) {
                            AppTab.CAMERA -> Icons.Filled.PhotoCamera
                            AppTab.MULTI -> Icons.Filled.ViewAgenda
                            AppTab.OPTIONS -> Icons.Filled.Tune
                            AppTab.INFO -> Icons.Filled.Info
                        },
                        t.label,
                        tint = if (sel) Cam.colors.accent else Cam.colors.textDim,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(t.label, fontSize = 10.5.sp, color = if (sel) Cam.colors.accent else Cam.colors.textDim, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
    CamSurface(modifier, shape = RoundedCornerShape(26.dp)) {
        if (vertical) {
            Column(Modifier.fillMaxSize().padding(vertical = 8.dp), verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally) { items() }
        } else {
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { items() }
        }
    }
}
