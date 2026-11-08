package com.reallanguage.app.feature.capture.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Size
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** All pixels are decoded and recognized on this device, one image strip at a time. */
object ScreenshotOcr {
    suspend fun read(resolver: ContentResolver, ordered: List<Uri>): String = withContext(Dispatchers.IO) {
        require(ordered.isNotEmpty())
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            ordered.mapIndexed { index, uri ->
                try {
                    readOne(resolver, uri) { bitmap ->
                        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                            .textBlocks.flatMap { it.lines }
                            .mapNotNull { line -> line.boundingBox?.let {
                                it to verifyAmbiguousLine(recognizer, bitmap, it, line.text.trim())
                            } }
                    }.ifBlank { throw IOException("未找到可读文字") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("第 ${index + 1} 张截图未能识别，请检查图片是否清晰，或重新添加。", e)
                }
            }.joinToString("\n\n")
        } finally {
            recognizer.close()
        }
    }

    // A bubble edge can resemble a vertical bar; very short replies can also be ambiguous.
    // Re-read those pixels at two sizes. Preserve the original unless both local reads agree.
    private fun verifyAmbiguousLine(recognizer: TextRecognizer, source: Bitmap, box: Rect, original: String): String {
        fun isHan(character: Char) = Character.UnicodeScript.of(character.code) == Character.UnicodeScript.HAN
        val shortReply = original.length in 2..4 && original.all(::isHan)
        val possibleBubbleEdge = original.startsWith('|') && original.getOrNull(1)?.let(::isHan) == true
        if (!shortReply && !possibleBubbleEdge) return original
        val region = Rect(box)
        val pad = (region.height() * .28f).toInt().coerceAtLeast(6)
        region.inset(-pad, -pad)
        if (!region.intersect(0, 0, source.width, source.height) || region.width() < 32 || region.height() < 32) return original
        val crop = Bitmap.createBitmap(source, region.left, region.top, region.width(), region.height())
        fun read(image: Bitmap) = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0))).text.trim()
        } catch (_: ExecutionException) { null }
        try {
            val close = read(crop) ?: return original
            if (close == original) return original
            val validCorrection = if (possibleBubbleEdge) close == original.drop(1)
                else close.length == original.length && close.all(::isHan)
            if (!validCorrection) return original
            val scaled = Bitmap.createScaledBitmap(crop, (crop.width * 1.3f).toInt(), (crop.height * 1.3f).toInt(), true)
            return try {
                if (read(scaled) == close) close else original
            } finally {
                if (scaled !== crop) scaled.recycle()
            }
        } finally {
            if (crop !== source) crop.recycle()
        }
    }

    private suspend fun readOne(
        resolver: ContentResolver,
        uri: Uri,
        recognize: (Bitmap) -> List<Pair<Rect, String>>,
    ): String {
        return withUprightImage(resolver, uri) { image ->
            var sample = 1
            while (image.width / sample > 2600) sample *= 2
            val stripHeight = 1800 * sample
            val overlap = 180 * sample
            val all = mutableListOf<String>()
            var start = 0
            while (start < image.height) {
                currentCoroutineContext().ensureActive()
                val end = minOf(image.height, start + stripHeight)
                val top = maxOf(0, start - overlap)
                val bitmap = image.strip(top, minOf(image.height, end + overlap), sample)
                try {
                    // Each seam has an overlap. Assign a line to just one core strip by its centre.
                    val lines = recognize(bitmap).filter { (box, text) ->
                        val center = top + box.centerY() * sample
                        text.isNotBlank() && center >= start && center < end
                    }.sortedWith(compareBy({ it.first.top }, { it.first.left }))
                    all += lines.map { it.second }
                } finally {
                    bitmap.recycle()
                }
                start = end
            }
            all.joinToString("\n")
        }
    }
}

internal suspend fun screenshotPreview(resolver: ContentResolver, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        withUprightImage(resolver, uri) { image ->
            // Sample the top of a long conversation at a readable resolution.
            var sample = 1
            while (image.width / (sample * 2) >= 400) sample *= 2
            image.strip(0, minOf(image.height, image.width), sample)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { resolver.loadThumbnail(uri, Size(480, 480), null) } catch (_: Exception) { null }
        } else null
    }
}

@Suppress("DEPRECATION")
private inline fun <T> withUprightImage(resolver: ContentResolver, uri: Uri, action: (UprightImage) -> T): T {
    val orientation = orientation(resolver, uri)
    return requireNotNull(resolver.openInputStream(uri)).use { input ->
        val decoder = BitmapRegionDecoder.newInstance(input, false) ?: throw IOException("图片无法解码")
        try {
            action(UprightImage(decoder, orientation))
        } finally {
            decoder.recycle()
        }
    }
}

/** One coordinate system for portrait, landscape, rotated and mirrored screenshot files. */
private class UprightImage(private val decoder: BitmapRegionDecoder, private val orientation: Int) {
    private val sourceToUpright = orientationTransform(orientation)
    private val extent = RectF(0f, 0f, decoder.width.toFloat(), decoder.height.toFloat()).also {
        sourceToUpright.mapRect(it)
        sourceToUpright.postTranslate(-it.left, -it.top)
    }
    private val uprightToSource = Matrix().also { check(sourceToUpright.invert(it)) }
    val width = extent.width().toInt()
    val height = extent.height().toInt()

    fun strip(top: Int, bottom: Int, sample: Int): Bitmap {
        val raw = RectF(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
        uprightToSource.mapRect(raw)
        val bounds = Rect().also(raw::roundOut)
        bounds.intersect(0, 0, decoder.width, decoder.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = decoder.decodeRegion(bounds, options) ?: throw IOException("无法读取图片区域")
        val upright = orientBitmap(decoded, orientation)
        if (decoded !== upright) decoded.recycle()
        return upright
    }
}

private fun orientation(resolver: ContentResolver, uri: Uri): Int = try {
    resolver.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
} catch (_: IOException) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun orientBitmap(source: Bitmap, orientation: Int): Bitmap {
    val matrix = orientationTransform(orientation)
    if (matrix.isIdentity) return source
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}

private fun orientationTransform(orientation: Int) = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
    }
}
