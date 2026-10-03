package audioserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.logging.Logger

private val log = Logger.getLogger("Main")

fun main() {
    val cfg = Config.fromEnv()
    val pipeline = AudioPipeline(cfg)

    val server = HttpServer.create(InetSocketAddress(cfg.port), 0)
    server.executor = Executors.newVirtualThreadPerTaskExecutor()

    server.createContext("/health") { ex ->
        respondText(ex, 200, "ok")
    }

    server.createContext("/stream") { ex ->
        try {
            handleStream(ex, cfg, pipeline)
        } catch (e: Exception) {
            log.warning("Error en /stream: ${e.message}")
            runCatching { respondText(ex, 500, "internal error") }
        } finally {
            ex.close()
        }
    }

    server.start()
    log.info("audio-server escuchando en :${cfg.port} | fuentes: ${cfg.sources.keys}")
}

private fun handleStream(ex: HttpExchange, cfg: Config, pipeline: AudioPipeline) {
    if (ex.requestMethod != "GET" && ex.requestMethod != "HEAD") {
        return respondText(ex, 405, "method not allowed")
    }
    val q = parseQuery(ex.requestURI.rawQuery)
    if (cfg.token != null && q["token"] != cfg.token) {
        return respondText(ex, 403, "forbidden")
    }
    val search = q["q"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
    val id = q["id"]
    val n = (q["n"]?.toIntOrNull() ?: 1).coerceIn(1, 10)
    val startSec = (q["t"]?.toIntOrNull() ?: 0).coerceIn(0, 86_400)
    val source = when {
        search != null -> "ytsearch$n:$search" // busca y toma el resultado número n
        id != null -> cfg.sources[id] ?: return respondText(ex, 404, "unknown id")
        else -> return respondText(ex, 400, "missing id or q")
    }

    ex.responseHeaders.add("Content-Type", "audio/mpeg")
    ex.responseHeaders.add("Cache-Control", "no-store")

    if (ex.requestMethod == "HEAD") {
        ex.sendResponseHeaders(200, -1)
        return
    }

    log.info("Stream id=$id q=$search n=$n t=$startSec solicitado por ${ex.remoteAddress}")
    val started = System.currentTimeMillis()
    var bytes = 0L
    // length = 0  ->  Transfer-Encoding: chunked (streaming real)
    ex.sendResponseHeaders(200, 0)
    pipeline.start(source, startSec).use { run ->
        try {
            val buf = ByteArray(16 * 1024)
            val out = ex.responseBody
            while (true) {
                val n = run.output.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush() // envía el chunk ya, sin esperar a más datos
                bytes += n
            }
            log.info("Stream id=$id terminado: $bytes bytes")
        } catch (e: IOException) {
            // Normal: el cliente (VLC/Alexa) cortó la conexión
            log.info("Stream id=$id cortado por el cliente tras $bytes bytes (${System.currentTimeMillis() - started} ms)")
        }
    } // close() mata yt-dlp y ffmpeg
}

private fun parseQuery(raw: String?): Map<String, String> =
    raw.orEmpty().split('&').filter { it.contains('=') }.associate {
        val (k, v) = it.split('=', limit = 2)
        URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
    }

private fun respondText(ex: HttpExchange, code: Int, body: String) {
    val bytes = body.toByteArray()
    ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
