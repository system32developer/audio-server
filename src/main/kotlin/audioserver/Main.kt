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
private const val PREFETCH = 2 // cuántos elementos de la cola se descargan por adelantado

fun main() {
    val cfg = Config.fromEnv()
    val cache = AudioCache(cfg, AudioPipeline(cfg))
    val queues = QueueStore()

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
            val item = q["q"]?.trim()?.take(200)?.takeIf { it.isNotEmpty() } ?: return respondText(ex, 400, "missing q")
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
        else -> respondText(ex, 404, "not found")
    }
}

/** Empieza a descargar los próximos elementos de la cola para que estén listos cuando toque. */
private fun prefetch(cache: AudioCache, queues: QueueStore, qid: String) {
    queues.list(qid).take(PREFETCH).forEach { runCatching { cache.obtain(Sources.search(it, 1)) } }
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
