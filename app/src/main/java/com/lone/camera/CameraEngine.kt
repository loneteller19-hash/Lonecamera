package com.lone.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Range
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.Executors

enum class Flash { OFF, ON, AUTO }

@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val ctx: Context) {

    var camera: Camera? = null
        private set
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main get() = ContextCompat.getMainExecutor(ctx)

    val isRecording get() = recording != null

    /** Noise / sharpening tuning pushed to every use case through Camera2 interop. */
    private fun tune(ext: Camera2Interop.Extender<*>, night: Boolean) {
        ext.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
        )
        ext.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            if (night) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_HIGH_QUALITY,
        )
        ext.setCaptureRequestOption(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
        )
        if (night) {
            // Allow the sensor up to 1/24 s per frame so each frame collects more light.
            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(24, 30))
        }
    }

    fun bind(
        owner: LifecycleOwner,
        view: PreviewView,
        front: Boolean,
        night: Boolean,
        flash: Flash,
        video: Boolean,
        hdr: Boolean,
        onReady: (Camera, Boolean) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val p = future.get()
            provider = p
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            fun attempt(useHdr: Boolean, withImage: Boolean): Camera {
                val sel = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        if (video) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
                    ).build()
                val pb = Preview.Builder().setResolutionSelector(sel)
                if (useHdr) pb.setDynamicRange(DynamicRange.HLG_10_BIT)
                tune(Camera2Interop.Extender(pb), night)
                val preview = pb.build().also { it.setSurfaceProvider(view.surfaceProvider) }

                val ib = ImageCapture.Builder()
                    .setResolutionSelector(sel)
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(
                        when (flash) {
                            Flash.OFF -> ImageCapture.FLASH_MODE_OFF
                            Flash.ON -> ImageCapture.FLASH_MODE_ON
                            Flash.AUTO -> ImageCapture.FLASH_MODE_AUTO
                        },
                    )
                if (useHdr && Build.VERSION.SDK_INT >= 34) {
                    ib.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                tune(Camera2Interop.Extender(ib), night)
                val img = ib.build()

                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(Quality.UHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)),
                    )
                    .setTargetVideoEncodingBitRate(if (night) 80_000_000 else 60_000_000)
                    .build()
                val vb = VideoCapture.Builder(recorder)
                if (useHdr) vb.setDynamicRange(DynamicRange.HLG_10_BIT)
                tune(Camera2Interop.Extender(vb), night)
                val vid = vb.build()

                p.unbindAll()
                val cam = if (withImage) {
                    p.bindToLifecycle(owner, selector, preview, img, vid)
                } else {
                    p.bindToLifecycle(owner, selector, preview, vid)
                }
                imageCapture = if (withImage) img else null
                videoCapture = vid
                return cam
            }

            var active = false
            val cam: Camera = try {
                if (!hdr) throw IllegalStateException("sdr")
                attempt(true, true).also { active = true }
            } catch (e: Exception) {
                try {
                    attempt(false, true)
                } catch (e2: Exception) {
                    attempt(false, false)
                }
            }
            camera = cam
            onReady(cam, active)
        }, main)
    }

    fun setRotation(rotation: Int) {
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setEv(index: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(index)
    }

    fun focus(view: PreviewView, x: Float, y: Float) {
        val c = camera ?: return
        val point = view.meteringPointFactory.createPoint(x, y)
        c.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point).disableAutoCancel().build(),
        )
    }

    fun setTorch(on: Boolean) {
        camera?.cameraControl?.enableTorch(on)
    }

    // ---------------------------------------------------------------- photo

    fun takePhoto(lut: Lut?, onSaved: () -> Unit, fail: (String) -> Unit) {
        val ic = imageCapture ?: return fail("Photo capture not available")
        val tmp = File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            io,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    try {
                        val finalFile = if (lut != null) grade(tmp, lut) else tmp
                        saveJpeg(finalFile, "LONE_${Exporter.stamp()}")
                        if (finalFile != tmp) finalFile.delete()
                        tmp.delete()
                        main.execute(onSaved)
                    } catch (e: Throwable) {
                        main.execute { fail(e.message ?: "Save failed") }
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    main.execute { fail(e.message ?: "Capture failed") }
                }
            },
        )
    }

    private fun grade(src: File, lut: Lut): File {
        val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts)
        lut.applyTo(bmp)
        val out = File(ctx.cacheDir, "graded_${System.currentTimeMillis()}.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        bmp.recycle()
        val orientation = ExifInterface(src.absolutePath).getAttribute(ExifInterface.TAG_ORIENTATION)
        if (orientation != null) {
            val e = ExifInterface(out.absolutePath)
            e.setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
            e.saveAttributes()
        }
        return out
    }

    private fun saveJpeg(file: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lonecamera")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Snapshot while recording: grabs the live preview frame, so it never disturbs the video. */
    fun snapshot(view: PreviewView, lut: Lut?, onSaved: () -> Unit) {
        val bmp = view.bitmap ?: return
        io.execute {
            val copy = bmp.copy(Bitmap.Config.ARGB_8888, true)
            lut?.applyTo(copy)
            val f = File(ctx.cacheDir, "snap_${System.currentTimeMillis()}.jpg")
            f.outputStream().use { copy.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            saveJpeg(f, "LONE_SNAP_${Exporter.stamp()}")
            f.delete()
            main.execute(onSaved)
        }
    }

    // ---------------------------------------------------------------- video

    @SuppressLint("MissingPermission")
    fun startVideo(onFinished: (Uri?) -> Unit) {
        val vc = videoCapture ?: return onFinished(null)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "LONE_RAW_${Exporter.stamp()}")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Lonecamera")
        }
        val opts = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        recording = vc.output.prepareRecording(ctx, opts)
            .withAudioEnabled()
            .start(main) { ev ->
                if (ev is VideoRecordEvent.Finalize) {
                    recording = null
                    onFinished(if (ev.hasError()) null else ev.outputResults.outputUri)
                }
            }
    }

    fun stopVideo() {
        recording?.stop()
    }

    fun release() {
        recording?.stop()
        provider?.unbindAll()
    }
}
