package audioserver

import java.util.concurrent.ConcurrentHashMap

/** Colas en memoria, una por usuario (qid). Cada elemento es un texto de búsqueda. */
class QueueStore(private val maxSize: Int = 50) {
    private val queues = ConcurrentHashMap<String, ArrayDeque<String>>()

    private fun queue(qid: String) = queues.computeIfAbsent(qid) { ArrayDeque() }

    /** Devuelve la posición (1 = siguiente) o -1 si la cola está llena. */
    fun add(qid: String, item: String): Int {
        val q = queue(qid)
        synchronized(q) {
            if (q.size >= maxSize) return -1
            q.addLast(item)
            return q.size
        }
    }

    fun pop(qid: String): String? = queue(qid).let { q -> synchronized(q) { q.removeFirstOrNull() } }

    fun list(qid: String): List<String> = queue(qid).let { q -> synchronized(q) { q.toList() } }

    fun clear(qid: String) = queue(qid).let { q -> synchronized(q) { q.clear() } }

    fun insert(qid: String, index: Int, item: String): Int {
        val q = queue(qid)
        synchronized(q) {
            if (q.size >= maxSize) return -1
            val at = (index - 1).coerceIn(0, q.size)
            q.add(at, item)
            return at + 1
        }
    }

    fun remove(qid: String, index: Int, expected: String?): Boolean {
        val q = queue(qid)
        synchronized(q) {
            val at = locate(q, index - 1, expected)
            if (at < 0) return false
            q.removeAt(at)
            return true
        }
    }

    fun move(qid: String, from: Int, to: Int, expected: String?): Boolean {
        val q = queue(qid)
        synchronized(q) {
            val source = locate(q, from - 1, expected)
            if (source < 0) return false
            val item = q.removeAt(source)
            q.add((to - 1).coerceIn(0, q.size), item)
            return true
        }
    }

    private fun locate(q: ArrayDeque<String>, at: Int, expected: String?): Int = when {
        expected == null -> if (at in q.indices) at else -1
        at in q.indices && q[at] == expected -> at
        else -> q.indexOf(expected)
    }
}
