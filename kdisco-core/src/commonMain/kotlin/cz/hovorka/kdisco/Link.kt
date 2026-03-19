package cz.hovorka.kdisco

/**
 * Base class for objects that can be members of doubly-linked circular lists ([Head]).
 */
open class Link : Linkage() {
    /**
     * Inserts this link at the end of the specified [Head] list.
     * If already in a list, removes from it first.
     */
    fun into(head: Head) {
        if (predRef != null) out()
        val last = head.predRef!!  // Head.pred points to last element (or Head itself)
        this.predRef = last
        this.sucRef = head
        last.sucRef = this
        head.predRef = this
    }

    /**
     * Removes this link from its current list.
     * No-op if not in a list.
     */
    fun out() {
        val p = predRef ?: return
        val s = sucRef ?: return
        p.sucRef = s
        s.predRef = p
        predRef = null
        sucRef = null
    }

    /**
     * Inserts this link immediately after the specified [linkage].
     * Accepts [Linkage] (not just [Link]) so you can insert after a [Head] sentinel.
     */
    fun follow(linkage: Linkage) {
        if (predRef != null) out()
        val s = linkage.sucRef ?: throw IllegalStateException("Target is not in any list")
        this.predRef = linkage
        this.sucRef = s
        linkage.sucRef = this
        s.predRef = this
    }

    /**
     * Inserts this link immediately before the specified [linkage].
     * Accepts [Linkage] (not just [Link]) so you can insert before a [Head] sentinel.
     */
    fun precede(linkage: Linkage) {
        if (predRef != null) out()
        val p = linkage.predRef ?: throw IllegalStateException("Target is not in any list")
        this.predRef = p
        this.sucRef = linkage
        p.sucRef = this
        linkage.predRef = this
    }
}
