package app.nogarbo.leflac.data

import android.net.Uri

data class AudioTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val folderName: String,
    val size: Long,
    val mimeType: String
) {
    val bitrate: Long
        get() = if (duration > 0) (size * 8) / (duration / 1000) / 1000 else 0
        
    val extension: String
        get() = when {
            mimeType.contains("flac") -> "FLAC"
            mimeType.contains("mpeg") -> "MP3"
            mimeType.contains("mp4") -> "AAC"
            mimeType.contains("ogg") -> "OGG"
            mimeType.contains("wav") -> "WAV"
            else -> mimeType.substringAfterLast("/")
        }.uppercase()
}
