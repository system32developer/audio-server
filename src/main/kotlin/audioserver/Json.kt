package audioserver

/** JSON mínimo a mano (el proyecto no usa librerías externas). */
fun jsonStr(s: String?): String {
    if (s == null) return "null"
    val sb = StringBuilder(s.length + 2).append('"')
    for (c in s) when {
        c == '"' -> sb.append("\\\"")
        c == '\\' -> sb.append("\\\\")
        c == '\n' -> sb.append("\\n")
        c == '\r' -> sb.append("\\r")
        c == '\t' -> sb.append("\\t")
        c < ' ' -> sb.append("\\u%04x".format(c.code))
        else -> sb.append(c)
    }
    return sb.append('"').toString()
}
