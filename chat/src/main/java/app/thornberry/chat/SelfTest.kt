package app.thornberry.chat

import app.thornberry.crypto.Crypto
import app.thornberry.crypto.Pairing

/** Quick known-answer checks run on the phone at start, to prove the crypto library works on this Android version. */
object SelfTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun run(): String = try {
        val shared = Crypto.x25519Agree(
            hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"),
            hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"),
        )
        check(shared.contentEquals(hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"))) { "x25519" }
        val priv = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        check(Crypto.sign(priv, ByteArray(0)).contentEquals(hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ))) { "ed25519" }
        val key = ByteArray(32) { (0x80 + it).toByte() }
        val nonce = ByteArray(24) { (0x40 + it).toByte() }
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
            "for the future, sunscreen would be it.").toByteArray()
        val sealed = Crypto.aeadEncrypt(key, nonce, aad, pt)
        check(sealed.copyOfRange(pt.size, sealed.size).contentEquals(hex("c0875924c1c7987947deafd8780acf49"))) { "xchacha" }
        // full pairing and ratchet round trip
        val on = "a".repeat(56)
        val a = Pairing.createOffer(Crypto.ed25519Generate(), on)
        val b = Pairing.createOffer(Crypto.ed25519Generate(), on.replace('a', 'b'))
        val ra = Pairing.complete(a, b.offer.encode())
        val rb = Pairing.complete(b, a.offer.encode())
        val (i, r) = if (ra.isInitiator) ra to rb else rb to ra
        val back = r.session.decrypt(i.session.encrypt("x".toByteArray(), i.associatedData), r.associatedData)
        check(String(back) == "x") { "ratchet" }
        "OK"
    } catch (e: Throwable) {
        "FAILED: ${e.javaClass.simpleName} ${e.message}"
    }
}
