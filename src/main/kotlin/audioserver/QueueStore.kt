package audioserver

import java.util.concurrent.ConcurrentHashMap

/**
 * Colas en memoria, una por usuario (qid). Cada elemento es un texto de búsqueda.
 * Una cola "existe" (aparece en /queues) en cuanto tiene al menos una canción o algo sonando.
 */
class QueueStore(private val maxSize: Int = 50) {
    private class State {
        val items = ArrayDeque<String>()
        val history = ArrayDeque<String>()
        var current: String? = null
        var touched: Long = System.currentTimeMillis()

        fun touch() { touched = System.currentTimeMillis() }

        fun startPlaying(item: String) {
            current?.let { history.addLast(it) }
            while (history.size > HISTORY_MAX) history.removeFirst()
            current = item
            touch()
        }
    }

    /** Foto inmutable de una cola, para el panel. */
    data class Snapshot(val qid: String, val items: List<String>, val current: String?, val canGoBack: Boolean)

    private val queues = ConcurrentHashMap<String, State>()

    private fun state(qid: String) = queues.computeIfAbsent(qid) { State() }

    /** Devuelve la posición (1 = siguiente) o -1 si la cola está llena. */
    fun add(qid: String, item: String): Int {
        val s = state(qid)
        synchronized(s) {
            if (s.items.size >= maxSize) return -1
            s.items.addLast(item)
            s.touch()
            return s.items.size
        }
    }

    /** Saca el siguiente elemento y lo marca como "sonando ahora". */
    fun pop(qid: String): String? {
        val s = queues[qid] ?: return null
        synchronized(s) {
            val next = s.items.removeFirstOrNull() ?: return null
            s.startPlaying(next)
            return next
        }
    }

    /** Vuelve a la canción anterior; la actual regresa al inicio de la cola. */
    fun previous(qid: String): String? {
        val s = queues[qid] ?: return null
        synchronized(s) {
            val prev = s.history.removeLastOrNull() ?: return null
            s.current?.let { s.items.addFirst(it) }
            s.current = prev
            s.touch()
            return prev
        }
    }

    /** Saca un elemento concreto de la cola y lo pone a sonar ya. */
    fun playNow(qid: String, index: Int, expected: String?): String? {
        val s = queues[qid] ?: return null
        synchronized(s) {
            val at = locate(s.items, index - 1, expected)
            if (at < 0) return null
            val item = s.items.removeAt(at)
            s.startPlaying(item)
            return item
        }
    }

    fun list(qid: String): List<String> = queues[qid]?.let { s -> synchronized(s) { s.items.toList() } } ?: emptyList()

    fun clear(qid: String) {
        val s = queues[qid] ?: return
        synchronized(s) { s.items.clear(); s.touch() }
    }

    fun shuffle(qid: String) {
        val s = queues[qid] ?: return
        synchronized(s) { s.items.shuffle(); s.touch() }
    }

    fun insert(qid: String, index: Int, item: String): Int {
        val s = state(qid)
        synchronized(s) {
            if (s.items.size >= maxSize) return -1
            val at = (index - 1).coerceIn(0, s.items.size)
            s.items.add(at, item)
            s.touch()
            return at + 1
        }
    }

    fun nowPlaying(qid: String, item: String) {
        val s = state(qid)
        synchronized(s) {
            val key = TrackIndex.key(item)
            if (s.current?.let { TrackIndex.key(it) } == key) { s.touch(); return }
            val queued = s.items.indexOfFirst { TrackIndex.key(it) == key }
            if (queued >= 0) s.items.removeAt(queued)
            s.startPlaying(item)
        }
    }

    fun remove(qid: String, index: Int, expected: String?): Boolean {
        val s = queues[qid] ?: return false
        synchronized(s) {
            val at = locate(s.items, index - 1, expected)
            if (at < 0) return false
            s.items.removeAt(at)
            s.touch()
            return true
        }
    }

    fun move(qid: String, from: Int, to: Int, expected: String?): Boolean {
        val s = queues[qid] ?: return false
        synchronized(s) {
            val source = locate(s.items, from - 1, expected)
            if (source < 0) return false
            val item = s.items.removeAt(source)
            s.items.add((to - 1).coerceIn(0, s.items.size), item)
            s.touch()
            return true
        }
    }

    /**
     * Colas detectadas: las que tienen canciones en espera, o algo sonando hace poco.
     * Una cola vacía deja de aparecer sola cuando pasa un rato sin actividad.
     */
    fun active(): List<Snapshot> {
        val now = System.currentTimeMillis()
        return queues.entries.mapNotNull { (qid, s) ->
            synchronized(s) {
                val live = s.items.isNotEmpty() || (s.current != null && now - s.touched < IDLE_MS)
                if (live) Snapshot(qid, s.items.toList(), s.current, s.history.isNotEmpty()) else null
            }
        }.sortedBy { it.qid }
    }

    private fun locate(q: ArrayDeque<String>, at: Int, expected: String?): Int = when {
        expected == null -> if (at in q.indices) at else -1
        at in q.indices && q[at] == expected -> at
        else -> q.indexOf(expected)
    }

    private companion object {
        const val HISTORY_MAX = 20
        const val IDLE_MS = 2 * 60 * 60 * 1000L
    }
}
