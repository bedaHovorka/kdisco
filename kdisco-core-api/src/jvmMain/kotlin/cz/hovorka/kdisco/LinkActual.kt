package cz.hovorka.kdisco


actual open class Link actual constructor() {
    internal open val jDiscoDelegate: jDisco.Link = jDisco.Link()

    init {
        // When a subclass (e.g. Process) overrides jDiscoDelegate, virtual dispatch here
        // returns null because the subclass property isn't initialized yet. into() fixes this.
        val d = jDiscoDelegate
        if (d != null) linkRegistry[d] = this
    }

    actual fun into(head: Head) {
        // Re-register with the now-correct delegate (fixes Process subclass init ordering).
        linkRegistry[jDiscoDelegate] = this
        jDiscoDelegate.into(head.jDiscoDelegate)
    }

    actual fun out() {
        jDiscoDelegate.out()
        linkRegistry.remove(jDiscoDelegate)
    }

    actual fun follow(link: Link) {
        jDiscoDelegate.follow(link.jDiscoDelegate)
    }

    actual fun precede(link: Link) {
        jDiscoDelegate.precede(link.jDiscoDelegate)
    }

    actual fun suc(): Link {
        val jDiscoSuc = jDiscoDelegate.suc() as? jDisco.Linkage
            ?: throw IllegalStateException("Successor is not a valid Linkage")
        return linkRegistry[jDiscoSuc]
            ?: throw IllegalStateException("No Kotlin wrapper found for jDisco Linkage")
    }

    actual fun pred(): Link {
        val jDiscoPred = jDiscoDelegate.pred() as? jDisco.Linkage
            ?: throw IllegalStateException("Predecessor is not a valid Linkage")
        return linkRegistry[jDiscoPred]
            ?: throw IllegalStateException("No Kotlin wrapper found for jDisco Linkage")
    }

    companion object {
        private val linkRegistry = HashMap<jDisco.Linkage, Link>()

        internal fun fromJDisco(jDiscoLink: jDisco.Linkage): Link? {
            return linkRegistry[jDiscoLink]
        }
    }
}
