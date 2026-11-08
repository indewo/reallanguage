package com.reallanguage.app.feature.capture.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ImportAudioInfo(val uri: Uri, val name: String, val durationMs: Long)

internal val AUDIO_DOCUMENT_TYPES = arrayOf(
    "audio/mpeg", "audio/mp3", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/aacp",
    "audio/wav", "audio/wave", "audio/x-wave", "audio/x-wav", "audio/vnd.wave", "video/mp4", "application/mp4",
)

internal suspend fun inspectImportAudio(context: Context, uri: Uri): ImportAudioInfo = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "音频文件"
    val ext = displayName.substringAfterLast('.', "").lowercase()
    val mime = resolver.getType(uri)?.lowercase()
    val container = if (ext.isNotEmpty()) ext else when (mime) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "video/mp4", "application/mp4" -> "mp4"
        "audio/wav", "audio/wave", "audio/x-wav", "audio/x-wave", "audio/vnd.wave" -> "wav"
        else -> ""
    }
    val codec = when (container) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4a-latm"
        "wav" -> "audio/raw"
        else -> throw IllegalArgumentException("请选择 MP3、M4A/AAC、PCM WAV 或含 AAC 音轨的 MP4 文件。")
    }
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
        val audioTracks = tracks.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        require(audioTracks.isNotEmpty()) { "这个文件没有可用的音轨，请更换文件。" }
        val supported = audioTracks.firstOrNull { it.getString(MediaFormat.KEY_MIME) == codec }
            ?: throw IllegalArgumentException("文件中的音频编码不受支持，请更换文件。")
        val trackDuration = if (supported.containsKey(MediaFormat.KEY_DURATION))
            supported.getLong(MediaFormat.KEY_DURATION) / 1_000 else 0L
        val duration = if (trackDuration > 0L) trackDuration else {
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(context, uri)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally { metadata.release() }
        }
        require(duration > 0) { "无法读取音频时长，请检查文件是否完整。" }
        ImportAudioInfo(uri, displayName, duration)
    } finally { extractor.release() }
}
