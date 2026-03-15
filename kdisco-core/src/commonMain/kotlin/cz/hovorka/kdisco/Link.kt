package cz.hovorka.kdisco

/**
 * Base class for objects that can be members of doubly-linked circular lists ([Head]).
 *
 * `Link` provides the fundamental linked-list operations for objects that need to
 * participate in [Head] containers. Any class that extends `Link` can be inserted
 * into, removed from, and traversed within doubly-linked circular lists.
 *
 * ### List Structure
 * Links are organized in doubly-linked circular lists managed by [Head] objects:
 * - Each link has a successor ([suc]) and predecessor ([pred])
 * - Lists are circular: the last link's successor is the first link
 * - A link can belong to at most one list at a time
 *
 * ### Common Use Cases
 * - **Queues**: Customer entities waiting for service in simulation
 * - **Sets**: Collections of related simulation objects
 * - **Process scheduling**: Links form the basis of event queues
 *
 * ### JVM Implementation
 * On JVM, this delegates to `jdisco.Link` from the jDisco library. The actual
 * implementation uses a WeakHashMap registry to enable type-safe traversal while
 * maintaining compatibility with jDisco's untyped API.
 *
 * ### Thread Safety
 * `Link` operations are **not** thread-safe. Concurrent access from multiple
 * threads requires external synchronization.
 *
 * ### Example: Queue Management
 * ```kotlin
 * class Customer(val id: Int) : Link() {
 *     override fun toString() = "Customer($id)"
 * }
 *
 * val waitingQueue = Head()
 * val serviceQueue = Head()
 *
 * val customer1 = Customer(1)
 * val customer2 = Customer(2)
 *
 * customer1.into(waitingQueue)  // Add to waiting queue
 * customer2.into(waitingQueue)
 *
 * // Move first customer to service
 * waitingQueue.first()?.apply {
 *     out()                     // Remove from waiting queue
 *     into(serviceQueue)        // Add to service queue
 * }
 * ```
 *
 * ### Example: Process Simulation
 * ```kotlin
 * class Customer : Process() {
 *     override fun actions() {
 *         this.into(arrivalQueue)
 *         println("Customer arrives at t=${time()}")
 *         passivate()  // Wait for service
 *         this.out()   // Leave queue when served
 *     }
 * }
 * ```
 *
 * @see Head
 * @see Process
 * @see asSequence
 * @since 0.1.0
 */
expect open class Link() {
    /**
     * Inserts this link at the end of the specified [Head] list.
     *
     * If this link is already in a list, it is first removed from that list
     * before being added to the new one. The link becomes the last element
     * of [head], positioned after [Head.last] and before [Head.first].
     *
     * ### Example
     * ```kotlin
     * val queue = Head()
     * val customer = Customer()
     * customer.into(queue)  // Customer is now last in queue
     * ```
     *
     * @param head the list to insert this link into
     */
    fun into(head: Head)

    /**
     * Removes this link from its current list.
     *
     * After this operation, the link is not part of any list. If the link
     * is not currently in a list, this operation has no effect.
     *
     * ### Example
     * ```kotlin
     * val customer = queue.first()
     * customer?.out()  // Remove from queue
     * ```
     */
    fun out()

    /**
     * Inserts this link immediately after the specified [link].
     *
     * This link will be positioned between [link] and its current successor.
     * If this link is already in a list, it is first removed from that list.
     * The [link] must be a member of some [Head].
     *
     * ### Example
     * ```kotlin
     * val priority = queue.first()
     * urgentCustomer.follow(priority)  // Insert after priority customer
     * ```
     *
     * @param link the link after which to insert this link
     * @throws IllegalStateException if [link] is not in any list
     */
    fun follow(link: Link)

    /**
     * Inserts this link immediately before the specified [link].
     *
     * This link will be positioned between [link]'s current predecessor and [link].
     * If this link is already in a list, it is first removed from that list.
     * The [link] must be a member of some [Head].
     *
     * ### Example
     * ```kotlin
     * val lastCustomer = queue.last()
     * if (lastCustomer != null) {
     *     newCustomer.precede(lastCustomer)  // Insert before last
     * }
     * ```
     *
     * @param link the link before which to insert this link
     * @throws IllegalStateException if [link] is not in any list
     */
    fun precede(link: Link)

    /**
     * Returns the successor (next) link in the circular list.
     *
     * In a circular list, the successor of the last link is the first link.
     * This enables infinite traversal: calling [suc] repeatedly will cycle
     * through all links in the list indefinitely.
     *
     * ### Example
     * ```kotlin
     * val first = queue.first() ?: return  // Handle empty list
     * var current = first
     * do {
     *     println(current)
     *     current = current.suc()
     * } while (current !== first)  // Use identity check, not equality
     * ```
     *
     * @return the next link in the circular list
     * @throws IllegalStateException if this link is not in any list
     */
    fun suc(): Link

    /**
     * Returns the predecessor (previous) link in the circular list.
     *
     * In a circular list, the predecessor of the first link is the last link.
     * This enables reverse traversal of the list.
     *
     * ### Example
     * ```kotlin
     * // Traverse backwards from last to first
     * val last = queue.last() ?: return  // Handle empty list
     * var current = last
     * do {
     *     println(current)
     *     current = current.pred()
     * } while (current !== last)  // Use identity check, not equality
     * ```
     *
     * @return the previous link in the circular list
     * @throws IllegalStateException if this link is not in any list
     */
    fun pred(): Link
}
