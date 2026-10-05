package audioserver

import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/** Normalización de textos de búsqueda para que "Naruto " y "naruto" usen la misma caché. */
object Sources {
    private fun text(query: String, n: Int) =
        "ytsearch$n:" + query.trim().lowercase().replace(Regex("\\s+"), " ")

    fun search(query: String, n: Int): String {
        val pinned = if (n == 1) TrackIndex.pinnedId(query) else null
        return if (pinned != null) "https://www.youtube.com/watch?v=$pinned" else text(query, n)
    }

    /** Búsqueda por texto aunque haya un video fijado (respaldo si ese video no está disponible). */
    fun fallback(query: String): String = text(query, 1)
}

/** Un audio en la caché: puede estar descargándose (varios lectores lo siguen) o ya completo. */
class CacheEntry(val source: String, val finalFile: Path, val partFile: Path) {
    enum class State { DOWNLOADING, DONE, FAILED }

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile var state: State = State.DOWNLOADING
    @Volatile var written: Long = 0

    fun advance(n: Int) = lock.withLock { written += n; changed.signalAll() }
    fun finish(s: State) = lock.withLock { state = s; changed.signalAll() }

    /** Espera a que existan bytes más allá de [pos]. false si falló, terminó antes o venció el tiempo. */
    fun awaitBytes(pos: Long, timeoutMs: Long): Boolean {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        lock.withLock {
            while (written <= pos) {
                if (state != State.DOWNLOADING || nanos <= 0) return false
                nanos = changed.awaitNanos(nanos)
            }
            return true
        }
    }
}

/**
 * Caché en disco de MP3 ya convertidos.
 *  - Una sola descarga por audio, aunque lo pidan varios clientes a la vez.
 *  - Mientras se descarga, los clientes reciben los datos conforme llegan (streaming progresivo).
 *  - Si ya está completo, se sirve del disco al instante.
 *  - Límite de tamaño: borra lo menos usado.
 */
class AudioCache(private val cfg: Config, private val pipeline: AudioPipeline) {
    private val log = Logger.getLogger("AudioCache")
    private val entries = ConcurrentHashMap<String, CacheEntry>()
    private val slots = Semaphore(cfg.maxDownloads)
    private val dir: Path = Path.of(cfg.cacheDir).also { Files.createDirectories(it) }

    init {
        // restos de descargas interrumpidas en un arranque anterior
        Files.list(dir).use { s -> s.filter { it.toString().endsWith(".part") }.forEach { Files.deleteIfExists(it) } }
        log.info("Caché en $dir (máx ${cfg.cacheMaxBytes / 1024 / 1024} MB)")
    }

    private fun keyOf(source: String): String =
        MessageDigest.getInstance("SHA-1").digest(source.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** Devuelve la entrada de la caché; si no existe, inicia la descarga en segundo plano. */
    fun obtain(source: String): CacheEntry {
        val key = keyOf(source)
        return entries.compute(key) { _, existing ->
            when {
                existing != null && existing.state == CacheEntry.State.DOWNLOADING -> existing
                existing != null && existing.state == CacheEntry.State.DONE && Files.exists(existing.finalFile) -> {
                    touch(existing.finalFile); existing
                }
                else -> newEntry(key, source)
            }
        }!!
    }

    private fun newEntry(key: String, source: String): CacheEntry {
        val finalFile = dir.resolve("$key.mp3")
        val entry = CacheEntry(source, finalFile, dir.resolve("$key.part"))
        if (Files.exists(finalFile)) {
            entry.written = Files.size(finalFile)
            entry.state = CacheEntry.State.DONE
            touch(finalFile)
        } else {
            Thread.ofVirtual().name("dl-$key").start { download(key, entry) }
        }
        return entry
    }

    private fun download(key: String, e: CacheEntry) {
        slots.acquireUninterruptibly()
        val t0 = System.currentTimeMillis()
        try {
            pipeline.start(e.source).use { run ->
                Files.newOutputStream(e.partFile).use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = run.output.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        e.advance(n)
                    }
                }
                if (e.written > 0 && run.succeeded()) {
                    Files.move(e.partFile, e.finalFile, StandardCopyOption.REPLACE_EXISTING)
                    e.finish(CacheEntry.State.DONE)
                    log.info("Descarga completa ${e.source}: ${e.written} bytes en ${System.currentTimeMillis() - t0} ms")
                    prune()
                } else {
                    log.warning("Descarga fallida (${e.written} bytes): ${e.source}")
                    Files.deleteIfExists(e.partFile)
                    e.finish(CacheEntry.State.FAILED)
                    entries.remove(key, e)
                }
            }
        } catch (ex: Exception) {
            log.warning("Error descargando ${e.source}: ${ex.message}")
            runCatching { Files.deleteIfExists(e.partFile) }
            e.finish(CacheEntry.State.FAILED)
            entries.remove(key, e)
        } finally {
            slots.release()
        }
    }

    /** Envía el audio a [out] desde el byte [startByte], siguiendo la descarga si aún no termina. */
    fun streamTo(e: CacheEntry, startByte: Long, out: OutputStream): Long {
        if (!e.awaitBytes(startByte, 90_000)) return 0
        openReader(e).use { raf ->
            var pos = startByte
            val buf = ByteArray(16 * 1024)
            while (e.awaitBytes(pos, 120_000)) {
                raf.seek(pos)
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), e.written - pos).toInt())
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush() // envía el chunk ya
                pos += n
            }
            return pos - startByte
        }
    }

    private fun openReader(e: CacheEntry): RandomAccessFile =
        try { RandomAccessFile(e.partFile.toFile(), "r") } catch (_: IOException) { RandomAccessFile(e.finalFile.toFile(), "r") }

    private fun touch(f: Path) {
        runCatching { Files.setLastModifiedTime(f, FileTime.fromMillis(System.currentTimeMillis())) }
    }

    /** Si la caché supera el límite, borra los audios menos recientes. */
    private fun prune() {
        runCatching {
            val files = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".mp3") }.toList() }
            var total = files.sumOf { Files.size(it) }
            if (total <= cfg.cacheMaxBytes) return
            for (f in files.sortedBy { Files.getLastModifiedTime(it) }) {
                if (total <= cfg.cacheMaxBytes) break
                val size = Files.size(f)
                entries.remove(f.fileName.toString().removeSuffix(".mp3"))
                Files.deleteIfExists(f)
                total -= size
                log.info("Caché: eliminado ${f.fileName} ($size bytes)")
            }
        }.onFailure { log.warning("Error limpiando caché: ${it.message}") }
    }
}
