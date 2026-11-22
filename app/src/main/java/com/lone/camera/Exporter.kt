package com.lone.camera

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File

enum class Shape(val label: String, val suffix: String, val aspect: Float?) {
    ORIGINAL("Original", "", null),
    LANDSCAPE("Landscape", "_land", 16f / 9f),
    PORTRAIT("Portrait", "_port", 9f / 16f),
}

data class ExportJob(val shape: Shape, val lut: Lut?, val voiceClean: Boolean)

/**
 * Re-renders a finished recording with a LUT, voice clean-up and/or a crop to landscape /
 * portrait. Must be called from the main thread (Media3 Transformer needs a Looper).
 */
@UnstableApi
object Exporter {

    fun run(
        ctx: Context,
        source: Uri,
        jobs: List<ExportJob>,
        onStatus: (String) -> Unit,
        onDone: () -> Unit,
    ) {
        if (jobs.isEmpty()) { onDone(); return }
        val (w, h) = frameSize(ctx, source)
        runNext(ctx, source, w, h, jobs, 0, onStatus, onDone)
    }

    private fun runNext(
        ctx: Context, source: Uri, w: Int, h: Int, jobs: List<ExportJob>, idx: Int,
        onStatus: (String) -> Unit, onDone: () -> Unit,
    ) {
        if (idx >= jobs.size) { onDone(); return }
        val job = jobs[idx]
        onStatus("Rendering ${job.shape.label} (${idx + 1}/${jobs.size})…")

        val video = ArrayList<Effect>()
        job.lut?.let { video.add(SingleColorLut.createFromCube(it.toCube())) }
        job.shape.aspect?.let { target ->
            val frameAr = w.toFloat() / h
            if (frameAr > target) {
                val f = target / frameAr
                video.add(Crop(-f, f, -1f, 1f))
            } else if (frameAr < target) {
                val f = frameAr / target
                video.add(Crop(-1f, 1f, -f, f))
            }
        }
        val audio = ArrayList<AudioProcessor>()
        if (job.voiceClean) audio.add(VoiceCleanProcessor())

        val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
            .setEffects(Effects(audio, video))
            .build()

        val out = File(ctx.cacheDir, "export_${System.currentTimeMillis()}_$idx.mp4")
        val next = {
            runNext(ctx, source, w, h, jobs, idx + 1, onStatus, onDone)
        }
        val transformer = Transformer.Builder(ctx)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val name = "LONE_${stamp()}${job.shape.suffix}${if (job.lut != null) "_" + job.lut.name.replace(' ', '') else ""}"
                    saveVideo(ctx, out, name)
                    out.delete()
                    next()
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    onStatus("Render failed: ${exportException.errorCodeName}")
                    out.delete()
                    next()
                }
            })
            .build()
        transformer.start(item, out.absolutePath)
    }

    /** Display-oriented frame size (rotation applied). */
    private fun frameSize(ctx: Context, uri: Uri): Pair<Int, Int> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1920
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1080
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            if (rot == 90 || rot == 270) Pair(h, w) else Pair(w, h)
        } catch (e: Exception) {
            Pair(1920, 1080)
        } finally {
            r.release()
        }
    }

    fun stamp(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())

    private fun saveVideo(ctx: Context, file: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Lonecamera")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
    }
}
