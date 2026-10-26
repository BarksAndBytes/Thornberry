package app.thornberry.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias UpdateResult = GroupSession.UpdateResult

class GroupSessionTest {
    private val now = 1_000_000_000_000L
    private val hour = 3_600_000L
    private fun b(s: String) = s.toByteArray()

    private class Setup(val admin: Crypto.KeyPair, val bob: Crypto.KeyPair, val carol: Crypto.KeyPair,
                        val a: GroupSession, val bobS: GroupSession, val carolS: GroupSession)

    /** Admin creates a group and adds bob and carol; everyone ends up on the same state. */
    private fun threeMembers(): Setup {
        val admin = Crypto.ed25519Generate(); val bob = Crypto.ed25519Generate(); val carol = Crypto.ed25519Generate()
        val a = GroupSession.create(admin)
        val u1 = a.addMember(admin, bob.public, now)
        val bobS = GroupSession.join(u1, bob, admin.public)
        val u2 = a.addMember(admin, carol.public, now)
        val carolS = GroupSession.join(u2, carol, admin.public)
        assertEquals(UpdateResult.APPLIED, bobS.applyUpdate(u2, now, bob.public))
        return Setup(admin, bob, carol, a, bobS, carolS)
    }

    @Test fun records_flow_between_members() {
        val s = threeMembers()
        val m = s.bob.let { s.bobS.seal(it, 1, b("hello group"), now, now + hour) }
        val r = s.carolS.open(m, now)
        assertContentEquals(b("hello group"), r.body)
        assertContentEquals(s.bob.public, r.sender)
        assertEquals(1, r.kind)
        assertContentEquals(b("hello group"), s.a.open(m, now).body)
    }

    @Test fun records_are_padded_to_size_classes() {
        val s = threeMembers()
        val sizes = listOf(0, 10, 50, 100).map { s.bobS.seal(s.bob, 1, ByteArray(it), now, now + hour).size }
        assertEquals(1, sizes.toSet().size)
        assertTrue(s.bobS.seal(s.bob, 1, ByteArray(500), now, now + hour).size > sizes[0])
        assertFailsWith<CryptoException> { s.bobS.seal(s.bob, 1, ByteArray(9000), now, now + hour) }
    }

    @Test fun records_from_another_group_are_rejected() {
        val s = threeMembers()
        val outsider = Crypto.ed25519Generate()
        val other = GroupSession.create(outsider)
        assertFailsWith<CryptoException> { s.carolS.open(other.seal(outsider, 1, b("x"), now, now + hour), now) }
    }

    @Test fun replay_expired_and_future_records_rejected() {
        val s = threeMembers()
        val m = s.bobS.seal(s.bob, 1, b("once"), now, now + hour)
        s.carolS.open(m, now)
        assertFailsWith<CryptoException> { s.carolS.open(m, now) }
        val old = s.bobS.seal(s.bob, 1, b("old"), now, now + 1000)
        assertFailsWith<CryptoException> { s.carolS.open(old, now + 2000) }
        val future = s.bobS.seal(s.bob, 1, b("fut"), now + 2 * hour, now + 3 * hour)
        assertFailsWith<CryptoException> { s.carolS.open(future, now) }
    }

    @Test fun out_of_order_records_accepted_once_each() {
        val s = threeMembers()
        val ms = (0 until 10).map { s.bobS.seal(s.bob, 1, b("m$it"), now, now + hour) }
        for (i in listOf(9, 3, 0, 5, 1, 2, 8, 4, 7, 6)) assertContentEquals(b("m$i"), s.carolS.open(ms[i], now).body)
        for (m in ms) assertFailsWith<CryptoException> { s.carolS.open(m, now) }
    }

    @Test fun every_flipped_byte_is_rejected() {
        val s = threeMembers()
        val m = s.bobS.seal(s.bob, 1, b("tamper"), now, now + hour)
        for (i in m.indices) {
            val t = m.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException>("byte $i") { s.carolS.open(t, now) }
        }
        assertContentEquals(b("tamper"), s.carolS.open(m, now).body) // original still fine afterwards
    }

    @Test fun removal_rotates_key_and_locks_out_the_removed_member() {
        val s = threeMembers()
        val update = s.a.removeMember(s.admin, s.carol.public, now)
        assertEquals(UpdateResult.APPLIED, s.bobS.applyUpdate(update, now, s.bob.public))
        assertEquals(UpdateResult.REMOVED, s.carolS.applyUpdate(update, now, s.carol.public))
        assertFalse(s.carolS.isActive)
        assertEquals(2, s.a.currentEpoch)

        val secret = s.a.seal(s.admin, 1, b("after removal"), now, now + hour)
        assertContentEquals(b("after removal"), s.bobS.open(secret, now).body)
        assertFailsWith<CryptoException> { s.carolS.open(secret, now) }
        // Carol cannot send into the group either, even with the old key she once had.
        val oldGroupCarol = s.carolS
        assertFailsWith<Exception> { oldGroupCarol.seal(s.carol, 1, b("sneaky"), now, now + hour) }
    }

