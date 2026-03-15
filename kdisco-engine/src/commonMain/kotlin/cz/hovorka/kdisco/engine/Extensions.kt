package cz.hovorka.kdisco.engine

/**
 * Returns a Kotlin [Sequence] that iterates over all links in this [Head].
 */
fun Head.asSequence(): Sequence<Link> = sequence {
    var current = first()
    while (current != null) {
        yield(current)
        current = current.suc()
    }
}

/**
 * Returns a type-filtered [Sequence] over links of a specific type.
 */
inline fun <reified T : Link> Head.asSequenceOf(): Sequence<T> =
    asSequence().filterIsInstance<T>()
