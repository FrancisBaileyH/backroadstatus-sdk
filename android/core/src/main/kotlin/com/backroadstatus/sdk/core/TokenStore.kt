package com.backroadstatus.sdk.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.openid.appauth.AuthState
import org.json.JSONException

/**
 * Persists an [AuthState] across launches.
 *
 * Backed by [EncryptedSharedPreferences] (AES-GCM with a Keystore-derived
 * master key). This is the Android analogue of the iOS Keychain with
 * `kSecAttrAccessibleAfterFirstUnlock`: the master key is `AFTER_FIRST_UNLOCK`
 * so background token refreshes after a reboot (before the user has entered
 * their lockscreen credential once) still work.
 *
 * Writes are synchronous (`commit()`) so a crash between
 * [AuthState.stateChangeListener] firing and the process surviving the next
 * boot can't lose a rotated refresh token — the same failure mode the iOS
 * `OIDAuthStateChangeDelegate` protects against.
 */
internal object TokenStore {
    private const val TAG = "BackroadStatus"
    private const val PREFS_NAME = "com.backroadstatus.sdk.tokens"
    private const val KEY_AUTH_STATE = "authState"

    private var cached: SharedPreferences? = null

    @SuppressLint("ApplySharedPref") // commit() is intentional; see class doc.
    fun save(context: Context, state: AuthState) {
        val prefs = prefs(context) ?: return
        val json = state.jsonSerializeString()
        val ok = prefs.edit().putString(KEY_AUTH_STATE, json).commit()
        if (!ok) {
            Log.e(TAG, "TokenStore: failed to write AuthState — token rotation will not persist.")
        }
    }

    fun load(context: Context): AuthState? {
        val prefs = prefs(context) ?: return null
        val json = prefs.getString(KEY_AUTH_STATE, null) ?: return null
        return try {
            AuthState.jsonDeserialize(json)
        } catch (e: JSONException) {
            Log.w(TAG, "TokenStore: failed to deserialize AuthState — dropping.", e)
            null
        }
    }

    @SuppressLint("ApplySharedPref")
    fun clear(context: Context) {
        val prefs = prefs(context) ?: return
        prefs.edit().remove(KEY_AUTH_STATE).commit()
    }

    @Synchronized
    private fun prefs(context: Context): SharedPreferences? {
        cached?.let { return it }
        return try {
            val appContext = context.applicationContext
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                // Matches iOS kSecAttrAccessibleAfterFirstUnlock — tokens
                // are readable after the first unlock following a reboot.
                .setUserAuthenticationRequired(false)
                .build()
            val p = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = p
            p
        } catch (t: Throwable) {
            Log.e(TAG, "TokenStore: failed to open EncryptedSharedPreferences", t)
            null
        }
    }
}
