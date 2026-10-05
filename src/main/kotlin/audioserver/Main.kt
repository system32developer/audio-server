package audioserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.logging.Logger

private val log = Logger.getLogger("Main")
private val QID = Regex("[A-Za-z0-9_-]{1,64}")
private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
private const val PREFETCH = 2 // cuántos elementos de la cola se descargan por adelantado

fun main() {
    val cfg = Config.fromEnv()
    val cache = AudioCache(cfg, AudioPipeline(cfg))
    val queues = QueueStore(cfg.queueMax)
    val catalog = Catalog(cfg)
    val enricher = Enricher(catalog)

    val server = HttpServer.create(InetSocketAddress(cfg.port), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()

    server.createContext("/health") { ex -> respondText(ex, 200, "ok") }

    server.createContext("/stream") { ex ->
        try {
            handleStream(ex, cfg, cache)
        } catch (e: Exception) {
            log.warning("Error en /stream: ${e.message}")
            runCatching { respondText(ex, 500, "internal error") }
        } finally {
            ex.close()
        }
    }

    server.createContext("/queue") { ex ->
        try {
            handleQueue(ex, cfg, cache, queues)
        } catch (e: Exception) {
            log.warning("Error en /queue: ${e.message}")
            runCatching { respondText(ex, 500, "internal error") }
        } finally {
            ex.close()
        }
    }

    server.createContext("/queues") { ex ->
        try {
            handleQueues(ex, cfg, queues, enricher)
        } catch (e: Exception) {
            log.warning("Error en /queues: ${e.message}")
            runCatching { respondText(ex, 500, "internal error") }
        } finally {
            ex.close()
        }
    }

    for ((path, isPlaylist) in listOf("/search" to false, "/playlist" to true)) {
        server.createContext(path) { ex ->
            try {
                handleCatalog(ex, cfg, catalog, isPlaylist)
            } catch (e: Exception) {
                log.warning("Error en $path: ${e.message}")
                runCatching { respondText(ex, 500, "internal error") }
            } finally {
                ex.close()
            }
        }
    }

    server.start()
    log.info("audio-server escuchando en :${cfg.port}")
}

// ------------------------------------------------------------------ /stream

private fun handleStream(ex: HttpExchange, cfg: Config, cache: AudioCache) {
    if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") return respondText(ex, 405, "method not allowed")
    val q = parseQuery(ex.requestURI.rawQuery)
    if (!authorized(cfg, q)) return respondText(ex, 403, "forbidden")

    val search = q["q"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
    val id = q["id"]
    val n = (q["n"]?.toIntOrNull() ?: 1).coerceIn(1, 10)
    val startSec = (q["t"]?.toIntOrNull() ?: 0).coerceIn(0, 86_400)
    val source = when {
        search != null -> Sources.search(search, n)
        id != null -> cfg.sources[id] ?: return respondText(ex, 404, "unknown id")
        else -> return respondText(ex, 400, "missing id or q")
    }

    val entry = cache.obtain(source)
    val hit = entry.state == CacheEntry.State.DONE
    val startByte = startSec.toLong() * cfg.bytesPerSecond
    log.info("Stream $source t=$startSec ${if (hit) "CACHE HIT" else "descargando"} desde ${ex.remoteAddress}")

    if (ex.requestMethod == "HEAD") {
        ex.responseHeaders.add("Content-Type", "audio/mpeg")
        ex.sendResponseHeaders(200, -1)
        return
    }
    // Espera al primer byte: si la descarga falla, se responde un error real en vez de un stream vacío
    if (!entry.awaitBytes(startByte, 90_000)) return respondText(ex, 502, "audio unavailable")

    ex.responseHeaders.add("Content-Type", "audio/mpeg")
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(200, 0) // chunked
    try {
        val sent = cache.streamTo(entry, startByte, ex.responseBody)
        log.info("Stream $source terminado: $sent bytes enviados")
    } catch (e: IOException) {
        log.info("Stream $source cortado por el cliente") // normal: Alexa/navegador cerró la conexión
    }
}

// ------------------------------------------------------------------ /queue/*

private fun handleQueue(ex: HttpExchange, cfg: Config, cache: AudioCache, queues: QueueStore) {
    val q = parseQuery(ex.requestURI.rawQuery)
    if (!authorized(cfg, q)) return respondText(ex, 403, "forbidden")
    val qid = q["qid"]?.takeIf { QID.matches(it) } ?: return respondText(ex, 400, "invalid qid")

    when (ex.requestURI.path.removePrefix("/queue")) {
        "/add" -> {
            val item = itemFrom(q) ?: return respondText(ex, 400, "missing q")
            val pos = queues.add(qid, item)
            if (pos < 0) return respondText(ex, 409, "queue full")
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] + '$item' (posición $pos)")
            respondText(ex, 200, pos.toString())
        }
        "/next" -> {
            val item = queues.pop(qid)
            if (item == null) {
                ex.sendResponseHeaders(204, -1) // cola vacía
            } else {
                prefetch(cache, queues, qid)
                log.info("Cola[$qid] siguiente: '$item'")
                respondText(ex, 200, item)
            }
        }
        "/list" -> respondText(ex, 200, queues.list(qid).joinToString("\n"))
        "/clear" -> { queues.clear(qid); respondText(ex, 200, "ok") }
        "/insert" -> {
            val item = itemFrom(q) ?: return respondText(ex, 400, "missing q")
            val index = (q["i"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
            val pos = queues.insert(qid, index, item)
            if (pos < 0) return respondText(ex, 409, "queue full")
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] + '$item' (insertado en $pos)")
            respondText(ex, 200, pos.toString())
        }
        "/remove" -> {
            val index = q["i"]?.toIntOrNull() ?: return respondText(ex, 400, "missing i")
            val expected = q["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            if (!queues.remove(qid, index, expected)) return respondText(ex, 404, "gone")
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] - posición $index")
            respondText(ex, 200, "ok")
        }
        "/move" -> {
            val from = q["from"]?.toIntOrNull() ?: return respondText(ex, 400, "missing from")
            val to = q["to"]?.toIntOrNull() ?: return respondText(ex, 400, "missing to")
            val expected = q["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            if (!queues.move(qid, from, to, expected)) return respondText(ex, 404, "gone")
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] movido $from -> $to")
            respondText(ex, 200, "ok")
        }
        "/prev" -> {
            val item = queues.previous(qid)
            if (item == null) {
                ex.sendResponseHeaders(204, -1) // no hay anterior
            } else {
                prefetch(cache, queues, qid)
                log.info("Cola[$qid] anterior: '$item'")
                respondText(ex, 200, item)
            }
        }
        "/playnow" -> {
            val index = q["i"]?.toIntOrNull() ?: return respondText(ex, 400, "missing i")
            val expected = q["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
            val item = queues.playNow(qid, index, expected) ?: return respondText(ex, 404, "gone")
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] reproducir ya: '$item'")
            respondText(ex, 200, item)
        }
        "/shuffle" -> {
            queues.shuffle(qid)
            prefetch(cache, queues, qid)
            log.info("Cola[$qid] mezclada")
            respondText(ex, 200, "ok")
        }
        else -> respondText(ex, 404, "not found")
    }
}

/** Empieza a descargar los próximos elementos de la cola para que estén listos cuando toque. */
private fun prefetch(cache: AudioCache, queues: QueueStore, qid: String) {
    queues.list(qid).take(PREFETCH).forEach { runCatching { cache.obtain(Sources.search(it, 1)) } }
}

private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")

private fun isYouTubeUrl(url: String): Boolean = runCatching {
    val uri = java.net.URI(url)
    (uri.scheme == "https" || uri.scheme == "http") && uri.host?.lowercase()?.let { it in YOUTUBE_HOSTS } == true
}.getOrDefault(false)

private fun handleCatalog(ex: HttpExchange, cfg: Config, catalog: Catalog, playlist: Boolean) {
    if (ex.requestMethod != "GET") return respondText(ex, 405, "method not allowed")
    val q = parseQuery(ex.requestURI.rawQuery)
    if (!authorized(cfg, q)) return respondText(ex, 403, "forbidden")
    val limit = (q["limit"]?.toIntOrNull() ?: if (playlist) 50 else 10).coerceIn(1, 50)
    val entries = if (playlist) {
        val url = q["url"]?.trim().orEmpty()
        if (!isYouTubeUrl(url)) return respondText(ex, 400, "invalid url")
        catalog.playlist(url, limit)
    } else {
        val text = q["q"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: return respondText(ex, 400, "missing q")
        catalog.search(text, limit)
    }
    respondText(ex, 200, entries.joinToString("\n") { it.toLine() })
}

// ------------------------------------------------------------------ /queues (panel)

/** GET /queues -> panel web · GET /queues/api -> JSON con todas las colas detectadas. */
private fun handleQueues(ex: HttpExchange, cfg: Config, queues: QueueStore, enricher: Enricher) {
    if (ex.requestMethod != "GET") return respondText(ex, 405, "method not allowed")
    val q = parseQuery(ex.requestURI.rawQuery)
    if (!authorized(cfg, q)) return respondText(ex, 403, "forbidden")

    when (ex.requestURI.path.removeSuffix("/")) {
        "/queues" -> respondBytes(ex, 200, "text/html; charset=utf-8", Panel.html)
        "/queues/api" -> {
            val json = queues.active().joinToString(",", "{\"queues\":[", "]}") { snap ->
                buildString {
                    append("{\"qid\":").append(jsonStr(snap.qid))
                    append(",\"count\":").append(snap.items.size)
                    append(",\"canGoBack\":").append(snap.canGoBack)
                    append(",\"current\":").append(snap.current?.let { trackJson(it, enricher) } ?: "null")
                    append(",\"items\":").append(snap.items.joinToString(",", "[", "]") { trackJson(it, enricher) })
                    append('}')
                }
            }
            respondBytes(ex, 200, "application/json; charset=utf-8", json.toByteArray())
        }
        else -> respondText(ex, 404, "not found")
    }
}

/** Datos de una canción para el panel; si aún no tiene miniatura, se resuelve en segundo plano. */
private fun trackJson(text: String, enricher: Enricher): String {
    val info = TrackIndex.info(text)
    if (info == null) enricher.request(text)
    return buildString {
        append("{\"text\":").append(jsonStr(text))
        append(",\"title\":").append(jsonStr(info?.title ?: text))
        append(",\"channel\":").append(jsonStr(info?.channel?.ifEmpty { null }))
        append(",\"id\":").append(jsonStr(info?.id))
        append(",\"duration\":").append(info?.duration?.toString() ?: "null")
        append('}')
    }
}

/**
 * Texto que entra a la cola. Solo "q" (uso de siempre) o "id" + título/canal/duración (desde el panel):
 * con id, esa canción se reproduce exactamente tal cual se eligió.
 */
private fun itemFrom(q: Map<String, String>): String? {
    val text = (q["q"] ?: q["title"])?.replace(Regex("[\\r\\n\\t]+"), " ")?.trim()?.take(200)?.takeIf { it.isNotEmpty() }
        ?: return null
    val id = q["id"]?.takeIf { VIDEO_ID.matches(it) } ?: return text
    return TrackIndex.register(TrackIndex.Track(id, text, q["channel"].orEmpty().trim().take(100), durationSeconds(q["dur"])))
}

object Panel {
    val html: ByteArray by lazy {
        Panel::class.java.getResourceAsStream("/panel.html")?.use { it.readBytes() } ?: "panel.html no encontrado".toByteArray()
    }
}

// ------------------------------------------------------------------ utilidades

private fun authorized(cfg: Config, q: Map<String, String>) = cfg.token == null || q["token"] == cfg.token

private fun parseQuery(raw: String?): Map<String, String> =
    raw.orEmpty().split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

private fun respondText(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray()
    ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) }
}

private fun respondBytes(ex: HttpExchange, code: Int, type: String, bytes: ByteArray) {
    ex.responseHeaders.add("Content-Type", type)
    ex.responseHeaders.add("Cache-Control", "no-store")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
