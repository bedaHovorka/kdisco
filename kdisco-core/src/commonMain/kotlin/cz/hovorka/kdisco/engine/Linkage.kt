package cz.hovorka.kdisco.engine

/**
 * Base class for doubly-linked list infrastructure.
 *
 * Both [Link] (list items) and [Head] (list containers) extend this class.
 * Provides predecessor/successor navigation.
 */
open class Linkage internal constructor() {
    internal var predRef: Linkage? = null
    internal var sucRef: Linkage? = null

    /**
     * Returns the next [Link] in the list, or null if at the end (successor is a [Head]).
     */
    fun suc(): Link? {
        val s = sucRef
        return if (s is Link) s else null
    }

    /**
     * Returns the previous [Link] in the list, or null if at the start (predecessor is a [Head]).
     */
    fun pred(): Link? {
        val p = predRef
        return if (p is Link) p else null
    }
}
