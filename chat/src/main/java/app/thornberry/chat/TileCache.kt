package app.thornberry.chat

import app.thornberry.crypto.Crypto
import java.io.File

/**
 * On-phone cache of map tiles you have already looked at (no bulk download: OpenStreetMap's tile policy forbids it).
 *
 * Privacy: a cache shows where you have looked, so it is protected like the rest of the app's data.
 * - Every tile is encrypted with the Keystore key ([SecureStorage]).
 * - File names are salted hashes of the tile address, so the folder listing does not reveal places.
 * - The cache has a size cap, evicts the least recently used tiles, can be switched off, and can be cleared.
 * - The salt is destroyed on clearing, so nothing from before can be matched afterwards.
 * File sizes and modification times remain visible to anyone with access to the phone's storage.
 */
object TileCache {
    const val MAX_BYTES = 60L * 1024 * 1024
    private const val SALT_FILE = "salt"
    private const val TRIM_EVERY = 40

    private lateinit var dir: File
    private var salt: ByteArray? = null
    private var writes = 0
    private val lock = Any()

    fun init(filesDir: File) {
        synchronized(lock) { if (!::dir.isInitialized) dir = File(filesDir, "tiles").also { it.mkdirs() } }
    }

    private fun saltBytes(): ByteArray = synchronized(lock) {
        salt?.let { return it }
        val s = SecureStorage.read(dir, SALT_FILE) ?: Crypto.randomBytes(16).also { SecureStorage.write(dir, SALT_FILE, it) }
        salt = s
        s
    }

    private fun nameFor(z: Int, x: Int, y: Int): String =
        Crypto.sha256(saltBytes() + "$z/$x/$y".toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    /** The tile bytes and when they were fetched, or null (also null if the file cannot be decrypted). */
    fun get(z: Int, x: Int, y: Int): Pair<ByteArray, Long>? {
        return try {
            val name = nameFor(z, x, y)
            val f = File(dir, name)
            if (!f.exists()) return null
            val plain = SecureStorage.decrypt(f.readBytes(), name)
            if (plain.size <= 8) return null
            var t = 0L
            for (i in 0 until 8) t = (t shl 8) or (plain[i].toLong() and 0xff)
            f.setLastModified(System.currentTimeMillis()) // recently used
            plain.copyOfRange(8, plain.size) to t
        } catch (e: Exception) { null }
    }

    fun put(z: Int, x: Int, y: Int, png: ByteArray, now: Long = System.currentTimeMillis()) {
        try {
            val name = nameFor(z, x, y)
            val head = ByteArray(8) { i -> (now shr (56 - 8 * i)).toByte() }
            SecureStorage.write(dir, name, head + png)
            val trim = synchronized(lock) { ++writes % TRIM_EVERY == 0 }
            if (trim) trimToSize()
        } catch (e: Exception) { ChatLog.log("TILE_CACHE_FAIL", e.javaClass.simpleName) }
    }

    private fun tileFiles(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name != SALT_FILE && !it.name.endsWith(".tmp") } ?: emptyList()

    fun sizeBytes(): Long = tileFiles().sumOf { it.length() }

    private fun trimToSize() {
        val files = tileFiles().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES * 9 / 10) break
            total -= f.length(); f.delete()
        }
    }

    /** Delete every cached tile and the salt. */
    fun clear() {
        synchronized(lock) {
            dir.listFiles()?.forEach { it.delete() }
            salt = null
        }
    }
}