    @Test fun removed_member_cannot_forge_records_in_the_old_epoch() {
        val s = threeMembers()
        val carolCopy = GroupSession.restore(s.carolS.serialize()) // Carol kept her old state
        val update = s.a.removeMember(s.admin, s.carol.public, now)
        s.bobS.applyUpdate(update, now, s.bob.public)
        val forged = carolCopy.seal(s.carol, 1, b("still here"), now, now + hour) // old epoch, valid signature, old key
        // Within the grace window old-epoch records are accepted only from CURRENT members.
        assertFailsWith<CryptoException> { s.bobS.open(forged, now + hour) }
    }

    @Test fun late_old_epoch_record_from_a_remaining_member_is_accepted_only_within_grace() {
        val s = threeMembers()
        val late = s.bobS.seal(s.bob, 1, b("in flight"), now, now + 100 * hour)
        val update = s.a.rotate(s.admin, now)
        s.carolS.applyUpdate(update, now, s.carol.public)
        assertContentEquals(b("in flight"), s.carolS.open(late, now + hour).body)
        val late2 = s.bobS.seal(s.bob, 1, b("too late"), now, now + 100 * hour)
        assertFailsWith<CryptoException> { s.carolS.open(late2, now + 25 * hour) }
    }

    @Test fun updates_must_come_from_admins_and_cannot_roll_back() {
        val s = threeMembers()
        assertFailsWith<CryptoException> { s.bobS.removeMember(s.bob, s.carol.public, now) } // bob is not an admin
        val first = s.a.rotate(s.admin, now)
        val second = s.a.rotate(s.admin, now)
        assertEquals(UpdateResult.APPLIED, s.bobS.applyUpdate(first, now, s.bob.public))
        assertEquals(UpdateResult.APPLIED, s.bobS.applyUpdate(second, now, s.bob.public))
        assertEquals(UpdateResult.STALE, s.bobS.applyUpdate(first, now, s.bob.public)) // replayed old update
        assertEquals(3, s.bobS.currentEpoch)
    }

    @Test fun tampered_or_forged_updates_rejected() {
        val s = threeMembers()
        val u = s.a.rotate(s.admin, now)
        for (i in u.indices) {
            val t = u.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException>("byte $i") { s.bobS.applyUpdate(t, now, s.bob.public) }
        }
    }

    @Test fun conflicting_updates_at_the_same_version_are_rejected() {
        val s = threeMembers()
        val adminCopy = GroupSession.restore(s.a.serialize())
        val u1 = s.a.rotate(s.admin, now)
        val u2 = adminCopy.rotate(s.admin, now) // same version number, different key
        assertEquals(UpdateResult.APPLIED, s.bobS.applyUpdate(u1, now, s.bob.public))
        assertFailsWith<CryptoException> { s.bobS.applyUpdate(u2, now, s.bob.public) }
    }

    @Test fun join_checks_signer_and_membership() {
        val admin = Crypto.ed25519Generate(); val bob = Crypto.ed25519Generate(); val eve = Crypto.ed25519Generate()
        val a = GroupSession.create(admin)
        val u = a.addMember(admin, bob.public, now)
        assertFailsWith<CryptoException> { GroupSession.join(u, bob, eve.public) } // wrong inviter
        assertFailsWith<CryptoException> { GroupSession.join(u, eve, admin.public) } // not in member list
        GroupSession.join(u, bob, admin.public)
    }

    @Test fun save_and_restore_keeps_replay_protection() {
        val s = threeMembers()
        val m = s.bobS.seal(s.bob, 1, b("x"), now, now + hour)
        s.carolS.open(m, now)
        val restored = GroupSession.restore(s.carolS.serialize())
        assertFailsWith<CryptoException> { restored.open(m, now) }
        val next = s.bobS.seal(s.bob, 1, b("y"), now, now + hour)
        assertContentEquals(b("y"), restored.open(next, now).body)
        val bobRestored = GroupSession.restore(s.bobS.serialize())
        val after = bobRestored.seal(s.bob, 1, b("z"), now, now + hour)
        assertContentEquals(b("z"), restored.open(after, now).body) // sequence continued, not reused
        assertFailsWith<CryptoException> { GroupSession.restore(ByteArray(10)) }
    }

    @Test fun junk_never_crashes_open_or_update() {
        val s = threeMembers()
        val r = java.util.Random(3)
        repeat(2000) {
            val junk = ByteArray(r.nextInt(400)).also { r.nextBytes(it) }
            if (junk.isNotEmpty()) junk[0] = 1
            assertFailsWith<CryptoException> { s.carolS.open(junk, now) }
            assertFailsWith<CryptoException> { s.carolS.applyUpdate(junk, now, s.carol.public) }
        }
    }
}
