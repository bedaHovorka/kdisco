package cz.hovorka.kdisco

/**
 * On JVM, kDisco's DiscoException IS jDisco's DiscoException via zero-overhead typealias.
 * This ensures `catch (e: DiscoException)` catches exceptions thrown by jDisco internals.
 */
actual typealias DiscoException = jDisco.DiscoException
