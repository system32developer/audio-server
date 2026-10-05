package audioserver

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * Datos de las canciones de la cola (miniatura, título, canal, duración) y, para las
 * agregadas desde el panel, el id exacto del video elegido.
 *
 * La cola sigue guardando solo texto (así tu skill de Alexa funciona igual), y este índice
 * traduce ese texto a un video concreto:
 *  - "fijada" (pinned): el usuario eligió ese video en el panel -> /stream reproduce ese id exacto.
 *  - no fijada: solo informativa (miniatura/título); el audio se sigue buscando como siempre.
 */
object TrackIndex {
    data class Track(val id: String, val title: String, val channel: String, val duration: Int?)
    private class Slot(val track: Track, val pinned: Boolean)

    private const val MAX = 5000
    private val slots = object : LinkedHashMap<String, Slot>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Slot>) = size > MAX
    }

    fun key(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Datos conocidos de un texto de la cola (fijados o no). */
    @Synchronized fun info(text: String): Track? = slots[key(text)]?.track

    /** Id de video elegido explícitamente desde el panel, si lo hay. */
    @Synchronized fun pinnedId(text: String): String? = slots[key(text)]?.takeIf { it.pinned }?.track?.id

    @Synchronized fun remember(text: String, track: Track) {
        val k = key(text)
        if (slots[k]?.pinned != true) slots[k] = Slot(track, false)
    }

    /**
     * Registra una canción elegida en el panel y devuelve el texto con el que va a la cola.
     * Normalmente es el título; si ese título ya apunta a otro video, se desambigua.
     */
    @Synchronized fun register(track: Track): String {
        val title = track.title.take(170)
        val candidates = listOfNotNull(
            title,
            track.channel.takeIf { it.isNotBlank() }?.let { "$title - ${it.take(25)}" },
            "$title [${track.id}]",
        )
        val text = candidates.firstOrNull { c ->
            val cur = slots[key(c)]
            cur == null || !cur.pinned || cur.track.id == track.id
        } ?: candidates.last()
        slots[key(text)] = Slot(track, true)
        return text
    }
}

/** Resuelve en segundo plano miniatura/título de textos que llegaron sin id (p. ej. por voz). */
class Enricher(private val catalog: Catalog) {
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val tried = ConcurrentHashMap.newKeySet<String>()
    private val slots = Semaphore(2)

    fun request(text: String) {
        val k = TrackIndex.key(text)
        if (TrackIndex.info(text) != null || k in tried || !running.add(k)) return
        Thread.ofVirtual().start {
            slots.acquireUninterruptibly()
            try {
                val hit = runCatching { catalog.search(text, 1).firstOrNull() }.getOrNull()
                if (hit != null) {
                    TrackIndex.remember(text, TrackIndex.Track(hit.id, hit.title, hit.channel, durationSeconds(hit.duration)))
                }
            } finally {
                if (tried.size > 2000) tried.clear()
                tried.add(k)
                running.remove(k)
                slots.release()
            }
        }
    }
}

fun durationSeconds(raw: String?): Int? = raw?.trim()?.toDoubleOrNull()?.toInt()?.takeIf { it > 0 }
