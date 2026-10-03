package audioserver

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

class Catalog(private val cfg: Config) {
    private val log = Logger.getLogger("Catalog")

    data class Entry(val id: String, val title: String, val channel: String, val duration: String) {
        fun toLine() = listOf(id, title, channel, duration).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
    }

    fun search(query: String, limit: Int): List<Entry> =
        list("ytsearch$limit:$query", limit, timeoutSeconds = 30, wholePlaylist = false)

    fun playlist(url: String, limit: Int): List<Entry> =
        list(url, limit, timeoutSeconds = 120, wholePlaylist = true)

    private fun list(source: String, limit: Int, timeoutSeconds: Long, wholePlaylist: Boolean): List<Entry> {
        val cookies: Path? = cfg.ytDlpCookies?.let {
            Files.createTempFile("ytc", ".txt").also { t -> Files.copy(Path.of(it), t, StandardCopyOption.REPLACE_EXISTING) }
        }
        try {
            val cmd = mutableListOf(
                cfg.ytDlp, "--flat-playlist", "--no-warnings", "--ignore-errors",
                "--playlist-end", limit.toString(),
                "--print", "%(id)s$SEP%(title)s$SEP%(channel,uploader)s$SEP%(duration)s",
            )
            if (wholePlaylist) cmd += "--yes-playlist"
            cookies?.let { cmd += listOf("--cookies", it.toString()) }
            cmd += listOf("--", source)

            val process = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = StringBuilder()
            val reader = Thread.ofVirtual().start {
                runCatching { process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') } }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warning("yt-dlp excedió ${timeoutSeconds}s: $source")
                process.destroyForcibly()
            }
            reader.join(3_000)
            return parse(output.toString(), limit)
        } finally {
            cookies?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun parse(raw: String, limit: Int): List<Entry> = raw.lineSequence()
        .map { it.split(SEP) }
        .filter { it.size >= 4 && it[0].isNotBlank() && it[1].trim() !in UNAVAILABLE }
        .map { Entry(it[0].trim(), it[1].trim(), it[2].trim().takeUnless { c -> c == "NA" }.orEmpty(), it[3].trim()) }
        .take(limit)
        .toList()

    private companion object {
        const val SEP = "\u001f"
        val UNAVAILABLE = setOf("[Private video]", "[Deleted video]")
    }
}
