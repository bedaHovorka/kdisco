package cz.hovorka.kdisco

/**
 * A doubly-linked circular list container for [Link] objects.
 */
class Head : Linkage() {
    init {
        predRef = this  // circular sentinel
        sucRef = this
    }

    /** Returns the first link in this list, or null if empty. */
    fun first(): Link? {
        val s = sucRef
        return if (s is Link) s else null
    }

    /** Returns the last link in this list, or null if empty. */
    fun last(): Link? {
        val p = predRef
        return if (p is Link) p else null
    }

    /** Returns true if this list contains no links. */
    fun empty(): Boolean = sucRef === this

    /** Returns the number of links in this list. */
    fun cardinal(): Int {
        var count = 0
        var current = first()
        while (current != null) {
            count++
            current = current.suc()
        }
        return count
    }

    /** Removes all links from this list. */
    fun clear() {
        while (!empty()) {
            first()?.out()
        }
    }
}
