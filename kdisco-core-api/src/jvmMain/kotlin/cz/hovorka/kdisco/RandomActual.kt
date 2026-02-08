package cz.hovorka.kdisco

/**
 * On JVM, kDisco's Random IS jDisco's Random (which IS java.util.Random) via zero-overhead typealias.
 * This preserves `list.shuffle(random)` compatibility (Kotlin's shuffle accepts java.util.Random).
 */
actual typealias Random = jDisco.Random
