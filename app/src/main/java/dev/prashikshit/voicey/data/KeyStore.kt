package dev.prashikshit.voicey.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted key/value storage backed by EncryptedSharedPreferences with a master key
 * kept in the device's hardware-backed keystore. Used for API keys and user settings.
 */
class KeyStore(context: Context) {
    private val prefs: SharedPreferences = shared(context.applicationContext)

    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
    }

    private companion object {
        const val PREFS_NAME = "voicey_secure_prefs"

        // Building the master key and Tink keysets hits the hardware keystore and costs
        // noticeable time on every dictation. One wrapper per process is safe: all reads and
        // writes go through the same underlying SharedPreferences file object.
        @Volatile
        private var cached: SharedPreferences? = null

        fun shared(context: Context): SharedPreferences =
            cached ?: synchronized(this) {
                cached ?: EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { cached = it }
            }
    }
}
