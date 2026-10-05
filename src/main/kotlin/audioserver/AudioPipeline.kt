package audioserver

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.logging.Logger

/**
 * Lanza el proceso externo y expone su salida como InputStream.
 * - Fuente http(s) / ytsearchN:  yt-dlp (stdout) | ffmpeg (stdin -> MP3 stdout)
 * - Fuente local:                ffmpeg lee el archivo -> MP3 stdout
 * Salida: MP3 CBR sin cabeceras Xing/ID3 (así se puede saltar a un segundo exacto por bytes).
 */
class AudioPipeline(private val cfg: Config) {
    private val log = Logger.getLogger("AudioPipeline")

    class Running(
        val output: InputStream,
        private val processes: List<Process>,
        private val tempFile: Path? = null,
    ) : AutoCloseable {
        /** Espera a que terminen todos los procesos; true si todos salieron con código 0. */
        fun succeeded(): Boolean = processes.all { it.waitFor() == 0 }

        override fun close() {
            processes.forEach { if (it.isAlive) it.destroy() }
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
            tempFile?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    fun start(source: String): Running {
        // yt-dlp reescribe el archivo de cookies; trabaja sobre una copia temporal (el montaje puede ser de solo lectura)
        val cookieCopy: Path? = cfg.ytDlpCookies?.let {
            Files.createTempFile("ytc", ".txt").also { t -> Files.copy(Path.of(it), t, StandardCopyOption.REPLACE_EXISTING) }
        }
        val ffmpegOut = listOf(
            "-vn", "-acodec", "libmp3lame",
            "-b:a", cfg.bitrate, "-ar", cfg.sampleRate.toString(), "-ac", "2",
            "-write_xing", "0", "-write_id3v2", "0",
            "-f", "mp3", "pipe:1",
        )
        val ffmpegBase = listOf(cfg.ffmpeg, "-hide_banner", "-loglevel", "error")
        val builders = if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("ytsearch")) {
            listOf(
                ProcessBuilder(ytDlpCommand(source, cookieCopy)),
                ProcessBuilder(ffmpegBase + listOf("-i", "pipe:0") + ffmpegOut),
            )
        } else {
            listOf(ProcessBuilder(ffmpegBase + listOf("-i", source) + ffmpegOut))
        }
        builders.forEach { it.redirectError(ProcessBuilder.Redirect.PIPE) }

        // startPipeline conecta stdout(n) -> stdin(n+1) a nivel de SO, sin pasar por la JVM
        val processes = ProcessBuilder.startPipeline(builders)
        processes.forEachIndexed { i, p ->
            val name = builders[i].command().first()
            Thread.ofVirtual().start {
                runCatching { p.errorStream.bufferedReader().forEachLine { log.warning("[$name] $it") } }
            }
        }
        log.info("Pipeline iniciado: ${builders.joinToString(" | ") { it.command().first() }}")
        return Running(processes.last().inputStream, processes, cookieCopy)
    }

    /** "ytsearchN:texto" -> toma solo el resultado número N de la búsqueda. */
    private fun ytDlpCommand(source: String, cookies: Path?): List<String> {
        val cmd = mutableListOf(cfg.ytDlp, "-f", "bestaudio/best", "-q", "-o", "-")
        cookies?.let { cmd += listOf("--cookies", it.toString()) }
        cfg.ytDlpExtractorArgs?.let { cmd += listOf("--extractor-args", it) }   // <- línea nueva
        val n = Regex("^ytsearch(\\d+):").find(source)?.groupValues?.get(1)
        if (n != null) cmd += listOf("--playlist-items", n) else cmd += "--no-playlist"
        cmd += listOf("--", source)
        return cmd
    }
}
