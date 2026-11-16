package com.example.claudewake

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the lock-screen PIN / pattern encrypted with a non-exportable Android Keystore key.
 * Values never leave this device and are never logged.
 */
object SecretStore {
    private const val ALIAS = "claudewake_pin_key"
    private const val PIN = "pin"
    private const val PATTERN = "pattern"
    private const val GEOM = "pgeom"

    private fun sp(c: Context) = c.getSharedPreferences("claudewake_secret", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return kg.generateKey()
    }

    private fun put(c: Context, name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(value.toByteArray())
        sp(c).edit()
            .putString(name + "_iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(name, Base64.encodeToString(enc, Base64.NO_WRAP))
            .remove("iv")
            .apply()
    }

    private fun get(c: Context, name: String): String? {
        return try {
            // "iv" is the un-suffixed key used by the first release; read it for migration.
            val ivStr = sp(c).getString(name + "_iv", null) ?: sp(c).getString("iv", null) ?: return null
            val encStr = sp(c).getString(name, null) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivStr, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(encStr, Base64.NO_WRAP)))
        } catch (_: Exception) {
            null
        }
    }

    // ---------- PIN ----------
    fun savePin(c: Context, pin: String) = put(c, PIN, pin)
    fun loadPin(c: Context): String? = get(c, PIN)
    fun hasPin(c: Context): Boolean = loadPin(c) != null

    // ---------- Pattern ----------
    private const val GRID = "pgrid"

    fun savePattern(c: Context, order: List<Int>) = put(c, PATTERN, order.joinToString(","))

    fun loadPattern(c: Context): List<Int>? =
        get(c, PATTERN)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size >= 4 }

    /**
     * The real lock-screen dot grid, captured from the phone itself.
     * [left] and [side] are fractions of the screen width, [top] is a fraction of the height.
     */
    fun saveGrid(c: Context, left: Float, top: Float, side: Float) =
        put(c, GRID, String.format(Locale.US, "%.5f,%.5f,%.5f", left, top, side))

    fun loadGrid(c: Context): Triple<Float, Float, Float>? {
        val parts = get(c, GRID)?.split(",") ?: return null
        val left = parts.getOrNull(0)?.toFloatOrNull() ?: return null
        val top = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        val side = parts.getOrNull(2)?.toFloatOrNull() ?: return null
        if (side !in 0.2f..1.2f || left < 0f || top < 0f) return null
        return Triple(left, top, side)
    }

    fun hasGrid(c: Context): Boolean = sp(c).getString(GRID, null) != null

    /** Legacy: per-dot screen fractions written by the first pattern-capable build. */
    fun loadPatternPoints(c: Context): List<Pair<Float, Float>> {
        val raw = get(c, GEOM) ?: return emptyList()
        return raw.split(";").mapNotNull { part ->
            val xy = part.split(",")
            val x = xy.getOrNull(0)?.toFloatOrNull()
            val y = xy.getOrNull(1)?.toFloatOrNull()
            if (x != null && y != null) x to y else null
        }
    }

    fun hasPattern(c: Context): Boolean {
        val order = loadPattern(c) ?: return false
        return loadGrid(c) != null || loadPatternPoints(c).size == order.size
    }

    fun clear(c: Context) = sp(c).edit().clear().apply()
}
