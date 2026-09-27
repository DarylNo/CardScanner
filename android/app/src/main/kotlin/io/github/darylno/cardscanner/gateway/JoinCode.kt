package io.github.darylno.cardscanner.gateway

import java.security.SecureRandom
import java.util.Locale

/**
 * The guest gateway's only secret: a 6-digit join code from [SecureRandom].
 *
 * [matches] compares in constant time (every digit is always examined), so response
 * timing says nothing about how many leading digits a guess got right. Brute force is
 * bounded separately by [GatewayServer]'s per-IP lockout.
 */
class JoinCode(private val random: SecureRandom = SecureRandom()) {

    @Volatile
    var current: String = generate()
        private set

    /** Replaces the code with a fresh one and returns it. */
    fun rotate(): String {
        current = generate()
        return current
    }

    /**
     * True when [candidate] (spaces and dashes ignored, so "123 456" works) equals the
     * current code. Constant-time over the code's length.
     */
    fun matches(candidate: String?): Boolean {
        val want = current
        val got = candidate?.filterNot { it == ' ' || it == '-' }?.trim() ?: return false
        var diff = got.length xor want.length
        for (i in want.indices) {
            val c = if (i < got.length) got[i].code else 0
            diff = diff or (c xor want[i].code)
        }
        return diff == 0
    }

    private fun generate(): String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))

    companion object {
        const val LENGTH = 6
    }
}
