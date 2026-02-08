package cz.hovorka.kdisco

actual class Head actual constructor() {
    internal val jDiscoDelegate: jDisco.Head = jDisco.Head()

    actual fun clear() {
        jDiscoDelegate.clear()
    }

    actual fun empty(): Boolean {
        return jDiscoDelegate.empty()
    }

    actual fun cardinal(): Int {
        return jDiscoDelegate.cardinal()
    }

    actual fun first(): Link? {
        val jDiscoFirst = jDiscoDelegate.first() as? jDisco.Linkage ?: return null
        return Link.fromJDisco(jDiscoFirst)
    }

    actual fun last(): Link? {
        val jDiscoLast = jDiscoDelegate.last() as? jDisco.Linkage ?: return null
        return Link.fromJDisco(jDiscoLast)
    }
}
