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

    fun search(query: String, limit: Int): List<Entry> {
        // Si pegan un enlace de YouTube, se resuelve ese video exacto
        videoId(query)?.let { id ->
            return list("https://www.youtube.com/watch?v=$id", 1, timeoutSeconds = 30, wholePlaylist = false)
        }
        // Con limit = 1 (lo usa el Enricher) se mantiene el primer resultado de YouTube
        if (limit == 1) return list("ytsearch1:$query", 1, timeoutSeconds = 30, wholePlaylist = false)

        val pool = (limit * 2).coerceAtMost(20)
        return list("ytsearch$pool:$query", pool, timeoutSeconds = 40, wholePlaylist = false)
            .sortedByDescending { score(it) }
            .take(limit)
    }

    private fun videoId(text: String): String? {
        val t = text.trim()
        if ("youtube.com" !in t && "youtu.be" !in t) return null
        return URL_ID.find(t)?.groupValues?.get(1)
    }

    /** Sube los canales oficiales y baja covers, remixes y similares. */
    private fun score(e: Entry): Int {
        val ch = e.channel.lowercase()
        val title = e.title.lowercase()
        var s = 0
        if (ch.endsWith("- topic")) s += 3                                   // audio oficial autogenerado
        if ("vevo" in ch || "official" in ch || "oficial" in ch) s += 2
        if (BAD.containsMatchIn(title)) s -= 2
        return s
    }

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
        val URL_ID = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/)([A-Za-z0-9_-]{11})""")
        val BAD = Regex("""\b(cover|remix|karaoke|sped up|slowed|nightcore|8d|reverb)\b""")
    }
}
