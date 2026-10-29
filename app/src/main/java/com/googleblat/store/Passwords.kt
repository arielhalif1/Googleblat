package com.googleblat.store

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Unlock password, stored as a salted PBKDF2 hash, with growing delays after wrong guesses. */
object Passwords {
    sealed class Result {
        object Ok : Result()
        object Wrong : Result()
        data class TooSoon(val seconds: Long) : Result()
    }

    private const val PREFS = "auth"
    private const val ITERATIONS = 60_000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(ctx: Context) = prefs(ctx).contains("hash")

    fun set(ctx: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(ctx).edit()
            .putString("salt", b64(salt))
            .putString("hash", b64(hash(password, salt)))
            .putInt("fails", 0)
            .putLong("retryAt", 0)
            .apply()
    }

    fun check(ctx: Context, password: String): Result {
        val p = prefs(ctx)
        val wait = p.getLong("retryAt", 0) - System.currentTimeMillis()
        if (wait > 0) return Result.TooSoon((wait + 999) / 1000)

        val salt = Base64.decode(p.getString("salt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", ""), Base64.NO_WRAP)
        if (MessageDigest.isEqual(hash(password, salt), expected)) {
            p.edit().putInt("fails", 0).apply()
            return Result.Ok
        }
        val fails = p.getInt("fails", 0) + 1
        val delaySeconds = minOf(30L * fails, 600L)
        p.edit()
            .putInt("fails", fails)
            .putLong("retryAt", System.currentTimeMillis() + delaySeconds * 1000)
            .apply()
        return Result.Wrong
    }

    private fun hash(password: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256))
            .encoded

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
