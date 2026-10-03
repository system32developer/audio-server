package audioserver

/** Configuración 100% por variables de entorno. */
data class Config(
    val port: Int,
    val ytDlp: String,
    val ffmpeg: String,
    val bitrate: String,
    val sampleRate: Int,
    val token: String?,
    val ytDlpCookies: String?,
    val sources: Map<String, String>,
    val cacheDir: String,
    val cacheMaxBytes: Long,
    val maxDownloads: Int,
) {
    /** MP3 CBR: bytes por segundo de audio (sirve para saltar a un punto del archivo). */
    val bytesPerSecond: Long
        get() = (bitrate.trim().lowercase().removeSuffix("k").toLongOrNull() ?: 128L) * 1000L / 8L

    companion object {
        fun fromEnv(): Config {
            val env = System.getenv()
            // AUDIO_SOURCES="123=https://ejemplo.com/video;456=/ruta/archivo.mp3"
            val sources = (env["AUDIO_SOURCES"] ?: "")
                .split(';')
                .mapNotNull { entry ->
                    val i = entry.indexOf('=')
                    if (i <= 0) null else entry.substring(0, i).trim() to entry.substring(i + 1).trim()
                }.toMap()
            return Config(
                port = env["PORT"]?.toInt() ?: 8080,
                ytDlp = env["YTDLP_PATH"] ?: "yt-dlp",
                ffmpeg = env["FFMPEG_PATH"] ?: "ffmpeg",
                bitrate = env["AUDIO_BITRATE"] ?: "128k",
                sampleRate = env["AUDIO_SAMPLE_RATE"]?.toInt() ?: 44100,
                token = env["STREAM_TOKEN"]?.takeIf { it.isNotBlank() },
                ytDlpCookies = env["YTDLP_COOKIES"]?.takeIf { it.isNotBlank() },
                sources = sources,
                cacheDir = env["CACHE_DIR"] ?: "/cache",
                cacheMaxBytes = (env["CACHE_MAX_MB"]?.toLongOrNull() ?: 2048L) * 1024L * 1024L,
                maxDownloads = env["MAX_DOWNLOADS"]?.toIntOrNull() ?: 3,
            )
        }
    }
}
