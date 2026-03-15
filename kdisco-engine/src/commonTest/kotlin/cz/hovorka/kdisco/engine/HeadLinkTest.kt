package cz.hovorka.kdisco.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame

class HeadLinkTest {

    private open class Item : Link()

    @Test
    fun emptyHeadHasCardinalZero() {
        val head = Head()
        assertTrue(head.empty())
        assertEquals(0, head.cardinal())
        assertNull(head.first())
        assertNull(head.last())
    }

    @Test
    fun intoAddsLinkToHead() {
        val head = Head()
        val item = Item()
        item.into(head)
        assertFalse(head.empty())
        assertEquals(1, head.cardinal())
        assertSame(item, head.first())
        assertSame(item, head.last())
    }

    @Test
    fun multipleIntoMaintainsOrder() {
        val head = Head()
        val a = Item()
        val b = Item()
        val c = Item()
        a.into(head)
        b.into(head)
        c.into(head)
        assertEquals(3, head.cardinal())
        assertSame(a, head.first())
        assertSame(c, head.last())
    }

    @Test
    fun outRemovesLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        a.out()
        assertEquals(1, head.cardinal())
        assertSame(b, head.first())
    }

    @Test
    fun outOnLastItemMakesHeadEmpty() {
        val head = Head()
        val a = Item()
        a.into(head)
        a.out()
        assertTrue(head.empty())
    }

    @Test
    fun sucReturnsNextLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        assertSame(b, a.suc())
    }

    @Test
    fun predReturnsPreviousLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        assertSame(a, b.pred())
    }

    @Test
    fun sucOfLastReturnsNull() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        // Last link's successor is the Head sentinel — returns null
        assertNull(b.suc())
    }

    @Test
    fun predOfFirstReturnsNull() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        // First link's predecessor is the Head sentinel — returns null
        assertNull(a.pred())
    }

    @Test
    fun followInsertsAfter() {
        val head = Head()
        val a = Item()
        val b = Item()
        val c = Item()
        a.into(head)
        c.into(head)
        b.follow(a)  // insert b after a
        assertEquals(3, head.cardinal())
        assertSame(a, head.first())
        assertSame(b, a.suc())
        assertSame(c, b.suc())
    }

    @Test
    fun precedeInsertsBefore() {
        val head = Head()
        val a = Item()
        val b = Item()
        val c = Item()
        a.into(head)
        c.into(head)
        b.precede(c)  // insert b before c
        assertEquals(3, head.cardinal())
        assertSame(b, a.suc())
        assertSame(c, b.suc())
    }

    @Test
    fun clearRemovesAllLinks() {
        val head = Head()
        Item().into(head)
        Item().into(head)
        Item().into(head)
        head.clear()
        assertTrue(head.empty())
        assertEquals(0, head.cardinal())
    }

    @Test
    fun clearedLinksCanBeReinserted() {
        val head1 = Head()
        val head2 = Head()
        val item = Item()
        item.into(head1)
        head1.clear()
        item.into(head2)
        assertEquals(1, head2.cardinal())
        assertSame(item, head2.first())
    }

    @Test
    fun intoMovesLinkBetweenHeads() {
        val head1 = Head()
        val head2 = Head()
        val item = Item()
        item.into(head1)
        assertEquals(1, head1.cardinal())
        item.into(head2)
        assertEquals(0, head1.cardinal())
        assertEquals(1, head2.cardinal())
        assertSame(item, head2.first())
    }

    @Test
    fun asSequenceIteratesAllLinks() {
        val head = Head()
        val items = List(5) { Item().also { it.into(head) } }
        val result = head.asSequence().toList()
        assertEquals(5, result.size)
        assertEquals(items, result)
    }

    @Test
    fun asSequenceOfFiltersType() {
        class Special : Link()
        val head = Head()
        Item().into(head)
        val special = Special()
        special.into(head)
        Item().into(head)
        val result = head.asSequenceOf<Special>().toList()
        assertEquals(1, result.size)
        assertSame(special, result[0])
    }
}
