package cz.hovorka.kdisco

/**
 * A doubly-linked circular list container for [Link] objects.
 *
 * `Head` manages a collection of [Link] objects organized as a doubly-linked
 * circular list. Links can be inserted, removed, and traversed. The list
 * maintains both forward and backward references, enabling efficient iteration
 * in either direction.
 *
 * On JVM, kDisco wraps the battle-tested jDisco library (used in production since 2007),
 * providing a SIMULA-style linked list implementation.
 *
 * ### Thread Safety
 * `Head` is **not** thread-safe. Concurrent access from multiple threads
 * requires external synchronization.
 *
 * ### Example
 * ```kotlin
 * class Customer : Link()
 *
 * val queue = Head()
 * val customer1 = Customer()
 * val customer2 = Customer()
 *
 * customer1.into(queue)  // Add to queue
 * customer2.into(queue)  // Add to queue
 *
 * println(queue.cardinal())  // Prints: 2
 * println(queue.empty())     // Prints: false
 *
 * queue.first()?.out()  // Remove first customer
 * println(queue.cardinal())  // Prints: 1
 * ```
 *
 * @see Link
 * @see asSequence
 * @since 0.1.0
 */
expect class Head() {
    /**
     * Removes all links from this list.
     *
     * After this operation, [empty] returns `true` and [cardinal] returns `0`.
     * The removed links are taken out of this list but are not otherwise modified.
     */
    fun clear()

    /**
     * Returns `true` if this list contains no links.
     *
     * @return `true` if the list is empty, `false` otherwise
     */
    fun empty(): Boolean

    /**
     * Returns the number of links currently in this list.
     *
     * @return the count of links in this list (0 or greater)
     */
    fun cardinal(): Int

    /**
     * Returns the first link in this list, or `null` if the list is empty.
     *
     * @return the first [Link] in the list, or `null` if [empty] is `true`
     */
    fun first(): Link?

    /**
     * Returns the last link in this list, or `null` if the list is empty.
     *
     * @return the last [Link] in the list, or `null` if [empty] is `true`
     */
    fun last(): Link?
}
