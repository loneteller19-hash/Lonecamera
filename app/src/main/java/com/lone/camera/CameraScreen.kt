package com.lone.camera

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
fun CameraScreen(engine: CameraEngine) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var videoMode by remember { mutableStateOf(true) }
    var front by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(Flash.OFF) }
    var dual by remember { mutableStateOf(true) }
    var hdr by remember { mutableStateOf(true) }
    var hdrActive by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(true) }
    var grid by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var lut by remember { mutableStateOf<Lut?>(null) }
    val luts = remember { mutableStateListOf<Lut>().apply { addAll(Lut.builtIn) } }

    var zoom by remember { mutableFloatStateOf(1f) }
    var minZ by remember { mutableFloatStateOf(1f) }
    var maxZ by remember { mutableFloatStateOf(1f) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var focusPt by remember { mutableStateOf<Offset?>(null) }
    var evIndex by remember { mutableFloatStateOf(0f) }
    var evMin by remember { mutableIntStateOf(0) }
    var evMax by remember { mutableIntStateOf(0) }

    // (Re)bind the camera when anything that changes the use cases changes.
    LaunchedEffect(front, night, flash, videoMode, hdr) {
        engine.bind(owner, previewView, front, night, flash, videoMode, hdr) { cam, active ->
            hdrActive = active
            if (hdr && !active) status = "HDR not available here, using SDR"
            val zs = cam.cameraInfo.zoomState.value
            minZ = zs?.minZoomRatio ?: 1f
            maxZ = minOf(zs?.maxZoomRatio ?: 1f, 10f)
            zoom = 1f.coerceIn(minZ, maxZ)
            engine.setZoom(zoom)
            val r = cam.cameraInfo.exposureState.exposureCompensationRange
            evMin = r.lower; evMax = r.upper; evIndex = 0f
        }
    }

    // Live preview of the chosen LUT (a colour-matrix approximation; the saved file uses the exact LUT).
    LaunchedEffect(lut) {
        val l = lut
        if (l == null) {
            previewView.setLayerType(View.LAYER_TYPE_NONE, null)
        } else {
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(l.approxMatrix())) }
            previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        }
    }

    LaunchedEffect(recording) {
        seconds = 0
        while (recording) { delay(1000); seconds++ }
    }
    LaunchedEffect(status) {
        val s = status
        if (s != null && !s.startsWith("Rendering")) { delay(2500); status = null }
    }
    LaunchedEffect(focusPt) {
        if (focusPt != null) { delay(3500); focusPt = null }
    }

    val importLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".cube") ?: "Imported"
                val l = Lut.parseCube(name, text)
                luts.add(l); lut = l
                status = "LUT loaded: $name"
            } catch (e: Exception) {
                status = "Could not read .cube file"
            }
        }
    }

    fun process(uri: Uri?) {
        if (uri == null) { status = "Recording failed"; return }
        if (hdrActive && (dual || lut != null)) {
            status = "HDR clip saved (LUT and dual export are SDR-only, turn HDR off for them)"
        }
        val jobs = when {
            hdrActive -> if (voice) listOf(ExportJob(Shape.ORIGINAL, null, true)) else emptyList()
            dual -> listOf(ExportJob(Shape.LANDSCAPE, lut, voice), ExportJob(Shape.PORTRAIT, lut, voice))
            lut != null || voice -> listOf(ExportJob(Shape.ORIGINAL, lut, voice))
            else -> emptyList()
        }
        if (jobs.isEmpty()) { if (!hdrActive) status = "Saved"; return }
        Exporter.run(ctx, uri, jobs, { status = it }, { status = "Saved to Movies/Lonecamera" })
    }

    fun shutter() {
        if (videoMode) {
            if (!recording) {
                recording = true
                engine.startVideo { uri -> recording = false; process(uri) }
            } else {
                engine.stopVideo()
            }
        } else {
            engine.takePhoto(if (hdrActive) null else lut, { status = "Photo saved" }, { status = it })
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // ---------------------------------------------------------------- top glass bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIcon(
                    when (flash) { Flash.OFF -> Icons.Rounded.FlashOff; Flash.ON -> Icons.Rounded.FlashOn; Flash.AUTO -> Icons.Rounded.FlashAuto },
                    active = flash != Flash.OFF,
                ) { flash = Flash.values()[(flash.ordinal + 1) % 3] }

                if (recording) {
                    Row(
                        Modifier.glass(RoundedCornerShape(50), 0.18f).padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(9.dp).clip(CircleShape).background(AppleRed))
                        Text(
                            "  %02d:%02d".format(seconds / 60, seconds % 60),
                            color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Row(
                        Modifier.glass(RoundedCornerShape(50), 0.14f).padding(horizontal = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(Modifier.size(44.dp).clickable(enabled = !recording) { hdr = !hdr }, Alignment.Center) {
                            Text("HDR", color = if (hdr) AppleYellow else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.size(44.dp).clickable { night = !night }, Alignment.Center) {
                            Icon(Icons.Rounded.NightsStay, null, tint = if (night) AppleYellow else Color.White, modifier = Modifier.size(22.dp))
                        }
                        Box(Modifier.size(44.dp).clickable { dual = !dual }, Alignment.Center) {
                            Icon(Icons.Rounded.ScreenRotation, null, tint = if (dual) AppleYellow else Color.White, modifier = Modifier.size(22.dp))
                        }
                        Box(Modifier.size(44.dp).clickable { voice = !voice }, Alignment.Center) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = if (voice) AppleYellow else Color.White, modifier = Modifier.size(22.dp))
                        }
                    }
                }
                GlassIcon(Icons.Rounded.Tune, active = sheet) { sheet = !sheet }
            }

            // ---------------------------------------------------------------- viewfinder
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (videoMode) 9f / 16f else 3f / 4f)
                    .clip(RoundedCornerShape(26.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { off ->
                            focusPt = off
                            engine.focus(previewView, off.x, off.y)
                        }
                    }
                    .pointerInput(evMin, evMax) {
                        detectVerticalDragGestures { _, dy ->
                            evIndex = (evIndex - dy / 25f).coerceIn(evMin.toFloat(), evMax.toFloat())
                            engine.setEv(evIndex.roundToInt())
                            if (focusPt == null) focusPt = Offset(size.width / 2f, size.height / 2f)
                        }
                    },
            ) {
                AndroidView({ previewView }, Modifier.fillMaxSize())

                if (grid) GridOverlay()

                focusPt?.let { p ->
                    Box(
                        Modifier
                            .offset { IntOffset((p.x - 38.dp.toPx()).roundToInt(), (p.y - 38.dp.toPx()).roundToInt()) }
                            .size(76.dp)
                            .border(1.5.dp, AppleYellow, RoundedCornerShape(3.dp)),
                    )
                    Text(
                        "☀ %+d".format(evIndex.roundToInt()),
                        color = AppleYellow, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.offset { IntOffset((p.x + 46.dp.toPx()).roundToInt(), (p.y - 10.dp.toPx()).roundToInt()) },
                    )
                }

                if (hdrActive) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(12.dp)
                            .glass(RoundedCornerShape(50), 0.2f).padding(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text("HDR", color = AppleYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                if (night) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(12.dp)
                            .glass(RoundedCornerShape(50), 0.2f).padding(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text("NIGHT", color = AppleYellow, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                if (dual && videoMode) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .glass(RoundedCornerShape(50), 0.2f).padding(horizontal = 10.dp, vertical = 5.dp),
                    ) { Text("DUAL 16:9 + 9:16", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // -------------------------------------------------------------------- status pill
        status?.let {
            Box(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 76.dp)
                    .glass(RoundedCornerShape(50), 0.24f).padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text(it, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }

        // -------------------------------------------------------------------- bottom controls
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (sheet) {
                TuneSheet(
                    luts = luts, selected = lut, onSelect = { lut = it },
                    dual = dual, onDual = { dual = !dual },
                    voice = voice, onVoice = { voice = !voice },
                    night = night, onNight = { night = !night },
                    grid = grid, onGrid = { grid = !grid },
                    onImport = { importLut.launch(arrayOf("*/*")) },
                )
                Spacer(Modifier.height(12.dp))
            }

            // zoom pills
            Row(
                Modifier.glass(RoundedCornerShape(50), 0.14f).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(0.5f, 1f, 2f, 3f, 5f).filter { it >= minZ - 0.01f && it <= maxZ + 0.01f }.forEach { z ->
                    val sel = kotlin.math.abs(zoom - z) < 0.05f
                    Box(
                        Modifier.size(40.dp).clip(CircleShape)
                            .background(if (sel) Color.Black.copy(alpha = 0.45f) else Color.Transparent)
                            .clickable { zoom = z; engine.setZoom(z) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (z < 1f) ".5" else z.toInt().toString() + if (sel) "×" else "",
                            color = if (sel) AppleYellow else Color.White,
                            fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // mode selector
            Row(
                Modifier.glass(RoundedCornerShape(50), 0.14f).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("VIDEO" to true, "PHOTO" to false).forEach { (label, isVideo) ->
                    val sel = videoMode == isVideo
                    Box(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) Color.Black.copy(alpha = 0.4f) else Color.Transparent)
                            .clickable(enabled = !recording) { videoMode = isVideo }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Text(label, color = if (sel) AppleYellow else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))

            // shutter row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (videoMode) {
                    GlassIcon(Icons.Rounded.PhotoCamera, size = 48) {
                        engine.snapshot(previewView, lut) { status = "Snapshot saved" }
                    }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Box(
                    Modifier.size(80.dp).border(4.dp, Color.White, CircleShape).padding(6.dp).clickable { shutter() },
                    contentAlignment = Alignment.Center,
                ) {
                    val inner = if (videoMode) AppleRed else Color.White
                    Box(
                        Modifier
                            .size(if (recording) 30.dp else 62.dp)
                            .clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                            .background(inner),
                    )
                }
                GlassIcon(Icons.Rounded.Cameraswitch, size = 48) { if (!recording) front = !front }
            }
        }
    }
}

@Composable
private fun GridOverlay() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.28f)
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
        }
    }
}

@Composable
private fun TuneSheet(
    luts: List<Lut>, selected: Lut?, onSelect: (Lut?) -> Unit,
    dual: Boolean, onDual: () -> Unit,
    voice: Boolean, onVoice: () -> Unit,
    night: Boolean, onNight: () -> Unit,
    grid: Boolean, onGrid: () -> Unit,
    onImport: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp)
            .glass(RoundedCornerShape(28.dp), 0.2f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LOOKS & LUT", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { GlassPill("Standard", selected == null) { onSelect(null) } }
            items(luts) { l -> GlassPill(l.name, selected == l) { onSelect(l) } }
            item { GlassPill("+ Import .cube") { onImport() } }
        }
        GlassToggleRow("Dual orientation", "Export 16:9 and 9:16 from one recording", dual, onDual)
        GlassToggleRow("Voice clean", "Cuts rumble, hiss and background noise", voice, onVoice)
        GlassToggleRow("Night engine", "Max noise reduction, longer frame exposure", night, onNight)
        GlassToggleRow("Grid", "Rule-of-thirds overlay", grid, onGrid)
    }
}
