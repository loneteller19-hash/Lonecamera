package com.lone.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var engine: CameraEngine
    private lateinit var orientation: OrientationEventListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        engine = CameraEngine(applicationContext)

        // UI stays portrait; capture rotation follows how the phone is physically held,
        // so holding it sideways records a true landscape file.
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when {
                    o >= 315 || o < 45 -> Surface.ROTATION_0
                    o < 135 -> Surface.ROTATION_270
                    o < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                engine.setRotation(r)
            }
        }

        setContent { Root() }
    }

    @Composable
    private fun Root() {
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        fun granted() = needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        var ok by remember { mutableStateOf(granted()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            ok = granted()
        }
        LaunchedEffect(Unit) { if (!ok) launcher.launch(needed) }

        if (ok) {
            CameraScreen(engine)
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                Text("Camera and microphone permission needed", color = Color.White)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onPause() {
        orientation.disable()
        super.onPause()
    }

    override fun onDestroy() {
        engine.release()
        super.onDestroy()
    }
}
