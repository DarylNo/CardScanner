package io.github.darylno.cardscanner.gateway

import io.github.darylno.cardscanner.core.MiniJson
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Admin pairing for the phone server (docs/PHONE_ONLY_PLAN.md → Roles, owner
 * decision 2026-09-30): the computer pairs ONCE with a one-time 8-digit code
 * shown on the phone and is then remembered — a long-lived `cs_admin` cookie —
 * until revoked on the phone. Everyone else joins as a guest with the 6-digit
 * [JoinCode].
 *
 *  - [newCode] makes a code valid for [CODE_TTL_MS], usable ONCE; making a new
 *    one or pairing with it retires it. [redeem] runs inside the gateway's
 *    join critical section, so wrong admin codes count toward the same
 *    per-IP lockout and global rotation budget as wrong guest codes.
 *  - Only a SHA-256 of each admin token is kept ([file], so pairings survive
 *    a restart); a copied file can't be replayed as a cookie.
 *  - [revokeAll] forgets every paired computer.
 */
class AdminPairing(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private var pending: String? = null
    private var pendingUntil = 0L
    private val hashes = LinkedHashMap<String, Long>()     // sha256(token) → paired at (ms)

    init { load() }

    /** A fresh one-time code (8 digits), replacing any earlier one. */
    fun newCode(): String = synchronized(lock) {
        val c = (0 until CODE_DIGITS).joinToString("") { random.nextInt(10).toString() }
        pending = c
        pendingUntil = clock() + CODE_TTL_MS
        c
    }

    /** The code still waiting to be used, or null (none made, used, or expired). */
    val pendingCode: String? get() = synchronized(lock) { pending?.takeIf { clock() < pendingUntil } }

    /** Retire the waiting code without pairing (the phone closed the dialog). */
    fun cancelCode() = synchronized(lock) { pending = null }

    /**
     * [given] (spaces ignored) against the waiting code, constant-time. Right →
     * the code is used up and a new admin token is returned (only its hash is kept).
     */
    fun redeem(given: String, now: Long = clock()): String? = synchronized(lock) {
        val want = pending ?: return null
        if (now >= pendingUntil) { pending = null; return null }
        val g = given.filter { !it.isWhitespace() }
        if (!MessageDigest.isEqual(g.toByteArray(), want.toByteArray())) return null
        pending = null
        val b = ByteArray(32).also(random::nextBytes)
        val token = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        hashes[sha256(token)] = now
        save()
        token
    }

    fun isAdmin(token: String): Boolean = synchronized(lock) { sha256(token) in hashes }

    /** How many computers are paired. */
    val count: Int get() = synchronized(lock) { hashes.size }

    /** Forget every paired computer (their cookies stop working at once). */
    fun revokeAll() = synchronized(lock) { hashes.clear(); pending = null; save() }

    private fun load() {
        val f = file ?: return
        try {
            val root = MiniJson.parse(f.readText(Charsets.UTF_8)) as? Map<*, *> ?: return
            for (a in root["admins"] as? List<*> ?: emptyList<Any?>()) {
                val m = a as? Map<*, *> ?: continue
                val h = m["hash"] as? String ?: continue
                hashes[h] = (m["paired_at"] as? Number)?.toLong() ?: 0L
            }
        } catch (e: Exception) {
            // missing or unreadable: no computer is paired
        }
    }

    private fun save() {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(MiniJson.stringify(mapOf("admins" to hashes.map { (h, t) ->
            linkedMapOf("hash" to h, "paired_at" to t) })), Charsets.UTF_8)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    companion object {
        const val CODE_DIGITS = 8
        const val CODE_TTL_MS = 10 * 60_000L

        private fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
