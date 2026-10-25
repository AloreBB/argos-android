package dev.alore.argos.model

/** Sessions sharing a name prefix. */
data class SessionGroup(val name: String, val sessions: List<TmuxSession>)

/**
 * Groups sessions by naming convention, with no extra state: the prefix before the first '/'
 * is the group ("magic-agents/backend" -> "magic-agents"); names without '/' go to "General".
 * The FULL name stays the real tmux target; the short name is display-only.
 */
object SessionGrouping {
    const val SEPARATOR = '/'
    const val UNGROUPED = "General"

    /**
     * - "magic-agents/backend" -> ("magic-agents", "backend")
     * - "main"                 -> ("General", "main")
     * - "/foo" (empty prefix)  -> ("General", "/foo")
     * - "foo/" (empty suffix)  -> ("foo", "foo/")  (keeps a usable label)
     */
    fun split(fullName: String): Pair<String, String> {
        val index = fullName.indexOf(SEPARATOR)
        if (index < 0) return UNGROUPED to fullName
        val prefix = fullName.substring(0, index)
        val suffix = fullName.substring(index + 1)
        if (prefix.isEmpty()) return UNGROUPED to fullName
        return prefix to suffix.ifEmpty { fullName }
    }

    fun groupName(fullName: String): String = split(fullName).first

    fun shortName(fullName: String): String = split(fullName).second

    /** Groups alphabetically (case-insensitive) with "General" always last; sessions by short name. */
    fun groups(sessions: List<TmuxSession>): List<SessionGroup> {
        val buckets = LinkedHashMap<String, MutableList<TmuxSession>>()
        for (session in sessions) buckets.getOrPut(groupName(session.name)) { mutableListOf() }.add(session)

        val groupOrder = compareBy<String> { it == UNGROUPED }.thenBy(String.CASE_INSENSITIVE_ORDER) { it }
        return buckets.keys.sortedWith(groupOrder).map { name ->
            SessionGroup(
                name = name,
                sessions = buckets.getValue(name).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { shortName(it.name) }),
            )
        }
    }
}
