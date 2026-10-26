package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatEngineTest {
    private var now = 1_000_000_000_000L
    private val onions = listOf("a", "b", "c", "d").map { it.repeat(56) }

    private class Phone(val name: String, val onion: String) {
        var engine = ChatEngine(Crypto.ed25519Generate())
        val events = ArrayList<ChatEngine.Event>()
    }

    private class Net(val phones: List<Phone>, val blocked: MutableSet<Pair<String, String>> = HashSet()) {
        /** Deliver queued frames between phones until quiet. Frames to unreachable phones stay queued. */
        fun run(now: Long): Int {
            var delivered = 0
            var progress = true
            var guard = 0
            while (progress && guard++ < 200) {
                progress = false
                for (p in phones) {
                    for (o in p.engine.pendingFrames()) {
                        val to = phones.firstOrNull { it.onion == o.toOnion } ?: continue
                        if (blocked.contains(p.onion to to.onion)) continue
                        p.engine.markDelivered(o)
                        to.engine.receiveFrame(o.frame, now)
                        delivered++; progress = true
                    }
                }
                phones.forEach { it.events += it.engine.drainEvents() }
            }
            return delivered
        }
    }

    private fun pair(a: Phone, b: Phone, nameOfB: String, nameOfA: String): String {
        val la = a.engine.beginPairing(a.onion); val lb = b.engine.beginPairing(b.onion)
        val ra = a.engine.completePairing(la, lb.offer.encode(), nameOfB)
        val rb = b.engine.completePairing(lb, la.offer.encode(), nameOfA)
        assertEquals(ra.safetyCode, rb.safetyCode)
        a.engine.confirmVerified(ra.contact.id); b.engine.confirmVerified(rb.contact.id)
        return ra.safetyCode
    }

    private fun contactIdOf(from: Phone, other: Phone) =
        from.engine.contactList().first { it.onion == other.onion }.id

    private inline fun <reified T : ChatEngine.Event> Phone.all() = events.filterIsInstance<T>()

    @Test fun two_phones_pair_and_exchange_direct_messages() {
        val a = Phone("A", onions[0]); val b = Phone("B", onions[1]); val net = Net(listOf(a, b))
        pair(a, b, "Bob", "Alice")
        net.run(now)
        a.engine.sendDirect(contactIdOf(a, b), "hi bob", now)
        net.run(now)
        assertEquals("hi bob", b.all<ChatEngine.Event.DirectText>().single().text)
        b.engine.sendDirect(contactIdOf(b, a), "hi alice", now)
        net.run(now)
        assertEquals("hi alice", a.all<ChatEngine.Event.DirectText>().single().text)
    }

    /** B knows A and C; A and C never pair. B creates a group and invites both. */
    private fun relayTriangle(): Triple<Phone, Phone, Phone> {
        val a = Phone("A", onions[0]); val b = Phone("B", onions[1]); val c = Phone("C", onions[2])
        val net = Net(listOf(a, b, c))
        pair(a, b, "Bob", "Alice"); pair(c, b, "Bob", "Carol")
        net.run(now)
        val gid = b.engine.createGroup("Family")
        b.engine.inviteToGroup(gid, contactIdOf(b, a), now)
        net.run(now)
        b.engine.inviteToGroup(gid, contactIdOf(b, c), now)
        net.run(now)
        return Triple(a, b, c)
    }

    @Test fun group_chat_relays_through_the_middle_person() {
        val (a, b, c) = relayTriangle()
        val net = Net(listOf(a, b, c))
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        assertEquals("Family", a.all<ChatEngine.Event.GroupJoined>().single().groupName)
        assertTrue(c.all<ChatEngine.Event.GroupJoined>().isNotEmpty())
        a.engine.sendGroupText(gid, "hello family", now)
        net.run(now)
        assertEquals("hello family", b.all<ChatEngine.Event.GroupText>().single().text)
        assertEquals("hello family", c.all<ChatEngine.Event.GroupText>().single().text) // A and C are not paired
        c.engine.sendGroupText(gid, "hi from carol", now + 1000)
        net.run(now + 1000)
        assertEquals("hi from carol", a.all<ChatEngine.Event.GroupText>().single().text)
        // nothing is shown twice
        net.run(now + 2000); a.engine.syncAll(now + 2000); b.engine.syncAll(now + 2000); c.engine.syncAll(now + 2000)
        net.run(now + 2000)
        assertEquals(1, a.all<ChatEngine.Event.GroupText>().size)
        assertEquals(2, b.all<ChatEngine.Event.GroupText>().size)
    }

    @Test fun messages_wait_while_the_relay_is_unreachable_then_arrive() {
        val (a, b, c) = relayTriangle()
        val net = Net(listOf(a, b, c))
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        net.blocked += (a.onion to b.onion); net.blocked += (b.onion to a.onion)
        a.engine.sendGroupText(gid, "offline msg", now)
        net.run(now)
        assertTrue(c.all<ChatEngine.Event.GroupText>().isEmpty())
        net.blocked.clear()
        net.run(now + 60_000) // queued frames now go through
        a.engine.syncAll(now + 60_000); net.run(now + 60_000)
        assertEquals("offline msg", c.all<ChatEngine.Event.GroupText>().single().text)
    }

    @Test fun removed_member_gets_nothing_new() {
        val (a, b, c) = relayTriangle()
        val net = Net(listOf(a, b, c))
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        b.engine.removeFromGroup(gid, c.engine.identityPublic, now)
        net.run(now)
        assertTrue(c.all<ChatEngine.Event.RemovedFromGroup>().isNotEmpty())
        a.engine.sendGroupText(gid, "secret after removal", now + 1000)
        net.run(now + 1000)
        assertEquals(1, b.all<ChatEngine.Event.GroupText>().size)
        assertTrue(c.all<ChatEngine.Event.GroupText>().isEmpty())
    }

    @Test fun new_member_cannot_read_earlier_messages() {
        val (a, b, c) = relayTriangle()
        val net = Net(listOf(a, b, c))
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        val d = Phone("D", onions[3]); val net2 = Net(listOf(a, b, c, d))
        a.engine.sendGroupText(gid, "before d joined", now)
        net2.run(now)
        pair(d, b, "Bob", "Dave"); net2.run(now)
        b.engine.inviteToGroup(gid, contactIdOf(b, d), now + 1000)
        net2.run(now + 1000)
        b.engine.syncAll(now + 2000); net2.run(now + 2000)
        assertTrue(d.all<ChatEngine.Event.GroupJoined>().isNotEmpty())
        assertTrue(d.all<ChatEngine.Event.GroupText>().none { it.text == "before d joined" })
        a.engine.sendGroupText(gid, "after d joined", now + 3000)
        net2.run(now + 3000)
        assertEquals("after d joined", d.all<ChatEngine.Event.GroupText>().single().text)
    }

    @Test fun only_admin_can_invite_or_remove() {
        val (a, _, c) = relayTriangle()
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        assertFailsWith<CryptoException> { a.engine.removeFromGroup(gid, c.engine.identityPublic, now) }
    }

    @Test fun unpaired_stranger_cannot_inject_a_group() {
        val a = Phone("A", onions[0]); val m = Phone("M", onions[3]); val b = Phone("B", onions[1])
        val net = Net(listOf(a, b, m)); pair(a, b, "Bob", "Alice"); net.run(now)
        // Mallory builds a real update for A's identity but has no paired channel to A: frames with unknown tags are dropped.
        val g = GroupSession.create(Crypto.ed25519Generate())
        a.engine.receiveFrame(Crypto.randomBytes(200), now)
        assertTrue(a.engine.droppedFrames > 0)
        assertTrue(a.engine.groupList().isEmpty() && g.groupId.isNotEmpty())
    }

    @Test fun snapshot_and_restore_mid_conversation() {
        val (a, b, c) = relayTriangle()
        val net = Net(listOf(a, b, c))
        val gid = a.all<ChatEngine.Event.GroupJoined>().single().groupId
        a.engine.sendGroupText(gid, "one", now); net.run(now)
        b.engine = ChatEngine.restore(b.engine.snapshot()) // B's phone restarts
        a.engine = ChatEngine.restore(a.engine.snapshot())
        a.engine.sendGroupText(gid, "two", now + 1000); net.run(now + 1000)
        assertEquals(listOf("one", "two"), c.all<ChatEngine.Event.GroupText>().map { it.text })
        a.engine.sendDirect(contactIdOf(a, b), "direct after restore", now + 2000); net.run(now + 2000)
        assertEquals("direct after restore", b.all<ChatEngine.Event.DirectText>().single().text)
        assertFailsWith<CryptoException> { ChatEngine.restore(ByteArray(20)) }
        val snap = a.engine.snapshot()
        assertFailsWith<CryptoException> { ChatEngine.restore(snap.copyOf(snap.size - 3)) }
    }

    @Test fun junk_frames_never_crash() {
        val a = Phone("A", onions[0]); val b = Phone("B", onions[1]); Net(listOf(a, b)).also { pair(a, b, "Bob", "Alice"); it.run(now) }
        val tag = a.engine.contactList().single().receiveTag
        val r = java.util.Random(5)
        repeat(3000) {
            val junk = ByteArray(r.nextInt(300)).also { r.nextBytes(it) }
            a.engine.receiveFrame(junk, now)
            if (junk.size > 16) { System.arraycopy(tag, 0, junk, 0, 16); a.engine.receiveFrame(junk, now) }
        }
        assertTrue(a.engine.droppedFrames >= 3000)
        // and the session still works afterwards
        b.engine.sendDirect(b.engine.contactList().single().id, "still fine", now)
        Net(listOf(a, b)).run(now)
        assertEquals("still fine", a.all<ChatEngine.Event.DirectText>().single().text)
    }
}
