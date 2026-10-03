package audioserver

import java.io.InputStream
import java.util.logging.Logger

/**
 * Lanza el proceso externo y expone su salida como InputStream.
 * - Fuente http(s): yt-dlp (stdout) | ffmpeg (stdin -> MP3 stdout)
 * - Fuente local:   ffmpeg lee el archivo -> MP3 stdout
 * Se usa MP3 CBR (compatible con Alexa AudioPlayer, 16-48 kHz).
 */
class AudioPipeline(private val cfg: Config) {
    private val log = Logger.getLogger("AudioPipeline")

    class Running(val output: InputStream, private val processes: List<Process>) : AutoCloseable {
        override fun close() {
            processes.forEach { if (it.isAlive) it.destroy() }
            processes.forEach { if (it.isAlive) it.destroyForcibly() }
        }
    }

    fun start(source: String, startSec: Int = 0): Running {
        val seek = if (startSec > 0) listOf("-ss", startSec.toString()) else emptyList()
        val ffmpegOut = listOf(
            "-vn", "-acodec", "libmp3lame",
            "-b:a", cfg.bitrate, "-ar", cfg.sampleRate.toString(), "-ac", "2",
            "-f", "mp3", "pipe:1",
        )
        val builders = if (source.startsWith("http://") || source.startsWith("https://") || source.startsWith("ytsearch")) {
            listOf(
                ProcessBuilder(ytDlpCommand(source)),
                ProcessBuilder(listOf(cfg.ffmpeg, "-hide_banner", "-loglevel", "error") + seek + listOf("-i", "pipe:0") + ffmpegOut),
            )
        } else {
            listOf(ProcessBuilder(listOf(cfg.ffmpeg, "-hide_banner", "-loglevel", "error") + seek + listOf("-i", source) + ffmpegOut))
        }
        // stderr de cada proceso -> lo leemos nosotros para loguearlo
        builders.forEach { it.redirectError(ProcessBuilder.Redirect.PIPE) }

        // startPipeline conecta stdout(n) -> stdin(n+1) a nivel de SO, sin pasar por la JVM
        val processes = ProcessBuilder.startPipeline(builders)
        processes.forEachIndexed { i, p ->
            val name = builders[i].command().first()
            Thread.ofVirtual().start {
                p.errorStream.bufferedReader().forEachLine { log.warning("[$name] $it") }
            }
        }
        log.info("Pipeline iniciado: ${builders.joinToString(" | ") { it.command().first() }}")
        return Running(processes.last().inputStream, processes)
    }

    /** "ytsearchN:texto" -> toma solo el resultado número N de la búsqueda. */
    private fun ytDlpCommand(source: String): List<String> {
        val cmd = mutableListOf(cfg.ytDlp, "-f", "bestaudio/best", "-q", "-o", "-")
        cfg.ytDlpCookies?.let { cmd += listOf("--cookies", it) }
        val n = Regex("^ytsearch(\\d+):").find(source)?.groupValues?.get(1)
        if (n != null) cmd += listOf("--playlist-items", n) else cmd += "--no-playlist"
        cmd += listOf("--", source)
        return cmd
    }
}
