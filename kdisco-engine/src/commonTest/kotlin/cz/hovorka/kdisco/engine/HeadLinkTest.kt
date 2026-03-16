package cz.hovorka.kdisco.engine

import assertk.assertThat
import assertk.assertions.*
import kotlin.test.Test

class HeadLinkTest {

    private open class Item : Link()

    @Test
    fun emptyHeadHasCardinalZero() {
        val head = Head()
        assertThat(head.empty()).isTrue()
        assertThat(head.cardinal()).isEqualTo(0)
        assertThat(head.first()).isNull()
        assertThat(head.last()).isNull()
    }

    @Test
    fun intoAddsLinkToHead() {
        val head = Head()
        val item = Item()
        item.into(head)
        assertThat(head.empty()).isFalse()
        assertThat(head.cardinal()).isEqualTo(1)
        assertThat(head.first()).isSameInstanceAs(item)
        assertThat(head.last()).isSameInstanceAs(item)
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
        assertThat(head.cardinal()).isEqualTo(3)
        assertThat(head.first()).isSameInstanceAs(a)
        assertThat(head.last()).isSameInstanceAs(c)
    }

    @Test
    fun outRemovesLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        a.out()
        assertThat(head.cardinal()).isEqualTo(1)
        assertThat(head.first()).isSameInstanceAs(b)
    }

    @Test
    fun outOnLastItemMakesHeadEmpty() {
        val head = Head()
        val a = Item()
        a.into(head)
        a.out()
        assertThat(head.empty()).isTrue()
    }

    @Test
    fun sucReturnsNextLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        assertThat(a.suc()).isSameInstanceAs(b)
    }

    @Test
    fun predReturnsPreviousLink() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        assertThat(b.pred()).isSameInstanceAs(a)
    }

    @Test
    fun sucOfLastReturnsNull() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        // Last link's successor is the Head sentinel — returns null
        assertThat(b.suc()).isNull()
    }

    @Test
    fun predOfFirstReturnsNull() {
        val head = Head()
        val a = Item()
        val b = Item()
        a.into(head)
        b.into(head)
        // First link's predecessor is the Head sentinel — returns null
        assertThat(a.pred()).isNull()
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
        assertThat(head.cardinal()).isEqualTo(3)
        assertThat(head.first()).isSameInstanceAs(a)
        assertThat(a.suc()).isSameInstanceAs(b)
        assertThat(b.suc()).isSameInstanceAs(c)
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
        assertThat(head.cardinal()).isEqualTo(3)
        assertThat(a.suc()).isSameInstanceAs(b)
        assertThat(b.suc()).isSameInstanceAs(c)
    }

    @Test
    fun clearRemovesAllLinks() {
        val head = Head()
        Item().into(head)
        Item().into(head)
        Item().into(head)
        head.clear()
        assertThat(head.empty()).isTrue()
        assertThat(head.cardinal()).isEqualTo(0)
    }

    @Test
    fun clearedLinksCanBeReinserted() {
        val head1 = Head()
        val head2 = Head()
        val item = Item()
        item.into(head1)
        head1.clear()
        item.into(head2)
        assertThat(head2.cardinal()).isEqualTo(1)
        assertThat(head2.first()).isSameInstanceAs(item)
    }

    @Test
    fun intoMovesLinkBetweenHeads() {
        val head1 = Head()
        val head2 = Head()
        val item = Item()
        item.into(head1)
        assertThat(head1.cardinal()).isEqualTo(1)
        item.into(head2)
        assertThat(head1.cardinal()).isEqualTo(0)
        assertThat(head2.cardinal()).isEqualTo(1)
        assertThat(head2.first()).isSameInstanceAs(item)
    }

    @Test
    fun asSequenceIteratesAllLinks() {
        val head = Head()
        val items = List(5) { Item().also { it.into(head) } }
        val result = head.asSequence().toList()
        assertThat(result.size).isEqualTo(5)
        assertThat(result).isEqualTo(items)
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
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0]).isSameInstanceAs(special)
    }
}
